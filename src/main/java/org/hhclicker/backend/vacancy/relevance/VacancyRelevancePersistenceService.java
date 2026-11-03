package org.hhclicker.backend.vacancy.relevance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.vacancy.catalog.model.VacancyRelevance;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;

import java.util.Map;
import java.util.UUID;

@Service
public class VacancyRelevancePersistenceService {

    private final VacancyRepository vacancies;

    public VacancyRelevancePersistenceService(VacancyRepository vacancies) {
        this.vacancies = vacancies;
    }

    @Transactional
    public void save(Map<UUID, VacancyRelevance> evaluations) {
        evaluations.forEach((id, relevance) -> {
            if (!vacancies.saveRelevance(id, relevance)) {
                throw new IllegalStateException("Vacancy disappeared while relevance was being saved");
            }
        });
    }
}
