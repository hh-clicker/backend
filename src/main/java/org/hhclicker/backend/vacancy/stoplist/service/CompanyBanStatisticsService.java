package org.hhclicker.backend.vacancy.stoplist.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.vacancy.stoplist.repository.CompanyBanRepository;

import java.util.UUID;

/**
 * Публичная read-проекция числа компаний, заблокированных для резюме.
 */
@Service
@Transactional(readOnly = true)
public class CompanyBanStatisticsService {

    private final CompanyBanRepository companyBanRepository;

    public CompanyBanStatisticsService(CompanyBanRepository companyBanRepository) {
        this.companyBanRepository = companyBanRepository;
    }

    public long countVisibleForResume(UUID resumeId) {
        return companyBanRepository.countDistinctVisibleEmployers(resumeId);
    }
}
