package org.hhclicker.backend.overview;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.account.service.AccountService;
import org.hhclicker.backend.application.model.ApplicationModels.Attention;
import org.hhclicker.backend.application.model.ApplicationModels.Outcome;
import org.hhclicker.backend.application.model.ApplicationModels.Run;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.overview.service.OverviewData;
import org.hhclicker.backend.overview.service.OverviewMetric;
import org.hhclicker.backend.overview.service.OverviewService;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.resume.model.ResumeStatistics;
import org.hhclicker.backend.resume.service.ResumeProfile;
import org.hhclicker.backend.resume.service.ResumeService;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;
import org.hhclicker.backend.search.service.SearchProfile;
import org.hhclicker.backend.search.service.SearchService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Оркестрация overview: выбор контекста, метрики и ограниченные read-проекции соседних модулей.
 */
class OverviewServiceTests {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final UUID ACCOUNT_ONE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_TWO_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID RESUME_ONE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID RESUME_TWO_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID FOREIGN_RESUME_ID = UUID.fromString("20000000-0000-0000-0000-000000000099");
    private static final UUID UNKNOWN_ID = UUID.fromString("90000000-0000-0000-0000-000000000099");

    private final AccountService accounts = mock(AccountService.class);
    private final ResumeService resumes = mock(ResumeService.class);
    private final SearchService searches = mock(SearchService.class);
    private final ApplicationService applications = mock(ApplicationService.class);
    private final OverviewService service = new OverviewService(accounts, resumes, searches, applications);

    @Test
    void requestedAccountIsSelectedWithoutASeparateLookupAndAccountOrderIsPreserved() {
        HhAccount first = account(ACCOUNT_ONE_ID, "Первый");
        HhAccount requested = account(ACCOUNT_TWO_ID, "Запрошенный");
        when(accounts.findAll()).thenReturn(List.of(first, requested));
        when(resumes.findAll(ACCOUNT_TWO_ID)).thenReturn(List.of());

        OverviewData data = service.load(ACCOUNT_TWO_ID, null);

        assertSame(requested, data.selectedAccount());
        assertEquals(List.of(ACCOUNT_ONE_ID, ACCOUNT_TWO_ID), data.accounts().stream().map(HhAccount::id).toList());
        verify(accounts).findAll();
        verifyNoMoreInteractions(accounts);
        verify(resumes).findAll(ACCOUNT_TWO_ID);
    }

    @Test
    void unknownRequestedAccountFallsBackToFirstWithoutASeparateLookup() {
        HhAccount first = account(ACCOUNT_ONE_ID, "Первый");
        when(accounts.findAll()).thenReturn(List.of(first, account(ACCOUNT_TWO_ID, "Второй")));
        when(resumes.findAll(ACCOUNT_ONE_ID)).thenReturn(List.of());

        OverviewData data = service.load(UNKNOWN_ID, null);

        assertSame(first, data.selectedAccount());
        verify(accounts).findAll();
        verifyNoMoreInteractions(accounts);
        verify(resumes).findAll(ACCOUNT_ONE_ID);
    }

    @Test
    void nullRequestedAccountSelectsFirstAccount() {
        HhAccount first = account(ACCOUNT_ONE_ID, "Первый");
        when(accounts.findAll()).thenReturn(List.of(first, account(ACCOUNT_TWO_ID, "Второй")));
        when(resumes.findAll(ACCOUNT_ONE_ID)).thenReturn(List.of());

        assertSame(first, service.load(null, null).selectedAccount());

        verify(resumes).findAll(ACCOUNT_ONE_ID);
    }

    @Test
    void noAccountsProducesAnEmptyZeroValuedOverviewWithoutDownstreamCalls() {
        when(accounts.findAll()).thenReturn(List.of());

        OverviewData data = service.load(UNKNOWN_ID, UNKNOWN_ID);

        assertNull(data.selectedAccount());
        assertNull(data.selectedResume());
        assertTrue(data.resumes().isEmpty());
        assertTrue(data.recentSearches().isEmpty());
        assertTrue(data.applicationRuns().isEmpty());
        assertTrue(data.attentionTasks().isEmpty());
        assertEquals(List.of(0L, 0L, 0L, 0L, 0L), data.metrics().stream().map(OverviewMetric::value).toList());
        assertEquals("Нет сохранённых поисков", data.metrics().getFirst().hint());
        assertEquals("из 0 в день; попытки всего аккаунта", data.metrics().getLast().hint());
        verifyNoInteractions(resumes, searches, applications);
    }

    @Test
    void requestedResumeIsSelectedAndResumeOrderIsPreserved() {
        ResumeProfile first = resume(RESUME_ONE_ID, ACCOUNT_ONE_ID, "Первое", 20, 1, 2);
        ResumeProfile requested = resume(RESUME_TWO_ID, ACCOUNT_ONE_ID, "Запрошенное", 35, 3, 4);
        stubAccountWithResumes(List.of(first, requested));

        OverviewData data = service.load(ACCOUNT_ONE_ID, RESUME_TWO_ID);

        assertSame(requested, data.selectedResume());
        assertEquals(List.of(RESUME_ONE_ID, RESUME_TWO_ID),
                data.resumes().stream().map(profile -> profile.resume().id()).toList());
        verify(searches).findAll(RESUME_TWO_ID);
        verify(applications).list(RESUME_TWO_ID);
        verify(applications).attention(RESUME_TWO_ID);
    }

    @Test
    void unknownOrForeignRequestedResumeFallsBackToFirstResumeOfSelectedAccount() {
        ResumeProfile first = resume(RESUME_ONE_ID, ACCOUNT_ONE_ID, "Первое", 20, 1, 2);
        stubAccountWithResumes(List.of(first, resume(RESUME_TWO_ID, ACCOUNT_ONE_ID, "Второе", 20, 3, 4)));

        OverviewData unknown = service.load(ACCOUNT_ONE_ID, FOREIGN_RESUME_ID);

        assertSame(first, unknown.selectedResume());
        verify(resumes).findAll(ACCOUNT_ONE_ID);
        verify(searches).findAll(RESUME_ONE_ID);
        verify(searches, never()).findAll(FOREIGN_RESUME_ID);
        verify(applications, never()).list(FOREIGN_RESUME_ID);
    }

    @Test
    void nullRequestedResumeFallsBackToFirstResume() {
        ResumeProfile first = resume(RESUME_ONE_ID, ACCOUNT_ONE_ID, "Первое", 20, 1, 2);
        stubAccountWithResumes(List.of(first, resume(RESUME_TWO_ID, ACCOUNT_ONE_ID, "Второе", 20, 3, 4)));

        OverviewData data = service.load(ACCOUNT_ONE_ID, null);

        assertSame(first, data.selectedResume());
        assertTrue(data.applicationRuns().isEmpty());
        assertEquals("Нет сохранённых поисков", data.metrics().getFirst().hint());
    }

    @Test
    void accountWithoutResumesProducesSafeMetricsAndDoesNotLoadResumeScopedData() {
        when(accounts.findAll()).thenReturn(List.of(account(ACCOUNT_ONE_ID, "Основной")));
        when(resumes.findAll(ACCOUNT_ONE_ID)).thenReturn(List.of());

        OverviewData data = service.load(ACCOUNT_ONE_ID, RESUME_ONE_ID);

        assertNull(data.selectedResume());
        assertEquals(List.of(0L, 0L, 0L, 0L, 0L), data.metrics().stream().map(OverviewMetric::value).toList());
        assertEquals("из 0 в день; попытки всего аккаунта", data.metrics().getLast().hint());
        assertTrue(data.recentSearches().isEmpty());
        assertTrue(data.applicationRuns().isEmpty());
        assertTrue(data.attentionTasks().isEmpty());
        verifyNoInteractions(searches, applications);
    }

    @Test
    void metricsRecentSearchesRunsAndAttentionReflectTheEntireSelectedResumeContext() {
        ResumeProfile selected = resume(RESUME_ONE_ID, ACCOUNT_ONE_ID, "Java", 42, 17, 8);
        stubAccountWithResumes(List.of(selected));
        SearchProfile first = search("Поиск 1", SearchStatus.PAUSED, 11, 1);
        SearchProfile second = search("Поиск 2", SearchStatus.ACTIVE, 12, 2);
        SearchProfile third = search("Поиск 3", SearchStatus.SCANNING, 13, 3);
        SearchProfile fourth = search("Поиск 4", SearchStatus.ERROR, 14, 4);
        when(searches.findAll(RESUME_ONE_ID)).thenReturn(List.of(first, second, third, fourth));
        Run firstRun = run("30000000-0000-0000-0000-000000000001", RunStatus.COMPLETED);
        Run secondRun = run("30000000-0000-0000-0000-000000000002", RunStatus.RUNNING);
        when(applications.list(RESUME_ONE_ID)).thenReturn(List.of(firstRun, secondRun));
        List<Attention> tasks = List.of(
                attention(1, "OPEN"), attention(2, "OPEN"), attention(3, "RESOLVED"), attention(4, "OPEN"),
                attention(5, "OPEN"), attention(6, "OPEN"), attention(7, "OPEN"));
        when(applications.attention(RESUME_ONE_ID)).thenReturn(tasks);
        when(applications.succeededToday(RESUME_ONE_ID)).thenReturn(6L);
        when(applications.remaining(RESUME_ONE_ID)).thenReturn(9L);

        OverviewData data = service.load(ACCOUNT_ONE_ID, RESUME_ONE_ID);

        assertEquals(List.of(
                new OverviewMetric("new", "Новые вакансии", 17, "4 сохранённых поисков", "purple"),
                new OverviewMetric("selected", "Выбраны к отклику", 8, "Текущая очередь", "blue"),
                new OverviewMetric("succeeded", "Успешно сегодня", 6, "По выбранному резюме", "green"),
                new OverviewMetric("attention", "Требуют внимания", 6, "Ожидают проверки", "orange"),
                new OverviewMetric("limit", "Остаток лимита", 9, "из 42 в день; попытки всего аккаунта", "blue")
        ), data.metrics());
        assertEquals(List.of(firstRun, secondRun), data.applicationRuns(),
                "OverviewService должен сохранить все запуски, оставив выбор активного DTO-фабрике");
        assertEquals(List.of(1, 2, 4, 5, 6, 7),
                data.attentionTasks().stream().map(task -> Integer.parseInt(task.vacancyTitle().substring(8))).toList());
        assertEquals(List.of("Поиск 1", "Поиск 2", "Поиск 3"),
                data.recentSearches().stream().map(search -> search.name()).toList());
        assertEquals(List.of("PAUSED", "ACTIVE", "ACTIVE"),
                data.recentSearches().stream().map(search -> search.status()).toList());
        assertEquals(List.of(11L, 12L, 13L),
                data.recentSearches().stream().map(search -> search.newVacancies()).toList());
        assertEquals(first.search().id(), data.recentSearches().getFirst().id());
        assertEquals(first.search().createdAt(), data.recentSearches().getFirst().createdAt());
        verify(searches).findAll(RESUME_ONE_ID);
        verify(applications).list(RESUME_ONE_ID);
        verify(applications).attention(RESUME_ONE_ID);
        verify(applications).succeededToday(RESUME_ONE_ID);
        verify(applications).remaining(RESUME_ONE_ID);
    }

    @Test
    void fewerThanThreeSearchesAreReturnedInInputOrderAndEveryNonPausedStatusMapsToActive() {
        ResumeProfile selected = resume(RESUME_ONE_ID, ACCOUNT_ONE_ID, "Java", 20, 0, 0);
        stubAccountWithResumes(List.of(selected));
        SearchProfile scanning = search("Сканируется", SearchStatus.SCANNING, 1, 1);
        SearchProfile error = search("Ошибка", SearchStatus.ERROR, 2, 2);
        when(searches.findAll(RESUME_ONE_ID)).thenReturn(List.of(scanning, error));

        OverviewData data = service.load(ACCOUNT_ONE_ID, RESUME_ONE_ID);

        assertEquals(List.of("Сканируется", "Ошибка"), data.recentSearches().stream().map(s -> s.name()).toList());
        assertEquals(List.of("ACTIVE", "ACTIVE"), data.recentSearches().stream().map(s -> s.status()).toList());
        assertEquals("2 сохранённых поисков", data.metrics().getFirst().hint());
    }

    private void stubAccountWithResumes(List<ResumeProfile> profiles) {
        when(accounts.findAll()).thenReturn(List.of(account(ACCOUNT_ONE_ID, "Основной")));
        when(resumes.findAll(ACCOUNT_ONE_ID)).thenReturn(profiles);
    }

    private HhAccount account(UUID id, String name) {
        return new HhAccount(id, name, "hh-" + name, AuthorizationStatus.AUTHORIZED, CREATED_AT, CREATED_AT);
    }

    private ResumeProfile resume(UUID id, UUID accountId, String name, int dailyLimit, long newVacancies,
                                 long queuedVacancies) {
        var settings = new ResumeAutomationSettings("Письмо", dailyLimit, 30, 90, true);
        var resume = new HhResume(id, accountId, "abcdef0123456789abcd", name, settings, CREATED_AT, CREATED_AT);
        return new ResumeProfile(resume, "Основной", new ResumeStatistics(4, newVacancies, queuedVacancies, 0, 0));
    }

    private SearchProfile search(String name, SearchStatus status, long newVacancies, int number) {
        UUID id = UUID.fromString("40000000-0000-0000-0000-%012d".formatted(number));
        VacancySearch search = new VacancySearch(id, RESUME_ONE_ID, name, "https://hh.ru/search/" + number,
                "a".repeat(64), 5, status, newVacancies, 100, null, null, CREATED_AT.plusSeconds(number), CREATED_AT);
        return new SearchProfile(search, "Java");
    }

    private Run run(String id, RunStatus status) {
        return new Run(UUID.fromString(id), 1, ACCOUNT_ONE_ID, RESUME_ONE_ID,
                UUID.fromString("50000000-0000-0000-0000-000000000001"), "abcdef0123456789abcd", "Java",
                status, CREATED_AT, null, 20, 20, 30, 90, "Письмо", true, List.of());
    }

    private Attention attention(int number, String status) {
        UUID id = UUID.fromString("60000000-0000-0000-0000-%012d".formatted(number));
        return new Attention(id, "QUESTIONNAIRE", status, "Проверить", "Описание",
                UUID.fromString("30000000-0000-0000-0000-000000000002"), 2, id,
                UUID.fromString("70000000-0000-0000-0000-%012d".formatted(number)), "hh-" + number,
                "Вакансия" + number, "employer-" + number, "Компания" + number, "Java",
                CREATED_AT.plusSeconds(number), Outcome.QUESTIONNAIRE, false, true);
    }
}
