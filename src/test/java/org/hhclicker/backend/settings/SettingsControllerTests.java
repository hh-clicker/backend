package org.hhclicker.backend.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hhclicker.backend.settings.controller.SettingsController;
import org.hhclicker.backend.settings.model.NotificationRun;
import org.hhclicker.backend.settings.model.NotificationSettings;
import org.hhclicker.backend.settings.service.NotificationService;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.shared.exception.GlobalExceptionHandler;
import org.hhclicker.backend.vacancy.relevance.VacancyRelevanceService;
import org.hhclicker.backend.vacancy.relevance.VacancyRelevanceNotificationRun;
import org.hhclicker.backend.market.MarketAnalysisService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone HTTP-контракт настроек и notification-state с настоящим обработчиком ошибок.
 */
class SettingsControllerTests {

    private static final UUID RUN_ONE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID RUN_TWO_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RESUME_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String SETTINGS_URL = "/api/v1/settings";

    private final NotificationService notifications = mock(NotificationService.class);
    private final VacancyRelevanceService relevance = mock(VacancyRelevanceService.class);
    private final MarketAnalysisService market = mock(MarketAnalysisService.class);
    private final org.hhclicker.backend.vacancy.relevance.ParallelVacancyRelevanceService parallelRelevance =
            mock(org.hhclicker.backend.vacancy.relevance.ParallelVacancyRelevanceService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SettingsController(notifications, relevance, market, parallelRelevance))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getReturnsAllFiveSettingsInsideTheFrontendEnvelopeAfterOneServiceCall() throws Exception {
        when(notifications.settings()).thenReturn(new NotificationSettings(true, false, true, false, true, true));

        mockMvc.perform(get(SETTINGS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.notifications.desktopEnabled").value(true))
                .andExpect(jsonPath("$.settings.notifications.soundEnabled").value(false))
                .andExpect(jsonPath("$.settings.notifications.notifyRunCompleted").value(true))
                .andExpect(jsonPath("$.settings.notifications.notifyRunPaused").value(false))
                .andExpect(jsonPath("$.settings.notifications.notifyAttentionRequired").value(true))
                .andExpect(jsonPath("$.settings.notifications.notifyRelevanceCompleted").value(true))
                .andExpect(jsonPath("$.settings.notifications.notifyMarketCompleted").value(true));

        verify(notifications).settings();
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void getMapsDomainFailureThroughGlobalExceptionHandler() throws Exception {
        when(notifications.settings()).thenThrow(domainFailure());

        mockMvc.perform(get(SETTINGS_URL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_SETTINGS_CONFLICT"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void getMapsUnexpectedFailureToGenericProblemDetail() throws Exception {
        when(notifications.settings()).thenThrow(new IllegalStateException("internal detail"));

        mockMvc.perform(get(SETTINGS_URL))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.category").value("INTERNAL"))
                .andExpect(jsonPath("$.detail").value("Не удалось выполнить операцию."));
    }

    @Test
    void putPassesAllFiveValuesAndBuildsResponseFromTheServiceResult() throws Exception {
        NotificationSettings stored = new NotificationSettings(true, false, false, true, true, true);
        when(notifications.save(any())).thenReturn(stored);

        mockMvc.perform(put(SETTINGS_URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body(false, true, true, false, false, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.notifications.desktopEnabled").value(true))
                .andExpect(jsonPath("$.settings.notifications.soundEnabled").value(false))
                .andExpect(jsonPath("$.settings.notifications.notifyRunCompleted").value(false))
                .andExpect(jsonPath("$.settings.notifications.notifyRunPaused").value(true))
                .andExpect(jsonPath("$.settings.notifications.notifyAttentionRequired").value(true));

        ArgumentCaptor<NotificationSettings> captor = ArgumentCaptor.forClass(NotificationSettings.class);
        verify(notifications).save(captor.capture());
        assertEquals(new NotificationSettings(false, true, true, false, false, true), captor.getValue());
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void putAcceptsAllFiveSettingsDisabled() throws Exception {
        NotificationSettings disabled = new NotificationSettings(false, false, false, false, false, false, true);
        when(notifications.save(disabled)).thenReturn(disabled);

        mockMvc.perform(put(SETTINGS_URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body(false, false, false, false, false, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.notifications.desktopEnabled").value(false))
                .andExpect(jsonPath("$.settings.notifications.notifyAttentionRequired").value(false))
                .andExpect(jsonPath("$.settings.notifications.notifyRelevanceCompleted").value(false))
                .andExpect(jsonPath("$.settings.notifications.notifyMarketCompleted").value(true));

        verify(notifications).save(disabled);
    }

    @Test
    void putCanDisableMarketNotificationsIndependently() throws Exception {
        NotificationSettings stored = new NotificationSettings(true, true, true, true, true, true, false);
        when(notifications.save(stored)).thenReturn(stored);

        mockMvc.perform(put(SETTINGS_URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body(true, true, true, true, true, true, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.notifications.notifyMarketCompleted").value(false));

        verify(notifications).save(stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"notifications\":null}"})
    void putRejectsMissingOrNullNotificationsObject(String body) throws Exception {
        expectValidationFailure(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "desktopEnabled", "soundEnabled", "notifyRunCompleted", "notifyRunPaused", "notifyAttentionRequired", "notifyRelevanceCompleted", "notifyMarketCompleted"
    })
    void putRejectsEachMissingBooleanField(String missingField) throws Exception {
        expectValidationFailure(bodyWithFieldOmitted(missingField));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "desktopEnabled", "soundEnabled", "notifyRunCompleted", "notifyRunPaused", "notifyAttentionRequired", "notifyRelevanceCompleted", "notifyMarketCompleted"
    })
    void putRejectsEachNullBooleanField(String nullField) throws Exception {
        expectValidationFailure(bodyWithFieldValue(nullField, "null"));
    }

    @Test
    void putRejectsWrongBooleanType() throws Exception {
        expectValidationFailure(bodyWithFieldValue("desktopEnabled", "{}"));
    }

    @Test
    void putRejectsEmptyBody() throws Exception {
        mockMvc.perform(put(SETTINGS_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

        verifyNoInteractions(notifications);
    }

    @Test
    void putRejectsMalformedJson() throws Exception {
        expectValidationFailure("{\"notifications\":");
    }

    @Test
    void notificationStateSerializesSettingsAndCompleteRunsDirectlyInServiceOrder() throws Exception {
        NotificationSettings settings = new NotificationSettings(true, false, true, false, true, true);
        NotificationRun first = run(RUN_ONE_ID, 41, "COMPLETED", List.of("attention-1", "attention-2"));
        NotificationRun second = run(RUN_TWO_ID, 42, "PAUSED_AUTH", List.of());
        when(notifications.settings()).thenReturn(settings);
        when(notifications.runs()).thenReturn(List.of(first, second));
        when(relevance.notificationRuns()).thenReturn(List.of(new VacancyRelevanceNotificationRun(
                UUID.fromString("40000000-0000-0000-0000-000000000001"), ACCOUNT_ID, RESUME_ID,
                "Java", "done", "relevance-revision", true, 12, 11, 1, null)));

        mockMvc.perform(get(SETTINGS_URL + "/notification-state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.desktopEnabled").value(true))
                .andExpect(jsonPath("$.settings.soundEnabled").value(false))
                .andExpect(jsonPath("$.settings.notifications").doesNotExist())
                .andExpect(jsonPath("$.runs.length()").value(2))
                .andExpect(jsonPath("$.runs[0].id").value(RUN_ONE_ID.toString()))
                .andExpect(jsonPath("$.runs[0].accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.runs[0].resumeId").value(RESUME_ID.toString()))
                .andExpect(jsonPath("$.runs[0].resumeName").value("Java"))
                .andExpect(jsonPath("$.runs[0].sequence").value(41))
                .andExpect(jsonPath("$.runs[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.runs[0].mode").value("APPLY"))
                .andExpect(jsonPath("$.runs[0].revision").value("revision-41"))
                .andExpect(jsonPath("$.runs[0].total").value(5))
                .andExpect(jsonPath("$.runs[0].succeeded").value(3))
                .andExpect(jsonPath("$.runs[0].failed").value(1))
                .andExpect(jsonPath("$.runs[0].attentionIds[0]").value("attention-1"))
                .andExpect(jsonPath("$.runs[0].attentionIds[1]").value("attention-2"))
                .andExpect(jsonPath("$.runs[1].id").value(RUN_TWO_ID.toString()))
                .andExpect(jsonPath("$.relevanceRuns[0].status").value("done"))
                .andExpect(jsonPath("$.relevanceRuns[0].evaluatedCount").value(11))
                .andExpect(jsonPath("$.relevanceRuns[0].missingCount").value(1));

        InOrder order = inOrder(notifications);
        order.verify(notifications).settings();
        order.verify(notifications).runs();
        order.verifyNoMoreInteractions();
        verify(relevance).notificationRuns();
    }

    @Test
    void notificationStateSerializesAnEmptyRunList() throws Exception {
        when(notifications.settings()).thenReturn(new NotificationSettings(true, true, true, true, true, true));
        when(notifications.runs()).thenReturn(List.of());

        mockMvc.perform(get(SETTINGS_URL + "/notification-state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs.length()").value(0));
    }

    @Test
    void notificationStateDoesNotReadRunsAfterSettingsFailure() throws Exception {
        when(notifications.settings()).thenThrow(domainFailure());

        mockMvc.perform(get(SETTINGS_URL + "/notification-state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_SETTINGS_CONFLICT"));

        verify(notifications, never()).runs();
    }

    @Test
    void notificationStateMapsRunsFailureThroughGlobalExceptionHandler() throws Exception {
        when(notifications.settings()).thenReturn(new NotificationSettings(true, true, true, true, true, true));
        when(notifications.runs()).thenThrow(new IllegalStateException("projection failed"));

        mockMvc.perform(get(SETTINGS_URL + "/notification-state"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    private void expectValidationFailure(String content) throws Exception {
        mockMvc.perform(put(SETTINGS_URL).contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
        verifyNoInteractions(notifications);
    }

    private String body(boolean desktop, boolean sound, boolean completed, boolean paused, boolean attention,
                        boolean relevance) {
        return body(desktop, sound, completed, paused, attention, relevance, true);
    }

    private String body(boolean desktop, boolean sound, boolean completed, boolean paused, boolean attention,
                        boolean relevance, boolean market) {
        return """
                {"notifications":{"desktopEnabled":%s,"soundEnabled":%s,"notifyRunCompleted":%s,
                  "notifyRunPaused":%s,"notifyAttentionRequired":%s,"notifyRelevanceCompleted":%s,
                  "notifyMarketCompleted":%s}}
                """.formatted(desktop, sound, completed, paused, attention, relevance, market);
    }

    private String bodyWithFieldOmitted(String missingField) {
        return bodyWithFieldValue(missingField, null);
    }

    private String bodyWithFieldValue(String targetField, String replacement) {
        String[] fields = {"desktopEnabled", "soundEnabled", "notifyRunCompleted", "notifyRunPaused",
                "notifyAttentionRequired", "notifyRelevanceCompleted", "notifyMarketCompleted"};
        StringBuilder json = new StringBuilder("{\"notifications\":{");
        boolean first = true;
        for (String field : fields) {
            if (field.equals(targetField) && replacement == null) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            json.append('\"').append(field).append("\":")
                    .append(field.equals(targetField) ? replacement : "true");
            first = false;
        }
        return json.append("}}").toString();
    }

    private NotificationRun run(UUID id, long sequence, String status, List<String> attentionIds) {
        return new NotificationRun(id, ACCOUNT_ID, RESUME_ID, "Java", sequence, status, "revision-" + sequence,
                5, 3, 1, 5, 300, 5, attentionIds);
    }

    private ApplicationException domainFailure() {
        return new ApplicationException(ApplicationErrorCategory.CONFLICT,
                "NOTIFICATION_SETTINGS_CONFLICT", "Настройки временно недоступны.");
    }
}
