package org.hhclicker.backend.application;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.application.model.ApplicationModels.Attention;
import org.hhclicker.backend.application.model.ApplicationModels.Outcome;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.application.telegram.TelegramApplicationAttentionActions;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramApplicationAttentionActionsTests {
    private static final UUID ATTENTION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final ApplicationService applications = mock(ApplicationService.class);
    private final TelegramApplicationAttentionActions actions = new TelegramApplicationAttentionActions(applications);

    @Test
    void resolveUsesTheSameApplicationActionAsWeb() {
        when(applications.attentionTask(ATTENTION_ID)).thenReturn(attention("OPEN", true));

        assertEquals("Ситуация отмечена как решённая.",
                actions.handle("attention:resolve:" + ATTENTION_ID).orElseThrow());

        verify(applications).attention(ATTENTION_ID, "RESOLVED");
    }

    @Test
    void skipIsRejectedWhenVacancyCannotBeSkipped() {
        when(applications.attentionTask(ATTENTION_ID)).thenReturn(attention("OPEN", false));

        assertEquals("Эту вакансию больше нельзя добавить в пропущенные.",
                actions.handle("attention:skip:" + ATTENTION_ID).orElseThrow());

        verify(applications, never()).attention(any(), anyString());
    }

    @Test
    void processedAttentionCannotBeHandledTwice() {
        when(applications.attentionTask(ATTENTION_ID)).thenReturn(attention("RESOLVED", false));

        assertEquals("Эта ситуация уже обработана.",
                actions.handle("attention:resolve:" + ATTENTION_ID).orElseThrow());

        verify(applications, never()).attention(any(), anyString());
    }

    private Attention attention(String status, boolean canSkip) {
        return new Attention(ATTENTION_ID, "ERROR", status, "Проверить", "Описание", UUID.randomUUID(), 7,
                ATTENTION_ID, UUID.randomUUID(), "123", "Java", "employer", "Компания", "Резюме",
                Instant.parse("2026-09-21T09:00:00Z"), Outcome.UNKNOWN, false, canSkip);
    }
}
