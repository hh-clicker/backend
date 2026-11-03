package org.hhclicker.backend.settings;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.hhclicker.backend.settings.controller.SettingsController;
import org.hhclicker.backend.settings.model.NotificationSettings;
import org.hhclicker.backend.settings.repository.NotificationRepository;
import org.hhclicker.backend.settings.service.NotificationService;
import org.hhclicker.backend.vacancy.relevance.VacancyRelevanceService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationSettingsTests {
    @Test
    void savesAllFivePreferencesWithoutChangingQueueState() {
        var repository = mock(NotificationRepository.class);
        var service = new NotificationService(repository);
        var settings = new NotificationSettings(false, true, false, true, false, true);
        assertSame(settings, service.save(settings));
        verify(repository).save(settings);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void missingBooleansAndMissingNotificationObjectAreRejected() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator.validate(new SettingsController.Settings(null)).isEmpty());
            assertFalse(validator.validate(new SettingsController.Settings(
                    new NotificationSettings(true, null, true, true, true, true))).isEmpty());
            assertFalse(validator.validate(new SettingsController.Settings(
                    new NotificationSettings(true, true, true, true, true, true, null))).isEmpty());
            assertTrue(validator.validate(new SettingsController.Settings(
                    new NotificationSettings(false, false, false, false, false, false))).isEmpty());
        }
    }

    @Test
    void apiResponseMatchesFrontendSettingsEnvelope() {
        var service = mock(NotificationService.class);
        var settings = new NotificationSettings(true, false, true, false, true, true);
        when(service.settings()).thenReturn(settings);
        assertEquals(settings, new SettingsController(service, mock(VacancyRelevanceService.class),
                mock(org.hhclicker.backend.market.MarketAnalysisService.class),
                mock(org.hhclicker.backend.vacancy.relevance.ParallelVacancyRelevanceService.class)).get().settings().notifications());
    }
}
