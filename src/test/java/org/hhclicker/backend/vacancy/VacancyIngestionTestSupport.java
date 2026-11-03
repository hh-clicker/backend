package org.hhclicker.backend.vacancy;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.vacancy.catalog.exception.InvalidVacancyDataException;
import org.hhclicker.backend.vacancy.catalog.model.Vacancy;
import org.hhclicker.backend.vacancy.catalog.model.VacancySearchLink;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;
import org.hhclicker.backend.vacancy.catalog.repository.EmployerStateLock;
import org.hhclicker.backend.vacancy.catalog.repository.VacancySearchLinkRepository;
import org.hhclicker.backend.vacancy.catalog.service.*;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Общая обвязка unit-тестов приёма результатов поиска. Часы фиксированы; стоп-лист подменяется
 * портом {@link EmployerBanLookup}, чтобы каталог не зависел от подсценария {@code stoplist}.
 */
abstract class VacancyIngestionTestSupport {

    protected final VacancyRepository vacancies = mock(VacancyRepository.class);
    protected final VacancySearchLinkRepository links = mock(VacancySearchLinkRepository.class);
    protected final EmployerBanLookup banLookup = mock(EmployerBanLookup.class);
    protected final Clock clock = Clock.fixed(VacancyFixtures.NOW, ZoneOffset.UTC);
    protected final EmployerStateLock employerStateLock = mock(EmployerStateLock.class);
    protected final VacancyIngestionService service =
            new VacancyIngestionService(vacancies, links, banLookup, clock, employerStateLock);

    protected final UUID searchId = UUID.randomUUID();
    protected final UUID resumeId = UUID.randomUUID();

    @BeforeEach
    void stubEmptyCatalog() {
        when(vacancies.findAllByResumeIdAndHhVacancyIds(any(), any())).thenReturn(List.of());
        when(banLookup.findApplicableScopes(any(), any())).thenReturn(Map.of());
        // По умолчанию вставка удаётся и возвращает идентификатор переданной вакансии.
        when(vacancies.insertIfAbsent(any())).thenAnswer(invocation ->
                Optional.of(((Vacancy) invocation.getArgument(0)).id()));
    }

    protected VacancyIngestionResult ingest(NormalizedVacancy... incoming) {
        return service.ingest(new IngestSearchResultsCommand(searchId, resumeId, List.of(incoming)));
    }

    protected VacancyIngestionResult ingest(List<NormalizedVacancy> incoming) {
        return service.ingest(new IngestSearchResultsCommand(searchId, resumeId, incoming));
    }

    protected Vacancy captureInsert() {
        ArgumentCaptor<Vacancy> captor = ArgumentCaptor.forClass(Vacancy.class);
        verify(vacancies).insertIfAbsent(captor.capture());
        return captor.getValue();
    }

    protected Vacancy captureExternalUpdate() {
        ArgumentCaptor<Vacancy> captor = ArgumentCaptor.forClass(Vacancy.class);
        verify(vacancies).updateExternalData(captor.capture());
        return captor.getValue();
    }

    protected VacancySearchLink captureLink() {
        ArgumentCaptor<VacancySearchLink> captor = ArgumentCaptor.forClass(VacancySearchLink.class);
        verify(links).upsert(captor.capture());
        return captor.getValue();
    }

    protected void assertRejected(VacancyFixtures.Incoming incoming) {
        assertInvalidVacancyData(() -> ingest(incoming.build()));
    }

    protected void assertAccepted(VacancyFixtures.Incoming incoming) {
        ingest(incoming.build());
    }

    protected void assertInvalidVacancyData(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(InvalidVacancyDataException.class, executable);
        assertEquals(ApplicationErrorCategory.VALIDATION, exception.category());
        assertEquals("INVALID_VACANCY_DATA", exception.machineCode());
    }
}
