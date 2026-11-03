package org.hhclicker.backend.vacancy;

import tools.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hhclicker.backend.shared.exception.GlobalExceptionHandler;
import org.hhclicker.backend.vacancy.catalog.controller.VacancyController;
import org.hhclicker.backend.vacancy.catalog.exception.InvalidVacancyDataException;
import org.hhclicker.backend.vacancy.catalog.exception.VacancyNotFoundException;
import org.hhclicker.backend.vacancy.catalog.model.*;
import org.hhclicker.backend.vacancy.catalog.service.VacancyCatalogService;
import org.hhclicker.backend.vacancy.selection.controller.VacancySelectionController;
import org.hhclicker.backend.vacancy.selection.exception.VacancyTransitionNotAllowedException;
import org.hhclicker.backend.vacancy.selection.service.QuestionnaireApplicationVerificationGateway;
import org.hhclicker.backend.vacancy.selection.service.VacancySelectionService;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-контракт каталога вакансий и пользовательского отбора.
 */
class VacancyControllerTests {

    private final VacancyCatalogService catalog = mock(VacancyCatalogService.class);
    private final VacancySelectionService selection = mock(VacancySelectionService.class);
    private final QuestionnaireApplicationVerificationGateway verificationGateway = mock(QuestionnaireApplicationVerificationGateway.class);

    private final UUID vacancyId = UUID.randomUUID();
    private final UUID resumeId = UUID.randomUUID();
    private final UUID searchId = UUID.randomUUID();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new VacancyController(catalog), new VacancySelectionController(selection, verificationGateway))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private VacancyListing full() {
        var enrichment = JsonNodeFactory.instance.objectNode();
        enrichment.putArray("workFormats").add("REMOTE").add("HYBRID");
        enrichment.put("responsesCount", 42);
        Vacancy vacancy = new Vacancy(vacancyId, resumeId, "118123456", "Java Developer", "3529", "Example",
                new VacancySalary(100_000L, 200_000L, SalaryCurrency.RUR, true), "Moscow", "Remote", "1-3 years",
                VacancyFixtures.EARLIER, "from 100000", List.of("Quick response"), Boolean.TRUE,
                VacancyStatus.COMPANY_BANNED, CompanyBanScope.GLOBAL, true,
                VacancyFixtures.EARLIER, VacancyFixtures.NOW, VacancyFixtures.EARLIER, VacancyFixtures.NOW,
                new VacancyRelevance(VacancyRelevanceCategory.ADJACENT, VacancyRelevanceConfidence.medium,
                        "Другой стек разработки.", VacancyFixtures.NOW), enrichment);
        return new VacancyListing(vacancy, searchId, true);
    }

    private VacancyListing sparse() {
        Vacancy vacancy = new Vacancy(vacancyId, resumeId, "118123456", "Java Developer", null, "Employer ID unknown",
                null, "", "", "", null, "", List.of(), null,
                VacancyStatus.NEW, null, false,
                VacancyFixtures.EARLIER, VacancyFixtures.NOW, VacancyFixtures.EARLIER, VacancyFixtures.NOW, null);
        return new VacancyListing(vacancy, null, false);
    }

    @Test
    void listExposesTheFullVacancyProjection() throws Exception {
        when(catalog.findAll(null, null)).thenReturn(List.of(full()));

        mockMvc.perform(get("/api/v1/vacancies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(vacancyId.toString()))
                .andExpect(jsonPath("$[0].resumeId").value(resumeId.toString()))
                .andExpect(jsonPath("$[0].hhVacancyId").value("118123456"))
                .andExpect(jsonPath("$[0].searchId").value(searchId.toString()))
                .andExpect(jsonPath("$[0].title").value("Java Developer"))
                .andExpect(jsonPath("$[0].company.id").value("3529"))
                .andExpect(jsonPath("$[0].company.name").value("Example"))
                .andExpect(jsonPath("$[0].salary.from").value(100000))
                .andExpect(jsonPath("$[0].salary.to").value(200000))
                .andExpect(jsonPath("$[0].salary.currency").value("RUR"))
                .andExpect(jsonPath("$[0].salary.gross").value(true))
                .andExpect(jsonPath("$[0].location").value("Moscow"))
                .andExpect(jsonPath("$[0].workFormat").value("Remote"))
                .andExpect(jsonPath("$[0].experience").value("1-3 years"))
                .andExpect(jsonPath("$[0].salaryText").value("from 100000"))
                .andExpect(jsonPath("$[0].labels[0]").value("Quick response"))
                .andExpect(jsonPath("$[0].status").value("COMPANY_BANNED"))
                .andExpect(jsonPath("$[0].banScope").value("GLOBAL"))
                .andExpect(jsonPath("$[0].queuedForApplication").value(true))
                .andExpect(jsonPath("$[0].hasQuestionnaire").value(true))
                .andExpect(jsonPath("$[0].hasUnconfirmedApplication").value(true))
                .andExpect(jsonPath("$[0].relevance.category").value("ADJACENT"))
                .andExpect(jsonPath("$[0].relevance.confidence").value("medium"))
                .andExpect(jsonPath("$[0].relevance.reason").value("Другой стек разработки."))
                .andExpect(jsonPath("$[0].enrichment.workFormats[0]").value("REMOTE"))
                .andExpect(jsonPath("$[0].enrichment.workFormats[1]").value("HYBRID"))
                .andExpect(jsonPath("$[0].enrichment.responsesCount").value(42))
                .andExpect(jsonPath("$[0].firstSeenAt").exists())
                .andExpect(jsonPath("$[0].lastSeenAt").exists());
    }

    @Test
    void unknownValuesAreExposedAsNullsRatherThanDefaults() throws Exception {
        when(catalog.findAll(null, null)).thenReturn(List.of(sparse()));

        mockMvc.perform(get("/api/v1/vacancies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].salary").value(nullValue()))
                .andExpect(jsonPath("$[0].hasQuestionnaire").value(nullValue()))
                .andExpect(jsonPath("$[0].searchId").value(nullValue()))
                .andExpect(jsonPath("$[0].banScope").value(nullValue()))
                .andExpect(jsonPath("$[0].company.id").value(nullValue()))
                .andExpect(jsonPath("$[0].publishedAt").value(nullValue()))
                .andExpect(jsonPath("$[0].relevance").value(nullValue()))
                .andExpect(jsonPath("$[0].enrichment").value(nullValue()))
                .andExpect(jsonPath("$[0].labels.length()").value(0));
    }

    @Test
    void filtersArePassedToTheCatalogService() throws Exception {
        when(catalog.findAll(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/vacancies").param("resumeId", resumeId.toString()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/vacancies").param("searchId", searchId.toString()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/vacancies")
                        .param("resumeId", resumeId.toString())
                        .param("searchId", searchId.toString()))
                .andExpect(status().isOk());

        verify(catalog).findAll(resumeId, null);
        verify(catalog).findAll(null, searchId);
        verify(catalog).findAll(resumeId, searchId);
    }

    @Test
    void singleVacancyIsReturned() throws Exception {
        when(catalog.findById(vacancyId)).thenReturn(full());

        mockMvc.perform(get("/api/v1/vacancies/{id}", vacancyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(vacancyId.toString()));
    }

    @Test
    void missingVacancyIsReportedAsNotFoundProblemDetail() throws Exception {
        when(catalog.findById(vacancyId)).thenThrow(new VacancyNotFoundException(vacancyId));

        mockMvc.perform(get("/api/v1/vacancies/{id}", vacancyId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VACANCY_NOT_FOUND"))
                .andExpect(jsonPath("$.category").value("NOT_FOUND"));
    }

    @Test
    void malformedVacancyIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/vacancies/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void storedDescriptionIsReturnedAsIs() throws Exception {
        when(catalog.findText(vacancyId)).thenReturn(new VacancyText("Tasks", VacancyFixtures.NOW));

        mockMvc.perform(get("/api/v1/vacancies/{id}/text", vacancyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plainText").value("Tasks"))
                .andExpect(jsonPath("$.textLoadedAt").exists());
    }

    @Test
    void descriptionThatWasNeverCapturedStaysNull() throws Exception {
        when(catalog.findText(vacancyId)).thenReturn(new VacancyText(null, null));

        mockMvc.perform(get("/api/v1/vacancies/{id}/text", vacancyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plainText").value(nullValue()))
                .andExpect(jsonPath("$.textLoadedAt").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void queueFlagIsPassedToTheSelectionService(boolean queued) throws Exception {
        when(selection.setQueued(vacancyId, queued)).thenReturn(full());

        mockMvc.perform(put("/api/v1/vacancies/{id}/application-queue", vacancyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queuedForApplication\":%s}".formatted(queued)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(vacancyId.toString()));

        verify(selection).setQueued(vacancyId, queued);
    }

    @Test
    void emptyQueueRequestBodyMustNotSilentlyRemoveTheVacancy() throws Exception {
        expectValidationFailure(put("/api/v1/vacancies/{id}/application-queue", vacancyId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        expectValidationFailure(put("/api/v1/vacancies/{id}/application-queue", vacancyId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"queuedForApplication\":null}"));

        verify(selection, never()).setQueued(any(UUID.class), anyBoolean());
    }

    @Test
    void bulkQueueRequestIsDelegatedAndKeepsTheReturnedOrder() throws Exception {
        UUID second = UUID.randomUUID();
        when(selection.setQueued(anyList(), eq(true))).thenReturn(List.of(full(), sparse()));

        mockMvc.perform(put("/api/v1/vacancies/application-queue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vacancyIds\":[\"%s\",\"%s\"],\"queuedForApplication\":true}"
                                .formatted(vacancyId, second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        verify(selection).setQueued(List.of(vacancyId, second), true);
    }

    @Test
    void bulkQueueRequestValidationRejectsEmptyAndOversizedLists() throws Exception {
        expectValidationFailure(put("/api/v1/vacancies/application-queue")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vacancyIds\":[],\"queuedForApplication\":true}"));

        String tooMany = IntStream.range(0, 501)
                .mapToObj(index -> "\"" + UUID.randomUUID() + "\"")
                .collect(Collectors.joining(","));
        expectValidationFailure(put("/api/v1/vacancies/application-queue")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vacancyIds\":[%s],\"queuedForApplication\":true}".formatted(tooMany)));

        expectValidationFailure(put("/api/v1/vacancies/application-queue")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vacancyIds\":[\"%s\"]}".formatted(vacancyId)));

        verify(selection, never()).setQueued(anyList(), anyBoolean());
    }

    @Test
    void skipAndRestoreAreDelegatedToTheSelectionService() throws Exception {
        when(selection.skip(vacancyId)).thenReturn(full());
        when(selection.restore(vacancyId)).thenReturn(full());

        mockMvc.perform(put("/api/v1/vacancies/{id}/skip", vacancyId)).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/vacancies/{id}/restore", vacancyId)).andExpect(status().isOk());

        verify(selection).skip(vacancyId);
        verify(selection).restore(vacancyId);
    }

    @Test
    void manualQuestionnaireApplicationCheckIsDelegatedToTheGateway() throws Exception {
        UUID commandId = UUID.randomUUID();
        when(verificationGateway.verifyQuestionnaireApplication(vacancyId)).thenReturn(commandId);

        mockMvc.perform(post("/api/v1/vacancies/{id}/questionnaire-application-check", vacancyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandId").value(commandId.toString()));

        verify(verificationGateway).verifyQuestionnaireApplication(vacancyId);
    }

    @Test
    void forbiddenTransitionIsReportedAsConflictProblemDetail() throws Exception {
        when(selection.skip(vacancyId))
                .thenThrow(new VacancyTransitionNotAllowedException("Refresh the list and try again."));

        mockMvc.perform(put("/api/v1/vacancies/{id}/skip", vacancyId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VACANCY_TRANSITION_NOT_ALLOWED"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void invalidSelectionRequestIsReportedAsBadRequestProblemDetail() throws Exception {
        when(selection.setQueued(anyList(), anyBoolean()))
                .thenThrow(new InvalidVacancyDataException("No vacancy selected."));

        mockMvc.perform(put("/api/v1/vacancies/application-queue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vacancyIds\":[\"%s\"],\"queuedForApplication\":true}".formatted(vacancyId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VACANCY_DATA"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    private void expectValidationFailure(RequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }
}
