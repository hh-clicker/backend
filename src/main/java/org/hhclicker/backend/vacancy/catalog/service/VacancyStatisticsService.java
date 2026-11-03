package org.hhclicker.backend.vacancy.catalog.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.vacancy.catalog.model.VacancyCounts;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;

import java.util.UUID;

/**
 * Публичная read-проекция текущего состояния вакансий резюме.
 */
@Service
@Transactional(readOnly = true)
public class VacancyStatisticsService {

    private final VacancyRepository vacancyRepository;

    public VacancyStatisticsService(VacancyRepository vacancyRepository) {
        this.vacancyRepository = vacancyRepository;
    }

    public VacancyCounts getByResumeId(UUID resumeId) {
        return vacancyRepository.getCountsByResumeId(resumeId);
    }
}
