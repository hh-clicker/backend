package org.hhclicker.backend.overview.service;

import org.springframework.stereotype.Service;
import org.hhclicker.backend.resume.model.ResumeStatistics;
import org.hhclicker.backend.resume.service.ResumeStatisticsProvider;
import org.hhclicker.backend.search.service.SearchStatisticsService;
import org.hhclicker.backend.vacancy.catalog.model.VacancyCounts;
import org.hhclicker.backend.vacancy.catalog.service.VacancyStatisticsService;
import org.hhclicker.backend.vacancy.stoplist.service.CompanyBanStatisticsService;

import java.util.UUID;

/**
 * Leaf-read адаптер, собирающий статистику резюме из публичных read-сервисов модулей.
 */
@Service
public class DatabaseResumeStatisticsProvider implements ResumeStatisticsProvider {

    private final SearchStatisticsService searchStatisticsService;
    private final VacancyStatisticsService vacancyStatisticsService;
    private final CompanyBanStatisticsService companyBanStatisticsService;

    public DatabaseResumeStatisticsProvider(
            SearchStatisticsService searchStatisticsService,
            VacancyStatisticsService vacancyStatisticsService,
            CompanyBanStatisticsService companyBanStatisticsService
    ) {
        this.searchStatisticsService = searchStatisticsService;
        this.vacancyStatisticsService = vacancyStatisticsService;
        this.companyBanStatisticsService = companyBanStatisticsService;
    }

    @Override
    public ResumeStatistics get(UUID resumeId) {
        VacancyCounts vacancyCounts = vacancyStatisticsService.getByResumeId(resumeId);
        return new ResumeStatistics(
                searchStatisticsService.countByResumeId(resumeId),
                vacancyCounts.newVacancies(),
                vacancyCounts.queuedVacancies(),
                0,
                companyBanStatisticsService.countVisibleForResume(resumeId)
        );
    }
}
