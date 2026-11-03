package org.hhclicker.backend.shared.exception;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Полный transport-контракт общей границы ошибок через standalone Spring MVC.
 */
class GlobalExceptionHandlerTests {

    private static final String INTERNAL_MESSAGE = "database-password=must-not-leak";
    private static final String INVALID_FORMAT_DETAIL = "Проверьте формат переданных данных.";
    private static final UUID VALID_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ErrorTestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @CsvSource({
            "VALIDATION,400,Bad Request",
            "NOT_FOUND,404,Not Found",
            "CONFLICT,409,Conflict",
            "EXTERNAL_SYSTEM,502,Bad Gateway",
            "INTERNAL,500,Internal Server Error"
    })
    void applicationCategoriesMapToCompleteSafeProblemDetails(
            ApplicationErrorCategory category, int expectedStatus, String expectedTitle) throws Exception {
        String safeMessage = "Безопасное сообщение " + category;
        String machineCode = "TEST_" + category;

        mockMvc.perform(get("/shared-test/domain")
                        .param("category", category.name())
                        .param("ignored", "query-is-not-an-instance"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.title").value(expectedTitle))
                .andExpect(jsonPath("$.detail").value(safeMessage))
                .andExpect(jsonPath("$.instance").value("/shared-test/domain"))
                .andExpect(jsonPath("$.code").value(machineCode))
                .andExpect(jsonPath("$.category").value(category.name()))
                .andExpect(content().string(not(containsString(INTERNAL_MESSAGE))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void missingRequiredBodyFieldReportsItsFieldAndStableCustomMessage() throws Exception {
        mockMvc.perform(post("/shared-test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"%s\"}".formatted(VALID_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"))
                .andExpect(jsonPath("$.detail").value("name: required text"))
                .andExpect(jsonPath("$.instance").value("/shared-test/body"));
    }

    @Test
    void blankNotBlankBodyFieldReportsTheSameValidationContract() throws Exception {
        mockMvc.perform(post("/shared-test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \",\"id\":\"%s\"}".formatted(VALID_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("name: required text"));
    }

    @Test
    void multipleBodyErrorsReportOneRealFieldErrorWithoutDependingOnValidatorOrdering() throws Exception {
        mockMvc.perform(post("/shared-test/body").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"))
                .andExpect(jsonPath("$.detail").value(anyOf(
                        is("name: required text"), is("id: required id"))));
    }

    @Test
    void malformedJsonUsesTheCommonSafeRequestFormatProblem() throws Exception {
        expectInvalidFormat(post("/shared-test/body").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":"), "/shared-test/body");
    }

    @Test
    void emptyRequestBodyUsesTheCommonSafeRequestFormatProblem() throws Exception {
        expectInvalidFormat(post("/shared-test/body").contentType(MediaType.APPLICATION_JSON),
                "/shared-test/body");
    }

    @Test
    void missingRequiredRequestParameterUsesTheCommonSafeRequestFormatProblem() throws Exception {
        expectInvalidFormat(get("/shared-test/required"), "/shared-test/required");
    }

    @Test
    void uuidTypeMismatchUsesTheCommonSafeRequestFormatProblem() throws Exception {
        expectInvalidFormat(get("/shared-test/uuid").param("id", "not-a-uuid"), "/shared-test/uuid");
    }

    @Test
    void constraintViolationExceptionUsesTheCommonSafeRequestFormatProblem() throws Exception {
        expectInvalidFormat(get("/shared-test/constraint-violation"), "/shared-test/constraint-violation");
    }

    /**
     * Фиксирует желаемую request-validation границу. В Spring MVC method constraint может породить
     * HandlerMethodValidationException, которого текущий GlobalExceptionHandler явно не обрабатывает.
     */
    @Test
    void methodParameterValidationUsesRequestValidationProblemInsteadOfGeneric500() throws Exception {
        expectInvalidFormat(get("/shared-test/method-validation").param("value", "   "),
                "/shared-test/method-validation");
    }

    @Test
    void unexpectedExceptionReturnsOnlyGenericPublicInformation() throws Exception {
        mockMvc.perform(get("/shared-test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").value("Не удалось выполнить операцию."))
                .andExpect(jsonPath("$.instance").value("/shared-test/unexpected"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.category").value("INTERNAL"))
                .andExpect(content().string(not(containsString(INTERNAL_MESSAGE))))
                .andExpect(content().string(not(containsString("RuntimeException"))));
    }

    private void expectInvalidFormat(org.springframework.test.web.servlet.RequestBuilder request, String instance)
            throws Exception {
        ResultActions result = mockMvc.perform(request);
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value(INVALID_FORMAT_DETAIL))
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @RestController
    @RequestMapping("/shared-test")
    public static class ErrorTestController {

        @GetMapping("/domain")
        void domain(@RequestParam("category") ApplicationErrorCategory category) {
            throw new ApplicationException(category, "TEST_" + category, "Безопасное сообщение " + category,
                    new IllegalStateException(INTERNAL_MESSAGE));
        }

        @PostMapping("/body")
        void body(@Valid @RequestBody TestBody body) {
        }

        @GetMapping("/required")
        void required(@RequestParam("value") String value) {
        }

        @GetMapping("/uuid")
        void uuid(@RequestParam("id") UUID id) {
        }

        @GetMapping("/constraint-violation")
        void constraintViolation() {
            throw new ConstraintViolationException(INTERNAL_MESSAGE, Set.of());
        }

        @GetMapping("/method-validation")
        @Validated
        void methodValidation(
                @RequestParam("value") @NotBlank(message = "required query value") String value) {
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new RuntimeException(INTERNAL_MESSAGE);
        }
    }

    public record TestBody(
            @NotBlank(message = "required text") String name,
            @NotNull(message = "required id") UUID id
    ) {
    }
}
