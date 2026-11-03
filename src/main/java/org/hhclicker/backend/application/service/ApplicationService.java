package org.hhclicker.backend.application.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.application.model.ApplicationModels.*;
import org.hhclicker.backend.application.repository.ApplicationRepository;
import org.hhclicker.backend.browser.service.BrowserProfileLookup;
import org.hhclicker.backend.resume.service.ResumeService;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.vacancy.catalog.service.VacancyCatalogService;
import org.hhclicker.backend.vacancy.selection.service.VacancySelectionService;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Transactional
public class ApplicationService {
    private final ApplicationRepository repository;
    private final ResumeService resumes;
    private final BrowserProfileLookup profiles;
    private final VacancyCatalogService vacancies;
    private final VacancySelectionService vacancySelection;

    public ApplicationService(ApplicationRepository repository, ResumeService resumes, BrowserProfileLookup profiles,
                              VacancyCatalogService vacancies, VacancySelectionService vacancySelection) {
        this.repository = repository;
        this.resumes = resumes;
        this.profiles = profiles;
        this.vacancies = vacancies;
        this.vacancySelection = vacancySelection;
    }

    private static ApplicationException conflict(String message) {
        return new ApplicationException(ApplicationErrorCategory.CONFLICT, "APPLICATION_RUN_CONFLICT", message);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        repository.lock();
        repository.recover();
    }

    public boolean busy() {
        return repository.busy();
    }

    public boolean exists(UUID id) {
        return repository.exists(id);
    }

    public boolean inspectionAllowed(UUID profileId) {
        return repository.inspectionAllowed(profileId);
    }

    public long succeededToday(UUID resumeId) {
        return repository.succeededToday(resumeId);
    }

    public UUID accountForResume(UUID id) {
        return resumes.findById(id).resume().accountId();
    }

    public Run find(UUID id) {
        return repository.find(id).orElseThrow(() -> conflict("Запуск не найден."));
    }

    public List<Run> list(UUID resumeId) {
        return repository.list(resumeId);
    }

    public List<RunSummary> summaries(UUID resumeId) {
        return repository.summaries(resumeId);
    }

    public List<Attention> attention(UUID resumeId) {
        return list(resumeId).stream().flatMap(run -> run.applications().stream()
                .filter(item -> item.status().equals("ATTENTION_REQUIRED")).map(item -> attention(run, item))).toList();
    }

    public List<Attention> openAttention() {
        return repository.openAttentionRunIds().stream().map(this::find).flatMap(run -> run.applications().stream()
                .filter(item -> item.status().equals("ATTENTION_REQUIRED") && item.attentionStatus().equals("OPEN"))
                .map(item -> attention(run, item))).toList();
    }

    public Attention attentionTask(UUID id) {
        var item = repository.item(id);
        if (!item.status().equals("ATTENTION_REQUIRED")) throw conflict("Задача не требует ручной проверки.");
        return attention(find(item.runId()), item);
    }

    public Attention attention(UUID id, String status) {
        repository.lock();
        if (!List.of("RESOLVED", "SKIPPED").contains(status)) throw conflict("Недопустимое состояние задачи.");
        var item = repository.item(id);
        if (!item.status().equals("ATTENTION_REQUIRED")) throw conflict("Задача не требует ручной проверки.");
        if (status.equals("SKIPPED")) vacancySelection.skip(item.vacancyId());
        repository.acknowledge(id, status);
        repository.finishIfIdle(item.runId());
        // Acknowledgment does not assert HH success, remove the duplicate guard or enqueue another attempt.
        return attention(find(item.runId()), repository.item(id));
    }

    private Attention attention(Run run, Item item) {
        var vacancy = vacancies.findById(item.vacancyId()).vacancy();
        String kind = switch (item.outcome()) {
            case QUESTIONNAIRE -> "QUESTIONNAIRE";
            case CAPTCHA -> "CAPTCHA";
            case AUTH -> "AUTH";
            default -> "ERROR";
        };
        boolean canContinue = run.status().name().startsWith("PAUSED_")
                && run.applications().stream().anyMatch(i -> i.status().equals("QUEUED"))
                && run.applications().stream().noneMatch(i -> i.status().equals("IN_PROGRESS"));
        return new Attention(item.id(), kind, item.attentionStatus(), item.outcome().title(), item.message(), run.id(),
                run.sequence(), item.id(), item.vacancyId(), item.hhVacancyId(), item.vacancyTitle(), vacancy.employerId(),
                item.companyName(), run.resumeName(), item.attemptedAt(), item.outcome(), canContinue,
                vacancy.status().isActive(), run.mode());
    }

    public long remaining(UUID resumeId) {
        var resume = resumes.findById(resumeId).resume();
        return Math.max(0, resume.settings().dailyLimit() - repository.attemptsToday(resume.accountId()));
    }

    public Run start(Start start) {
        start.aiLetter().validate();
        org.hhclicker.backend.resume.model.AiLetterSettings.validateFallback(start.coverLetter());
        if (start.mode() == RunMode.DRY_RUN && (start.aiLetter().enabled() || !start.coverLetter().isBlank()))
            throw conflict("Холостой запуск не должен содержать настройки сопроводительного письма.");
        repository.lock();
        var existing = repository.find(start.requestId());
        if (existing.isPresent()) {
            var run = existing.get();
            if (!run.resumeId().equals(start.resumeId()) || run.dailyLimit() != start.dailyLimit()
                    || run.pauseFromSeconds() != start.pauseFromSeconds() || run.pauseToSeconds() != start.pauseToSeconds()
                    || !run.coverLetter().equals(start.coverLetter()) || run.skipQuestionnaires() != start.skipQuestionnaires()
                    || !run.aiLetter().equals(start.aiLetter()) || run.mode() != start.mode()
                    || !run.applications().stream().map(Item::vacancyId).toList().equals(start.vacancyIds())) {
                throw conflict("Этот запрос уже создал другой запуск. Обновите страницу.");
            }
            return run;
        }
        if (repository.busy()) throw conflict("Сначала завершите или отмените предыдущий запуск.");
        if (start.vacancyIds().isEmpty() || start.vacancyIds().size() > start.dailyLimit()
                || new HashSet<>(start.vacancyIds()).size() != start.vacancyIds().size()
                || start.pauseToSeconds() < start.pauseFromSeconds())
            throw conflict("Проверьте очередь и ограничения запуска.");
        var resume = resumes.findById(start.resumeId()).resume();
        if (start.mode() == RunMode.APPLY && start.vacancyIds().size() > remaining(start.resumeId()))
            throw conflict("Недостаточно остатка дневного лимита.");
        repository.create(start, resume.accountId(), profiles.forAccount(resume.accountId()).id(), resume.hhResumeId(),
                resume.name(), resume.settings().dailyLimit());
        repository.snapshotLetterSettings(start.requestId(), start.aiLetter(), resume.plainText());
        int position = 0;
        for (UUID id : start.vacancyIds()) {
            var vacancy = vacancies.findById(id).vacancy();
            if (!vacancy.resumeId().equals(resume.id()) || !vacancy.queuedForApplication() || !vacancy.status().isActive()) {
                throw conflict("Вакансия «" + vacancy.title() + "» недоступна для отправки или больше не находится в очереди. Обновите список вакансий.");
            }
            if (start.mode() == RunMode.APPLY && repository.guarded(resume.accountId(), vacancy.hhVacancyId(), start.requestId())) {
                throw conflict("По вакансии «" + vacancy.title() + "» уже подтверждён отклик или ещё выполняется другая попытка. Проверьте историю запусков.");
            }
            var source = vacancies.applicationSource(id);
            repository.add(start.requestId(), resume.accountId(), id, vacancy.hhVacancyId(), vacancy.title(), vacancy.companyName(),
                    source.url(), source.page(), position++, Boolean.TRUE.equals(vacancy.hasQuestionnaire()));
        }
        return find(start.requestId());
    }

    public Run changeStatus(UUID id, RunStatus status) {
        return changeStatus(id, status, false);
    }

    public Run changeStatus(UUID id, RunStatus status, boolean confirmFallback) {
        repository.lock();
        var run = find(id);
        if (confirmFallback && (run.status() != RunStatus.PAUSED_AI_LIMIT || status != RunStatus.RUNNING || run.coverLetter().isBlank()))
            throw conflict("Резервное письмо можно разрешить только при паузе из-за лимита Codex, если текст задан.");
        if (run.status() == RunStatus.PAUSED_AI_LIMIT && status == RunStatus.PAUSED_USER)
            throw conflict("Выберите повтор генерации, резервное письмо или отмену запуска.");
        if (run.status() == RunStatus.CANCELLED || run.status() == RunStatus.COMPLETED) {
            if (run.status() == status) return run;
            throw conflict("Завершённый запуск нельзя возобновить.");
        }
        if (status != RunStatus.RUNNING && status != RunStatus.PAUSED_USER && status != RunStatus.CANCELLED)
            throw conflict("Недопустимое действие.");
        if (status == RunStatus.RUNNING && run.applications().stream().noneMatch(item -> List.of("QUEUED", "IN_PROGRESS").contains(item.status()))) {
            repository.status(id, RunStatus.COMPLETED);
            return find(id);
        }
        if (status == RunStatus.RUNNING && run.mode() == RunMode.APPLY && remaining(run.resumeId()) == 0)
            throw conflict("Дневной лимит исчерпан.");
        if (status == RunStatus.RUNNING && run.status() == RunStatus.PAUSED_AI_LIMIT) repository.resumeAiLimit(id, confirmFallback);
        repository.status(id, status);
        if (status == RunStatus.RUNNING) repository.recordTechnicalFailure(id, false);
        repository.advance(id, 0);
        return find(id);
    }

    public Optional<Task> claim(UUID profileId) {
        repository.lock();
        var task = repository.next(profileId);
        if (task.isEmpty()) return task;
        var item = repository.item(task.get().itemId());
        var run = find(item.runId());
        if (run.mode() == RunMode.APPLY && repository.attemptsToday(run.accountId()) >= run.accountDailyLimit()) {
            repository.status(run.id(), RunStatus.PAUSED_LIMIT);
            return Optional.empty();
        }
        repository.claim(item.id());
        // A historical questionnaire flag is not current HH state. Inspect the detail first:
        // a manual application may already have completed since the last attempt.
        if (!eligible(item) || (run.mode() == RunMode.APPLY && repository.guarded(run.accountId(), item.hhVacancyId(), item.id()))) {
            complete(item.id(), run.mode() == RunMode.DRY_RUN ? Outcome.TEXT_UNAVAILABLE : Outcome.NOT_SENT);
            return Optional.empty();
        }
        // Transfer duplicate protection to the new attempt without rewriting the old result.
        // The unchanged unique index still protects the next permit and confirmed results.
        if (run.mode() == RunMode.APPLY) repository.releaseUncertainGuard(run.accountId(), item.hhVacancyId());
        return task;
    }

    public boolean allowed(UUID itemId) {
        var item = repository.item(itemId);
        return item.status().equals("IN_PROGRESS") && find(item.runId()).status() == RunStatus.RUNNING && eligible(item);
    }

    public boolean permit(UUID itemId) {
        repository.lock();
        var item = repository.item(itemId);
        var run = find(item.runId());
        if (run.mode() == RunMode.DRY_RUN) return false;
        // A lost permit response is never retried: a second permit for the same item is denied.
        if (!allowed(itemId) || item.submissionStartedAt() != null || (run.aiLetter().enabled() && item.letter() == null)
                || repository.attemptsToday(run.accountId()) >= run.accountDailyLimit()
                || repository.guarded(run.accountId(), item.hhVacancyId(), itemId)) return false;
        repository.permit(itemId);
        return true;
    }

    public boolean prepareLetter(UUID itemId, PreparedLetter letter) {
        repository.lock();
        var item = repository.item(itemId);
        if (!allowed(itemId) || item.submissionStartedAt() != null) return false;
        var run = find(item.runId());
        if (run.mode() == RunMode.DRY_RUN) return false;
        if (letter == null || letter.source() == null) throw conflict("Не указан источник письма.");
        if ("RATE_LIMIT".equals(letter.errorCode()) && !run.aiFallbackConfirmed()) {
            if (!run.aiLetter().enabled() || letter.generationId() == null || !List.of("RATE_LIMIT", "FALLBACK").contains(letter.source()))
                throw conflict("Некорректное сообщение о лимите Codex.");
            if (letter.resetsAt() != null && (letter.resetsAt() < 0 || letter.resetsAt() > 253402300799L))
                throw conflict("Некорректное время сброса лимита.");
            repository.pauseAiLimit(itemId, run.id(), letter.resetsAt());
            return false;
        }
        if ("RATE_LIMIT_CONFIRMED".equals(letter.errorCode()) && !run.aiFallbackConfirmed())
            throw conflict("Использование резервного письма не подтверждено.");
        if ("AI_FAILED".equals(letter.source())) {
            if (!run.aiLetter().enabled() || !run.coverLetter().isBlank() || !"".equals(letter.text())
                    || letter.errorCode() == null || !letter.errorCode().matches("[A-Z_]{1,64}"))
                throw conflict("Некорректное сообщение об ошибке AI-письма.");
            repository.prepareLetter(itemId, letter);
            boolean temporaryOrLocal = List.of("SERVICE_UNAVAILABLE", "NETWORK_ERROR", "TIMEOUT", "GENERATION_TIMEOUT",
                    "INTERRUPTED", "VACANCY_TEXT_MISSING", "INVALID_OUTPUT", "EMPTY_LETTER").contains(letter.errorCode());
            complete(itemId, Outcome.AI_LETTER_FAILED, temporaryOrLocal ? null : QueueBlocker.GENERATION);
            return false;
        }
        org.hhclicker.backend.resume.model.AiLetterSettings.validateFallback(letter.text());
        if (letter.errorCode() != null && !letter.errorCode().matches("[A-Z_]{1,64}"))
            throw conflict("Некорректная причина fallback.");
        switch (letter.source()) {
            case "GENERATED" -> {
                var ai = run.aiLetter();
                if (!ai.enabled() || letter.generationId() == null || letter.errorCode() != null || letter.text().length() > ai.maxLength())
                    throw conflict("Некорректное AI-письмо.");
                int marker = ai.template().indexOf(org.hhclicker.backend.resume.model.AiLetterSettings.MARKER);
                String prefix = ai.template().substring(0, marker);
                String suffix = ai.template().substring(marker + org.hhclicker.backend.resume.model.AiLetterSettings.MARKER.length());
                if (!letter.text().startsWith(prefix) || !letter.text().endsWith(suffix)
                        || letter.text().length() <= prefix.length() + suffix.length()
                        || letter.text().substring(prefix.length(), letter.text().length() - suffix.length()).isBlank())
                    throw conflict("AI-письмо не соответствует шаблону запуска.");
            }
            case "FALLBACK", "STATIC" -> {
                if ("FALLBACK".equals(letter.source()) && run.coverLetter().isBlank())
                    throw conflict("Пустое резервное письмо запрещает автоматический отклик без AI-письма.");
                if (!letter.text().equals(run.coverLetter()) || run.aiLetter().enabled() != letter.source().equals("FALLBACK")
                        || (letter.source().equals("FALLBACK") && letter.errorCode() == null))
                    throw conflict("Обычное письмо не соответствует настройкам запуска.");
            }
            default -> throw conflict("Неизвестный источник письма.");
        }
        if (item.letter() != null) return item.letter().equals(letter);
        repository.prepareLetter(itemId, letter);
        return true;
    }

    public void complete(UUID itemId, Outcome outcome) {
        complete(itemId, outcome, null);
    }

    public void complete(UUID itemId, Outcome outcome, QueueBlocker blocker) {
        complete(itemId, outcome, blocker, null);
    }

    public void complete(UUID itemId, Outcome outcome, QueueBlocker blocker, String vacancyPlainText) {
        repository.lock();
        var item = repository.item(itemId);
        if (!item.status().equals("IN_PROGRESS")) return;
        var run = find(item.runId());
        if (run.mode() == RunMode.DRY_RUN) {
            if (outcome == Outcome.STOPPED && run.status() != RunStatus.CANCELLED && run.status() != RunStatus.COMPLETED) {
                repository.retry(itemId);
                repository.advance(run.id(), 0);
                return;
            }
            if (outcome != Outcome.TEXT_LOADED && outcome != Outcome.TEXT_UNAVAILABLE
                    && outcome != Outcome.AUTH && outcome != Outcome.CAPTCHA && outcome != Outcome.BROWSER_UNAVAILABLE
                    && outcome != Outcome.VACANCY_UNAVAILABLE && outcome != Outcome.VACANCY_ARCHIVED
                    && outcome != Outcome.STOPPED)
                throw conflict("Недопустимый результат холостого запуска.");
            if (outcome == Outcome.TEXT_LOADED) {
                if (vacancyPlainText == null || vacancyPlainText.isBlank()) outcome = Outcome.TEXT_UNAVAILABLE;
                else vacancies.saveText(item.vacancyId(), vacancyPlainText);
            }
            repository.finish(itemId, outcome, false);
            if (outcome == Outcome.VACANCY_ARCHIVED) vacancySelection.closeFromHh(item.vacancyId());
            int failures = repository.recordTechnicalFailure(run.id(), outcome == Outcome.TEXT_UNAVAILABLE);
            if (blocker == null) blocker = switch (outcome) {
                case AUTH -> QueueBlocker.AUTH;
                case CAPTCHA -> QueueBlocker.CAPTCHA;
                case BROWSER_UNAVAILABLE -> QueueBlocker.BROWSER;
                default -> null;
            };
            if (run.status() == RunStatus.RUNNING && (blocker != null || failures >= 3))
                repository.status(run.id(), blocker == QueueBlocker.AUTH ? RunStatus.PAUSED_AUTH
                        : blocker == QueueBlocker.CAPTCHA ? RunStatus.PAUSED_CAPTCHA : RunStatus.PAUSED_ERROR);
            repository.advance(run.id(), ThreadLocalRandom.current().nextInt(run.pauseFromSeconds(), run.pauseToSeconds() + 1));
            return;
        }
        if (outcome == Outcome.TEXT_LOADED || outcome == Outcome.TEXT_UNAVAILABLE)
            throw conflict("Результат загрузки текста недопустим для реального отклика.");
        // Bind the snapshot to the persisted task, never to an arbitrary ID from the agent.
        if (vacancyPlainText != null && outcome != Outcome.VACANCY_ARCHIVED)
            vacancies.saveText(item.vacancyId(), vacancyPlainText);
        if (outcome == Outcome.STOPPED && run.status() != RunStatus.CANCELLED && run.status() != RunStatus.COMPLETED) {
            // STOPPED is emitted only while the agent still knows that no HH submission click happened.
            // Drop the provisional permit and let the same vacancy start cleanly after resume.
            repository.retry(itemId);
            repository.advance(run.id(), 0);
            return;
        }
        if ((outcome == Outcome.SENT || outcome == Outcome.SENT_LETTER_UNCONFIRMED
                || outcome == Outcome.SENT_LETTER_REJECTED_ALREADY_VIEWED) && item.submissionStartedAt() == null)
            outcome = Outcome.UNKNOWN;
        if (outcome == Outcome.VACANCY_ARCHIVED && item.submissionStartedAt() != null)
            outcome = Outcome.UNKNOWN;
        boolean guard = outcome.applied() || (outcome == Outcome.UNKNOWN && item.submissionStartedAt() != null);
        repository.finish(itemId, outcome, guard);
        if (outcome == Outcome.VACANCY_ARCHIVED) vacancySelection.closeFromHh(item.vacancyId());
        if (outcome.applied()) {
            vacancies.markApplied(item.vacancyId());
            repository.resolvePreviousApplicationAttention(run.accountId(), item.hhVacancyId());
        }
        if (outcome == Outcome.QUESTIONNAIRE) vacancies.markQuestionnaire(item.vacancyId());
        int failures = repository.recordTechnicalFailure(run.id(), outcome == Outcome.NOT_SENT || outcome == Outcome.UNKNOWN || outcome == Outcome.AI_LETTER_FAILED);
        if (blocker == null) blocker = switch (outcome) {
            case AUTH -> QueueBlocker.AUTH;
            case CAPTCHA -> QueueBlocker.CAPTCHA;
            case RESUME_NOT_FOUND -> QueueBlocker.RESUME;
            case BROWSER_UNAVAILABLE -> QueueBlocker.BROWSER;
            default -> null;
        };
        if (run.status() == RunStatus.RUNNING && (blocker != null || failures >= 3)) {
            repository.status(run.id(), blocker == QueueBlocker.AUTH ? RunStatus.PAUSED_AUTH
                    : blocker == QueueBlocker.CAPTCHA ? RunStatus.PAUSED_CAPTCHA : RunStatus.PAUSED_ERROR);
        }
        repository.advance(run.id(), ThreadLocalRandom.current().nextInt(run.pauseFromSeconds(), run.pauseToSeconds() + 1));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void lost(UUID itemId) {
        var item = repository.item(itemId);
        complete(itemId, find(item.runId()).mode() == RunMode.DRY_RUN ? Outcome.TEXT_UNAVAILABLE
                : item.submissionStartedAt() == null ? Outcome.NOT_SENT : Outcome.UNKNOWN, QueueBlocker.BROWSER);
    }

    private boolean eligible(Item item) {
        var vacancy = vacancies.findById(item.vacancyId()).vacancy();
        return vacancy.status().isActive() && vacancy.queuedForApplication();
    }
}
