package org.hhclicker.backend.search.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.search.repository.SearchRepository;

import java.util.UUID;

/**
 * Публичная read-проекция количества сохранённых поисков резюме.
 */
@Service
@Transactional(readOnly = true)
public class SearchStatisticsService {

    private final SearchRepository searchRepository;

    public SearchStatisticsService(SearchRepository searchRepository) {
        this.searchRepository = searchRepository;
    }

    public long countByResumeId(UUID resumeId) {
        return searchRepository.countByResumeId(resumeId);
    }
}
