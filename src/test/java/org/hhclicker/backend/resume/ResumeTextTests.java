package org.hhclicker.backend.resume;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.account.service.AccountService;
import org.hhclicker.backend.resume.dto.ImportResumesRequest;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.resume.model.ResumeStatistics;
import org.hhclicker.backend.resume.repository.ResumeRepository;
import org.hhclicker.backend.resume.service.ImportResumeItem;
import org.hhclicker.backend.resume.service.ResumeService;
import org.hhclicker.backend.resume.service.ResumeStatisticsProvider;
import org.hhclicker.backend.resume.service.UpdateResumeSettingsCommand;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

class ResumeTextTests {
    private final ResumeRepository repository = mock(ResumeRepository.class);
    private final AccountService accounts = mock(AccountService.class);
    private final ResumeStatisticsProvider statistics = mock(ResumeStatisticsProvider.class);
    private final Instant now = Instant.parse("2026-09-03T10:00:00Z");
    private final ResumeService service = new ResumeService(repository, accounts, statistics, Clock.fixed(now, ZoneOffset.UTC));
    private final UUID accountId = UUID.randomUUID();
    private final String hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private HhResume resume() {
        when(accounts.findById(accountId)).thenReturn(new HhAccount(accountId, "Test", null, AuthorizationStatus.AUTHORIZED, now, now));
        when(statistics.get(any())).thenReturn(ResumeStatistics.empty());
        return new HhResume(UUID.randomUUID(), accountId, hash, "Developer", ResumeAutomationSettings.defaults(),
                now, now, "Developer\n\nExperience\nBuilding services", now);
    }

    @Test
    void importPersistsTextAndServerTimestamp() {
        var stored = resume();
        when(repository.findByHhResumeId(hash)).thenReturn(Optional.of(stored));
        var result = service.importResumes(accountId, List.of(new ImportResumeItem(hash, "Developer", stored.plainText())));
        verify(repository).insertIfAbsent(argThat(candidate -> candidate.plainText().equals(stored.plainText()) && candidate.textLoadedAt().equals(now)));
        assertEquals(stored.plainText(), result.getFirst().resume().plainText());
    }

    @Test
    void changingAutomationSettingsPreservesTextSnapshot() {
        var stored = resume();
        when(repository.findById(stored.id())).thenReturn(Optional.of(stored));
        when(repository.updateSettings(any())).thenReturn(true);
        var updated = service.updateSettings(stored.id(), new UpdateResumeSettingsCommand("Letter", 20, 30, 60, true));
        assertEquals(stored.plainText(), updated.resume().plainText());
        assertEquals(stored.textLoadedAt(), updated.resume().textLoadedAt());
    }

    @Test
    void missingTextIsRejectedAtImportBoundary() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new ImportResumesRequest(accountId, List.of(new ImportResumesRequest.Item(hash, "Developer", " ")));
            assertFalse(factory.getValidator().validate(request).isEmpty());
        }
    }
}
