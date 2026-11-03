package org.hhclicker.backend.resume;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.resume.model.AiLetterSettings;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Границы моделей писем и настроек автооткликов.
 * <p>
 * Базовые случаи (один обязательный маркер, минимальный бюджет генерации, маркер в обычном письме)
 * уже проверяет {@code AiLetterSettingsTests}; здесь только не покрытые им границы и нормализация.
 */
class ResumeLetterSettingsModelTests {

    private static final String MARKER = AiLetterSettings.MARKER;
    private static final String NUL = String.valueOf('\0');

    @Test
    void nullTemplateIsNormalizedToTheDefaultOne() {
        assertEquals(AiLetterSettings.DEFAULT_TEMPLATE, new AiLetterSettings(false, null, 1500).template());
        assertEquals(AiLetterSettings.DEFAULT_TEMPLATE, new AiLetterSettings(true, null, 1500).template());
        assertDoesNotThrow(() -> new AiLetterSettings(true, null, 1500).validate(),
                "подставленный шаблон по умолчанию обязан проходить валидацию");
    }

    @Test
    void defaultSettingsAreDisabledWithTheStandardTemplateAndBudget() {
        AiLetterSettings defaults = AiLetterSettings.defaults();

        assertFalse(defaults.enabled());
        assertEquals(AiLetterSettings.DEFAULT_TEMPLATE, defaults.template());
        assertEquals(1500, defaults.maxLength());
        assertTrue(AiLetterSettings.DEFAULT_TEMPLATE.contains(MARKER));
        assertEquals(AiLetterSettings.DEFAULT_TEMPLATE.indexOf(MARKER),
                AiLetterSettings.DEFAULT_TEMPLATE.lastIndexOf(MARKER),
                "шаблон по умолчанию должен содержать ровно один маркер");
    }

    @Test
    void disabledGenerationAcceptsATemplateWithoutMarker() {
        assertDoesNotThrow(() -> new AiLetterSettings(false, "Совершенно обычный текст", 1500).validate());
        assertDoesNotThrow(() -> new AiLetterSettings(false, "", 1500).validate());
        assertDoesNotThrow(() -> new AiLetterSettings(false, MARKER + MARKER, 1500).validate());
    }

    @Test
    void letterBudgetBoundsAreInclusive() {
        assertThrows(RuntimeException.class, () -> new AiLetterSettings(false, "Текст", 199).validate());
        assertDoesNotThrow(() -> new AiLetterSettings(false, "Текст", 200).validate());
        assertDoesNotThrow(() -> new AiLetterSettings(false, "Текст", 5000).validate());
        assertThrows(RuntimeException.class, () -> new AiLetterSettings(false, "Текст", 5001).validate());
    }

    @Test
    void templateLengthBoundIsInclusive() {
        assertDoesNotThrow(() -> new AiLetterSettings(false, "и".repeat(5000), 1500).validate());
        assertThrows(RuntimeException.class, () -> new AiLetterSettings(false, "и".repeat(5001), 1500).validate());
    }

    @Test
    void nulCharacterInTemplateIsRejected() {
        assertThrows(RuntimeException.class, () -> new AiLetterSettings(false, "Текст" + NUL + "хвост", 1500).validate());
    }

    @Test
    void roomLeftForGenerationIsMeasuredAgainstTheTemplateWithoutMarker() {
        String template = "и".repeat(300) + MARKER;

        assertDoesNotThrow(() -> new AiLetterSettings(true, template, 500).validate(),
                "ровно 200 символов под генерацию — допустимый минимум");
        assertThrows(RuntimeException.class, () -> new AiLetterSettings(true, template, 499).validate());
    }

    @Test
    void fallbackLetterMustBePresentAndBounded() {
        assertThrows(RuntimeException.class, () -> AiLetterSettings.validateFallback(null));
        assertDoesNotThrow(() -> AiLetterSettings.validateFallback("и".repeat(10_000)));
        assertThrows(RuntimeException.class, () -> AiLetterSettings.validateFallback("и".repeat(10_001)));
        assertThrows(RuntimeException.class, () -> AiLetterSettings.validateFallback("Письмо" + NUL + "хвост"));
    }

    @Test
    void automationDefaultsMatchTheDocumentedStartingPoint() {
        ResumeAutomationSettings defaults = ResumeAutomationSettings.defaults();

        assertEquals("", defaults.coverLetter());
        assertEquals(20, defaults.dailyLimit());
        assertEquals(30, defaults.pauseFromSeconds());
        assertEquals(90, defaults.pauseToSeconds());
        assertTrue(defaults.skipQuestionnaires());
        assertEquals(AiLetterSettings.defaults(), defaults.aiLetter());
    }

    @Test
    void automationSettingsWithoutGenerationBlockFallBackToDefaults() {
        assertEquals(AiLetterSettings.defaults(),
                new ResumeAutomationSettings("Письмо", 10, 15, 25, false).aiLetter());
        assertEquals(AiLetterSettings.defaults(),
                new ResumeAutomationSettings("Письмо", 10, 15, 25, false, null).aiLetter());
    }

    @Test
    void explicitGenerationBlockIsPreserved() {
        var ai = new AiLetterSettings(true, "Привет!\n" + MARKER + "\nПока!", 900);

        assertEquals(ai, new ResumeAutomationSettings("Письмо", 10, 15, 25, false, ai).aiLetter());
    }
}
