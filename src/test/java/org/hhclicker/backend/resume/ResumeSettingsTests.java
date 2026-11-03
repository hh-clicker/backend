package org.hhclicker.backend.resume;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.hhclicker.backend.resume.exception.InvalidResumeDataException;
import org.hhclicker.backend.resume.exception.ResumeNotFoundException;
import org.hhclicker.backend.resume.model.AiLetterSettings;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.service.UpdateResumeSettingsCommand;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Настройки автооткликов: валидация, значения по умолчанию и неизменяемые поля резюме.
 */
class ResumeSettingsTests extends ResumeServiceTestSupport {

    private void stubExistingResume() {
        when(repository.findById(resumeId)).thenReturn(Optional.of(resume()));
    }

    private void stubWritableResume() {
        stubExistingResume();
        when(repository.updateSettings(any())).thenReturn(true);
        stubAccount("Основной аккаунт");
        stubStatistics();
    }

    private UpdateResumeSettingsCommand command(String coverLetter, int dailyLimit, int pauseFrom, int pauseTo,
                                                AiLetterSettings ai) {
        return new UpdateResumeSettingsCommand(coverLetter, dailyLimit, pauseFrom, pauseTo, true, ai);
    }

    private HhResume captureUpdate() {
        ArgumentCaptor<HhResume> captor = ArgumentCaptor.forClass(HhResume.class);
        verify(repository).updateSettings(captor.capture());
        return captor.getValue();
    }

    @Test
    void updateStoresNewSettingsAndKeepsEverythingElseUntouched() {
        stubWritableResume();
        var ai = new AiLetterSettings(true, AI_TEMPLATE, 1200);

        var profile = service.updateSettings(resumeId, command("Новое письмо", 42, 20, 60, ai));

        HhResume written = captureUpdate();
        assertEquals("Новое письмо", written.settings().coverLetter());
        assertEquals(42, written.settings().dailyLimit());
        assertEquals(20, written.settings().pauseFromSeconds());
        assertEquals(60, written.settings().pauseToSeconds());
        assertEquals(ai, written.settings().aiLetter());
        assertEquals(resumeId, written.id());
        assertEquals(accountId, written.accountId());
        assertEquals(HASH, written.hhResumeId());
        assertEquals("Java-разработчик", written.name());
        assertEquals(EARLIER, written.createdAt());
        assertEquals(PLAIN_TEXT, written.plainText());
        assertEquals(EARLIER, written.textLoadedAt());
        assertEquals(NOW, written.updatedAt(), "updated_at должен браться из фиксированных часов сервиса");
        assertSame(written, profile.resume());
    }

    @Test
    void coverLetterIsTrimmedBeforeStoring() {
        stubWritableResume();

        service.updateSettings(resumeId, command("   Письмо с пробелами   ", 20, 30, 90, null));

        assertEquals("Письмо с пробелами", captureUpdate().settings().coverLetter());
    }

    @Test
    void missingAiSettingsFallBackToDefaults() {
        stubWritableResume();

        service.updateSettings(resumeId, command("Письмо", 20, 30, 90, null));

        assertEquals(AiLetterSettings.defaults(), captureUpdate().settings().aiLetter());
    }

    @Test
    void updatedProfileCarriesAccountNameAndStatistics() {
        stubWritableResume();

        var profile = service.updateSettings(resumeId, command("Письмо", 20, 30, 90, null));

        assertEquals("Основной аккаунт", profile.accountName());
        assertSame(statistics, profile.statistics());
    }

    @Test
    void generationTemplateWithoutMarkerIsRejectedBeforeWriting() {
        stubExistingResume();

        var exception = assertThrows(ApplicationException.class, () -> service.updateSettings(resumeId,
                command("Письмо", 20, 30, 90, new AiLetterSettings(true, "Шаблон без маркера", 1500))));

        assertEquals("INVALID_AI_LETTER_SETTINGS", exception.machineCode());
        verify(repository, never()).updateSettings(any());
    }

    @Test
    void coverLetterContainingTheGenerationMarkerIsRejected() {
        stubExistingResume();

        var exception = assertThrows(ApplicationException.class, () -> service.updateSettings(resumeId,
                command("Здравствуйте! #AI_CHANGE#", 20, 30, 90, null)));

        assertEquals("INVALID_AI_LETTER_SETTINGS", exception.machineCode());
        verify(repository, never()).updateSettings(any());
    }

    @Test
    void nullCoverLetterIsRejected() {
        stubExistingResume();

        assertThrows(ApplicationException.class,
                () -> service.updateSettings(resumeId, command(null, 20, 30, 90, null)));
        verify(repository, never()).updateSettings(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 500})
    void dailyLimitBoundariesAreAccepted(int dailyLimit) {
        stubWritableResume();

        service.updateSettings(resumeId, command("Письмо", dailyLimit, 30, 90, null));

        assertEquals(dailyLimit, captureUpdate().settings().dailyLimit());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 501})
    void dailyLimitOutsideTheAllowedRangeIsRejected(int dailyLimit) {
        stubExistingResume();

        assertResumeValidationFailure(() -> service.updateSettings(resumeId, command("Письмо", dailyLimit, 30, 90, null)));
        verify(repository, never()).updateSettings(any());
    }

    @Test
    void minimalPauseOfTenSecondsIsAcceptedAndNineIsNot() {
        stubWritableResume();
        service.updateSettings(resumeId, command("Письмо", 20, 10, 10, null));
        assertEquals(10, captureUpdate().settings().pauseFromSeconds());

        assertResumeValidationFailure(() -> service.updateSettings(resumeId, command("Письмо", 20, 9, 90, null)));
    }

    @Test
    void upperPauseBelowLowerPauseIsRejected() {
        stubExistingResume();

        assertResumeValidationFailure(() -> service.updateSettings(resumeId, command("Письмо", 20, 60, 30, null)));
        verify(repository, never()).updateSettings(any());
    }

    @Test
    void generationSettingsAreValidatedBeforeNumericLimits() {
        stubExistingResume();

        var exception = assertThrows(ApplicationException.class, () -> service.updateSettings(resumeId,
                command("Письмо", 0, 1, 0, new AiLetterSettings(true, "Без маркера", 1500))));

        assertEquals("INVALID_AI_LETTER_SETTINGS", exception.machineCode(),
                "нарушение настроек генерации сообщается раньше нарушения лимитов");
    }

    @Test
    void missingResumeIsReportedBeforeSettingsAreValidated() {
        when(repository.findById(resumeId)).thenReturn(Optional.empty());

        assertThrows(ResumeNotFoundException.class,
                () -> service.updateSettings(resumeId, command("Письмо", 0, 0, 0, null)));
        verify(repository, never()).updateSettings(any());
    }

    @Test
    void resumeRemovedBetweenReadAndWriteIsReportedAsNotFound() {
        stubExistingResume();
        when(repository.updateSettings(any())).thenReturn(false);

        var exception = assertThrows(ResumeNotFoundException.class,
                () -> service.updateSettings(resumeId, command("Письмо", 20, 30, 90, null)));

        assertEquals(ApplicationErrorCategory.NOT_FOUND, exception.category());
        assertEquals("RESUME_NOT_FOUND", exception.machineCode());
    }

    private void assertResumeValidationFailure(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(InvalidResumeDataException.class, executable);
        assertEquals(ApplicationErrorCategory.VALIDATION, exception.category());
        assertEquals("INVALID_RESUME_DATA", exception.machineCode());
    }
}
