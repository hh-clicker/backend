package org.hhclicker.backend.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.hhclicker.backend.search.exception.SearchNotFoundException;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Пауза, возобновление и удаление сохранённого поиска.
 */
class SearchPauseAndDeleteTests extends SearchServiceTestSupport {

    @ParameterizedTest(name = "{0} + paused={1} -> {2}")
    @CsvSource({
            "ACTIVE, true, PAUSED",
            "PAUSED, false, ACTIVE",
            "ERROR, true, PAUSED",
            "ERROR, false, ACTIVE",
            "ACTIVE, false, ACTIVE",
            "PAUSED, true, PAUSED"
    })
    void pauseFlagDrivesTheStatusAndAlwaysClearsTheStoredError(SearchStatus from, boolean paused, SearchStatus expected) {
        stubResume();
        VacancySearch existing = search(from);
        stubLocked(existing);
        when(repository.updateStatus(any())).thenReturn(true);

        var profile = service.setPaused(searchId, paused);

        VacancySearch written = captureStatusUpdate();
        assertEquals(expected, written.status());
        assertNull(written.lastError());
        assertEquals(LAST_SCANNED_AT, written.lastScannedAt(), "пауза не сбрасывает время последнего сканирования");
        assertEquals(existing.newVacancies(), written.newVacancies());
        assertEquals(existing.totalVacancies(), written.totalVacancies());
        assertEquals(existing.sourceUrl(), written.sourceUrl());
        assertEquals(CREATED_AT, written.createdAt());
        assertEquals(NOW, written.updatedAt());
        assertEquals(expected, profile.search().status());
        assertEquals(RESUME_NAME, profile.resumeName());
    }

    @Test
    void searchBeingScannedCannotBePaused() {
        stubLocked(search(SearchStatus.SCANNING));

        assertInvalidSearchData(() -> service.setPaused(searchId, true));

        verify(repository, never()).updateStatus(any());
    }

    @Test
    void pausingAMissingSearchIsReportedAsNotFound() {
        when(repository.findByIdForUpdate(searchId)).thenReturn(Optional.empty());

        assertThrows(SearchNotFoundException.class, () -> service.setPaused(searchId, true));

        verify(repository, never()).updateStatus(any());
    }

    @Test
    void statusUpdateThatChangedNoRowIsReportedAsNotFound() {
        stubLocked(search(SearchStatus.ACTIVE));
        when(repository.updateStatus(any())).thenReturn(false);

        assertThrows(SearchNotFoundException.class, () -> service.setPaused(searchId, true));
    }

    @Test
    void searchIsLockedBeforeItIsDeleted() {
        stubLocked(search(SearchStatus.ACTIVE));
        when(repository.deleteById(searchId)).thenReturn(true);

        service.delete(searchId);

        var order = inOrder(repository);
        order.verify(repository).findByIdForUpdate(searchId);
        order.verify(repository).deleteById(searchId);
    }

    @Test
    void deletingAMissingSearchIsReportedAsNotFound() {
        when(repository.findByIdForUpdate(searchId)).thenReturn(Optional.empty());

        assertThrows(SearchNotFoundException.class, () -> service.delete(searchId));

        verify(repository, never()).deleteById(any());
    }

    @Test
    void searchBeingScannedCannotBeDeleted() {
        stubLocked(search(SearchStatus.SCANNING));

        assertInvalidSearchData(() -> service.delete(searchId));

        verify(repository, never()).deleteById(any());
    }

    @Test
    void deleteThatRemovedNoRowIsReportedAsNotFound() {
        stubLocked(search(SearchStatus.ACTIVE));
        when(repository.deleteById(searchId)).thenReturn(false);

        assertThrows(SearchNotFoundException.class, () -> service.delete(searchId));
    }
}
