package org.hhclicker.backend.resume;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.hhclicker.backend.account.exception.AccountNotFoundException;
import org.hhclicker.backend.resume.exception.InvalidResumeDataException;
import org.hhclicker.backend.resume.exception.ResumeAlreadyExistsException;
import org.hhclicker.backend.resume.model.AiLetterSettings;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.resume.service.ImportResumeItem;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Импорт карточек HH: нормализация, границы, дедупликация и чужие резюме.
 */
class ResumeImportTests extends ResumeServiceTestSupport {

    static Stream<String> malformedHashes() {
        return Stream.of(
                " ",
                "abcdef0123456789abc",              // 19 символов — короче минимума
                "ABCDEF0123456789ABCD",             // верхний регистр не входит в [a-f0-9]
                "ghijklmnopqrstuvwxyz",             // не шестнадцатеричные символы
                "abcdef0123456789abcd-extra",       // посторонние символы
                "a".repeat(101)                     // длиннее 100 символов
        );
    }

    private ImportResumeItem item(String hash, String name, String plainText) {
        return new ImportResumeItem(hash, name, plainText);
    }

    /**
     * Любой запрошенный hash уже лежит в БД и принадлежит текущему аккаунту.
     */
    private void stubStoredForAnyHash() {
        when(repository.findByHhResumeId(anyString())).thenAnswer(invocation -> Optional.of(resume(
                UUID.randomUUID(), accountId, invocation.getArgument(0), "Java-разработчик",
                ResumeAutomationSettings.defaults())));
    }

    @Test
    void importTrimsInputAndStampsTheCandidateWithServerTime() {
        stubAccount("Основной аккаунт");
        stubStatistics();
        HhResume stored = resume();
        when(repository.findByHhResumeId(HASH)).thenReturn(Optional.of(stored));

        var result = service.importResumes(accountId,
                List.of(item("  " + HASH + "  ", "  Java-разработчик  ", "  " + PLAIN_TEXT + "  ")));

        ArgumentCaptor<HhResume> captor = ArgumentCaptor.forClass(HhResume.class);
        verify(repository).insertIfAbsent(captor.capture());
        HhResume candidate = captor.getValue();
        assertNotNull(candidate.id());
        assertEquals(accountId, candidate.accountId());
        assertEquals(HASH, candidate.hhResumeId());
        assertEquals("Java-разработчик", candidate.name());
        assertEquals(PLAIN_TEXT, candidate.plainText());
        assertEquals(ResumeAutomationSettings.defaults(), candidate.settings());
        assertEquals(NOW, candidate.createdAt());
        assertEquals(NOW, candidate.updatedAt());
        assertEquals(NOW, candidate.textLoadedAt());
        assertSame(stored, result.getFirst().resume());
        assertEquals("Основной аккаунт", result.getFirst().accountName());
        assertSame(statistics, result.getFirst().statistics());
    }

    @Test
    void repeatedImportReturnsTheStoredResumeWithoutOverwritingItsSettings() {
        stubAccount("Основной аккаунт");
        stubStatistics();
        var customSettings = settings("Своё письмо", 5, 15, 25, false, new AiLetterSettings(true, AI_TEMPLATE, 900));
        HhResume stored = resume(resumeId, accountId, HASH, "Название из прошлого импорта", customSettings);
        when(repository.findByHhResumeId(HASH)).thenReturn(Optional.of(stored));

        var result = service.importResumes(accountId, List.of(item(HASH, "Новое название", "Новый текст")));

        assertEquals("Название из прошлого импорта", result.getFirst().resume().name());
        assertEquals(customSettings, result.getFirst().resume().settings());
        assertEquals(PLAIN_TEXT, result.getFirst().resume().plainText());
    }

    @Test
    void oneHundredItemsAreAccepted() {
        stubAccount("Основной аккаунт");
        stubStatistics();
        stubStoredForAnyHash();
        var items = new ArrayList<ImportResumeItem>();
        for (int index = 0; index < 100; index++) {
            items.add(item("%020d".formatted(index), "Резюме " + index, PLAIN_TEXT));
        }

        assertEquals(100, service.importResumes(accountId, items).size());
        verify(repository, times(100)).insertIfAbsent(any());
    }

    @Test
    void moreThanOneHundredItemsAreRejected() {
        stubAccount("Основной аккаунт");
        var items = new ArrayList<ImportResumeItem>();
        for (int index = 0; index < 101; index++) {
            items.add(item("%020d".formatted(index), "Резюме " + index, PLAIN_TEXT));
        }

        assertValidationFailure(() -> service.importResumes(accountId, items));
        verify(repository, never()).insertIfAbsent(any());
    }

    @Test
    void nullAndEmptyItemListsAreRejected() {
        stubAccount("Основной аккаунт");

        assertValidationFailure(() -> service.importResumes(accountId, null));
        assertValidationFailure(() -> service.importResumes(accountId, List.of()));
        verify(repository, never()).insertIfAbsent(any());
    }

    @Test
    void unknownAccountIsRejectedBeforeTheItemListIsEvenLookedAt() {
        when(accounts.findById(accountId)).thenThrow(new AccountNotFoundException(accountId));

        assertThrows(AccountNotFoundException.class, () -> service.importResumes(accountId, null));

        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @MethodSource("malformedHashes")
    void malformedResumeHashIsRejected(String hash) {
        stubAccount("Основной аккаунт");

        assertValidationFailure(() -> service.importResumes(accountId, List.of(item(hash, "Java-разработчик", PLAIN_TEXT))));
        verify(repository, never()).insertIfAbsent(any());
    }

    @Test
    void nameBoundariesAreEnforced() {
        stubAccount("Основной аккаунт");
        stubStatistics();
        stubStoredForAnyHash();

        assertValidationFailure(() -> service.importResumes(accountId, List.of(item(HASH, "   ", PLAIN_TEXT))));
        assertValidationFailure(() -> service.importResumes(accountId, List.of(item(HASH, "и".repeat(121), PLAIN_TEXT))));
        assertEquals(1, service.importResumes(accountId, List.of(item(HASH, "и".repeat(120), PLAIN_TEXT))).size());
    }

    @Test
    void plainTextBoundariesAreEnforced() {
        stubAccount("Основной аккаунт");
        stubStatistics();
        stubStoredForAnyHash();

        assertValidationFailure(() -> service.importResumes(accountId, List.of(item(HASH, "Java-разработчик", "   "))));
        assertValidationFailure(() -> service.importResumes(accountId,
                List.of(item(HASH, "Java-разработчик", "и".repeat(100_001)))));
        assertEquals(1, service.importResumes(accountId,
                List.of(item(HASH, "Java-разработчик", "и".repeat(100_000)))).size());
    }

    @Test
    void theSameHashListedTwiceIsImportedOnce() {
        stubAccount("Основной аккаунт");
        stubStatistics();
        when(repository.findByHhResumeId(HASH)).thenReturn(Optional.of(resume()));

        var result = service.importResumes(accountId, List.of(
                item(HASH, "Java-разработчик", PLAIN_TEXT),
                item("  " + HASH + "  ", "Дубль после нормализации", PLAIN_TEXT)));

        assertEquals(1, result.size());
        verify(repository, times(1)).insertIfAbsent(any());
    }

    @Test
    void resumeAlreadyLinkedToAnotherAccountIsRejected() {
        stubAccount("Основной аккаунт");
        UUID otherAccountId = UUID.randomUUID();
        when(repository.findByHhResumeId(HASH)).thenReturn(Optional.of(
                resume(resumeId, otherAccountId, HASH, "Java-разработчик", ResumeAutomationSettings.defaults())));

        assertValidationFailure(() -> service.importResumes(accountId, List.of(item(HASH, "Java-разработчик", PLAIN_TEXT))));
    }

    @Test
    void resumeThatDisappearedRightAfterInsertIsReportedAsAlreadyExisting() {
        stubAccount("Основной аккаунт");
        when(repository.findByHhResumeId(HASH)).thenReturn(Optional.empty());

        var exception = assertThrows(ResumeAlreadyExistsException.class,
                () -> service.importResumes(accountId, List.of(item(HASH, "Java-разработчик", PLAIN_TEXT))));

        assertEquals(ApplicationErrorCategory.CONFLICT, exception.category());
        assertEquals("HH_RESUME_ALREADY_EXISTS", exception.machineCode());
    }

    @Test
    void invalidItemAbortsTheImportAfterEarlierItemsWereAlreadyWritten() {
        stubAccount("Основной аккаунт");
        stubStatistics();
        stubStoredForAnyHash();

        assertValidationFailure(() -> service.importResumes(accountId, List.of(
                item(HASH, "Java-разработчик", PLAIN_TEXT),
                item(OTHER_HASH, "  ", PLAIN_TEXT))));

        // Откат обеспечивает только @Transactional: сервис не проверяет весь список заранее.
        verify(repository, times(1)).insertIfAbsent(any());
    }

    private void assertValidationFailure(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(InvalidResumeDataException.class, executable);
        assertEquals(ApplicationErrorCategory.VALIDATION, exception.category());
        assertEquals("INVALID_RESUME_DATA", exception.machineCode());
    }
}
