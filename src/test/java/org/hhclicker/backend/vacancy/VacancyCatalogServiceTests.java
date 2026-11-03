package org.hhclicker.backend.vacancy;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.vacancy.catalog.exception.VacancyNotFoundException;
import org.hhclicker.backend.vacancy.catalog.model.VacancyApplicationSource;
import org.hhclicker.backend.vacancy.catalog.model.VacancyCounts;
import org.hhclicker.backend.vacancy.catalog.model.VacancyListing;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;
import org.hhclicker.backend.vacancy.catalog.service.VacancyCatalogService;
import org.hhclicker.backend.vacancy.catalog.service.VacancyStatisticsService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Read-проекции каталога вакансий. Работа со снимком текста уже покрыта {@code VacancyTextTests},
 * поэтому здесь только выборки, разрешение вакансии и записи состояния каталога.
 */
class VacancyCatalogServiceTests {

    private final VacancyRepository repository = mock(VacancyRepository.class);
    private final VacancyCatalogService service = new VacancyCatalogService(repository);
    private final VacancyStatisticsService statistics = new VacancyStatisticsService(repository);

    private final UUID vacancyId = UUID.randomUUID();
    private final UUID resumeId = UUID.randomUUID();
    private final UUID searchId = UUID.randomUUID();

    private VacancyListing listing() {
        return VacancyFixtures.listing(VacancyFixtures.active(vacancyId, resumeId), searchId);
    }

    @Test
    void withoutFiltersEveryStoredVacancyIsReturned() {
        List<VacancyListing> stored = List.of(listing());
        when(repository.findAllListings()).thenReturn(stored);

        assertSame(stored, service.findAll(null, null));

        verify(repository).findAllListings();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void resumeFilterSelectsOnlyVacanciesOfThatResume() {
        List<VacancyListing> stored = List.of(listing());
        when(repository.findListingsByResumeId(resumeId)).thenReturn(stored);

        assertSame(stored, service.findAll(resumeId, null));

        verify(repository).findListingsByResumeId(resumeId);
        verify(repository, never()).findAllListings();
    }

    @Test
    void searchFilterSelectsOnlyVacanciesOfThatSearch() {
        List<VacancyListing> stored = List.of(listing());
        when(repository.findListingsBySearchId(searchId)).thenReturn(stored);

        assertSame(stored, service.findAll(null, searchId));

        verify(repository).findListingsBySearchId(searchId);
    }

    @Test
    void searchFilterWinsOverTheResumeFilter() {
        List<VacancyListing> bySearch = List.of(listing());
        when(repository.findListingsBySearchId(searchId)).thenReturn(bySearch);

        assertSame(bySearch, service.findAll(resumeId, searchId));

        verify(repository).findListingsBySearchId(searchId);
        verify(repository, never()).findListingsByResumeId(any());
    }

    @Test
    void unknownSearchSimplyProducesAnEmptyListWithoutAskingTheSearchModule() {
        when(repository.findListingsBySearchId(searchId)).thenReturn(List.of());

        assertTrue(service.findAll(null, searchId).isEmpty());
    }

    @Test
    void singleVacancyIsReturnedAsAListing() {
        VacancyListing stored = listing();
        when(repository.findListingById(vacancyId)).thenReturn(Optional.of(stored));

        assertSame(stored, service.findById(vacancyId));
    }

    @Test
    void missingVacancyIsReportedAsNotFound() {
        when(repository.findListingById(vacancyId)).thenReturn(Optional.empty());

        var exception = assertThrows(VacancyNotFoundException.class, () -> service.findById(vacancyId));

        assertEquals(ApplicationErrorCategory.NOT_FOUND, exception.category());
        assertEquals("VACANCY_NOT_FOUND", exception.machineCode());
    }

    @Test
    void applicationSourceIsReturnedForAVacancyLinkedToASearch() {
        var source = new VacancyApplicationSource("https://hh.ru/search/vacancy?page=0", 2);
        when(repository.applicationSource(vacancyId)).thenReturn(Optional.of(source));

        assertSame(source, service.applicationSource(vacancyId));
    }

    @Test
    void applicationSourceOfAVacancyWithoutAnySearchLinkIsReportedAsNotFound() {
        when(repository.applicationSource(vacancyId)).thenReturn(Optional.empty());

        assertThrows(VacancyNotFoundException.class, () -> service.applicationSource(vacancyId));
    }

    @Test
    void applicationOutcomesAreDelegatedToTheRepository() {
        service.markApplied(vacancyId);
        service.markQuestionnaire(vacancyId);

        verify(repository).markApplied(vacancyId);
        verify(repository).markQuestionnaire(vacancyId);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void vacancyCountsOfTheResumeAreDelegatedWithoutChange() {
        var counts = new VacancyCounts(7, 3);
        when(repository.getCountsByResumeId(resumeId)).thenReturn(counts);

        assertSame(counts, statistics.getByResumeId(resumeId));

        verify(repository).getCountsByResumeId(resumeId);
        verifyNoMoreInteractions(repository);
    }
}
