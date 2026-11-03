package org.hhclicker.backend.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.hhclicker.backend.resume.exception.ResumeNotFoundException;
import org.hhclicker.backend.search.exception.SearchNotFoundException;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Запуск сканирования: переход в SCANNING и повторная проверка сохранённой ссылки.
 */
class SearchScanTests extends SearchServiceTestSupport {

    @ParameterizedTest
    @EnumSource(value = SearchStatus.class, names = {"ACTIVE", "PAUSED", "ERROR"})
    void scanMovesTheSearchIntoScanningAndReturnsTheNormalizedLink(SearchStatus from) {
        stubResume();
        VacancySearch existing = search(from);
        stubLocked(existing);
        when(repository.updateStatus(any())).thenReturn(true);

        var prepared = service.scan(searchId);

        VacancySearch written = captureStatusUpdate();
        assertEquals(SearchStatus.SCANNING, written.status());
        assertNull(written.lastError());
        assertEquals(LAST_SCANNED_AT, written.lastScannedAt(), "запуск не сбрасывает прошлое успешное сканирование");
        assertEquals(existing.newVacancies(), written.newVacancies());
        assertEquals(existing.totalVacancies(), written.totalVacancies());
        assertEquals(NOW, written.scanStartedAt());
        assertEquals(0, written.scanCurrentPage());
        assertNull(written.scanTotalPages());
        assertEquals(0, written.scanCollected());
        assertNull(written.scanTotalResults());
        assertEquals(NOW, written.updatedAt());
        assertEquals(CANONICAL_URL, prepared.sourceUrl(), "нормализация сохранённой ссылки идемпотентна");
        assertEquals(SearchStatus.SCANNING, prepared.profile().search().status());
        assertEquals(RESUME_NAME, prepared.profile().resumeName());
    }

    @Test
    void storedLinkIsRevalidatedAgainstTheResumeCurrentlySelected() {
        stubResume(resumeId, "ffffffffffffffffffff", RESUME_NAME);
        stubLocked(search(searchId, resumeId, SearchStatus.ACTIVE,
                "https://hh.ru/search/vacancy?resume=" + HH_RESUME_ID, SOURCE_HASH));

        assertInvalidSearchData(() -> service.scan(searchId));

        verify(repository, never()).updateStatus(any());
    }

    @Test
    void linkThatIsNoLongerAcceptableBlocksTheScan() {
        stubResume();
        stubLocked(search(searchId, resumeId, SearchStatus.ACTIVE, "https://hh.ru/vacancies", SOURCE_HASH));

        assertInvalidSearchData(() -> service.scan(searchId));

        verify(repository, never()).updateStatus(any());
    }

    @Test
    void searchAlreadyBeingScannedIsRejected() {
        stubLocked(search(SearchStatus.SCANNING));

        assertInvalidSearchData(() -> service.scan(searchId));

        verify(repository, never()).updateStatus(any());
        verify(resumes, never()).findById(any());
    }

    @Test
    void missingSearchIsReportedAsNotFound() {
        when(repository.findByIdForUpdate(searchId)).thenReturn(Optional.empty());

        assertThrows(SearchNotFoundException.class, () -> service.scan(searchId));
    }

    @Test
    void missingResumeStopsTheScan() {
        stubLocked(search(SearchStatus.ACTIVE));
        when(resumes.findById(resumeId)).thenThrow(new ResumeNotFoundException(resumeId));

        assertThrows(ResumeNotFoundException.class, () -> service.scan(searchId));

        verify(repository, never()).updateStatus(any());
    }

    @Test
    void statusUpdateThatChangedNoRowIsReportedAsNotFound() {
        stubResume();
        stubLocked(search(SearchStatus.ACTIVE));
        when(repository.updateStatus(any())).thenReturn(false);

        assertThrows(SearchNotFoundException.class, () -> service.scan(searchId));
    }
}
