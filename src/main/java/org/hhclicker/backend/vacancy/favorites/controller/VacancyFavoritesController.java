package org.hhclicker.backend.vacancy.favorites.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import org.hhclicker.backend.vacancy.favorites.model.VacancyFavorites;
import org.hhclicker.backend.vacancy.favorites.service.VacancyFavoritesService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class VacancyFavoritesController {
    private final VacancyFavoritesService favorites;

    public VacancyFavoritesController(VacancyFavoritesService favorites) {
        this.favorites = favorites;
    }

    @GetMapping("/vacancy-favorites")
    public VacancyFavorites find(@RequestParam UUID accountId) {
        return favorites.find(accountId);
    }

    @PutMapping("/vacancies/{id}/favorite")
    public VacancyFavorites setVacancy(@PathVariable UUID id, @Valid @RequestBody SetFavoriteRequest request) {
        return favorites.set(id, false, request.favorite());
    }

    @PutMapping("/vacancies/{id}/employer-favorite")
    public VacancyFavorites setEmployer(@PathVariable UUID id, @Valid @RequestBody SetFavoriteRequest request) {
        return favorites.set(id, true, request.favorite());
    }

    public record SetFavoriteRequest(@NotNull Boolean favorite) {
    }
}
