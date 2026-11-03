package org.hhclicker.backend.vacancy;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.vacancy.catalog.exception.VacancyNotFoundException;
import org.hhclicker.backend.vacancy.catalog.model.VacancyText;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;
import org.hhclicker.backend.vacancy.catalog.service.VacancyCatalogService;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class VacancyTextTests {
    private final VacancyRepository repository = mock(VacancyRepository.class);
    private final VacancyCatalogService service = new VacancyCatalogService(repository);
    private final UUID id = UUID.randomUUID();

    @Test
    void missingAndEmptyTextRemainDistinct() {
        var notLoaded = new VacancyText(null, null);
        when(repository.findText(id)).thenReturn(Optional.of(notLoaded));
        assertEquals(notLoaded, service.findText(id));
        var empty = new VacancyText("", Instant.now());
        when(repository.findText(id)).thenReturn(Optional.of(empty));
        assertEquals(empty, service.findText(id));
        service.saveText(id, "");
        verify(repository).saveText(id, "");
    }

    @Test
    void snapshotPreservesParagraphsAndDoesNotChangeVacancyState() {
        service.saveText(id, "Tasks\n\n- Build services\n- Review code");
        verify(repository).saveText(id, "Tasks\n\n- Build services\n- Review code");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void invalidOptionalTextDoesNotOverwriteOrThrow() {
        service.saveText(id, null);
        service.saveText(id, "x".repeat(100001));
        service.saveText(id, "invalid\0text");
        verifyNoInteractions(repository);
    }

    @Test
    void deletedVacancyIsNotReportedAsAnEmptyDescription() {
        when(repository.findText(id)).thenReturn(Optional.empty());
        assertThrows(VacancyNotFoundException.class, () -> service.findText(id));
    }
}
