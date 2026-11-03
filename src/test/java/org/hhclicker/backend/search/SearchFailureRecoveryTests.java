package org.hhclicker.backend.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Ошибка сканирования и восстановление зависших сканирований после перезапуска backend.
 */
class SearchFailureRecoveryTests extends SearchServiceTestSupport {

    private static final String RESTART_MESSAGE = "Backend был перезапущен. Повторите сканирование.";
    private static final String DEFAULT_FAILURE_MESSAGE = "Сканирование прервано. Повторите запуск.";

    private List<VacancySearch> captureAllStatusUpdates(int expectedCount) {
        ArgumentCaptor<VacancySearch> captor = ArgumentCaptor.forClass(VacancySearch.class);
        verify(repository, times(expectedCount)).updateStatus(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void interruptedScanBecomesErrorWithTheReportedMessage() {
        stubLocked(new VacancySearch(searchId, resumeId, "Java в Москве", CANONICAL_URL, SOURCE_HASH, 5,
                SearchStatus.SCANNING, 7, 42, LAST_SCANNED_AT, null,
                CREATED_AT, 2, 4, 200, 360, CREATED_AT, CREATED_AT));

        service.failScan(searchId, "Агент потерял страницу выдачи.");

        VacancySearch written = captureStatusUpdate();
        assertEquals(SearchStatus.ERROR, written.status());
        assertEquals("Агент потерял страницу выдачи.", written.lastError());
        assertEquals(LAST_SCANNED_AT, written.lastScannedAt(), "прошлое успешное сканирование сохраняется");
        assertEquals(NOW, written.updatedAt());
        assertEquals(CREATED_AT, written.createdAt());
        assertEquals(searchId, written.id());
        assertNull(written.scanStartedAt());
        assertNull(written.scanCurrentPage());
        assertNull(written.scanTotalPages());
        assertNull(written.scanCollected());
        assertNull(written.scanTotalResults());
    }

    @Test
    void missingMessageIsReplacedWithTheDefaultOne() {
        stubLocked(search(SearchStatus.SCANNING));

        service.failScan(searchId, null);

        assertEquals(DEFAULT_FAILURE_MESSAGE, captureStatusUpdate().lastError());
    }

    @Test
    void messageOfExactlyOneThousandCharactersIsStoredAsIs() {
        stubLocked(search(SearchStatus.SCANNING));
        String message = "и".repeat(1000);

        service.failScan(searchId, message);

        assertEquals(message, captureStatusUpdate().lastError());
    }

    @Test
    void longerMessageIsTruncatedToOneThousandCharacters() {
        stubLocked(search(SearchStatus.SCANNING));

        service.failScan(searchId, "и".repeat(5000));

        String stored = captureStatusUpdate().lastError();
        assertEquals(1000, stored.length());
        assertTrue(stored.chars().allMatch(character -> character == 'и'));
    }

    @Test
    void failureOfAMissingSearchIsASafeNoOp() {
        when(repository.findByIdForUpdate(searchId)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.failScan(searchId, "Что-то пошло не так."));

        verify(repository, never()).updateStatus(any());
    }

    @ParameterizedTest
    @EnumSource(value = SearchStatus.class, names = {"ACTIVE", "PAUSED", "ERROR"})
    void failureOfASearchThatIsNotScanningIsASafeNoOp(SearchStatus current) {
        stubLocked(search(current));

        assertDoesNotThrow(() -> service.failScan(searchId, "Опоздавшая ошибка агента."));

        verify(repository, never()).updateStatus(any());
    }

    @Test
    void everyInterruptedScanIsMarkedAsFailedAfterRestart() {
        UUID secondId = UUID.randomUUID();
        when(repository.findAll()).thenReturn(List.of(
                search(SearchStatus.SCANNING),
                search(secondId, resumeId, SearchStatus.SCANNING, CANONICAL_URL, "b".repeat(64))));

        service.recoverInterruptedScans();

        List<VacancySearch> written = captureAllStatusUpdates(2);
        assertEquals(List.of(searchId, secondId), written.stream().map(VacancySearch::id).toList());
        written.forEach(search -> {
            assertEquals(SearchStatus.ERROR, search.status());
            assertEquals(RESTART_MESSAGE, search.lastError());
            assertEquals(LAST_SCANNED_AT, search.lastScannedAt());
            assertEquals(NOW, search.updatedAt(), "время берётся из фиксированных часов сервиса");
        });
    }

    @Test
    void searchesInOtherStatusesAreLeftUntouchedAfterRestart() {
        UUID scanningId = UUID.randomUUID();
        when(repository.findAll()).thenReturn(List.of(
                search(SearchStatus.ACTIVE),
                search(scanningId, resumeId, SearchStatus.SCANNING, CANONICAL_URL, "b".repeat(64)),
                search(UUID.randomUUID(), resumeId, SearchStatus.PAUSED, CANONICAL_URL, "c".repeat(64)),
                search(UUID.randomUUID(), resumeId, SearchStatus.ERROR, CANONICAL_URL, "d".repeat(64))));

        service.recoverInterruptedScans();

        List<VacancySearch> written = captureAllStatusUpdates(1);
        assertEquals(scanningId, written.getFirst().id());
    }

    @Test
    void restartWithoutAnySavedSearchDoesNothing() {
        when(repository.findAll()).thenReturn(List.of());

        assertDoesNotThrow(() -> service.recoverInterruptedScans());

        verify(repository, never()).updateStatus(any());
    }
}
