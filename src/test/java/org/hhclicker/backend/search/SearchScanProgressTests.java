package org.hhclicker.backend.search;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.hhclicker.backend.search.exception.SearchNotFoundException;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SearchScanProgressTests extends SearchServiceTestSupport {

    private VacancySearch scanning(int currentPage, int collected) {
        return new VacancySearch(searchId, resumeId, "Java в Москве", CANONICAL_URL, SOURCE_HASH, 5,
                SearchStatus.SCANNING, 7, 42, LAST_SCANNED_AT, null,
                CREATED_AT, currentPage, 5, collected, 421, CREATED_AT, CREATED_AT);
    }

    @Test
    void progressIsStoredWithoutChangingTheLastSuccessfulResult() {
        VacancySearch existing = scanning(1, 100);
        stubLocked(existing);
        when(repository.updateScanProgress(any())).thenReturn(true);

        service.updateScanProgress(searchId, 2, 5, 200, 421);

        ArgumentCaptor<VacancySearch> captor = ArgumentCaptor.forClass(VacancySearch.class);
        verify(repository).updateScanProgress(captor.capture());
        VacancySearch written = captor.getValue();
        assertEquals(SearchStatus.SCANNING, written.status());
        assertEquals(2, written.scanCurrentPage());
        assertEquals(5, written.scanTotalPages());
        assertEquals(200, written.scanCollected());
        assertEquals(421, written.scanTotalResults());
        assertEquals(CREATED_AT, written.scanStartedAt());
        assertEquals(7, written.newVacancies());
        assertEquals(42, written.totalVacancies());
        assertEquals(LAST_SCANNED_AT, written.lastScannedAt());
        assertEquals(NOW, written.updatedAt());
    }

    @Test
    void progressMayOmitTheApproximateHhResultCount() {
        stubLocked(scanning(1, 100));
        when(repository.updateScanProgress(any())).thenReturn(true);

        service.updateScanProgress(searchId, 2, 5, 200, null);

        ArgumentCaptor<VacancySearch> captor = ArgumentCaptor.forClass(VacancySearch.class);
        verify(repository).updateScanProgress(captor.capture());
        assertNull(captor.getValue().scanTotalResults());
    }

    @Test
    void impossibleOrRegressingProgressIsRejected() {
        stubLocked(scanning(2, 150));

        assertInvalidSearchData(() -> service.updateScanProgress(searchId, 1, 5, 100, 421));
        assertInvalidSearchData(() -> service.updateScanProgress(searchId, 3, 6, 200, 421));
        assertInvalidSearchData(() -> service.updateScanProgress(searchId, 2, 5, 201, 421));
        assertInvalidSearchData(() -> service.updateScanProgress(searchId, 2, 5, 150, 100));

        verify(repository, never()).updateScanProgress(any());
    }

    @Test
    void progressIsRejectedAfterTheScanHasFinished() {
        stubLocked(search(SearchStatus.ACTIVE));

        assertInvalidSearchData(() -> service.updateScanProgress(searchId, 1, 5, 100, 421));

        verify(repository, never()).updateScanProgress(any());
    }

    @Test
    void missingProgressUpdateIsReportedAsMissingSearch() {
        stubLocked(scanning(1, 100));
        when(repository.updateScanProgress(any())).thenReturn(false);

        assertThrows(SearchNotFoundException.class,
                () -> service.updateScanProgress(searchId, 2, 5, 200, 421));
    }
}
