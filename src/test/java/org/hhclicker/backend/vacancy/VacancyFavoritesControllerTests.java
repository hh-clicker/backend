package org.hhclicker.backend.vacancy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hhclicker.backend.shared.exception.GlobalExceptionHandler;
import org.hhclicker.backend.vacancy.catalog.exception.InvalidVacancyDataException;
import org.hhclicker.backend.vacancy.catalog.exception.VacancyNotFoundException;
import org.hhclicker.backend.vacancy.favorites.controller.VacancyFavoritesController;
import org.hhclicker.backend.vacancy.favorites.model.VacancyFavorites;
import org.hhclicker.backend.vacancy.favorites.service.VacancyFavoritesService;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-контракт локальных звёздочек вакансий и работодателей.
 */
class VacancyFavoritesControllerTests {

    private final VacancyFavoritesService favorites = mock(VacancyFavoritesService.class);

    private final UUID accountId = UUID.randomUUID();
    private final UUID vacancyId = UUID.randomUUID();
    private final VacancyFavorites stored =
            new VacancyFavorites(UUID.randomUUID(), List.of("118123456"), List.of("3529"));

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new VacancyFavoritesController(favorites))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void favoritesOfTheAccountAreReturned() throws Exception {
        when(favorites.find(accountId)).thenReturn(stored);

        mockMvc.perform(get("/api/v1/vacancy-favorites").param("accountId", accountId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vacancyIds[0]").value("118123456"))
                .andExpect(jsonPath("$.employerIds[0]").value("3529"));

        verify(favorites).find(accountId);
    }

    @Test
    void missingAccountParameterIsReportedAsAValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/vacancy-favorites"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void vacancyFavoriteIsSetAndCleared() throws Exception {
        when(favorites.set(any(), anyBoolean(), anyBoolean())).thenReturn(stored);

        mockMvc.perform(put("/api/v1/vacancies/{id}/favorite", vacancyId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vacancyIds.length()").value(1));
        mockMvc.perform(put("/api/v1/vacancies/{id}/favorite", vacancyId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":false}"))
                .andExpect(status().isOk());

        verify(favorites).set(vacancyId, false, true);
        verify(favorites).set(vacancyId, false, false);
    }

    @Test
    void employerFavoriteUsesTheDedicatedEndpoint() throws Exception {
        when(favorites.set(any(), anyBoolean(), anyBoolean())).thenReturn(stored);

        mockMvc.perform(put("/api/v1/vacancies/{id}/employer-favorite", vacancyId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}"))
                .andExpect(status().isOk());

        verify(favorites).set(vacancyId, true, true);
    }

    @Test
    void emptyBodyMustNotSilentlyChangeTheFavoriteFlag() throws Exception {
        mockMvc.perform(put("/api/v1/vacancies/{id}/favorite", vacancyId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

        verify(favorites, never()).set(any(), anyBoolean(), anyBoolean());
    }

    @Test
    void missingVacancyIsReportedAsNotFoundProblemDetail() throws Exception {
        when(favorites.set(any(), anyBoolean(), anyBoolean()))
                .thenThrow(new VacancyNotFoundException(vacancyId));

        mockMvc.perform(put("/api/v1/vacancies/{id}/favorite", vacancyId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VACANCY_NOT_FOUND"));
    }

    @Test
    void employerFavoriteWithoutAnEmployerIsReportedAsBadRequestProblemDetail() throws Exception {
        when(favorites.set(any(), anyBoolean(), anyBoolean()))
                .thenThrow(new InvalidVacancyDataException("HH did not provide the employer id."));

        mockMvc.perform(put("/api/v1/vacancies/{id}/employer-favorite", vacancyId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VACANCY_DATA"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void malformedVacancyIdIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/vacancies/{id}/favorite", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }
}
