package org.hhclicker.backend.vacancy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hhclicker.backend.shared.exception.GlobalExceptionHandler;
import org.hhclicker.backend.vacancy.catalog.exception.VacancyNotFoundException;
import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;
import org.hhclicker.backend.vacancy.catalog.model.Vacancy;
import org.hhclicker.backend.vacancy.catalog.model.VacancyListing;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;
import org.hhclicker.backend.vacancy.stoplist.controller.CompanyBanController;
import org.hhclicker.backend.vacancy.stoplist.controller.VacancyCompanyBanController;
import org.hhclicker.backend.vacancy.stoplist.exception.CompanyBanAlreadyExistsException;
import org.hhclicker.backend.vacancy.stoplist.exception.CompanyBanNotFoundException;
import org.hhclicker.backend.vacancy.stoplist.exception.InvalidCompanyBanDataException;
import org.hhclicker.backend.vacancy.stoplist.model.CompanyBan;
import org.hhclicker.backend.vacancy.stoplist.service.CompanyBanService;
import org.hhclicker.backend.vacancy.stoplist.service.CompanyBanView;
import org.hhclicker.backend.vacancy.stoplist.service.CreateCompanyBanCommand;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-контракт стоп-листа: строгий /company-bans и идемпотентный ban через вакансию.
 */
class CompanyBanControllerTests {

    private final CompanyBanService service = mock(CompanyBanService.class);

    private final UUID banId = UUID.randomUUID();
    private final UUID resumeId = UUID.randomUUID();
    private final UUID vacancyId = UUID.randomUUID();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CompanyBanController(service), new VacancyCompanyBanController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private CompanyBanView view(CompanyBanScope scope, UUID owner) {
        CompanyBan ban = new CompanyBan(banId, "3529", "https://hh.ru/employer/3529", "Example",
                scope, owner, vacancyId, VacancyFixtures.EARLIER, VacancyFixtures.EARLIER);
        return new CompanyBanView(ban, owner == null ? null : "Java Developer", "Java Developer", 7);
    }

    private VacancyListing listing() {
        Vacancy vacancy = VacancyFixtures.stored(vacancyId, resumeId, "118123456", "3529",
                VacancyStatus.COMPANY_BANNED, CompanyBanScope.GLOBAL, false);
        return new VacancyListing(vacancy, null, false);
    }

    @Test
    void stopListExposesEveryComputedField() throws Exception {
        when(service.findAll(null)).thenReturn(List.of(view(CompanyBanScope.RESUME, resumeId)));

        mockMvc.perform(get("/api/v1/company-bans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(banId.toString()))
                .andExpect(jsonPath("$[0].employerId").value("3529"))
                .andExpect(jsonPath("$[0].employerUrl").value("https://hh.ru/employer/3529"))
                .andExpect(jsonPath("$[0].companyName").value("Example"))
                .andExpect(jsonPath("$[0].scope").value("RESUME"))
                .andExpect(jsonPath("$[0].resumeId").value(resumeId.toString()))
                .andExpect(jsonPath("$[0].resumeName").value("Java Developer"))
                .andExpect(jsonPath("$[0].sourceVacancyId").value(vacancyId.toString()))
                .andExpect(jsonPath("$[0].sourceVacancyTitle").value("Java Developer"))
                .andExpect(jsonPath("$[0].hiddenVacanciesCount").value(7))
                .andExpect(jsonPath("$[0].createdAt").exists());
    }

    @Test
    void stopListOfAResumeIsRequestedWithTheFilter() throws Exception {
        when(service.findAll(resumeId)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/company-bans").param("resumeId", resumeId.toString()))
                .andExpect(status().isOk());

        verify(service).findAll(resumeId);
    }

    @Test
    void creationAnswersWithCreatedStatusAndIgnoresClientComputedFields() throws Exception {
        when(service.create(any())).thenReturn(view(CompanyBanScope.RESUME, resumeId));
        String body = """
                {"employerUrl":"https://hh.ru/employer/3529","companyName":"Example","scope":"RESUME",
                 "resumeId":"%s","resumeName":"FAKE","sourceVacancyId":"%s","sourceVacancyTitle":"FAKE",
                 "hiddenVacanciesCount":9999}
                """.formatted(resumeId, vacancyId);

        mockMvc.perform(post("/api/v1/company-bans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resumeName").value("Java Developer"))
                .andExpect(jsonPath("$.sourceVacancyTitle").value("Java Developer"))
                .andExpect(jsonPath("$.hiddenVacanciesCount").value(7));

        ArgumentCaptor<CreateCompanyBanCommand> captor = ArgumentCaptor.forClass(CreateCompanyBanCommand.class);
        verify(service).create(captor.capture());
        CreateCompanyBanCommand command = captor.getValue();
        assertEquals("https://hh.ru/employer/3529", command.employerUrl());
        assertEquals("Example", command.companyName());
        assertEquals(CompanyBanScope.RESUME, command.scope());
        assertEquals(resumeId, command.resumeId());
        assertEquals(vacancyId, command.sourceVacancyId());
    }

    @Test
    void creationValidatesTheEmployerLinkAndScopeOnTheHttpBoundary() throws Exception {
        expectValidationFailure(post("/api/v1/company-bans").contentType(MediaType.APPLICATION_JSON)
                .content("{\"employerUrl\":\"   \",\"scope\":\"GLOBAL\"}"));
        expectValidationFailure(post("/api/v1/company-bans").contentType(MediaType.APPLICATION_JSON)
                .content("{\"employerUrl\":\"https://hh.ru/employer/%s\",\"scope\":\"GLOBAL\"}".formatted("1".repeat(500))));
        expectValidationFailure(post("/api/v1/company-bans").contentType(MediaType.APPLICATION_JSON)
                .content("{\"employerUrl\":\"https://hh.ru/employer/3529\"}"));
        expectValidationFailure(post("/api/v1/company-bans").contentType(MediaType.APPLICATION_JSON)
                .content("{\"employerUrl\":\"https://hh.ru/employer/3529\",\"scope\":\"WORLDWIDE\"}"));

        verify(service, never()).create(any());
    }

    @Test
    void domainValidationOfTheStopListIsReportedAsBadRequestProblemDetail() throws Exception {
        when(service.create(any())).thenThrow(new InvalidCompanyBanDataException("Link must look like /employer/3529."));

        mockMvc.perform(post("/api/v1/company-bans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employerUrl\":\"https://hh.ru/employer/3529\",\"scope\":\"GLOBAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMPANY_BAN_DATA"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void duplicateBanIsReportedAsConflictProblemDetail() throws Exception {
        when(service.create(any())).thenThrow(new CompanyBanAlreadyExistsException("Already blocked globally."));

        mockMvc.perform(post("/api/v1/company-bans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employerUrl\":\"https://hh.ru/employer/3529\",\"scope\":\"GLOBAL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMPANY_BAN_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void scopeChangeIsDelegatedWithBothArguments() throws Exception {
        when(service.changeScope(eq(banId), any(), any())).thenReturn(view(CompanyBanScope.GLOBAL, null));

        mockMvc.perform(put("/api/v1/company-bans/{id}/scope", banId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"GLOBAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("GLOBAL"));

        verify(service).changeScope(banId, CompanyBanScope.GLOBAL, null);
    }

    @Test
    void scopeChangeRequiresAScope() throws Exception {
        expectValidationFailure(put("/api/v1/company-bans/{id}/scope", banId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));

        verify(service, never()).changeScope(any(), any(), any());
    }

    @Test
    void unknownBanIsReportedAsNotFoundProblemDetail() throws Exception {
        when(service.changeScope(any(), any(), any())).thenThrow(new CompanyBanNotFoundException(banId));

        mockMvc.perform(put("/api/v1/company-bans/{id}/scope", banId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"GLOBAL\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMPANY_BAN_NOT_FOUND"))
                .andExpect(jsonPath("$.category").value("NOT_FOUND"));
    }

    @Test
    void banRemovalAnswersWithNoContentAndAnEmptyBody() throws Exception {
        doNothing().when(service).delete(banId);

        mockMvc.perform(delete("/api/v1/company-bans/{id}", banId))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString().isEmpty()));

        verify(service).delete(banId);
    }

    @Test
    void removalOfAMissingBanIsReportedAsNotFound() throws Exception {
        doThrow(new CompanyBanNotFoundException(banId)).when(service).delete(banId);

        mockMvc.perform(delete("/api/v1/company-bans/{id}", banId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMPANY_BAN_NOT_FOUND"));
    }

    @Test
    void banThroughAVacancyReturnsTheVacanciesThatChanged() throws Exception {
        when(service.banVacancyCompany(vacancyId, CompanyBanScope.GLOBAL)).thenReturn(List.of(listing()));

        mockMvc.perform(post("/api/v1/vacancies/{id}/company-ban", vacancyId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"GLOBAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(vacancyId.toString()))
                .andExpect(jsonPath("$[0].status").value("COMPANY_BANNED"))
                .andExpect(jsonPath("$[0].banScope").value("GLOBAL"));

        verify(service).banVacancyCompany(vacancyId, CompanyBanScope.GLOBAL);
    }

    @Test
    void repeatedBanThroughAVacancyMayReturnAnEmptyList() throws Exception {
        when(service.banVacancyCompany(vacancyId, CompanyBanScope.RESUME)).thenReturn(List.of());

        mockMvc.perform(post("/api/v1/vacancies/{id}/company-ban", vacancyId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"RESUME\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void banThroughAVacancyRequiresAScope() throws Exception {
        expectValidationFailure(post("/api/v1/vacancies/{id}/company-ban", vacancyId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));

        verify(service, never()).banVacancyCompany(any(), any());
    }

    @Test
    void banThroughAMissingVacancyIsReportedAsNotFound() throws Exception {
        when(service.banVacancyCompany(any(), any())).thenThrow(new VacancyNotFoundException(vacancyId));

        mockMvc.perform(post("/api/v1/vacancies/{id}/company-ban", vacancyId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"GLOBAL\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VACANCY_NOT_FOUND"));
    }

    @Test
    void malformedBanIdIsRejected() throws Exception {
        mockMvc.perform(delete("/api/v1/company-bans/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }

    private void expectValidationFailure(RequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }
}
