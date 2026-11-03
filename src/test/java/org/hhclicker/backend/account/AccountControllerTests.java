package org.hhclicker.backend.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hhclicker.backend.account.controller.AccountController;
import org.hhclicker.backend.account.exception.AccountHasDependentDataException;
import org.hhclicker.backend.account.exception.AccountNotFoundException;
import org.hhclicker.backend.account.exception.HhAccountAlreadyExistsException;
import org.hhclicker.backend.account.exception.InvalidAccountDataException;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.account.service.AccountService;
import org.hhclicker.backend.account.service.CreateAccountCommand;
import org.hhclicker.backend.account.service.UpdateAccountCommand;
import org.hhclicker.backend.shared.exception.GlobalExceptionHandler;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-контракт HH-аккаунтов. Контроллер поднимается вместе с настоящим {@link GlobalExceptionHandler},
 * поэтому проверяются и Bean Validation запроса, и отображение доменных ошибок в ProblemDetail.
 */
class AccountControllerTests {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-04T09:00:00Z");
    private static final String HH_USER_ID = "hh-user-4815162342";

    private final AccountService accountService = mock(AccountService.class);

    private final UUID accountId = UUID.randomUUID();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AccountController(accountService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private HhAccount account() {
        return new HhAccount(accountId, "Primary HH", HH_USER_ID, AuthorizationStatus.AUTHORIZED, CREATED_AT, UPDATED_AT);
    }

    @Test
    void listExposesEveryAccountWithStatusAndTimestamps() throws Exception {
        UUID secondId = UUID.randomUUID();
        when(accountService.findAll()).thenReturn(List.of(account(),
                new HhAccount(secondId, "Spare HH", null, AuthorizationStatus.REQUIRED, CREATED_AT, CREATED_AT)));

        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(accountId.toString()))
                .andExpect(jsonPath("$[0].name").value("Primary HH"))
                .andExpect(jsonPath("$[0].hhUserId").value(HH_USER_ID))
                .andExpect(jsonPath("$[0].authorizationStatus").value("AUTHORIZED"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].updatedAt").exists())
                .andExpect(jsonPath("$[1].id").value(secondId.toString()))
                .andExpect(jsonPath("$[1].authorizationStatus").value("REQUIRED"));
    }

    @Test
    void singleAccountIsReturnedInFull() throws Exception {
        when(accountService.findById(accountId)).thenReturn(account());

        mockMvc.perform(get("/api/v1/accounts/{id}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId.toString()))
                .andExpect(jsonPath("$.name").value("Primary HH"))
                .andExpect(jsonPath("$.hhUserId").value(HH_USER_ID))
                .andExpect(jsonPath("$.authorizationStatus").value("AUTHORIZED"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void unknownAccountIsReportedAsNotFoundProblemDetail() throws Exception {
        when(accountService.findById(accountId)).thenThrow(new AccountNotFoundException(accountId));

        mockMvc.perform(get("/api/v1/accounts/{id}", accountId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.category").value("NOT_FOUND"));
    }

    @Test
    void malformedAccountIdIsRejectedAsRequestValidation() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void creationAnswersWithCreatedStatusAndMapsTheRequestIntoACommand() throws Exception {
        when(accountService.create(any())).thenReturn(account());

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Primary HH", HH_USER_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(accountId.toString()))
                .andExpect(jsonPath("$.authorizationStatus").value("AUTHORIZED"));

        ArgumentCaptor<CreateAccountCommand> captor = ArgumentCaptor.forClass(CreateAccountCommand.class);
        verify(accountService).create(captor.capture());
        assertEquals("Primary HH", captor.getValue().name());
        assertEquals(HH_USER_ID, captor.getValue().hhUserId());
    }

    @Test
    void creationWithoutHhUserIdIsAcceptedAndPassesNullThrough() throws Exception {
        when(accountService.create(any())).thenReturn(account());

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Primary HH\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateAccountCommand> captor = ArgumentCaptor.forClass(CreateAccountCommand.class);
        verify(accountService).create(captor.capture());
        assertNull(captor.getValue().hhUserId(), "hhUserId не обязателен и должен доходить до сервиса как null");
    }

    @Test
    void creationWithoutNameIsRejected() throws Exception {
        expectValidationFailure(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON).content("{\"hhUserId\":\"" + HH_USER_ID + "\"}"));
        expectValidationFailure(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON).content(body("   ", HH_USER_ID)));

        verify(accountService, never()).create(any());
    }

    @Test
    void creationWithTooLongNameIsRejected() throws Exception {
        expectValidationFailure(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON).content(body("n".repeat(121), HH_USER_ID)));

        verify(accountService, never()).create(any());
    }

    /**
     * Фиксирует текущее расхождение слоёв: {@code @Size} считает длину до тримминга,
     * а {@code AccountService} — после. Имя из 120 значащих символов с хвостовыми пробелами
     * сервис принял бы, но до него запрос не доходит.
     */
    @Test
    void trailingSpacesCountTowardsTheDtoLengthLimitAlthoughTheServiceWouldTrimThem() throws Exception {
        expectValidationFailure(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON).content(body("n".repeat(120) + "   ", null)));

        verify(accountService, never()).create(any());
    }

    @Test
    void creationWithTooLongHhUserIdIsRejected() throws Exception {
        expectValidationFailure(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON).content(body("Primary HH", "u".repeat(101))));

        verify(accountService, never()).create(any());
    }

    @Test
    void creationWithATakenHhUserIdIsReportedAsConflictProblemDetail() throws Exception {
        when(accountService.create(any())).thenThrow(new HhAccountAlreadyExistsException(HH_USER_ID));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON).content(body("Primary HH", HH_USER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HH_ACCOUNT_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void domainValidationFromTheServiceIsReportedAsBadRequestProblemDetail() throws Exception {
        when(accountService.create(any())).thenThrow(new InvalidAccountDataException("Name is too long."));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON).content(body("Primary HH", HH_USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_DATA"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void malformedJsonBodyIsRejected() throws Exception {
        expectValidationFailure(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":"));
    }

    @Test
    void updateMapsTheRequestIntoACommandAndReturnsTheStoredAccount() throws Exception {
        when(accountService.update(eq(accountId), any())).thenReturn(account());

        mockMvc.perform(put("/api/v1/accounts/{id}", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Renamed HH", "hh-user-new")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId.toString()));

        ArgumentCaptor<UpdateAccountCommand> captor = ArgumentCaptor.forClass(UpdateAccountCommand.class);
        verify(accountService).update(eq(accountId), captor.capture());
        assertEquals("Renamed HH", captor.getValue().name());
        assertEquals("hh-user-new", captor.getValue().hhUserId());
    }

    @Test
    void updateWithoutHhUserIdIsAcceptedAndClearsTheLink() throws Exception {
        when(accountService.update(eq(accountId), any())).thenReturn(account());

        mockMvc.perform(put("/api/v1/accounts/{id}", accountId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed HH\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateAccountCommand> captor = ArgumentCaptor.forClass(UpdateAccountCommand.class);
        verify(accountService).update(eq(accountId), captor.capture());
        assertNull(captor.getValue().hhUserId());
    }

    @Test
    void updateWithoutNameIsRejected() throws Exception {
        expectValidationFailure(put("/api/v1/accounts/{id}", accountId)
                .contentType(MediaType.APPLICATION_JSON).content(body("   ", HH_USER_ID)));

        verify(accountService, never()).update(any(), any());
    }

    @Test
    void updateOfAMissingAccountIsReportedAsNotFound() throws Exception {
        when(accountService.update(eq(accountId), any())).thenThrow(new AccountNotFoundException(accountId));

        mockMvc.perform(put("/api/v1/accounts/{id}", accountId)
                        .contentType(MediaType.APPLICATION_JSON).content(body("Renamed HH", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void updateWithATakenHhUserIdIsReportedAsConflict() throws Exception {
        when(accountService.update(eq(accountId), any())).thenThrow(new HhAccountAlreadyExistsException(HH_USER_ID));

        mockMvc.perform(put("/api/v1/accounts/{id}", accountId)
                        .contentType(MediaType.APPLICATION_JSON).content(body("Renamed HH", HH_USER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HH_ACCOUNT_ALREADY_EXISTS"));
    }

    @Test
    void deletionAnswersWithNoContentAndAnEmptyBody() throws Exception {
        doNothing().when(accountService).delete(accountId);

        mockMvc.perform(delete("/api/v1/accounts/{id}", accountId))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString().isEmpty(),
                        "204 не должен содержать тело ответа"));

        verify(accountService).delete(accountId);
    }

    @Test
    void deletionOfAMissingAccountIsReportedAsNotFound() throws Exception {
        doThrow(new AccountNotFoundException(accountId)).when(accountService).delete(accountId);

        mockMvc.perform(delete("/api/v1/accounts/{id}", accountId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void deletionOfAnAccountWithDependentDataIsReportedAsConflict() throws Exception {
        doThrow(new AccountHasDependentDataException(accountId, new RuntimeException("fk_hh_resume_account")))
                .when(accountService).delete(accountId);

        mockMvc.perform(delete("/api/v1/accounts/{id}", accountId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_DEPENDENT_DATA"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    private void expectValidationFailure(RequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    private String body(String name, String hhUserId) {
        String hhUserIdValue = hhUserId == null ? "null" : "\"" + hhUserId + "\"";
        return "{\"name\":\"%s\",\"hhUserId\":%s}".formatted(name, hhUserIdValue);
    }
}
