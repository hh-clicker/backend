package org.hhclicker.backend.application;

import org.hhclicker.backend.application.model.ApplicationModels.*;
import org.hhclicker.backend.application.repository.ApplicationRepository;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.browser.service.BrowserProfileLookup;
import org.hhclicker.backend.resume.model.AiLetterSettings;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.resume.model.ResumeStatistics;
import org.hhclicker.backend.resume.service.ResumeProfile;
import org.hhclicker.backend.resume.service.ResumeService;
import org.hhclicker.backend.vacancy.catalog.model.Vacancy;
import org.hhclicker.backend.vacancy.catalog.model.VacancyApplicationSource;
import org.hhclicker.backend.vacancy.catalog.model.VacancyListing;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;
import org.hhclicker.backend.vacancy.catalog.service.VacancyCatalogService;
import org.hhclicker.backend.vacancy.selection.service.VacancySelectionService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Общая обвязка unit-тестов прикладной машины состояний откликов.
 * <p>
 * Тесты работают на моках репозитория и соседних read-сервисов: проверяется решение сервиса,
 * а не SQL. Настоящий SQL, транзакции и ограничения PostgreSQL проверяются отдельно в {@code *IT}.
 */
abstract class ApplicationServiceTestSupport {

    protected static final String HH_RESUME_ID = "abcdef0123456789abcd";
    protected static final String HH_VACANCY_ID = "12345678";
    protected static final String SOURCE_URL = "https://hh.ru/search/vacancy?page=0";
    protected static final Instant NOW = Instant.parse("2026-09-04T09:00:00Z");
    protected static final String AI_TEMPLATE = "Здравствуйте!\n#AI_CHANGE#\nСпасибо!";

    protected final ApplicationRepository repository = mock(ApplicationRepository.class);
    protected final ResumeService resumes = mock(ResumeService.class);
    protected final BrowserProfileLookup profiles = mock(BrowserProfileLookup.class);
    protected final VacancyCatalogService vacancies = mock(VacancyCatalogService.class);
    protected final VacancySelectionService vacancySelection = mock(VacancySelectionService.class);
    protected final ApplicationService service = new ApplicationService(repository, resumes, profiles, vacancies, vacancySelection);

    protected final UUID accountId = UUID.randomUUID();
    protected final UUID resumeId = UUID.randomUUID();
    protected final UUID profileId = UUID.randomUUID();
    protected final UUID runId = UUID.randomUUID();
    protected final UUID itemId = UUID.randomUUID();
    protected final UUID vacancyId = UUID.randomUUID();

    protected Item item(String status, Outcome outcome, Instant submissionStartedAt) {
        return item(status, outcome, submissionStartedAt, null, "OPEN");
    }

    protected Item item(String status, Outcome outcome, Instant submissionStartedAt, PreparedLetter letter, String attentionStatus) {
        return new Item(itemId, runId, vacancyId, HH_VACANCY_ID, "Java-разработчик", "Синтетическая компания",
                status, outcome, NOW, submissionStartedAt, false, attentionStatus, letter);
    }

    protected Run run(RunStatus status, List<Item> items) {
        return run(status, items, AiLetterSettings.defaults(), "Обычное письмо", 20, 20);
    }

    protected Run run(RunStatus status, List<Item> items, AiLetterSettings ai, String coverLetter,
                      int dailyLimit, int accountDailyLimit) {
        return new Run(runId, 1, accountId, resumeId, profileId, HH_RESUME_ID, "Java-разработчик", status,
                NOW, null, dailyLimit, accountDailyLimit, 10, 10, coverLetter, true, items, ai);
    }

    /**
     * Готовит один item и его запуск в состоянии, из которого начинается проверяемый переход.
     */
    protected Run prepare(String itemStatus, Outcome outcome, Instant submissionStartedAt, RunStatus runStatus) {
        Item item = item(itemStatus, outcome, submissionStartedAt);
        Run run = run(runStatus, List.of(item));
        stub(item, run);
        stubVacancy(VacancyStatus.NEW, true);
        return run;
    }

    protected void stub(Item item, Run run) {
        when(repository.item(item.id())).thenReturn(item);
        when(repository.find(run.id())).thenReturn(Optional.of(run));
        stubVacancy(VacancyStatus.NEW, true);
    }

    protected Vacancy stubVacancy(VacancyStatus status, boolean queued) {
        return stubVacancy(vacancyId, HH_VACANCY_ID, status, queued);
    }

    protected Vacancy stubVacancy(UUID id, String hhVacancyId, VacancyStatus status, boolean queued) {
        Vacancy vacancy = mock(Vacancy.class);
        when(vacancy.id()).thenReturn(id);
        when(vacancy.resumeId()).thenReturn(resumeId);
        when(vacancy.hhVacancyId()).thenReturn(hhVacancyId);
        when(vacancy.title()).thenReturn("Java-разработчик");
        when(vacancy.companyName()).thenReturn("Синтетическая компания");
        when(vacancy.employerId()).thenReturn("3529");
        when(vacancy.status()).thenReturn(status);
        when(vacancy.queuedForApplication()).thenReturn(queued);
        when(vacancies.findById(id)).thenReturn(new VacancyListing(vacancy, UUID.randomUUID()));
        when(vacancies.applicationSource(id)).thenReturn(new VacancyApplicationSource(SOURCE_URL, 0));
        return vacancy;
    }

    /**
     * Резюме с дневным лимитом и текстом, который попадает в снимок запуска при включённом AI.
     */
    protected void stubResume(int dailyLimit, String plainText) {
        stubResume(dailyLimit, plainText, AiLetterSettings.defaults());
    }

    protected void stubResume(int dailyLimit, String plainText, AiLetterSettings ai) {
        HhResume resume = new HhResume(resumeId, accountId, HH_RESUME_ID, "Java-разработчик",
                new ResumeAutomationSettings("Обычное письмо", dailyLimit, 10, 10, true, ai),
                NOW, NOW, plainText, NOW);
        when(resumes.findById(resumeId)).thenReturn(new ResumeProfile(resume, "Основной аккаунт", ResumeStatistics.empty()));
        when(profiles.forAccount(accountId)).thenReturn(new BrowserProfile(profileId, accountId, NOW, NOW));
    }

    protected Start start(List<UUID> vacancyIds) {
        return start(vacancyIds, 20, "Обычное письмо", AiLetterSettings.defaults());
    }

    protected Start start(List<UUID> vacancyIds, int dailyLimit, String coverLetter, AiLetterSettings ai) {
        return new Start(runId, resumeId, vacancyIds, dailyLimit, 10, 10, coverLetter, true, ai);
    }
}
