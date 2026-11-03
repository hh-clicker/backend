package org.hhclicker.backend.application.telegram;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.application.model.ApplicationModels.Attention;
import org.hhclicker.backend.application.model.ApplicationModels.Outcome;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TelegramAttentionPresentationTests {
    private static final UUID ATTENTION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void openQuestionnaireShowsVacancyAndAvailableActionsWithoutAnExternalLink() {
        var presentation = TelegramAttentionPresentation.from(attention("OPEN", "QUESTIONNAIRE", true));

        assertTrue(presentation.text().startsWith("📝"));
        assertTrue(presentation.text().contains("Вакансия: Java-разработчик"));
        assertFalse(presentation.text().contains("https://"));
        assertEquals(2, presentation.buttons().size());
        assertEquals("attention:resolve:" + ATTENTION_ID, presentation.buttons().getFirst().data());
        assertEquals("attention:skip:" + ATTENTION_ID, presentation.buttons().getLast().data());
        assertFalse(presentation.closed());
    }

    @Test
    void resolvedAttentionHasNoButtons() {
        var presentation = TelegramAttentionPresentation.from(attention("RESOLVED", "ERROR", false));

        assertTrue(presentation.text().startsWith("✅"));
        assertTrue(presentation.text().contains("Статус: отмечено как решённое"));
        assertTrue(presentation.buttons().isEmpty());
        assertTrue(presentation.closed());
    }

    private Attention attention(String status, String kind, boolean canSkip) {
        return new Attention(ATTENTION_ID, kind, status, "Заполнить анкету работодателя", "Описание", UUID.randomUUID(),
                7, ATTENTION_ID, UUID.randomUUID(), "123", "Java-разработчик", "employer", "Компания", "Java",
                Instant.parse("2026-09-21T09:00:00Z"), Outcome.QUESTIONNAIRE, false, canSkip);
    }
}
