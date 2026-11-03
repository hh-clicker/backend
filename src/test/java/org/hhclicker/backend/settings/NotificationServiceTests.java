package org.hhclicker.backend.settings;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.settings.model.NotificationRun;
import org.hhclicker.backend.settings.model.NotificationSettings;
import org.hhclicker.backend.settings.repository.NotificationRepository;
import org.hhclicker.backend.settings.service.NotificationService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Тонкая service-прослойка уведомлений не изменяет данные и не скрывает ошибки repository.
 */
class NotificationServiceTests {

    private static final UUID RUN_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RESUME_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationService service = new NotificationService(repository);

    @Test
    void settingsReturnsTheExactRepositoryValueAfterOneRead() {
        NotificationSettings settings = new NotificationSettings(true, false, true, false, true, true);
        when(repository.settings()).thenReturn(settings);

        assertSame(settings, service.settings());

        verify(repository).settings();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void settingsPropagatesRepositoryFailure() {
        RuntimeException failure = new RuntimeException("settings storage unavailable");
        when(repository.settings()).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, service::settings));
    }

    @Test
    void runsReturnsTheSameOrderedListWithoutFilteringOrCopying() {
        List<NotificationRun> runs = List.of(
                run(UUID.fromString("30000000-0000-0000-0000-000000000002"), 2),
                run(RUN_ID, 1));
        when(repository.runs()).thenReturn(runs);

        assertSame(runs, service.runs());

        verify(repository).runs();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void runsReturnsTheRepositoryEmptyListInstance() {
        List<NotificationRun> empty = List.of();
        when(repository.runs()).thenReturn(empty);

        assertSame(empty, service.runs());
    }

    @Test
    void runsPropagatesRepositoryFailure() {
        RuntimeException failure = new RuntimeException("run projection unavailable");
        when(repository.runs()).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, service::runs));
    }

    @Test
    void savePropagatesRepositoryFailureWithoutAReadBack() {
        NotificationSettings settings = new NotificationSettings(false, true, false, true, false, true);
        RuntimeException failure = new RuntimeException("settings update failed");
        org.mockito.Mockito.doThrow(failure).when(repository).save(settings);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.save(settings)));

        verify(repository).save(settings);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void directNullSaveIsPassedThroughAndReturnedWithoutServiceValidation() {
        assertNull(service.save(null));

        verify(repository).save(null);
        verifyNoMoreInteractions(repository);
    }

    private NotificationRun run(UUID id, long sequence) {
        return new NotificationRun(id, ACCOUNT_ID, RESUME_ID, "Java", sequence, "COMPLETED", "revision",
                5, 3, 1, 5, 300, 5, List.of("attention"));
    }
}
