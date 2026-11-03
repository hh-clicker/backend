package org.hhclicker.backend.application.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApplicationModels {
    private ApplicationModels() {
    }

    public enum RunStatus {RUNNING, PAUSED_USER, PAUSED_CAPTCHA, PAUSED_AUTH, PAUSED_ERROR, PAUSED_LIMIT, PAUSED_AI_LIMIT, COMPLETED, CANCELLED}
    public enum RunMode {APPLY, DRY_RUN}

    public enum QueueBlocker {AUTH, CAPTCHA, BROWSER, RESUME, GENERATION}

    public enum Outcome {
        SENT, SENT_LETTER_UNCONFIRMED, SENT_LETTER_REJECTED_ALREADY_VIEWED, ALREADY_APPLIED, QUESTIONNAIRE, LETTER_REQUIRED,
        RESUME_UNAVAILABLE, RESUME_NOT_FOUND, VACANCY_UNAVAILABLE, VACANCY_ARCHIVED, BROWSER_UNAVAILABLE,
        CAPTCHA, AUTH, UNKNOWN, NOT_SENT, STOPPED, AI_LETTER_FAILED, TEXT_LOADED, TEXT_UNAVAILABLE;

        public boolean applied() {
            return this == SENT || this == SENT_LETTER_UNCONFIRMED
                    || this == SENT_LETTER_REJECTED_ALREADY_VIEWED || this == ALREADY_APPLIED;
        }

        public String title() {
            return switch (this) {
                case QUESTIONNAIRE -> "Заполнить анкету работодателя";
                case LETTER_REQUIRED -> "Добавить обязательное письмо";
                case AI_LETTER_FAILED -> "Не удалось подготовить AI-письмо";
                case SENT_LETTER_UNCONFIRMED, SENT_LETTER_REJECTED_ALREADY_VIEWED -> "Проверить сопроводительное письмо";
                case RESUME_UNAVAILABLE -> "Проверить резюме в форме отклика";
                case RESUME_NOT_FOUND -> "Восстановить доступ к выбранному резюме";
                case VACANCY_ARCHIVED -> "Вакансия в архиве";
                case CAPTCHA -> "Пройти проверку HH";
                case AUTH -> "Войти в аккаунт HH";
                case BROWSER_UNAVAILABLE -> "Проверить браузер и Browser Agent";
                case VACANCY_UNAVAILABLE -> "Проверить доступность вакансии";
                case NOT_SENT -> "Проверить страницу или форму отклика";
                case UNKNOWN -> "Проверить неподтверждённый отклик";
                case TEXT_UNAVAILABLE -> "Проверить текст вакансии";
                default -> "Результат отклика";
            };
        }

        public String message() {
            return switch (this) {
                case SENT -> "HH подтвердил отклик на эту вакансию.";
                case SENT_LETTER_UNCONFIRMED ->
                        "Отклик отправлен. Прикрепление письма не подтверждено — проверьте на HH. Повторного отклика не будет.";
                case SENT_LETTER_REJECTED_ALREADY_VIEWED ->
                        "Отклик отправлен, но сопроводительное письмо не прикрепилось: работодатель уже просмотрел отклик, и HH больше не позволяет его изменить. Повторного отклика не будет.";
                case ALREADY_APPLIED -> "На HH уже есть отклик. Повторно не отправляли.";
                case QUESTIONNAIRE -> "Нужно заполнить анкету работодателя вручную.";
                case LETTER_REQUIRED -> "HH требует сопроводительное письмо, но текст не задан.";
                case AI_LETTER_FAILED -> "AI-письмо не подготовлено. Резервное письмо не задано — отклик не отправлялся.";
                case RESUME_UNAVAILABLE -> "Нужное резюме нельзя выбрать для этой вакансии. Проверьте форму на HH.";
                case RESUME_NOT_FOUND ->
                        "Выбранное резюме не найдено среди доступных на HH. Очередь остановлена: проверьте аккаунт и доступность резюме.";
                case VACANCY_UNAVAILABLE ->
                        "Страница вакансии недоступна (404/410). Остальная очередь может продолжить работу.";
                case VACANCY_ARCHIVED -> "Вакансия перенесена в архив HH. Отклик не отправлялся.";
                case BROWSER_UNAVAILABLE ->
                        "Не удалось проверить готовность браузера и аккаунта. Проверьте Browser Agent и вход в HH.";
                case CAPTCHA -> "В браузере нужна ручная проверка. Автоматический обход не выполняется.";
                case AUTH -> "Нужно войти в HH в браузере этого аккаунта.";
                case UNKNOWN ->
                        "Предыдущий отклик не подтверждён. Если вакансия остаётся «К отклику», новый запуск сначала проверит её на HH: уже отправленный отклик не повторит, а при доступной кнопке «Откликнуться» выполнит обычный сценарий.";
                case NOT_SENT -> "Отклик не отправлялся: карточка или форма не распознана. Проверьте вкладку HH.";
                case STOPPED -> "Остановлено до отправки.";
                case TEXT_LOADED -> "Текст вакансии сохранён. Отклик не отправлялся.";
                case TEXT_UNAVAILABLE -> "Не удалось получить текст вакансии. Отклик не отправлялся.";
            };
        }
    }

    public record Start(UUID requestId, UUID resumeId, List<UUID> vacancyIds, int dailyLimit,
                        int pauseFromSeconds, int pauseToSeconds, String coverLetter, boolean skipQuestionnaires,
                        org.hhclicker.backend.resume.model.AiLetterSettings aiLetter, RunMode mode) {
        public Start {
            aiLetter = aiLetter == null ? org.hhclicker.backend.resume.model.AiLetterSettings.defaults() : aiLetter;
            mode = mode == null ? RunMode.APPLY : mode;
        }

        public Start(UUID requestId, UUID resumeId, List<UUID> vacancyIds, int dailyLimit, int pauseFromSeconds,
                     int pauseToSeconds, String coverLetter, boolean skipQuestionnaires,
                     org.hhclicker.backend.resume.model.AiLetterSettings aiLetter) {
            this(requestId, resumeId, vacancyIds, dailyLimit, pauseFromSeconds, pauseToSeconds, coverLetter,
                    skipQuestionnaires, aiLetter, RunMode.APPLY);
        }

        public Start(UUID requestId, UUID resumeId, List<UUID> vacancyIds, int dailyLimit, int pauseFromSeconds,
                     int pauseToSeconds, String coverLetter, boolean skipQuestionnaires) {
            this(requestId, resumeId, vacancyIds, dailyLimit, pauseFromSeconds, pauseToSeconds, coverLetter, skipQuestionnaires, null);
        }
    }

    public record RunSummary(UUID id, long sequence, UUID resumeId, String resumeName, RunStatus status,
                             Instant createdAt, long total, long attention, String revision,
                             Long estimatedRemainingSeconds, RunMode mode) {
        public RunSummary(UUID id, long sequence, UUID resumeId, String resumeName, RunStatus status,
                          Instant createdAt, long total, long attention, String revision, Long estimatedRemainingSeconds) {
            this(id, sequence, resumeId, resumeName, status, createdAt, total, attention, revision,
                    estimatedRemainingSeconds, RunMode.APPLY);
        }
    }

    public record Run(UUID id, long sequence, UUID accountId, UUID resumeId, UUID profileId, String hhResumeId,
                      String resumeName, RunStatus status, Instant createdAt, Instant finishedAt, int dailyLimit,
                      int accountDailyLimit, int pauseFromSeconds, int pauseToSeconds, String coverLetter,
                      boolean skipQuestionnaires, List<Item> applications,
                      org.hhclicker.backend.resume.model.AiLetterSettings aiLetter, Instant aiLimitResetsAt,
                      boolean aiFallbackConfirmed, RunMode mode) {
        public Run(UUID id, long sequence, UUID accountId, UUID resumeId, UUID profileId, String hhResumeId,
                   String resumeName, RunStatus status, Instant createdAt, Instant finishedAt, int dailyLimit,
                   int accountDailyLimit, int pauseFromSeconds, int pauseToSeconds, String coverLetter,
                   boolean skipQuestionnaires, List<Item> applications,
                   org.hhclicker.backend.resume.model.AiLetterSettings aiLetter, Instant aiLimitResetsAt,
                   boolean aiFallbackConfirmed) {
            this(id, sequence, accountId, resumeId, profileId, hhResumeId, resumeName, status, createdAt,
                    finishedAt, dailyLimit, accountDailyLimit, pauseFromSeconds, pauseToSeconds, coverLetter,
                    skipQuestionnaires, applications, aiLetter, aiLimitResetsAt, aiFallbackConfirmed, RunMode.APPLY);
        }
        public Run(UUID id, long sequence, UUID accountId, UUID resumeId, UUID profileId, String hhResumeId,
                   String resumeName, RunStatus status, Instant createdAt, Instant finishedAt, int dailyLimit,
                   int accountDailyLimit, int pauseFromSeconds, int pauseToSeconds, String coverLetter,
                   boolean skipQuestionnaires, List<Item> applications, org.hhclicker.backend.resume.model.AiLetterSettings aiLetter) {
            this(id, sequence, accountId, resumeId, profileId, hhResumeId, resumeName, status, createdAt, finishedAt,
                    dailyLimit, accountDailyLimit, pauseFromSeconds, pauseToSeconds, coverLetter, skipQuestionnaires, applications, aiLetter, null, false, RunMode.APPLY);
        }
        public Run(UUID id, long sequence, UUID accountId, UUID resumeId, UUID profileId, String hhResumeId,
                   String resumeName, RunStatus status, Instant createdAt, Instant finishedAt, int dailyLimit,
                   int accountDailyLimit, int pauseFromSeconds, int pauseToSeconds, String coverLetter,
                   boolean skipQuestionnaires, List<Item> applications) {
            this(id, sequence, accountId, resumeId, profileId, hhResumeId, resumeName, status, createdAt, finishedAt,
                    dailyLimit, accountDailyLimit, pauseFromSeconds, pauseToSeconds, coverLetter, skipQuestionnaires,
                    applications, org.hhclicker.backend.resume.model.AiLetterSettings.defaults());
        }
    }

    public record Item(UUID id, UUID runId, UUID vacancyId, String hhVacancyId, String vacancyTitle,
                       String companyName, String status, Outcome outcome, Instant attemptedAt,
                       Instant submissionStartedAt, boolean hasQuestionnaire, String attentionStatus,
                       PreparedLetter letter) {
        public Item(UUID id, UUID runId, UUID vacancyId, String hhVacancyId, String vacancyTitle, String companyName,
                    String status, Outcome outcome, Instant attemptedAt, Instant submissionStartedAt, boolean hasQuestionnaire, String attentionStatus) {
            this(id, runId, vacancyId, hhVacancyId, vacancyTitle, companyName, status, outcome, attemptedAt,
                    submissionStartedAt, hasQuestionnaire, attentionStatus, null);
        }

        public String message() {
            if (outcome == Outcome.AI_LETTER_FAILED && letter != null && letter.errorCode() != null) {
                String reason = switch (letter.errorCode()) {
                    case "AUTH_REQUIRED" -> "Требуется вход в Codex.";
                    case "RESUME_TEXT_MISSING" -> "Нет текста резюме.";
                    case "VACANCY_TEXT_MISSING" -> "Нет текста вакансии.";
                    case "TIMEOUT", "GENERATION_TIMEOUT" -> "Превышено время ожидания генерации.";
                    case "SERVICE_UNAVAILABLE", "NETWORK_ERROR" -> "Не удалось связаться с генератором.";
                    case "AUTH_CHECK_FAILED" -> "Не удалось проверить авторизацию Codex.";
                    case "MODEL_UNAVAILABLE" -> "Выбранная модель недоступна.";
                    case "INVALID_TEMPLATE" -> "Некорректный шаблон AI-письма.";
                    case "INVALID_OUTPUT", "EMPTY_LETTER" -> "Ответ генератора пустой, некорректный или превышает допустимую длину.";
                    default -> "Ошибка генерации: " + letter.errorCode() + ".";
                };
                return reason + " Резервное письмо не задано — отклик не отправлялся.";
            }
            return outcome == null ? null : outcome.message();
        }
    }

    public record Attention(UUID id, String kind, String status, String title, String description,
                            UUID runId, long runSequence, UUID applicationId, UUID vacancyId, String hhVacancyId,
                            String vacancyTitle, String employerId, String companyName, String resumeName,
                            Instant occurredAt, Outcome outcome, boolean canContinueRun, boolean canSkipVacancy,
                            RunMode mode) {
        public Attention(UUID id, String kind, String status, String title, String description,
                         UUID runId, long runSequence, UUID applicationId, UUID vacancyId, String hhVacancyId,
                         String vacancyTitle, String employerId, String companyName, String resumeName,
                         Instant occurredAt, Outcome outcome, boolean canContinueRun, boolean canSkipVacancy) {
            this(id, kind, status, title, description, runId, runSequence, applicationId, vacancyId,
                    hhVacancyId, vacancyTitle, employerId, companyName, resumeName, occurredAt, outcome,
                    canContinueRun, canSkipVacancy, RunMode.APPLY);
        }
    }

    public record Task(UUID itemId, UUID runId, UUID profileId, String hhResumeId, String hhVacancyId,
                       String sourceUrl, int searchPage, String coverLetter,
                       org.hhclicker.backend.resume.model.AiLetterSettings aiLetter, String resumePlainText,
                       boolean aiFallbackConfirmed, int generationAttempt, RunMode mode) {
        public Task(UUID itemId, UUID runId, UUID profileId, String hhResumeId, String hhVacancyId,
                    String sourceUrl, int searchPage, String coverLetter,
                    org.hhclicker.backend.resume.model.AiLetterSettings aiLetter, String resumePlainText,
                    boolean aiFallbackConfirmed, int generationAttempt) {
            this(itemId, runId, profileId, hhResumeId, hhVacancyId, sourceUrl, searchPage, coverLetter,
                    aiLetter, resumePlainText, aiFallbackConfirmed, generationAttempt, RunMode.APPLY);
        }
        public Task(UUID itemId, UUID runId, UUID profileId, String hhResumeId, String hhVacancyId,
                    String sourceUrl, int searchPage, String coverLetter,
                    org.hhclicker.backend.resume.model.AiLetterSettings aiLetter, String resumePlainText) {
            this(itemId, runId, profileId, hhResumeId, hhVacancyId, sourceUrl, searchPage, coverLetter, aiLetter, resumePlainText, false, 0);
        }
        public Task(UUID itemId, UUID profileId, String hhResumeId, String hhVacancyId, String sourceUrl, int searchPage, String coverLetter) {
            this(itemId, itemId, profileId, hhResumeId, hhVacancyId, sourceUrl, searchPage, coverLetter,
                    org.hhclicker.backend.resume.model.AiLetterSettings.defaults(), null);
        }
    }

    public record PreparedLetter(String text, String source, String errorCode, UUID generationId, Long resetsAt) {
        public PreparedLetter(String text, String source, String errorCode, UUID generationId) { this(text, source, errorCode, generationId, null); }
    }
}
