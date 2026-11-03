package org.hhclicker.backend.application;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.application.model.ApplicationModels.Run;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.application.service.ApplicationRunGateway;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.application.telegram.TelegramApplicationRunActions;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramApplicationRunActionsTests {
    private static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final ApplicationService applications = mock(ApplicationService.class);
    private final ApplicationRunGateway gateway = mock(ApplicationRunGateway.class);
    private final TelegramApplicationRunActions actions = new TelegramApplicationRunActions(applications, gateway);

    @Test
    void pauseUsesTheSameGatewayAsWebControl() {
        Run run = run(RunStatus.RUNNING);
        when(applications.find(RUN_ID)).thenReturn(run);

        assertEquals("Запуск поставлен на паузу.",
                actions.handle("application:pause:" + RUN_ID).orElseThrow());

        verify(gateway).changeApplicationStatus(RUN_ID, RunStatus.PAUSED_USER);
    }

    @Test
    void resumeUsesTheSameGatewayAsWebControl() {
        Run run = run(RunStatus.PAUSED_USER);
        when(applications.find(RUN_ID)).thenReturn(run);

        assertEquals("Запуск продолжен.",
                actions.handle("application:resume:" + RUN_ID).orElseThrow());

        verify(gateway).changeApplicationStatus(RUN_ID, RunStatus.RUNNING);
    }

    @Test
    void unrelatedCallbackIsIgnored() {
        assertTrue(actions.handle("other:action").isEmpty());
        verifyNoInteractions(applications, gateway);
    }

    private Run run(RunStatus status) {
        Run run = mock(Run.class);
        when(run.status()).thenReturn(status);
        return run;
    }
}
