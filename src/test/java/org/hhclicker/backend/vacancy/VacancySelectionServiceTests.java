package org.hhclicker.backend.vacancy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import org.hhclicker.backend.resume.model.AiLetterSettings;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.resume.repository.ResumeRepository;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.vacancy.catalog.exception.InvalidVacancyDataException;
import org.hhclicker.backend.vacancy.catalog.exception.VacancyNotFoundException;
import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;
import org.hhclicker.backend.vacancy.catalog.model.Vacancy;
import org.hhclicker.backend.vacancy.catalog.model.VacancyListing;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;
import org.hhclicker.backend.vacancy.selection.exception.VacancyTransitionNotAllowedException;
import org.hhclicker.backend.vacancy.selection.service.VacancySelectionService;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Пользовательский отбор вакансий: очередь откликов, пропуск и возврат в работу.
 */
class VacancySelectionServiceTests {

    private final VacancyRepository repository = mock(VacancyRepository.class);
    private final ResumeRepository resumeRepository = mock(ResumeRepository.class);
    private final Clock clock = Clock.fixed(VacancyFixtures.NOW, ZoneOffset.UTC);
    private final VacancySelectionService service = new VacancySelectionService(repository, resumeRepository, clock);

    private final UUID resumeId = UUID.randomUUID();
    private final UUID firstId = UUID.randomUUID();
    private final UUID secondId = UUID.randomUUID();

    private Vacancy vacancy(UUID id, VacancyStatus status, boolean queued) {
        return VacancyFixtures.stored(id, resumeId, "118" + Integer.toUnsignedString(id.hashCode()), VacancyFixtures.EMPLOYER_ID,
                status, status == VacancyStatus.COMPANY_BANNED ? CompanyBanScope.RESUME : null, queued);
    }

    private void catalogHas(Vacancy... stored) {
        List<Vacancy> vacancies = List.of(stored);
        when(repository.findAllByIds(any())).thenReturn(vacancies);
        for (Vacancy vacancy : vacancies) {
            when(resumeRepository.findById(vacancy.resumeId())).thenReturn(Optional.of(resume(ResumeAutomationSettings.defaults())));
            when(repository.findById(vacancy.id())).thenReturn(Optional.of(vacancy));
            when(repository.findListingById(vacancy.id()))
                    .thenReturn(Optional.of(VacancyFixtures.listing(vacancy, null)));
        }
        when(repository.findListingsByIds(any())).thenReturn(
                vacancies.stream().map(vacancy -> VacancyFixtures.listing(vacancy, null)).toList());
        when(repository.updateState(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(true);
    }

    private HhResume resume(ResumeAutomationSettings settings) {
        return new HhResume(resumeId, UUID.randomUUID(), "resume-1", "Резюме", settings,
                VacancyFixtures.EARLIER, VacancyFixtures.EARLIER);
    }

    private Vacancy withAutomationData(Vacancy source, Boolean hasQuestionnaire, JsonNode enrichment) {
        return new Vacancy(source.id(), source.resumeId(), source.hhVacancyId(), source.title(), source.employerId(),
                source.companyName(), source.salary(), source.location(), source.workFormat(), source.experience(),
                source.publishedAt(), source.salaryText(), source.labels(), hasQuestionnaire, source.status(),
                source.banScope(), source.queuedForApplication(), source.firstSeenAt(), source.lastSeenAt(),
                source.createdAt(), source.updatedAt(), source.relevance(), enrichment);
    }

    @Test
    void singleVacancyIsPutIntoTheQueueWithoutChangingItsStatus() {
        Vacancy stored = vacancy(firstId, VacancyStatus.NEW, false);
        catalogHas(stored);

        VacancyListing result = service.setQueued(firstId, true);

        assertEquals(firstId, result.vacancy().id());
        verify(repository).updateState(firstId, EnumSet.of(VacancyStatus.NEW), VacancyStatus.NEW,
                null, true, VacancyFixtures.NOW);
    }

    @Test
    void queueFlagThatAlreadyMatchesIsNotRewritten() {
        catalogHas(vacancy(firstId, VacancyStatus.NEW, true));

        service.setQueued(firstId, true);

        verify(repository, never()).updateState(any(), anyCollection(), any(), any(), anyBoolean(), any());
    }

    @Test
    void vacancyWithQuestionnaireCannotBeQueued() {
        catalogHas(withAutomationData(vacancy(firstId, VacancyStatus.NEW, false), true, null));

        var exception = assertThrows(VacancyTransitionNotAllowedException.class,
                () -> service.setQueued(firstId, true));

        assertEquals("Вакансию %s нельзя поставить в очередь: для отклика требуется анкета.".formatted(firstId),
                exception.getMessage());
        verify(repository, never()).updateState(any(), anyCollection(), any(), any(), anyBoolean(), any());
    }

    @Test
    void requiredLetterWithoutGenerationOrFallbackCannotBeQueued() {
        var enrichment = JsonNodeFactory.instance.objectNode().put("responseLetterRequired", true);
        Vacancy stored = withAutomationData(vacancy(firstId, VacancyStatus.NEW, false), false, enrichment);
        catalogHas(stored);

        var exception = assertThrows(VacancyTransitionNotAllowedException.class,
                () -> service.setQueued(firstId, true));

        assertEquals("Вакансию %s нельзя поставить в очередь: настройте генерацию или резервное письмо."
                .formatted(firstId), exception.getMessage());
        verify(repository, never()).updateState(any(), anyCollection(), any(), any(), anyBoolean(), any());
    }

    @Test
    void requiredLetterCanBeQueuedWhenAiGenerationIsEnabled() {
        var enrichment = JsonNodeFactory.instance.objectNode().put("responseLetterRequired", true);
        Vacancy stored = withAutomationData(vacancy(firstId, VacancyStatus.NEW, false), false, enrichment);
        catalogHas(stored);
        when(resumeRepository.findById(resumeId)).thenReturn(Optional.of(resume(new ResumeAutomationSettings(
                "", 20, 30, 90, true, new AiLetterSettings(true, AiLetterSettings.DEFAULT_TEMPLATE, 1500)))));

        service.setQueued(firstId, true);

        verify(repository).updateState(firstId, EnumSet.of(VacancyStatus.NEW), VacancyStatus.NEW,
                null, true, VacancyFixtures.NOW);
    }

    @Test
    void requiredLetterCanBeQueuedWhenFallbackIsConfigured() {
        var enrichment = JsonNodeFactory.instance.objectNode().put("responseLetterRequired", true);
        Vacancy stored = withAutomationData(vacancy(firstId, VacancyStatus.NEW, false), false, enrichment);
        catalogHas(stored);
        when(resumeRepository.findById(resumeId)).thenReturn(Optional.of(resume(new ResumeAutomationSettings(
                "Готовое письмо", 20, 30, 90, true))));

        service.setQueued(firstId, true);

        verify(repository).updateState(firstId, EnumSet.of(VacancyStatus.NEW), VacancyStatus.NEW,
                null, true, VacancyFixtures.NOW);
    }

    @Test
    void bulkResultFollowsTheRequestedOrder() {
        catalogHas(vacancy(firstId, VacancyStatus.NEW, false), vacancy(secondId, VacancyStatus.NEW, false));
        when(repository.findListingsByIds(any())).thenReturn(List.of(
                VacancyFixtures.listing(vacancy(firstId, VacancyStatus.NEW, true), null),
                VacancyFixtures.listing(vacancy(secondId, VacancyStatus.NEW, true), null)));

        var result = service.setQueued(List.of(secondId, firstId), true);

        assertEquals(List.of(secondId, firstId), result.stream().map(listing -> listing.vacancy().id()).toList());
    }

    @Test
    void repeatedIdentifiersAreCollapsedIntoOneEntry() {
        catalogHas(vacancy(firstId, VacancyStatus.NEW, false), vacancy(secondId, VacancyStatus.NEW, false));

        var result = service.setQueued(List.of(firstId, firstId, secondId), true);

        assertEquals(2, result.size());
        verify(repository).findAllByIds(List.of(firstId, secondId));
    }

    @Test
    void emptyRequestIsRejected() {
        assertInvalidVacancyData(() -> service.setQueued(List.of(), true));
        assertInvalidVacancyData(() -> service.setQueued((List<UUID>) null, true));

        verify(repository, never()).findAllByIds(any());
    }

    @Test
    void nullIdentifierInsideTheRequestIsRejected() {
        assertInvalidVacancyData(() -> service.setQueued(Arrays.asList(firstId, null), true));

        verify(repository, never()).findAllByIds(any());
    }

    @Test
    void missingVacancyCancelsTheWholeRequest() {
        when(repository.findAllByIds(any())).thenReturn(List.of(vacancy(firstId, VacancyStatus.NEW, false)));

        assertThrows(VacancyNotFoundException.class, () -> service.setQueued(List.of(firstId, secondId), true));

        verify(repository, never()).updateState(any(), anyCollection(), any(), any(), anyBoolean(), any());
    }

    @ParameterizedTest
    @EnumSource(value = VacancyStatus.class, names = {"COMPANY_BANNED", "ALREADY_APPLIED", "CLOSED"})
    void vacancyInANonSelectableStatusCannotBeQueued(VacancyStatus status) {
        catalogHas(vacancy(firstId, VacancyStatus.NEW, false), vacancy(secondId, status, false));

        assertThrows(VacancyTransitionNotAllowedException.class,
                () -> service.setQueued(List.of(firstId, secondId), true));

        verify(repository, never()).updateState(any(), anyCollection(), any(), any(), anyBoolean(), any());
    }

    /**
     * Фиксирует текущий контракт: проверка допустимости выполняется только при постановке
     * в очередь, поэтому снять с очереди можно вакансию в любом статусе.
     */
    @ParameterizedTest
    @EnumSource(value = VacancyStatus.class, names = {"COMPANY_BANNED", "ALREADY_APPLIED", "CLOSED"})
    void removingFromTheQueueIsAllowedForAnyStatus(VacancyStatus status) {
        Vacancy stored = vacancy(firstId, status, true);
        catalogHas(stored);

        service.setQueued(firstId, false);

        verify(repository).updateState(firstId, EnumSet.of(status), status, stored.banScope(),
                false, VacancyFixtures.NOW);
    }

    @Test
    void stateChangedByAParallelOperationIsReportedAsForbiddenTransition() {
        catalogHas(vacancy(firstId, VacancyStatus.NEW, false));
        when(repository.updateState(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(false);

        var exception = assertThrows(VacancyTransitionNotAllowedException.class,
                () -> service.setQueued(firstId, true));

        assertEquals(ApplicationErrorCategory.CONFLICT, exception.category());
        assertEquals("VACANCY_TRANSITION_NOT_ALLOWED", exception.machineCode());
    }

    @Test
    void vacancyThatDisappearedBeforeTheFinalReadIsReportedAsNotFound() {
        catalogHas(vacancy(firstId, VacancyStatus.NEW, false));
        when(repository.findListingsByIds(any())).thenReturn(List.of());

        assertThrows(VacancyNotFoundException.class, () -> service.setQueued(firstId, true));
    }

    @ParameterizedTest
    @EnumSource(value = VacancyStatus.class, names = {"NEW", "USER_SKIPPED"})
    void skipMovesAnActiveVacancyIntoUserSkippedAndDropsItFromTheQueue(VacancyStatus status) {
        catalogHas(vacancy(firstId, status, true));

        service.skip(firstId);

        verify(repository).updateState(firstId, EnumSet.of(status), VacancyStatus.USER_SKIPPED,
                null, false, VacancyFixtures.NOW);
    }

    @ParameterizedTest
    @EnumSource(value = VacancyStatus.class, names = {"COMPANY_BANNED", "ALREADY_APPLIED", "CLOSED"})
    void skipIsRejectedForVacanciesThatAreNoLongerActive(VacancyStatus status) {
        catalogHas(vacancy(firstId, status, false));

        assertThrows(VacancyTransitionNotAllowedException.class, () -> service.skip(firstId));

        verify(repository, never()).updateState(any(), anyCollection(), any(), any(), anyBoolean(), any());
    }

    @Test
    void restoreReturnsASkippedVacancyToNewAndKeepsTheQueueFlagUntouched() {
        catalogHas(vacancy(firstId, VacancyStatus.USER_SKIPPED, true));

        service.restore(firstId);

        verify(repository).updateState(firstId, EnumSet.of(VacancyStatus.USER_SKIPPED), VacancyStatus.NEW,
                null, true, VacancyFixtures.NOW);
    }

    @Test
    void restoreDoesNotPutAVacancyIntoTheQueueByItself() {
        catalogHas(vacancy(firstId, VacancyStatus.USER_SKIPPED, false));

        service.restore(firstId);

        verify(repository).updateState(firstId, EnumSet.of(VacancyStatus.USER_SKIPPED), VacancyStatus.NEW,
                null, false, VacancyFixtures.NOW);
    }

    @Test
    void questionnaireVacancyCanBeMarkedAsManuallyApplied() {
        Vacancy stored = withAutomationData(vacancy(firstId, VacancyStatus.NEW, true), true, null);
        catalogHas(stored);

        service.markQuestionnaireApplied(firstId);

        verify(repository).updateState(firstId, EnumSet.of(VacancyStatus.NEW), VacancyStatus.ALREADY_APPLIED,
                null, false, VacancyFixtures.NOW);
    }

    @Test
    void enrichedQuestionnaireVacancyCanBeMarkedAsManuallyApplied() {
        var enrichment = JsonNodeFactory.instance.objectNode().put("userTestPresent", true);
        Vacancy stored = withAutomationData(vacancy(firstId, VacancyStatus.NEW, false), false, enrichment);
        catalogHas(stored);

        service.markQuestionnaireApplied(firstId);

        verify(repository).updateState(firstId, EnumSet.of(VacancyStatus.NEW), VacancyStatus.ALREADY_APPLIED,
                null, false, VacancyFixtures.NOW);
    }

    @Test
    void alreadyAppliedQuestionnaireCompletionIsIdempotent() {
        catalogHas(withAutomationData(vacancy(firstId, VacancyStatus.ALREADY_APPLIED, false), true, null));

        service.markQuestionnaireApplied(firstId);

        verify(repository, never()).updateState(any(), anyCollection(), any(), any(), anyBoolean(), any());
    }

    @Test
    void ordinaryVacancyCannotBeMarkedAsQuestionnaireApplied() {
        catalogHas(vacancy(firstId, VacancyStatus.NEW, false));

        assertThrows(VacancyTransitionNotAllowedException.class,
                () -> service.markQuestionnaireApplied(firstId));

        verify(repository, never()).updateState(any(), anyCollection(), any(), any(), anyBoolean(), any());
    }

    @ParameterizedTest
    @EnumSource(value = VacancyStatus.class, names = {"USER_SKIPPED", "COMPANY_BANNED", "CLOSED"})
    void questionnaireAppliedIsRejectedOutsideNewStatus(VacancyStatus status) {
        catalogHas(withAutomationData(vacancy(firstId, status, false), true, null));

        assertThrows(VacancyTransitionNotAllowedException.class,
                () -> service.markQuestionnaireApplied(firstId));

        verify(repository, never()).updateState(any(), anyCollection(), any(), any(), anyBoolean(), any());
    }

    @ParameterizedTest
    @EnumSource(value = VacancyStatus.class, names = {"NEW", "COMPANY_BANNED", "ALREADY_APPLIED", "CLOSED"})
    void restoreIsRejectedForEverythingButASkippedVacancy(VacancyStatus status) {
        catalogHas(vacancy(firstId, status, false));

        assertThrows(VacancyTransitionNotAllowedException.class, () -> service.restore(firstId));

        verify(repository, never()).updateState(any(), anyCollection(), any(), any(), anyBoolean(), any());
    }

    @Test
    void skipAndRestoreOfAMissingVacancyAreReportedAsNotFound() {
        when(repository.findById(firstId)).thenReturn(Optional.empty());

        assertThrows(VacancyNotFoundException.class, () -> service.skip(firstId));
        assertThrows(VacancyNotFoundException.class, () -> service.restore(firstId));
    }

    private void assertInvalidVacancyData(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(InvalidVacancyDataException.class, executable);
        assertEquals(ApplicationErrorCategory.VALIDATION, exception.category());
        assertEquals("INVALID_VACANCY_DATA", exception.machineCode());
    }
}
