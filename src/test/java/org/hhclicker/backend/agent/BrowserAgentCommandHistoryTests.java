package org.hhclicker.backend.agent;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.agent.exception.BrowserAgentCommandNotFoundException;
import org.hhclicker.backend.agent.model.BrowserAgentCommand;
import org.hhclicker.backend.agent.model.BrowserAgentCommandResultStatus;
import org.hhclicker.backend.agent.model.BrowserAgentCommandStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Ограниченная in-memory история удаляет только самые старые завершённые команды.
 */
class BrowserAgentCommandHistoryTests extends BrowserAgentTestSupport {

    @Test
    void pruningRemovesTheOldestFinishedCommandButKeepsQueuedWork() {
        List<UUID> finished = new ArrayList<>();
        for (int index = 0; index < 199; index++) {
            finished.add(completeUrl(index).id());
        }
        service.openUrl(profile, url(199));
        BrowserAgentCommand inProgress = service.claimNext(AGENT_ID).orElseThrow();
        UUID queued = service.openUrl(profile, url(200));

        service.complete(inProgress.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null);

        assertThrows(BrowserAgentCommandNotFoundException.class, () -> service.findById(finished.getFirst()));
        assertEquals(BrowserAgentCommandStatus.SUCCEEDED, service.findById(finished.get(1)).status());
        assertEquals(BrowserAgentCommandStatus.QUEUED, service.findById(queued).status(),
                "активная команда не должна освобождать место в истории");
    }

    @Test
    void historyAboveTheLimitRemainsIntactWhenEveryCommandIsActive() {
        List<UUID> queued = new ArrayList<>();
        for (int index = 0; index < 201; index++) {
            queued.add(service.openUrl(profile, url(index)));
        }
        BrowserAgentCommand inProgress = service.claimNext(AGENT_ID).orElseThrow();

        assertEquals(queued.getFirst(), inProgress.id());
        assertEquals(BrowserAgentCommandStatus.IN_PROGRESS, service.findById(queued.getFirst()).status());
        assertEquals(BrowserAgentCommandStatus.QUEUED, service.findById(queued.getLast()).status());
    }

    private BrowserAgentCommand completeUrl(int index) {
        service.openUrl(profile, url(index));
        BrowserAgentCommand claimed = service.claimNext(AGENT_ID).orElseThrow();
        return service.complete(claimed.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null);
    }

    private String url(int index) {
        return "https://hh.ru/test-command/" + index;
    }
}
