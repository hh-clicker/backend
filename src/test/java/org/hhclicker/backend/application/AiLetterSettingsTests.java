package org.hhclicker.backend.application;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.resume.model.AiLetterSettings;

import static org.junit.jupiter.api.Assertions.*;

class AiLetterSettingsTests {
    @Test
    void defaultIsOptInAndTemplateMustContainExactlyOneMarker() {
        assertFalse(AiLetterSettings.defaults().enabled());
        assertDoesNotThrow(() -> new AiLetterSettings(true, AiLetterSettings.DEFAULT_TEMPLATE, 1500).validate());
        for (String value : new String[]{"", "Hello", "#AI_CHANGE##AI_CHANGE#"})
            assertThrows(RuntimeException.class, () -> new AiLetterSettings(true, value, 1500).validate());
    }

    @Test
    void fullLetterBudgetReservesRoomForGeneration() {
        assertDoesNotThrow(() -> new AiLetterSettings(true, "#AI_CHANGE#", 200).validate());
        assertThrows(RuntimeException.class, () -> new AiLetterSettings(true, "prefix #AI_CHANGE#", 200).validate());
        assertThrows(RuntimeException.class, () -> new AiLetterSettings(true, "#AI_CHANGE#", 5001).validate());
    }

    @Test
    void fallbackIsIndependentAndCannotContainUnexpandedTemplate() {
        assertDoesNotThrow(() -> AiLetterSettings.validateFallback(""));
        assertDoesNotThrow(() -> AiLetterSettings.validateFallback("Обычное письмо"));
        assertThrows(RuntimeException.class, () -> AiLetterSettings.validateFallback("Привет! #AI_CHANGE#"));
    }
}
