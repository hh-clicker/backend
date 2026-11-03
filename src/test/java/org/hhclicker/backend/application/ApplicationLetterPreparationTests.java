package org.hhclicker.backend.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.hhclicker.backend.application.model.ApplicationModels.Item;
import org.hhclicker.backend.application.model.ApplicationModels.PreparedLetter;
import org.hhclicker.backend.application.model.ApplicationModels.Run;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.resume.model.AiLetterSettings;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Подготовка сопроводительного письма: STATIC, FALLBACK и GENERATED против снимка запуска.
 */
class ApplicationLetterPreparationTests extends ApplicationServiceTestSupport {

    private static final String PREFIX = "Здравствуйте!\n";
    private static final String SUFFIX = "\nСпасибо!";
    private static final String GENERATED_TEXT = PREFIX + "Разрабатываю backend на Java." + SUFFIX;

    private void context(AiLetterSettings ai, String coverLetter, PreparedLetter stored, Instant submission) {
        Item item = item("IN_PROGRESS", null, submission, stored, "OPEN");
        Run run = run(RunStatus.RUNNING, List.of(item), ai, coverLetter, 20, 20);
        stub(item, run);
        stubVacancy(VacancyStatus.NEW, true);
    }

    private AiLetterSettings ai(int maxLength) {
        return new AiLetterSettings(true, AI_TEMPLATE, maxLength);
    }

    @Test
    void rateLimitRejectsSilentFallbackAndNeverStoresALetter() {
        context(ai(1500), "Обычное письмо", null, null);
        var generationId = UUID.randomUUID();
        assertFalse(service.prepareLetter(itemId, new PreparedLetter("Обычное письмо", "FALLBACK", "RATE_LIMIT", generationId, 1789829961L)));
        verify(repository).pauseAiLimit(itemId, runId, 1789829961L);
        verify(repository, never()).prepareLetter(any(), any());
        verify(repository, never()).permit(any());
    }

    @Test
    void agentCannotInventFallbackConsent() {
        context(ai(1500), "Обычное письмо", null, null);
        assertThrows(ApplicationException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter("Обычное письмо", "FALLBACK", "RATE_LIMIT_CONFIRMED", null)));
        verify(repository, never()).prepareLetter(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\n\t"})
    void emptyFallbackCannotBeUsedEvenIfAgentRequestsIt(String text) {
        context(ai(1500), text, null, null);
        assertThrows(ApplicationException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter(text, "FALLBACK", "NETWORK_ERROR", null)));
        verify(repository, never()).prepareLetter(any(), any());
    }

    @Test
    void staticLetterIsStoredWhenGenerationIsDisabled() {
        context(AiLetterSettings.defaults(), "Обычное письмо", null, null);
        var letter = new PreparedLetter("Обычное письмо", "STATIC", null, null);

        assertTrue(service.prepareLetter(itemId, letter));

        verify(repository).prepareLetter(itemId, letter);
    }

    @Test
    void staticLetterIsRejectedWhenGenerationIsEnabled() {
        context(ai(1500), "Обычное письмо", null, null);

        assertThrows(ApplicationException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter("Обычное письмо", "STATIC", null, null)));
        verify(repository, never()).prepareLetter(any(), any());
    }

    @Test
    void fallbackWithoutTechnicalReasonIsRejected() {
        context(ai(1500), "Обычное письмо", null, null);

        assertThrows(ApplicationException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter("Обычное письмо", "FALLBACK", null, null)));
    }

    @Test
    void fallbackWithMalformedTechnicalReasonIsRejected() {
        context(ai(1500), "Обычное письмо", null, null);

        assertThrows(ApplicationException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter("Обычное письмо", "FALLBACK", "timeout", null)));
    }

    @Test
    void fallbackTextMustEqualTheRunSnapshotLetter() {
        context(ai(1500), "Обычное письмо", null, null);

        assertThrows(ApplicationException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter("Другое письмо", "FALLBACK", "GENERATION_TIMEOUT", null)));
        verify(repository, never()).prepareLetter(any(), any());
    }

    @Test
    void fallbackMatchingTheRunSnapshotIsStored() {
        context(ai(1500), "Обычное письмо", null, null);
        var letter = new PreparedLetter("Обычное письмо", "FALLBACK", "GENERATION_TIMEOUT", null);

        assertTrue(service.prepareLetter(itemId, letter));

        verify(repository).prepareLetter(itemId, letter);
    }

    @Test
    void generatedLetterIsRejectedWhenGenerationIsDisabledForTheRun() {
        context(AiLetterSettings.defaults(), "Обычное письмо", null, null);

        assertThrows(ApplicationException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter(GENERATED_TEXT, "GENERATED", null, UUID.randomUUID())));
    }

    @Test
    void generatedLetterWithoutGenerationIdIsRejected() {
        context(ai(1500), "Обычное письмо", null, null);

        assertThrows(ApplicationException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter(GENERATED_TEXT, "GENERATED", null, null)));
    }

    @Test
    void generatedLetterCarryingAFallbackReasonIsRejected() {
        context(ai(1500), "Обычное письмо", null, null);

        assertThrows(ApplicationException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter(GENERATED_TEXT, "GENERATED", "GENERATION_TIMEOUT", UUID.randomUUID())));
    }

    @Test
    void generatedLetterLongerThanTheRunLimitIsRejected() {
        context(ai(40), "Обычное письмо", null, null);

        assertThrows(ApplicationException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter(PREFIX + "и".repeat(100) + SUFFIX, "GENERATED", null, UUID.randomUUID())));
    }

    @Test
    void generatedLetterWithEmptyGeneratedPartIsRejected() {
        context(ai(1500), "Обычное письмо", null, null);

        assertThrows(ApplicationException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter(PREFIX + "   " + SUFFIX, "GENERATED", null, UUID.randomUUID())));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Разрабатываю backend на Java.\nСпасибо!",
            "Здравствуйте!\nРазрабатываю backend на Java."
    })
    void generatedLetterMustPreserveBothTemplatePrefixAndSuffix(String generatedText) {
        context(ai(1500), "Обычное письмо", null, null);

        assertThrows(ApplicationException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter(generatedText, "GENERATED", null, UUID.randomUUID())));

        verify(repository, never()).prepareLetter(any(), any());
    }

    @Test
    void generatedLetterMatchingTheTemplateIsStored() {
        context(ai(1500), "Обычное письмо", null, null);
        var letter = new PreparedLetter(GENERATED_TEXT, "GENERATED", null, UUID.randomUUID());

        assertTrue(service.prepareLetter(itemId, letter));

        verify(repository).prepareLetter(itemId, letter);
    }

    @Test
    void letterContainingTheGenerationMarkerIsRejected() {
        context(AiLetterSettings.defaults(), "Обычное письмо", null, null);

        assertThrows(ApplicationException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter("Обычное письмо #AI_CHANGE#", "STATIC", null, null)));
    }

    @Test
    void unknownLetterSourceIsRejected() {
        context(AiLetterSettings.defaults(), "Обычное письмо", null, null);

        assertThrows(ApplicationException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter("Обычное письмо", "MANUAL", null, null)));
    }

    @Test
    void missingLetterSourceIsRejected() {
        context(AiLetterSettings.defaults(), "Обычное письмо", null, null);

        assertThrows(ApplicationException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter("Обычное письмо", null, null, null)));
    }

    @Test
    void repeatedIdenticalLetterIsAcceptedWithoutASecondWrite() {
        var stored = new PreparedLetter("Обычное письмо", "STATIC", null, null);
        context(AiLetterSettings.defaults(), "Обычное письмо", stored, null);

        assertTrue(service.prepareLetter(itemId, new PreparedLetter("Обычное письмо", "STATIC", null, null)));

        verify(repository, never()).prepareLetter(any(), any());
    }

    @Test
    void anotherGeneratedLetterCannotOverwriteTheStoredOne() {
        var stored = new PreparedLetter(GENERATED_TEXT, "GENERATED", null, UUID.randomUUID());
        context(ai(1500), "Обычное письмо", stored, null);

        assertFalse(service.prepareLetter(itemId,
                new PreparedLetter(GENERATED_TEXT, "GENERATED", null, UUID.randomUUID())));

        verify(repository, never()).prepareLetter(any(), any());
    }

    @Test
    void letterCannotBeStoredAfterTheSubmissionPermitWasIssued() {
        context(AiLetterSettings.defaults(), "Обычное письмо", null, NOW);

        assertFalse(service.prepareLetter(itemId, new PreparedLetter("Обычное письмо", "STATIC", null, null)));

        verify(repository, never()).prepareLetter(any(), any());
    }
}
