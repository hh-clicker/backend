package org.hhclicker.backend.vacancy;

import tools.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;
import org.hhclicker.backend.vacancy.catalog.model.Vacancy;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Повторное обнаружение вакансии: обновление внешних данных и пересмотр состояния.
 */
class VacancyIngestionUpdateTests extends VacancyIngestionTestSupport {

    private final UUID storedId = UUID.randomUUID();

    private void storedIs(VacancyStatus status, CompanyBanScope banScope, boolean queued, String employerId) {
        Vacancy stored = VacancyFixtures.stored(storedId, resumeId, VacancyFixtures.HH_VACANCY_ID,
                employerId, status, banScope, queued);
        when(vacancies.findAllByResumeIdAndHhVacancyIds(any(), any())).thenReturn(List.of(stored));
    }

    private void storedIsActiveQueued() {
        storedIs(VacancyStatus.NEW, null, true, VacancyFixtures.EMPLOYER_ID);
    }

    @Test
    void rediscoveryRefreshesExternalDataAndKeepsTheUserChoice() {
        storedIs(VacancyStatus.USER_SKIPPED, null, true, VacancyFixtures.EMPLOYER_ID);

        ingest(VacancyFixtures.incoming()
                .title("Senior Java-разработчик")
                .companyName("Новое название")
                .location("Санкт-Петербург")
                .salaryText("от 300 000 ₽")
                .build());

        Vacancy refreshed = captureExternalUpdate();
        assertEquals(storedId, refreshed.id());
        assertEquals("Senior Java-разработчик", refreshed.title());
        assertEquals("Новое название", refreshed.companyName());
        assertEquals("Санкт-Петербург", refreshed.location());
        assertEquals("от 300 000 ₽", refreshed.salaryText());
        assertEquals(VacancyStatus.USER_SKIPPED, refreshed.status(), "пользовательский статус сохраняется");
        assertTrue(refreshed.queuedForApplication(), "очередь пользователя сохраняется");
        assertEquals(VacancyFixtures.EARLIER, refreshed.firstSeenAt(), "первое обнаружение не переписывается");
        assertEquals(VacancyFixtures.EARLIER, refreshed.createdAt());
        assertEquals(VacancyFixtures.NOW, refreshed.lastSeenAt());
        assertEquals(VacancyFixtures.NOW, refreshed.updatedAt());
    }

    @Test
    void missingExternalValuesFallBackToTheStoredSnapshot() {
        storedIsActiveQueued();

        ingest(VacancyFixtures.incoming()
                .employerId(null)
                .publishedAt(null)
                .hasQuestionnaire(null)
                .companyName("Без ID")
                .build());

        Vacancy refreshed = captureExternalUpdate();
        assertEquals(VacancyFixtures.EMPLOYER_ID, refreshed.employerId(), "пустой ID работодателя не стирает старый");
        assertEquals(VacancyFixtures.EARLIER, refreshed.publishedAt());
        assertEquals(Boolean.FALSE, refreshed.hasQuestionnaire());
    }

    @Test
    void missingEnrichmentKeepsTheLastVerifiedSnapshotAndFreshEnrichmentReplacesIt() {
        var storedEnrichment = JsonNodeFactory.instance.objectNode().put("trustedEmployer", true);
        Vacancy base = VacancyFixtures.active(storedId, resumeId);
        Vacancy stored = new Vacancy(
                base.id(), base.resumeId(), base.hhVacancyId(), base.title(), base.employerId(), base.companyName(),
                base.salary(), base.location(), base.workFormat(), base.experience(), base.publishedAt(),
                base.salaryText(), base.labels(), base.hasQuestionnaire(), base.status(), base.banScope(),
                base.queuedForApplication(), base.firstSeenAt(), base.lastSeenAt(), base.createdAt(), base.updatedAt(),
                base.relevance(), storedEnrichment
        );
        when(vacancies.findAllByResumeIdAndHhVacancyIds(any(), any())).thenReturn(List.of(stored));

        ingest(VacancyFixtures.incoming().enrichment(null).build());
        assertEquals(storedEnrichment, captureExternalUpdate().enrichment());

        reset(vacancies);
        when(vacancies.findAllByResumeIdAndHhVacancyIds(any(), any())).thenReturn(List.of(stored));
        var freshEnrichment = JsonNodeFactory.instance.objectNode().put("trustedEmployer", false);
        ingest(VacancyFixtures.incoming().enrichment(freshEnrichment).build());
        assertEquals(freshEnrichment, captureExternalUpdate().enrichment());
    }

    @Test
    void unchangedLabelsAreNotRewritten() {
        storedIsActiveQueued();

        ingest(VacancyFixtures.incoming().labels(List.of("Быстрый ответ")).build());

        verify(vacancies, never()).replaceLabels(any(), any());
    }

    @Test
    void changedLabelsAreRewritten() {
        storedIsActiveQueued();

        ingest(VacancyFixtures.incoming().labels(List.of("Быстрый ответ", "Проверенный")).build());

        verify(vacancies).replaceLabels(storedId, List.of("Быстрый ответ", "Проверенный"));
    }

    @Test
    void ownApplicationOnHhOverridesAnyOtherStatus() {
        storedIs(VacancyStatus.USER_SKIPPED, null, true, VacancyFixtures.EMPLOYER_ID);

        ingest(VacancyFixtures.incoming().alreadyApplied(true).build());

        verify(vacancies).updateState(storedId, EnumSet.complementOf(EnumSet.of(VacancyStatus.ALREADY_APPLIED)),
                VacancyStatus.ALREADY_APPLIED, null, false, VacancyFixtures.NOW);
    }

    @Test
    void closedVacancyOnlyOverridesActiveAndBannedStatuses() {
        storedIsActiveQueued();

        ingest(VacancyFixtures.incoming().closed(true).build());

        verify(vacancies).updateState(storedId,
                EnumSet.of(VacancyStatus.NEW, VacancyStatus.USER_SKIPPED, VacancyStatus.COMPANY_BANNED),
                VacancyStatus.CLOSED, null, false, VacancyFixtures.NOW);
    }

    @Test
    void ownApplicationWinsOverClosedOnRediscovery() {
        storedIsActiveQueued();

        ingest(VacancyFixtures.incoming().alreadyApplied(true).closed(true).build());

        verify(vacancies).updateState(any(), anyCollection(), eq(VacancyStatus.ALREADY_APPLIED),
                isNull(), anyBoolean(), any());
        verify(vacancies, never()).updateState(any(), anyCollection(), eq(VacancyStatus.CLOSED),
                any(), anyBoolean(), any());
    }

    @Test
    void rediscoveryRepairsAnActiveVacancyOfAnAlreadyBlockedEmployer() {
        storedIsActiveQueued();
        when(banLookup.findApplicableScopes(any(), any()))
                .thenReturn(Map.of(VacancyFixtures.EMPLOYER_ID, CompanyBanScope.GLOBAL));

        ingest(VacancyFixtures.incoming().build());

        verify(vacancies).updateState(storedId,
                EnumSet.of(VacancyStatus.NEW, VacancyStatus.USER_SKIPPED, VacancyStatus.COMPANY_BANNED),
                VacancyStatus.COMPANY_BANNED, CompanyBanScope.GLOBAL, false, VacancyFixtures.NOW);
    }

    @Test
    void vacancyMovedToABlockedEmployerBecomesBanned() {
        storedIsActiveQueued();
        when(banLookup.findApplicableScopes(any(), any()))
                .thenReturn(Map.of("9999", CompanyBanScope.RESUME));

        ingest(VacancyFixtures.incoming().employerId("9999").build());

        verify(vacancies).updateState(storedId,
                EnumSet.of(VacancyStatus.NEW, VacancyStatus.USER_SKIPPED, VacancyStatus.COMPANY_BANNED),
                VacancyStatus.COMPANY_BANNED, CompanyBanScope.RESUME, false, VacancyFixtures.NOW);
    }

    @Test
    void bannedVacancyMovedToAFreeEmployerIsReleasedIntoNew() {
        storedIs(VacancyStatus.COMPANY_BANNED, CompanyBanScope.GLOBAL, false, VacancyFixtures.EMPLOYER_ID);

        ingest(VacancyFixtures.incoming().employerId("9999").build());

        verify(vacancies).updateState(storedId, EnumSet.of(VacancyStatus.COMPANY_BANNED),
                VacancyStatus.NEW, null, false, VacancyFixtures.NOW);
    }

    @Test
    void activeVacancyMovedToAFreeEmployerKeepsItsState() {
        storedIsActiveQueued();

        ingest(VacancyFixtures.incoming().employerId("9999").build());

        verify(vacancies, never()).updateState(any(), anyCollection(), any(), any(), anyBoolean(), any());
    }

    @Test
    void terminalSignalOfHhIsCheckedBeforeTheEmployerChange() {
        storedIs(VacancyStatus.COMPANY_BANNED, CompanyBanScope.GLOBAL, false, VacancyFixtures.EMPLOYER_ID);
        when(banLookup.findApplicableScopes(any(), any()))
                .thenReturn(Map.of("9999", CompanyBanScope.GLOBAL));

        ingest(VacancyFixtures.incoming().employerId("9999").alreadyApplied(true).build());

        verify(vacancies).updateState(any(), anyCollection(), eq(VacancyStatus.ALREADY_APPLIED),
                isNull(), anyBoolean(), any());
        verify(vacancies, never()).updateState(any(), anyCollection(), eq(VacancyStatus.COMPANY_BANNED),
                any(), anyBoolean(), any());
    }

    @Test
    void rediscoveryNeverTouchesTheStoredDescription() {
        storedIsActiveQueued();

        ingest(VacancyFixtures.incoming().title("Новое название").build());

        verify(vacancies).updateExternalData(any());
        verify(vacancies, never()).saveText(any(), any());
    }
}
