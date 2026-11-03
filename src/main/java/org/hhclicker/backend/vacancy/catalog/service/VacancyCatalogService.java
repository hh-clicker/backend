package org.hhclicker.backend.vacancy.catalog.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.vacancy.catalog.exception.VacancyNotFoundException;
import org.hhclicker.backend.vacancy.catalog.model.VacancyListing;
import org.hhclicker.backend.vacancy.catalog.model.VacancyText;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;

import java.util.List;
import java.util.UUID;

/**
 * Публичный read-сервис каталога найденных вакансий.
 */
@Service
@Transactional(readOnly = true)
public class VacancyCatalogService {

    private final VacancyRepository vacancyRepository;

    public VacancyCatalogService(VacancyRepository vacancyRepository) {
        this.vacancyRepository = vacancyRepository;
    }

    /**
     * Возвращает найденные вакансии. {@code searchId} имеет приоритет над {@code resumeId}:
     * в этом случае в проекции отдаётся именно запрошенный поиск. При выборке по резюме
     * одна логическая вакансия присутствует один раз и показывается с первой по времени связью.
     */
    public List<VacancyListing> findAll(UUID resumeId, UUID searchId) {
        if (searchId != null) {
            return vacancyRepository.findListingsBySearchId(searchId);
        }
        if (resumeId != null) {
            return vacancyRepository.findListingsByResumeId(resumeId);
        }
        return vacancyRepository.findAllListings();
    }

    public VacancyListing findById(UUID vacancyId) {
        return vacancyRepository.findListingById(vacancyId)
                .orElseThrow(() -> new VacancyNotFoundException(vacancyId));
    }

    public org.hhclicker.backend.vacancy.catalog.model.VacancyApplicationSource applicationSource(UUID id) {
        return vacancyRepository.applicationSource(id).orElseThrow(() -> new VacancyNotFoundException(id));
    }

    @Transactional
    public void markApplied(UUID id) {
        vacancyRepository.markApplied(id);
    }

    @Transactional
    public void markQuestionnaire(UUID id) {
        vacancyRepository.markQuestionnaire(id);
    }

    public VacancyText findText(UUID id) {
        return vacancyRepository.findText(id).orElseThrow(() -> new VacancyNotFoundException(id));
    }

    @Transactional
    public void saveText(UUID id, String plainText) {
        // Optional enrichment must not reject a valid application outcome.
        if (plainText != null && plainText.length() <= 100000 && plainText.indexOf('\0') < 0) {
            vacancyRepository.saveText(id, plainText);
        }
    }
}
