package org.hhclicker.backend.agent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.agent.exception.BrowserAgentCommandConflictException;
import org.hhclicker.backend.agent.exception.BrowserAgentCommandNotFoundException;
import org.hhclicker.backend.agent.exception.InvalidBrowserAgentDataException;
import org.hhclicker.backend.agent.model.*;
import org.hhclicker.backend.application.model.ApplicationModels.*;
import org.hhclicker.backend.application.service.ApplicationRunGateway;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.browser.model.BrowserAuthorizationResult;
import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.browser.model.BrowserResumeSummary;
import org.hhclicker.backend.browser.service.BrowserAuthorizationService;
import org.hhclicker.backend.browser.service.BrowserProfileLookup;
import org.hhclicker.backend.browser.service.BrowserRuntimeGateway;
import org.hhclicker.backend.browser.service.BrowserRuntimeSnapshot;
import org.hhclicker.backend.search.service.SearchProfile;
import org.hhclicker.backend.search.service.SearchScanGateway;
import org.hhclicker.backend.search.service.SearchService;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.vacancy.catalog.service.NormalizedVacancy;
import org.hhclicker.backend.vacancy.selection.service.QuestionnaireApplicationVerificationGateway;
import org.hhclicker.backend.vacancy.selection.service.VacancySelectionService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class BrowserAgentCommandService implements BrowserRuntimeGateway, SearchScanGateway, ApplicationRunGateway,
        QuestionnaireApplicationVerificationGateway {

    private static final int MAX_COMMAND_HISTORY = 200;

    private final Clock clock;
    private final Duration commandTimeout;
    private final BrowserAgentStatusService statusService;
    private final BrowserAuthorizationService authorizationService;
    private final SearchService searches;
    private final BrowserProfileLookup profiles;
    private final ApplicationService applications;
    private final VacancySelectionService vacancySelection;
    private final Map<UUID, BrowserAgentCommand> commands = new LinkedHashMap<>();
    private final Deque<UUID> queue = new ArrayDeque<>();
    // Access only under this monitor. Persisting scans remain IN_PROGRESS until commit/publication.
    private final Set<UUID> completingScans = new HashSet<>();

    public BrowserAgentCommandService(
            Clock clock,
            @Value("${hh-clicker.browser-agent.command-timeout:30s}") Duration commandTimeout,
            BrowserAgentStatusService statusService,
            BrowserAuthorizationService authorizationService,
            SearchService searches,
            BrowserProfileLookup profiles,
            ApplicationService applications,
            VacancySelectionService vacancySelection
    ) {
        this.clock = clock;
        this.commandTimeout = commandTimeout;
        this.statusService = statusService;
        this.authorizationService = authorizationService;
        this.searches = searches;
        this.profiles = profiles;
        this.applications = applications;
        this.vacancySelection = vacancySelection;
    }

    @Override
    public BrowserRuntimeSnapshot getSnapshot() {
        var snapshot = statusService.getStatus();
        return new BrowserRuntimeSnapshot(snapshot.status().name(), snapshot.version(), snapshot.browserOpen(), snapshot.activeProfileId());
    }

    @Override
    public synchronized UUID open(BrowserProfile profile) {
        return enqueue(BrowserAgentCommandType.OPEN_BROWSER, BrowserAgentCommandPayload.from(profile)).id();
    }

    @Override
    public synchronized UUID close() {
        return enqueue(BrowserAgentCommandType.CLOSE_BROWSER, null).id();
    }

    @Override
    public synchronized UUID openUrl(BrowserProfile profile, String url) {
        return enqueue(BrowserAgentCommandType.OPEN_URL, BrowserAgentCommandPayload.forUrl(profile, url)).id();
    }

    @Override
    public synchronized UUID checkAuthorization(BrowserProfile profile) {
        return enqueue(BrowserAgentCommandType.CHECK_AUTHORIZATION, BrowserAgentCommandPayload.from(profile)).id();
    }

    @Override
    public synchronized UUID verifyQuestionnaireApplication(UUID vacancyId) {
        var vacancy = vacancySelection.questionnaireVerificationTarget(vacancyId);
        var profile = profiles.forAccount(applications.accountForResume(vacancy.resumeId()));
        requireApplicationBrowser(profile.id());
        return enqueue(BrowserAgentCommandType.INSPECT_APPLICATION,
                BrowserAgentCommandPayload.forApplicationInspection(profile, vacancy.id(), vacancy.hhVacancyId())).id();
    }

    private boolean includesAuthorization(BrowserAgentCommandType type) {
        return type == BrowserAgentCommandType.OPEN_BROWSER || type == BrowserAgentCommandType.CHECK_AUTHORIZATION
                || type == BrowserAgentCommandType.DISCOVER_RESUMES || type == BrowserAgentCommandType.SCAN_SEARCH;
    }

    @Override
    public synchronized SearchProfile scanSearch(UUID searchId) {
        expireStaleCommands();
        if (applications.busy())
            throw new BrowserAgentCommandConflictException("Завершите или отмените запуск откликов перед сканированием.");
        var runtime = getSnapshot();
        if (!"READY".equals(runtime.agentStatus())) {
            throw new BrowserAgentCommandConflictException("Запустите Browser Agent и дождитесь, пока он освободится.");
        }
        if (commands.values().stream().anyMatch(c -> c.status() == BrowserAgentCommandStatus.QUEUED || c.status() == BrowserAgentCommandStatus.IN_PROGRESS)) {
            throw new BrowserAgentCommandConflictException("Дождитесь завершения предыдущей команды браузера.");
        }
        var profile = profiles.forAccount(searches.accountIdForSearch(searchId));
        if (!runtime.browserOpen() || !profile.id().equals(runtime.activeProfileId())) {
            throw new BrowserAgentCommandConflictException("Откройте браузер выбранного HH-аккаунта на странице «Браузер».");
        }
        var scan = searches.scan(searchId);
        var search = scan.profile();
        enqueue(BrowserAgentCommandType.SCAN_SEARCH,
                new BrowserAgentCommandPayload(profile.id(), scan.sourceUrl(), searchId, search.search().pagesLimit()));
        return search;
    }

    @Scheduled(fixedDelay = 5000)
    public synchronized void expireCommands() {
        expireStaleCommands();
    }

    @Override
    public synchronized UUID discoverResumes(BrowserProfile profile) {
        return enqueue(BrowserAgentCommandType.DISCOVER_RESUMES, BrowserAgentCommandPayload.from(profile)).id();
    }

    @Override
    public synchronized UUID readResumeText(BrowserProfile profile, String hhResumeId) {
        if (hhResumeId == null || !hhResumeId.matches("[a-f0-9]{20,100}")) {
            throw new InvalidBrowserAgentDataException("Некорректный идентификатор резюме.");
        }
        return enqueue(BrowserAgentCommandType.DISCOVER_RESUMES, BrowserAgentCommandPayload.forUrl(profile,
                "https://hh.ru/resume/" + hhResumeId + "?print=true")).id();
    }

    private BrowserAgentCommand enqueue(BrowserAgentCommandType type, BrowserAgentCommandPayload payload) {
        expireStaleCommands();
        boolean inspecting = payload != null && applications.inspectionAllowed(payload.profileId())
                && (type == BrowserAgentCommandType.OPEN_BROWSER || type == BrowserAgentCommandType.OPEN_URL
                || type == BrowserAgentCommandType.CHECK_AUTHORIZATION || type == BrowserAgentCommandType.INSPECT_APPLICATION);
        if (type != BrowserAgentCommandType.APPLY_VACANCY && applications.busy() && !inspecting) {
            throw new BrowserAgentCommandConflictException("Завершите или отмените запуск откликов перед управлением браузером. Ручные действия в Chrome доступны.");
        }
        Optional<BrowserAgentCommand> pending = commands.values().stream()
                .filter(command -> command.type() == type)
                .filter(command -> type == BrowserAgentCommandType.CLOSE_BROWSER
                        || Objects.equals(command.payload(), payload))
                .filter(command -> command.status() == BrowserAgentCommandStatus.QUEUED
                        || command.status() == BrowserAgentCommandStatus.IN_PROGRESS)
                .findFirst();

        if (pending.isPresent()) {
            return pending.get();
        }

        BrowserAgentCommand command = new BrowserAgentCommand(
                UUID.randomUUID(),
                type,
                payload,
                BrowserAgentCommandStatus.QUEUED,
                null,
                clock.instant(),
                null,
                null,
                null,
                null,
                null
        );
        commands.put(command.id(), command);
        queue.addLast(command.id());
        return command;
    }

    public synchronized Optional<BrowserAgentCommand> claimNext(String agentId) {
        expireStaleCommands();
        if (commands.values().stream().anyMatch(c -> c.status() == BrowserAgentCommandStatus.IN_PROGRESS))
            return Optional.empty();
        if (queue.isEmpty()) {
            var runtime = getSnapshot();
            if (runtime.browserOpen() && runtime.activeProfileId() != null
                    && Objects.equals(statusService.getStatus().agentId(), agentId.trim())) {
                applications.claim(runtime.activeProfileId()).ifPresent(task -> enqueue(BrowserAgentCommandType.APPLY_VACANCY,
                        new BrowserAgentCommandPayload(task.profileId(), null, null, null, task)));
            }
        }
        UUID commandId = queue.peekFirst();
        if (commandId == null) {
            return Optional.empty();
        }

        BrowserAgentCommand queued = getRequired(commandId);
        if (includesAuthorization(queued.type())) {
            authorizationService.record(queued.payload().profileId(), AuthorizationStatus.UNKNOWN);
        }
        BrowserAgentCommand claimed = new BrowserAgentCommand(
                queued.id(),
                queued.type(),
                queued.payload(),
                BrowserAgentCommandStatus.IN_PROGRESS,
                agentId.trim(),
                queued.createdAt(),
                clock.instant(),
                null,
                null,
                null,
                null
        );
        queue.removeFirst();
        commands.put(claimed.id(), claimed);
        return Optional.of(claimed);
    }

    public BrowserAgentCommand complete(
            UUID commandId,
            String agentId,
            BrowserAgentCommandResultStatus resultStatus,
            String errorMessage,
            BrowserAuthorizationResult authorization,
            List<BrowserResumeSummary> resumes,
            List<NormalizedVacancy> vacancies,
            Outcome application,
            QueueBlocker applicationBlocker,
            String vacancyPlainText
    ) {
        BrowserAgentCommand command;
        synchronized (this) {
            expireStaleCommands();
            command = getRequired(commandId);
            if (command.type() != BrowserAgentCommandType.SCAN_SEARCH) {
                return completeResult(command, agentId, resultStatus, errorMessage, authorization,
                        resumes, vacancies, application, applicationBlocker, vacancyPlainText);
            }
            if (!Objects.equals(command.agentId(), agentId.trim())) {
                throw new BrowserAgentCommandConflictException("Команда назначена другому Browser Agent.");
            }
            if (command.status() == BrowserAgentCommandStatus.SUCCEEDED || command.status() == BrowserAgentCommandStatus.FAILED)
                return command;
            if (command.status() != BrowserAgentCommandStatus.IN_PROGRESS || !completingScans.add(commandId)) {
                throw new BrowserAgentCommandConflictException("Результат сканирования уже обрабатывается или команда ещё не начата.");
            }
        }
        try {
            // The database transaction may take time; polling and expiration of other commands stay available.
            return completeResult(command, agentId, resultStatus, errorMessage, authorization,
                    resumes, vacancies, application, applicationBlocker, vacancyPlainText);
        } finally {
            synchronized (this) {
                completingScans.remove(commandId);
            }
        }
    }

    private BrowserAgentCommand completeResult(BrowserAgentCommand command, String agentId,
                                               BrowserAgentCommandResultStatus resultStatus, String errorMessage, BrowserAuthorizationResult authorization,
                                               List<BrowserResumeSummary> resumes, List<NormalizedVacancy> vacancies, Outcome application,
                                               QueueBlocker applicationBlocker, String vacancyPlainText) {

        // A delivered response can be lost: acknowledging the same result must be idempotent.
        if (Objects.equals(command.agentId(), agentId.trim())
                && (command.status() == BrowserAgentCommandStatus.SUCCEEDED || command.status() == BrowserAgentCommandStatus.FAILED)) {
            return command;
        }

        if (command.status() != BrowserAgentCommandStatus.IN_PROGRESS) {
            throw new BrowserAgentCommandConflictException("Команда Browser Agent уже завершена или ещё не начата.");
        }
        if (!command.agentId().equals(agentId.trim())) {
            throw new BrowserAgentCommandConflictException("Команда назначена другому Browser Agent.");
        }

        boolean discovered = command.type() == BrowserAgentCommandType.DISCOVER_RESUMES
                && resultStatus == BrowserAgentCommandResultStatus.SUCCEEDED
                && authorization != null && authorization.status() == AuthorizationStatus.AUTHORIZED;
        if (discovered && resumes == null) {
            throw new InvalidBrowserAgentDataException("Не передан список найденных резюме.");
        }
        if (discovered && command.payload().url() != null && (resumes.size() != 1
                || !("https://hh.ru/resume/" + resumes.getFirst().hhResumeId() + "?print=true").equals(command.payload().url())
                || resumes.getFirst().plainText() == null || resumes.getFirst().plainText().isBlank())) {
            throw new InvalidBrowserAgentDataException("Агент не вернул текст выбранного резюме. Обновите Browser Agent.");
        }
        if (!discovered && resumes != null) {
            throw new InvalidBrowserAgentDataException("Список резюме недопустим для этой команды или без подтверждённого входа.");
        }

        if (includesAuthorization(command.type()) && resultStatus == BrowserAgentCommandResultStatus.SUCCEEDED) {
            if (authorization == null || authorization.status() == null) {
                throw new InvalidBrowserAgentDataException("Команда должна содержать результат проверки авторизации.");
            }
            authorizationService.record(command.payload().profileId(), authorization.status());
        } else if (authorization != null) {
            throw new InvalidBrowserAgentDataException("Результат авторизации недопустим для этой команды.");
        }

        if (command.type() == BrowserAgentCommandType.SCAN_SEARCH) {
            if (resultStatus == BrowserAgentCommandResultStatus.SUCCEEDED
                    && authorization != null && authorization.status() == AuthorizationStatus.AUTHORIZED) {
                if (vacancies == null || vacancies.size() > command.payload().pagesLimit() * 100) {
                    throw new InvalidBrowserAgentDataException("Недопустимый размер результата сканирования.");
                }
                try {
                    searches.completeScan(command.payload().searchId(), vacancies);
                } catch (ApplicationException exception) {
                    resultStatus = BrowserAgentCommandResultStatus.FAILED;
                    errorMessage = exception.safeMessage();
                }
            } else {
                resultStatus = BrowserAgentCommandResultStatus.FAILED;
                if (authorization != null) errorMessage = authorization.message();
            }
            if (resultStatus == BrowserAgentCommandResultStatus.FAILED) {
                searches.failScan(command.payload().searchId(), normalizeErrorMessage(resultStatus, errorMessage));
            }
        } else if (vacancies != null) {
            throw new InvalidBrowserAgentDataException("Результат поиска недопустим для этой команды.");
        }

        if (command.type() == BrowserAgentCommandType.APPLY_VACANCY) {
            if (resultStatus == BrowserAgentCommandResultStatus.SUCCEEDED && application != null) {
                applications.complete(command.payload().application().itemId(), application, applicationBlocker, vacancyPlainText);
                if (application == Outcome.AUTH || applicationBlocker == QueueBlocker.AUTH)
                    authorizationService.record(command.payload().profileId(), AuthorizationStatus.REQUIRED);
                else if (application == Outcome.CAPTCHA || applicationBlocker == QueueBlocker.CAPTCHA)
                    authorizationService.record(command.payload().profileId(), AuthorizationStatus.UNKNOWN);
                else if (application.applied())
                    authorizationService.record(command.payload().profileId(), AuthorizationStatus.AUTHORIZED);
            } else {
                applications.lost(command.payload().application().itemId());
            }
        } else if (command.type() == BrowserAgentCommandType.INSPECT_APPLICATION) {
            if (resultStatus == BrowserAgentCommandResultStatus.FAILED) {
                if (application != null || applicationBlocker != null || vacancyPlainText != null) {
                    throw new InvalidBrowserAgentDataException("Недопустимый результат проверки отклика.");
                }
            } else if (application == null
                    || !EnumSet.of(Outcome.ALREADY_APPLIED, Outcome.QUESTIONNAIRE, Outcome.NOT_SENT,
                        Outcome.VACANCY_UNAVAILABLE, Outcome.VACANCY_ARCHIVED,
                        Outcome.BROWSER_UNAVAILABLE, Outcome.CAPTCHA, Outcome.AUTH)
                        .contains(application) || applicationBlocker != null || vacancyPlainText != null) {
                throw new InvalidBrowserAgentDataException("Недопустимый результат проверки отклика.");
            }
            if (application == Outcome.ALREADY_APPLIED) {
                try {
                    vacancySelection.markQuestionnaireApplied(command.payload().vacancyId());
                    authorizationService.record(command.payload().profileId(), AuthorizationStatus.AUTHORIZED);
                } catch (ApplicationException exception) {
                    resultStatus = BrowserAgentCommandResultStatus.FAILED;
                    errorMessage = exception.safeMessage();
                    application = null;
                }
            } else if (application == Outcome.VACANCY_ARCHIVED) {
                vacancySelection.closeFromHh(command.payload().vacancyId());
            } else if (application == Outcome.AUTH) {
                authorizationService.record(command.payload().profileId(), AuthorizationStatus.REQUIRED);
            } else if (application == Outcome.CAPTCHA) {
                authorizationService.record(command.payload().profileId(), AuthorizationStatus.UNKNOWN);
            }
        } else if (application != null || applicationBlocker != null || vacancyPlainText != null) {
            throw new InvalidBrowserAgentDataException("Результат отклика недопустим для этой команды.");
        }
        Instant completedAt = clock.instant();
        BrowserAgentCommand completed = new BrowserAgentCommand(
                command.id(),
                command.type(),
                command.payload(),
                resultStatus == BrowserAgentCommandResultStatus.SUCCEEDED
                        ? BrowserAgentCommandStatus.SUCCEEDED
                        : BrowserAgentCommandStatus.FAILED,
                command.agentId(),
                command.createdAt(),
                command.startedAt(),
                completedAt,
                normalizeErrorMessage(resultStatus, errorMessage),
                authorization,
                resumes == null ? null : List.copyOf(resumes),
                application
        );
        synchronized (this) {
            commands.put(completed.id(), completed);
            if (resultStatus == BrowserAgentCommandResultStatus.SUCCEEDED) {
                if (command.type() == BrowserAgentCommandType.OPEN_BROWSER) {
                    statusService.updateBrowserState(true, command.payload().profileId());
                } else if (command.type() == BrowserAgentCommandType.CLOSE_BROWSER) {
                    statusService.updateBrowserState(false, null);
                }
            }
            pruneHistory();
        }
        return completed;
    }

    public synchronized BrowserAgentCommand findById(UUID commandId) {
        expireStaleCommands();
        return getRequired(commandId);
    }

    public synchronized void updateScanProgress(UUID commandId, String agentId, int currentPage,
                                                int totalPages, int collected, Integer totalResults) {
        expireStaleCommands();
        BrowserAgentCommand command = getRequired(commandId);
        if (command.type() != BrowserAgentCommandType.SCAN_SEARCH
                || command.status() != BrowserAgentCommandStatus.IN_PROGRESS
                || !Objects.equals(command.agentId(), agentId.trim())) {
            throw new BrowserAgentCommandConflictException("Команда сканирования уже завершена или назначена другому Browser Agent.");
        }
        searches.updateScanProgress(command.payload().searchId(), currentPage, totalPages, collected, totalResults);
    }

    private BrowserAgentCommand getRequired(UUID commandId) {
        BrowserAgentCommand command = commands.get(commandId);
        if (command == null) {
            throw new BrowserAgentCommandNotFoundException(commandId);
        }
        return command;
    }

    private String normalizeErrorMessage(BrowserAgentCommandResultStatus status, String errorMessage) {
        if (status == BrowserAgentCommandResultStatus.SUCCEEDED) {
            return null;
        }
        if (errorMessage == null || errorMessage.isBlank()) {
            return "Browser Agent не сообщил причину ошибки.";
        }
        return errorMessage.trim();
    }

    private void pruneHistory() {
        while (commands.size() > MAX_COMMAND_HISTORY) {
            UUID completedCommandId = commands.values().stream()
                    .filter(command -> command.status() == BrowserAgentCommandStatus.SUCCEEDED
                            || command.status() == BrowserAgentCommandStatus.FAILED)
                    .map(BrowserAgentCommand::id)
                    .findFirst()
                    .orElse(null);
            if (completedCommandId == null) {
                return;
            }
            commands.remove(completedCommandId);
        }
    }

    private void expireStaleCommands() {
        Instant now = clock.instant();
        commands.replaceAll((commandId, command) -> {
            if (completingScans.contains(commandId)) return command;
            boolean queuedExpired = command.status() == BrowserAgentCommandStatus.QUEUED
                    && !now.isBefore(command.createdAt().plusSeconds(60));
            boolean runningExpired = command.status() == BrowserAgentCommandStatus.IN_PROGRESS
                    && command.startedAt() != null
                    && !now.isBefore(command.startedAt().plus(effectiveTimeout(command.type())));
            if (!queuedExpired && !runningExpired) {
                return command;
            }

            queue.remove(commandId);
            if (command.type() == BrowserAgentCommandType.APPLY_VACANCY)
                applications.lost(command.payload().application().itemId());
            if (command.type() == BrowserAgentCommandType.SCAN_SEARCH) {
                searches.failScan(command.payload().searchId(), "Агент не завершил сбор вовремя. Проверьте браузер и повторите сканирование.");
            }

            return new BrowserAgentCommand(
                    command.id(),
                    command.type(),
                    command.payload(),
                    BrowserAgentCommandStatus.FAILED,
                    command.agentId(),
                    command.createdAt(),
                    command.startedAt(),
                    now,
                    "Browser Agent не подтвердил выполнение команды вовремя.",
                    null,
                    null
            );
        });
    }

    private Duration effectiveTimeout(BrowserAgentCommandType type) {
        if (type == BrowserAgentCommandType.DISCOVER_RESUMES) return Duration.ofSeconds(90);
        if (type == BrowserAgentCommandType.INSPECT_APPLICATION) return Duration.ofSeconds(60);
        // Includes up to 210 seconds of generation before the independently bounded HH flow.
        if (type == BrowserAgentCommandType.APPLY_VACANCY) return Duration.ofMinutes(8);
        // Twenty pages may each spend up to 30 seconds navigating and 45 seconds settling on a slow connection.
        if (type == BrowserAgentCommandType.SCAN_SEARCH) return Duration.ofMinutes(40);
        // Запуск Chrome и загрузка страницы профиля выполняются в одной команде.
        return includesAuthorization(type) && commandTimeout.compareTo(Duration.ofSeconds(60)) < 0
                ? Duration.ofSeconds(60) : commandTimeout;
    }

    @Override
    public synchronized Run startApplications(Start start) {
        // An already accepted request must remain retrievable even while the agent is busy.
        if (applications.exists(start.requestId())) return applications.start(start);
        requireApplicationBrowser(profiles.forAccount(applications.accountForResume(start.resumeId())).id());
        return applications.start(start);
    }

    @Override
    public synchronized Run changeApplicationStatus(UUID id, RunStatus status) {
        if (status == RunStatus.RUNNING) requireApplicationBrowser(applications.find(id).profileId());
        return applications.changeStatus(id, status);
    }

    @Override
    public synchronized Run changeApplicationStatus(UUID id, RunStatus status, boolean confirmFallback) {
        if (status == RunStatus.RUNNING) requireApplicationBrowser(applications.find(id).profileId());
        return applications.changeStatus(id, status, confirmFallback);
    }

    private void requireApplicationBrowser(UUID profileId) {
        expireStaleCommands();
        var runtime = getSnapshot();
        if (!"READY".equals(runtime.agentStatus()) || !runtime.browserOpen() || !profileId.equals(runtime.activeProfileId())) {
            throw new BrowserAgentCommandConflictException("Откройте браузер нужного HH-аккаунта и дождитесь готовности Browser Agent.");
        }
        if (commands.values().stream().anyMatch(c -> c.status() == BrowserAgentCommandStatus.IN_PROGRESS || c.status() == BrowserAgentCommandStatus.QUEUED)) {
            throw new BrowserAgentCommandConflictException("Дождитесь завершения текущей команды браузера.");
        }
    }

    public synchronized boolean applicationControl(UUID id, String agentId, boolean permit) {
        expireStaleCommands();
        var command = getRequired(id);
        if (command.type() != BrowserAgentCommandType.APPLY_VACANCY || command.status() != BrowserAgentCommandStatus.IN_PROGRESS
                || !Objects.equals(command.agentId(), agentId.trim())) return false;
        UUID itemId = command.payload().application().itemId();
        return permit ? applications.permit(itemId) : applications.allowed(itemId);
    }

    public synchronized boolean prepareApplicationLetter(UUID id, String agentId,
                                                         org.hhclicker.backend.application.model.ApplicationModels.PreparedLetter letter) {
        expireStaleCommands();
        var command = getRequired(id);
        if (command.type() != BrowserAgentCommandType.APPLY_VACANCY || command.status() != BrowserAgentCommandStatus.IN_PROGRESS
                || !Objects.equals(command.agentId(), agentId.trim())) return false;
        return applications.prepareLetter(command.payload().application().itemId(), letter);
    }
}
