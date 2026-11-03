package org.hhclicker.backend.overview;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.hhclicker.backend.overview.service.DatabaseResumeStatisticsProvider;
import org.hhclicker.backend.search.service.SearchStatisticsService;
import org.hhclicker.backend.vacancy.catalog.model.VacancyCounts;
import org.hhclicker.backend.vacancy.catalog.service.VacancyStatisticsService;
import org.hhclicker.backend.vacancy.stoplist.service.CompanyBanStatisticsService;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Составная статистика резюме, собранная исключительно через публичные read-сервисы модулей.
 */
class DatabaseResumeStatisticsProviderTests {

    private static final UUID RESUME_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    private final SearchStatisticsService searches = mock(SearchStatisticsService.class);
    private final VacancyStatisticsService vacancies = mock(VacancyStatisticsService.class);
    private final CompanyBanStatisticsService companyBans = mock(CompanyBanStatisticsService.class);
    private final DatabaseResumeStatisticsProvider provider =
            new DatabaseResumeStatisticsProvider(searches, vacancies, companyBans);

    @Test
    void valuesAreReadForRequestedResumeAndPlacedIntoTheirCorrectFields() {
        when(vacancies.getByResumeId(RESUME_ID)).thenReturn(new VacancyCounts(12, 7));
        when(searches.countByResumeId(RESUME_ID)).thenReturn(4L);
        when(companyBans.countVisibleForResume(RESUME_ID)).thenReturn(3L);

        var statistics = provider.get(RESUME_ID);

        assertEquals(4, statistics.searches());
        assertEquals(12, statistics.newVacancies());
        assertEquals(7, statistics.queuedVacancies());
        assertEquals(0, statistics.successfulApplications(),
                "Текущий адаптер пока не подключает статистику успешных откликов");
        assertEquals(3, statistics.blockedCompanies());
        InOrder order = inOrder(vacancies, searches, companyBans);
        order.verify(vacancies).getByResumeId(RESUME_ID);
        order.verify(searches).countByResumeId(RESUME_ID);
        order.verify(companyBans).countVisibleForResume(RESUME_ID);
        order.verifyNoMoreInteractions();
    }

    @Test
    void vacancyFailureIsPropagatedBeforeLaterStatisticsAreRequested() {
        RuntimeException failure = new RuntimeException("vacancy statistics unavailable");
        when(vacancies.getByResumeId(RESUME_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> provider.get(RESUME_ID)));

        verifyNoInteractions(searches, companyBans);
    }

    @Test
    void searchFailureIsPropagatedAndCompanyStatisticsAreNotRequested() {
        RuntimeException failure = new RuntimeException("search statistics unavailable");
        when(vacancies.getByResumeId(RESUME_ID)).thenReturn(new VacancyCounts(12, 7));
        when(searches.countByResumeId(RESUME_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> provider.get(RESUME_ID)));

        InOrder order = inOrder(vacancies, searches);
        order.verify(vacancies).getByResumeId(RESUME_ID);
        order.verify(searches).countByResumeId(RESUME_ID);
        verifyNoInteractions(companyBans);
    }

    @Test
    void companyBanFailureIsPropagatedAfterEarlierReads() {
        RuntimeException failure = new RuntimeException("company statistics unavailable");
        when(vacancies.getByResumeId(RESUME_ID)).thenReturn(new VacancyCounts(12, 7));
        when(searches.countByResumeId(RESUME_ID)).thenReturn(4L);
        when(companyBans.countVisibleForResume(RESUME_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> provider.get(RESUME_ID)));

        InOrder order = inOrder(vacancies, searches, companyBans);
        order.verify(vacancies).getByResumeId(RESUME_ID);
        order.verify(searches).countByResumeId(RESUME_ID);
        order.verify(companyBans).countVisibleForResume(RESUME_ID);
    }
}
