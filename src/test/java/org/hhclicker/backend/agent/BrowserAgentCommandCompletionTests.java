package org.hhclicker.backend.agent;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.agent.exception.BrowserAgentCommandNotFoundException;
import org.hhclicker.backend.agent.model.BrowserAgentCommand;
import org.hhclicker.backend.agent.model.BrowserAgentCommandResultStatus;
import org.hhclicker.backend.agent.model.BrowserAgentCommandStatus;
import org.hhclicker.backend.application.model.ApplicationModels.Outcome;
import org.hhclicker.backend.application.model.ApplicationModels.QueueBlocker;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Завершение команды: владение, конечные состояния и проверка допустимости результата.
 */
class BrowserAgentCommandCompletionTests extends BrowserAgentTestSupport {

    private BrowserAgentCommand claimedOpenBrowser() {
        service.open(profile);
        return service.claimNext(AGENT_ID).orElseThrow();
    }

    private BrowserAgentCommand claimedOpenUrl() {
        service.openUrl(profile, "https://hh.ru/applicant/resumes");
        return service.claimNext(AGENT_ID).orElseThrow();
    }

    private BrowserAgentCommand claimedResumeDiscovery() {
        service.discoverResumes(profile);
        return service.claimNext(AGENT_ID).orElseThrow();
    }

    private BrowserAgentCommand claimedResumeTextRead() {
        service.readResumeText(profile, HH_RESUME_ID);
        return service.claimNext(AGENT_ID).orElseThrow();
    }

    private BrowserAgentCommand claimedApplyVacancy() {
        when(applications.claim(profileId)).thenReturn(Optional.of(task()));
        return service.claimNext(AGENT_ID).orElseThrow();
    }

    @Test
    void resultOfTheOwningAgentMovesTheCommandIntoItsFinalState() {
        BrowserAgentCommand claimed = claimedOpenBrowser();
        clock.advanceSeconds(3);

        BrowserAgentCommand completed = service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(), null, null, null, null, null);

        assertEquals(BrowserAgentCommandStatus.SUCCEEDED, completed.status());
        assertEquals(START.plusSeconds(3), completed.completedAt());
        assertEquals(claimed.startedAt(), completed.startedAt());
        assertEquals(AGENT_ID, completed.agentId());
        assertNull(completed.errorMessage());
        assertEquals(authorized(), completed.authorization());
        assertEquals(completed.status(), service.findById(claimed.id()).status());
    }

    @Test
    void resultOfAnotherAgentIsRefusedAndLeavesTheCommandRunning() {
        BrowserAgentCommand claimed = claimedOpenUrl();
        clearInvocations(statusService, authorizationService, searches, profiles, applications);

        assertConflict(() -> service.complete(claimed.id(), OTHER_AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, null, null, null, null, null, null));

        assertEquals(BrowserAgentCommandStatus.IN_PROGRESS, service.findById(claimed.id()).status());
        verifyNoInteractions(statusService, authorizationService, searches, profiles, applications);
    }

    @Test
    void unknownCommandIsReportedAsNotFound() {
        var exception = assertThrows(BrowserAgentCommandNotFoundException.class,
                () -> service.complete(UUID.randomUUID(), AGENT_ID,
                        BrowserAgentCommandResultStatus.SUCCEEDED, null, null, null, null, null, null, null));

        assertEquals(ApplicationErrorCategory.NOT_FOUND, exception.category());
        assertEquals("BROWSER_AGENT_COMMAND_NOT_FOUND", exception.machineCode());
        assertThrows(BrowserAgentCommandNotFoundException.class, () -> service.findById(UUID.randomUUID()));
    }

    @Test
    void resultOfACommandThatWasNeverHandedOutIsRefused() {
        UUID queuedId = service.open(profile);

        assertConflict(() -> service.complete(queuedId, AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(), null, null, null, null, null));

        assertEquals(BrowserAgentCommandStatus.QUEUED, service.findById(queuedId).status());
    }

    @Test
    void redeliveredResultOfTheSameAgentIsIdempotent() {
        BrowserAgentCommand claimed = claimedApplyVacancy();

        BrowserAgentCommand first = service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, null, null, null, Outcome.SENT, null, null);
        BrowserAgentCommand second = service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, null, null, null, Outcome.SENT, null, null);

        assertSame(first, second, "повторная доставка возвращает уже сохранённый результат");
        verify(applications, times(1)).complete(any(), any(), any(), any());
    }

    @Test
    void redeliveryFromAnotherAgentIsStillRefusedAfterTheCommandIsFinished() {
        BrowserAgentCommand claimed = claimedOpenUrl();
        service.complete(claimed.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null);

        assertConflict(() -> service.complete(claimed.id(), OTHER_AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, null, null, null, null, null, null));
    }

    @Test
    void failureKeepsTheReportedReason() {
        BrowserAgentCommand claimed = claimedOpenUrl();

        BrowserAgentCommand completed = service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.FAILED, "  Chrome не запустился  ",
                null, null, null, null, null, null);

        assertEquals(BrowserAgentCommandStatus.FAILED, completed.status());
        assertEquals("Chrome не запустился", completed.errorMessage());
    }

    @Test
    void failureWithoutAReasonGetsADefaultMessage() {
        BrowserAgentCommand claimed = claimedOpenUrl();

        assertEquals("Browser Agent не сообщил причину ошибки.", service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.FAILED, "   ", null, null, null, null, null, null).errorMessage());
    }

    @Test
    void successNeverCarriesAnErrorMessage() {
        BrowserAgentCommand claimed = claimedOpenUrl();

        assertNull(service.complete(claimed.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                "Ошибка, которой не было", null, null, null, null, null, null).errorMessage());
    }

    @Test
    void sessionCommandMustReportTheAuthorizationResult() {
        BrowserAgentCommand claimed = claimedOpenBrowser();

        assertInvalidAgentData(() -> service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, null, null, null, null, null, null));
        assertInvalidAgentData(() -> service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorization(null, null),
                null, null, null, null, null));
    }

    @Test
    void authorizationResultIsRefusedForCommandsThatDoNotCheckTheSession() {
        BrowserAgentCommand claimed = claimedOpenUrl();

        assertInvalidAgentData(() -> service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(), null, null, null, null, null));
    }

    /**
     * Фиксирует фактический контракт: неуспешная команда, проверяющая сессию, не имеет права
     * приложить результат авторизации — он отвергается как недопустимый для этой команды.
     */
    @Test
    void authorizationAttachedToAFailedSessionCommandIsCurrentlyRefused() {
        BrowserAgentCommand claimed = claimedOpenBrowser();

        assertInvalidAgentData(() -> service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.FAILED, "HH попросил вход",
                authorization(AuthorizationStatus.REQUIRED, "Нужен вход"), null, null, null, null, null));

        assertEquals(BrowserAgentCommandStatus.IN_PROGRESS, service.findById(claimed.id()).status(),
                "отклонённый результат не завершает команду");
    }

    @Test
    void resumeListIsRefusedForEveryCommandButConfirmedResumeDiscovery() {
        BrowserAgentCommand url = claimedOpenUrl();
        assertInvalidAgentData(() -> service.complete(url.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, null,
                List.of(resume(HH_RESUME_ID, "Текст")), null, null, null, null));
    }

    @Test
    void resumeListIsRefusedWithoutAConfirmedLogin() {
        BrowserAgentCommand discovery = claimedResumeDiscovery();

        assertInvalidAgentData(() -> service.complete(discovery.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null,
                authorization(AuthorizationStatus.REQUIRED, null),
                List.of(resume(HH_RESUME_ID, null)), null, null, null, null));
    }

    @Test
    void confirmedResumeDiscoveryWithoutAListIsRefused() {
        BrowserAgentCommand discovery = claimedResumeDiscovery();

        assertInvalidAgentData(() -> service.complete(discovery.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(), null, null, null, null, null));
    }

    @Test
    void resumeTextCommandRequiresExactlyTheRequestedResumeWithItsText() {
        BrowserAgentCommand read = claimedResumeTextRead();

        assertInvalidAgentData(() -> service.complete(read.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(),
                List.of(resume(HH_RESUME_ID, null)), null, null, null, null));
        assertInvalidAgentData(() -> service.complete(read.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(),
                List.of(resume(HH_RESUME_ID, "   ")), null, null, null, null));
        assertInvalidAgentData(() -> service.complete(read.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(),
                List.of(resume("0123456789abcdef0123", "Текст")), null, null, null, null));
        assertInvalidAgentData(() -> service.complete(read.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(),
                List.of(resume(HH_RESUME_ID, "Текст"), resume("0123456789abcdef0123", "Текст")),
                null, null, null, null));

        assertEquals(BrowserAgentCommandStatus.SUCCEEDED, service.complete(read.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(),
                List.of(resume(HH_RESUME_ID, "Текст резюме")), null, null, null, null).status());
    }

    @Test
    void vacancyListIsRefusedForCommandsThatAreNotAScan() {
        BrowserAgentCommand url = claimedOpenUrl();

        assertInvalidAgentData(() -> service.complete(url.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, null, null, List.of(), null, null, null));
    }

    @Test
    void applicationResultFieldsAreRefusedForCommandsThatAreNotAnApplication() {
        BrowserAgentCommand url = claimedOpenUrl();

        assertInvalidAgentData(() -> service.complete(url.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, null, null, null, Outcome.SENT, null, null));
        assertInvalidAgentData(() -> service.complete(url.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, null, null, null, null,
                QueueBlocker.CAPTCHA, null));
        assertInvalidAgentData(() -> service.complete(url.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, null, null, null, null, null,
                "Описание вакансии"));

        verify(applications, never()).complete(any(), any(), any(), anyString());
    }
}
