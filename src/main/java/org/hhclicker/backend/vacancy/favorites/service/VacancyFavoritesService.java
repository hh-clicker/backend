package org.hhclicker.backend.vacancy.favorites.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.resume.service.ResumeService;
import org.hhclicker.backend.vacancy.catalog.exception.InvalidVacancyDataException;
import org.hhclicker.backend.vacancy.catalog.service.VacancyCatalogService;
import org.hhclicker.backend.vacancy.favorites.model.VacancyFavorites;
import org.hhclicker.backend.vacancy.favorites.repository.VacancyFavoritesRepository;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class VacancyFavoritesService {
    private final VacancyFavoritesRepository repository;
    private final VacancyCatalogService vacancies;
    private final ResumeService resumes;

    public VacancyFavoritesService(VacancyFavoritesRepository repository, VacancyCatalogService vacancies, ResumeService resumes) {
        this.repository = repository;
        this.vacancies = vacancies;
        this.resumes = resumes;
    }

    public VacancyFavorites find(UUID accountId) {
        resumes.findAll(accountId); // Validate the requested account through a public domain service.
        return repository.findByAccount(accountId);
    }

    @Transactional
    public VacancyFavorites set(UUID vacancyId, boolean employer, boolean favorite) {
        var vacancy = vacancies.findById(vacancyId).vacancy();
        UUID accountId = resumes.findById(vacancy.resumeId()).resume().accountId();
        String targetId = employer ? vacancy.employerId() : vacancy.hhVacancyId();
        if (targetId == null) throw new InvalidVacancyDataException("HH не указал ID работодателя этой вакансии.");
        repository.set(accountId, targetId, employer, favorite);
        // Favorites never mutate a vacancy status, a ban or the application queue.
        return repository.findByAccount(accountId);
    }
}
