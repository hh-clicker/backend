package org.hhclicker.backend.overview;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.application.model.ApplicationModels.*;
import org.hhclicker.backend.overview.dto.OverviewResponse;
import org.hhclicker.backend.overview.service.OverviewData;
import org.hhclicker.backend.overview.service.OverviewMetric;
import org.hhclicker.backend.overview.service.OverviewRecentSearch;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.resume.model.ResumeStatistics;
import org.hhclicker.backend.resume.service.ResumeProfile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Чистое преобразование внутреннего результата overview в его HTTP-проекцию.
 */
class OverviewResponseTests {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final UUID ACCOUNT_ONE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_TWO_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID RESUME_ONE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID RESUME_TWO_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID RUN_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Test
    void accountsResumesSelectedIdsAndStatusesAreMappedInInputOrder() {
        HhAccount firstAccount = account(ACCOUNT_ONE_ID, "Первый", AuthorizationStatus.REQUIRED);
        HhAccount selectedAccount = account(ACCOUNT_TWO_ID, "Второй", AuthorizationStatus.AUTHORIZED);
        ResumeProfile firstResume = resume(RESUME_ONE_ID, ACCOUNT_TWO_ID, "Java");
        ResumeProfile selectedResume = resume(RESUME_TWO_ID, ACCOUNT_TWO_ID, "Backend");
        OverviewData data = data(List.of(firstAccount, selectedAccount), selectedAccount,
                List.of(firstResume, selectedResume), selectedResume, List.of(), List.of(), List.of(), List.of());

        OverviewResponse response = OverviewResponse.from(data);

        assertEquals(List.of(
                new OverviewResponse.SelectionOption(ACCOUNT_ONE_ID, "Первый"),
                new OverviewResponse.SelectionOption(ACCOUNT_TWO_ID, "Второй")
        ), response.accounts());
        assertEquals(ACCOUNT_TWO_ID, response.selectedAccountId());
        assertEquals(List.of(
                new OverviewResponse.SelectionOption(RESUME_ONE_ID, "Java"),
                new OverviewResponse.SelectionOption(RESUME_TWO_ID, "Backend")
        ), response.resumes());
        assertEquals(RESUME_TWO_ID, response.selectedResumeId());
        assertEquals("AUTHORIZED", response.authorizationStatus());
        assertEquals("OFFLINE", response.agentStatus(), "Текущий HTTP-контракт всегда сообщает OFFLINE");
    }

    @Test
    void absentSelectionsAndRunsProduceNullIdsUnknownAuthorizationAndNoActiveRun() {
        OverviewResponse response = OverviewResponse.from(
                data(List.of(), null, List.of(), null, List.of(), List.of(), List.of(), List.of()));

        assertNull(response.selectedAccountId());
        assertNull(response.selectedResumeId());
        assertEquals("UNKNOWN", response.authorizationStatus());
        assertEquals("OFFLINE", response.agentStatus());
        assertNull(response.activeRun());
    }

    @Test
    void firstNonTerminalRunIsSelectedInInputOrderAndItsStringStatusesAreCounted() {
        Run completed = run(UUID.fromString("30000000-0000-0000-0000-000000000010"), 10,
                RunStatus.COMPLETED, List.of());
        Run cancelled = run(UUID.fromString("30000000-0000-0000-0000-000000000011"), 11,
                RunStatus.CANCELLED, List.of());
        List<Item> items = List.of(
                item(1, "QUEUED", null),
                item(2, "IN_PROGRESS", null),
                item(3, "SUCCEEDED", null),
                item(4, "FAILED", null),
                item(5, "ATTENTION_REQUIRED", "OPEN"),
                item(6, "ATTENTION_REQUIRED", "RESOLVED"),
                item(7, "UNKNOWN_STATUS", null));
        Run firstActive = run(RUN_ID, 12, RunStatus.PAUSED_USER, items);
        Run laterActive = run(UUID.fromString("30000000-0000-0000-0000-000000000013"), 13,
                RunStatus.RUNNING, List.of());
        OverviewData data = data(List.of(), null, List.of(), null, List.of(), List.of(),
                List.of(completed, cancelled, firstActive, laterActive), List.of());

        OverviewResponse.ActiveRunSummary active = OverviewResponse.from(data).activeRun();

        assertEquals(RUN_ID, active.id());
        assertEquals(12, active.sequence());
        assertEquals("PAUSED_USER", active.status());
        assertEquals(7, active.total());
        assertEquals(5, active.processed(), "Обработанным считается любой строковый статус, кроме двух текущих");
        assertEquals(1, active.succeeded());
        assertEquals(1, active.failed());
        assertEquals(1, active.attention());
    }

    @Test
    void terminalRunsOnlyProduceNoActiveRun() {
        OverviewData data = data(List.of(), null, List.of(), null, List.of(), List.of(), List.of(
                run(RUN_ID, 1, RunStatus.COMPLETED, List.of()),
                run(UUID.fromString("30000000-0000-0000-0000-000000000002"), 2,
                        RunStatus.CANCELLED, List.of())), List.of());

        assertNull(OverviewResponse.from(data).activeRun());
    }

    @Test
    void metricsAndRecentSearchesAreTransferredWithoutSortingFilteringOrFieldChanges() {
        List<OverviewMetric> metrics = List.of(
                new OverviewMetric("second", "Вторая", 22, "hint 2", "blue"),
                new OverviewMetric("first", "Первая", 11, "hint 1", "purple"));
        List<OverviewRecentSearch> recentSearches = List.of(
                new OverviewRecentSearch(UUID.fromString("40000000-0000-0000-0000-000000000002"),
                        "Второй", CREATED_AT.plusSeconds(2), 8, "PAUSED"),
                new OverviewRecentSearch(UUID.fromString("40000000-0000-0000-0000-000000000001"),
                        "Первый", CREATED_AT.plusSeconds(1), 5, "ACTIVE"));

        OverviewResponse response = OverviewResponse.from(
                data(List.of(), null, List.of(), null, metrics, recentSearches, List.of(), List.of()));

        assertEquals(List.of("second", "first"), response.metrics().stream().map(m -> m.id()).toList());
        assertEquals("Вторая", response.metrics().getFirst().label());
        assertEquals(22, response.metrics().getFirst().value());
        assertEquals("hint 2", response.metrics().getFirst().hint());
        assertEquals("blue", response.metrics().getFirst().tone());
        assertEquals(List.of("Второй", "Первый"), response.recentSearches().stream().map(s -> s.name()).toList());
        assertEquals(recentSearches.getFirst().id(), response.recentSearches().getFirst().id());
        assertEquals(recentSearches.getFirst().createdAt(), response.recentSearches().getFirst().createdAt());
        assertEquals(8, response.recentSearches().getFirst().newVacancies());
        assertEquals("PAUSED", response.recentSearches().getFirst().status());
    }

    @Test
    void attentionProjectionKeepsOnlyFirstFiveInOrderAndPreservesNullableTimestamp() {
        List<Attention> tasks = List.of(
                attention(1, CREATED_AT), attention(2, CREATED_AT.plusSeconds(2)), attention(3, CREATED_AT.plusSeconds(3)),
                attention(4, CREATED_AT.plusSeconds(4)), attention(5, null), attention(6, CREATED_AT.plusSeconds(6)));

        List<OverviewResponse.AttentionItem> result = OverviewResponse.from(
                data(List.of(), null, List.of(), null, List.of(), List.of(), List.of(), tasks)).attentionItems();

        assertEquals(5, result.size());
        assertEquals(List.of("Вакансия 1", "Вакансия 2", "Вакансия 3", "Вакансия 4", "Вакансия 5"),
                result.stream().map(item -> item.vacancy()).toList());
        assertEquals(tasks.getFirst().id(), result.getFirst().id());
        assertEquals("Компания 1", result.getFirst().company());
        assertEquals("QUESTIONNAIRE", result.getFirst().kind());
        assertEquals(CREATED_AT.toString(), result.getFirst().occurredAt());
        assertNull(result.getLast().occurredAt());
    }

    private OverviewData data(List<HhAccount> accounts, HhAccount selectedAccount, List<ResumeProfile> resumes,
                              ResumeProfile selectedResume, List<OverviewMetric> metrics, List<OverviewRecentSearch> recentSearches,
                              List<Run> runs, List<Attention> attention) {
        return new OverviewData(accounts, selectedAccount, resumes, selectedResume, metrics, recentSearches, runs, attention);
    }

    private HhAccount account(UUID id, String name, AuthorizationStatus status) {
        return new HhAccount(id, name, "hh-" + name, status, CREATED_AT, CREATED_AT);
    }

    private ResumeProfile resume(UUID id, UUID accountId, String name) {
        HhResume resume = new HhResume(id, accountId, "abcdef0123456789abcd", name,
                ResumeAutomationSettings.defaults(), CREATED_AT, CREATED_AT);
        return new ResumeProfile(resume, "Второй", ResumeStatistics.empty());
    }

    private Run run(UUID id, long sequence, RunStatus status, List<Item> items) {
        return new Run(id, sequence, ACCOUNT_TWO_ID, RESUME_TWO_ID,
                UUID.fromString("50000000-0000-0000-0000-000000000001"), "abcdef0123456789abcd", "Backend",
                status, CREATED_AT, null, 20, 20, 30, 90, "Письмо", true, items);
    }

    private Item item(int number, String status, String attentionStatus) {
        UUID id = UUID.fromString("60000000-0000-0000-0000-%012d".formatted(number));
        return new Item(id, RUN_ID, UUID.fromString("70000000-0000-0000-0000-%012d".formatted(number)),
                "hh-" + number, "Вакансия " + number, "Компания " + number, status,
                Outcome.NOT_SENT, CREATED_AT.plusSeconds(number), null, false, attentionStatus);
    }

    private Attention attention(int number, Instant occurredAt) {
        UUID id = UUID.fromString("80000000-0000-0000-0000-%012d".formatted(number));
        return new Attention(id, "QUESTIONNAIRE", number % 2 == 0 ? "RESOLVED" : "OPEN", "Проверить", "Описание",
                RUN_ID, 12, id, UUID.fromString("70000000-0000-0000-0000-%012d".formatted(number)), "hh-" + number,
                "Вакансия " + number, "employer-" + number, "Компания " + number, "Backend", occurredAt,
                Outcome.QUESTIONNAIRE, false, true);
    }
}
