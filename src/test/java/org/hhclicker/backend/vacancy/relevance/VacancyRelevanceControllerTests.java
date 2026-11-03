package org.hhclicker.backend.vacancy.relevance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hhclicker.backend.shared.exception.GlobalExceptionHandler;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VacancyRelevanceControllerTests {

    private final VacancyRelevanceService service = mock(VacancyRelevanceService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new VacancyRelevanceController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void startsGenerationAndReturnsAcceptedContract() throws Exception {
        UUID vacancyId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        when(service.start(any())).thenReturn(new VacancyRelevanceRunResponse(jobId, resumeId, "queued", 1, 0, 1, null, null));

        mockMvc.perform(post("/api/v1/vacancies/relevance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vacancyIds\":[\"%s\"]}".formatted(vacancyId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.requestedCount").value(1))
                .andExpect(jsonPath("$.missingCount").value(1));
    }

    @Test
    void rejectsEmptyMalformedAndOversizedRequestsBeforeService() throws Exception {
        mockMvc.perform(post("/api/v1/vacancies/relevance").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vacancyIds\":[]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/vacancies/relevance").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vacancyIds\":[\"bad\"]}"))
                .andExpect(status().isBadRequest());
        String ids = java.util.stream.IntStream.range(0, 301).mapToObj(ignored -> "\"" + UUID.randomUUID() + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        mockMvc.perform(post("/api/v1/vacancies/relevance").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vacancyIds\":[%s]}".formatted(ids)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void pollsGenerationStatusAndRejectsMalformedId() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(service.find(jobId)).thenReturn(new VacancyRelevanceRunResponse(jobId, UUID.randomUUID(), "done", 3, 2, 1, null, null));

        mockMvc.perform(get("/api/v1/vacancies/relevance/{id}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.evaluatedCount").value(2))
                .andExpect(jsonPath("$.missingCount").value(1));
        mockMvc.perform(get("/api/v1/vacancies/relevance/{id}", "bad"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void restoresActiveRunOrReturnsNoContent() throws Exception {
        UUID resumeId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(service.active(resumeId)).thenReturn(new VacancyRelevanceRunResponse(
                jobId, resumeId, "running", 3, 0, 3, null, null));

        mockMvc.perform(get("/api/v1/vacancies/relevance/active").param("resumeId", resumeId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId.toString()))
                .andExpect(jsonPath("$.resumeId").value(resumeId.toString()))
                .andExpect(jsonPath("$.status").value("running"));

        mockMvc.perform(get("/api/v1/vacancies/relevance/active").param("resumeId", UUID.randomUUID().toString()))
                .andExpect(status().isNoContent());
    }
}
