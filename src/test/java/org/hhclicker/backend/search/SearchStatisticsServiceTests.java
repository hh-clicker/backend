package org.hhclicker.backend.search;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.search.repository.SearchRepository;
import org.hhclicker.backend.search.service.SearchStatisticsService;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Read-проекция числа сохранённых поисков резюме.
 */
class SearchStatisticsServiceTests {

    private final SearchRepository repository = mock(SearchRepository.class);
    private final SearchStatisticsService service = new SearchStatisticsService(repository);

    @Test
    void countIsDelegatedToTheRepositoryForTheRequestedResume() {
        UUID resumeId = UUID.randomUUID();
        when(repository.countByResumeId(resumeId)).thenReturn(7L);

        assertEquals(7L, service.countByResumeId(resumeId));

        verify(repository).countByResumeId(resumeId);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void repositoryValueIsReturnedWithoutAnyAdjustment() {
        UUID resumeId = UUID.randomUUID();
        when(repository.countByResumeId(resumeId)).thenReturn(0L);

        assertEquals(0L, service.countByResumeId(resumeId));
    }
}
