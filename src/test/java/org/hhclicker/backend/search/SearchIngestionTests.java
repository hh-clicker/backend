package org.hhclicker.backend.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.hhclicker.backend.search.exception.SearchNotFoundException;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;
import org.hhclicker.backend.vacancy.catalog.service.IngestSearchResultsCommand;
import org.hhclicker.backend.vacancy.catalog.service.NormalizedVacancy;
import org.hhclicker.backend.vacancy.catalog.service.VacancyIngestionResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Приём результатов поиска. Внутренняя логика сохранения вакансий относится к пакету
 * {@code vacancy} и здесь подменяется моком: проверяется только передача входа и результата.
 */
class SearchIngestionTests extends SearchServiceTestSupport {

    private final List<NormalizedVacancy> vacancies = List.of(
            new NormalizedVacancy("118123456", "Java-разработчик", "3529", "Синтетическая компания",
                    null, "Москва", "Удалённо", "1–3 года", null, "", List.of(), null, false, false, 0));

    private VacancyIngestionResult result() {
        return new VacancyIngestionResult(searchId, 1, 1, 0, 11, 130);
    }

    private void stubIngestion() {
        when(ingestion.ingest(any())).thenReturn(result());
        when(repository.updateAfterIngestion(any())).thenReturn(true);
    }

    private IngestSearchResultsCommand captureIngestionCommand() {
        ArgumentCaptor<IngestSearchResultsCommand> captor = ArgumentCaptor.forClass(IngestSearchResultsCommand.class);
        verify(ingestion).ingest(captor.capture());
        return captor.getValue();
    }

    @Test
    void ingestForwardsTheSearchItsOwnResumeAndTheVacancyListAsIs() {
        UUID ownerResumeId = UUID.randomUUID();
        stubLocked(search(searchId, ownerResumeId, SearchStatus.ACTIVE, CANONICAL_URL, SOURCE_HASH));
        stubIngestion();

        service.ingestVacancies(searchId, vacancies);

        IngestSearchResultsCommand command = captureIngestionCommand();
        assertEquals(searchId, command.searchId());
        assertEquals(ownerResumeId, command.resumeId(), "резюме берётся из сохранённого поиска, а не извне");
        assertSame(vacancies, command.vacancies());
    }

    @Test
    void ingestionResultIsReturnedWithoutAnyChange() {
        stubLocked(search(SearchStatus.ACTIVE));
        VacancyIngestionResult expected = result();
        when(ingestion.ingest(any())).thenReturn(expected);
        when(repository.updateAfterIngestion(any())).thenReturn(true);

        assertSame(expected, service.ingestVacancies(searchId, vacancies));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"ACTIVE, ACTIVE", "PAUSED, PAUSED", "ERROR, ACTIVE"})
    void statusAfterIngestionDependsOnlyOnWhetherTheSearchWasPaused(SearchStatus from, SearchStatus expected) {
        stubLocked(search(from));
        stubIngestion();

        service.ingestVacancies(searchId, vacancies);

        assertEquals(expected, captureIngestionUpdate().status());
    }

    @Test
    void countersAndTimestampsComeFromTheIngestionResult() {
        VacancySearch existing = search(SearchStatus.ERROR);
        stubLocked(existing);
        stubIngestion();

        service.ingestVacancies(searchId, vacancies);

        VacancySearch written = captureIngestionUpdate();
        assertEquals(11, written.newVacancies());
        assertEquals(130, written.totalVacancies());
        assertEquals(NOW, written.lastScannedAt());
        assertEquals(NOW, written.updatedAt());
        assertNull(written.lastError(), "успешный приём снимает прошлую ошибку");
        assertEquals(existing.id(), written.id());
        assertEquals(existing.resumeId(), written.resumeId());
        assertEquals(existing.name(), written.name());
        assertEquals(existing.sourceUrl(), written.sourceUrl());
        assertEquals(existing.sourceHash(), written.sourceHash());
        assertEquals(existing.pagesLimit(), written.pagesLimit());
        assertEquals(CREATED_AT, written.createdAt());
    }

    @Test
    void directIngestIsRejectedWhileAScanIsRunning() {
        stubLocked(search(SearchStatus.SCANNING));

        assertInvalidSearchData(() -> service.ingestVacancies(searchId, vacancies));

        verifyNoInteractions(ingestion);
        verify(repository, never()).updateAfterIngestion(any());
    }

    @Test
    void ingestIntoAMissingSearchIsReportedAsNotFound() {
        when(repository.findByIdForUpdate(searchId)).thenReturn(Optional.empty());

        assertThrows(SearchNotFoundException.class, () -> service.ingestVacancies(searchId, vacancies));

        verifyNoInteractions(ingestion);
    }

    @Test
    void ingestUpdateThatChangedNoRowIsReportedAsNotFound() {
        stubLocked(search(SearchStatus.ACTIVE));
        when(ingestion.ingest(any())).thenReturn(result());
        when(repository.updateAfterIngestion(any())).thenReturn(false);

        assertThrows(SearchNotFoundException.class, () -> service.ingestVacancies(searchId, vacancies));
    }

    @Test
    void completeScanIsAcceptedWhileScanningAndReturnsTheSearchToActive() {
        stubLocked(search(SearchStatus.SCANNING));
        stubIngestion();

        VacancyIngestionResult returned = service.completeScan(searchId, vacancies);

        VacancySearch written = captureIngestionUpdate();
        assertEquals(SearchStatus.ACTIVE, written.status());
        assertEquals(11, written.newVacancies());
        assertEquals(130, written.totalVacancies());
        assertEquals(NOW, written.lastScannedAt());
        assertEquals(11, returned.newVacancies());
        assertSame(vacancies, captureIngestionCommand().vacancies());
    }

    @ParameterizedTest
    @EnumSource(value = SearchStatus.class, names = {"ACTIVE", "PAUSED", "ERROR"})
    void completeScanIsRejectedWhenNoScanIsRunning(SearchStatus from) {
        stubLocked(search(from));

        assertInvalidSearchData(() -> service.completeScan(searchId, vacancies));

        verifyNoInteractions(ingestion);
        verify(repository, never()).updateAfterIngestion(any());
    }

    @Test
    void completeScanForAMissingSearchIsReportedAsNotFound() {
        when(repository.findByIdForUpdate(searchId)).thenReturn(Optional.empty());

        assertThrows(SearchNotFoundException.class, () -> service.completeScan(searchId, vacancies));
    }

    @Test
    void completeScanUpdateThatChangedNoRowIsReportedAsNotFound() {
        stubLocked(search(SearchStatus.SCANNING));
        when(ingestion.ingest(any())).thenReturn(result());
        when(repository.updateAfterIngestion(any())).thenReturn(false);

        assertThrows(SearchNotFoundException.class, () -> service.completeScan(searchId, vacancies));
    }
}
