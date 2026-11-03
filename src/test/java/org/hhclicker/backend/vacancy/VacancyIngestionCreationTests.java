package org.hhclicker.backend.vacancy;

import tools.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.hhclicker.backend.vacancy.catalog.model.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Создание новой вакансии при первом обнаружении: снимок полей, статус и стоп-лист.
 */
class VacancyIngestionCreationTests extends VacancyIngestionTestSupport {

    @Test
    void newVacancyIsStoredWithEveryFieldAndServerTimestamps() {
        var enrichment = JsonNodeFactory.instance.objectNode();
        enrichment.putArray("workFormats").add("REMOTE");
        ingest(VacancyFixtures.incoming()
                .salary(VacancyFixtures.salary(100_000L, 200_000L, "RUR", true))
                .position(3)
                .enrichment(enrichment)
                .build());

        Vacancy inserted = captureInsert();
        assertNotNull(inserted.id());
        assertEquals(resumeId, inserted.resumeId());
        assertEquals(VacancyFixtures.HH_VACANCY_ID, inserted.hhVacancyId());
        assertEquals("Java-разработчик", inserted.title());
        assertEquals(VacancyFixtures.EMPLOYER_ID, inserted.employerId());
        assertEquals("Синтетическая компания", inserted.companyName());
        assertEquals(100_000L, inserted.salary().from());
        assertEquals(200_000L, inserted.salary().to());
        assertEquals(SalaryCurrency.RUR, inserted.salary().currency());
        assertEquals("Москва", inserted.location());
        assertEquals("Удалённо", inserted.workFormat());
        assertEquals("1-3 года", inserted.experience());
        assertEquals(VacancyFixtures.EARLIER, inserted.publishedAt());
        assertEquals("от 100 000 ₽", inserted.salaryText());
        assertEquals(List.of("Быстрый ответ"), inserted.labels());
        assertEquals(Boolean.FALSE, inserted.hasQuestionnaire());
        assertEquals(VacancyStatus.NEW, inserted.status());
        assertNull(inserted.banScope());
        assertFalse(inserted.queuedForApplication(), "найденная вакансия не попадает в очередь сама");
        assertEquals(VacancyFixtures.NOW, inserted.firstSeenAt());
        assertEquals(VacancyFixtures.NOW, inserted.lastSeenAt());
        assertEquals(VacancyFixtures.NOW, inserted.createdAt());
        assertEquals(VacancyFixtures.NOW, inserted.updatedAt());
        assertEquals(enrichment, inserted.enrichment());
        assertNotSame(enrichment, inserted.enrichment(), "в каталог передаётся независимый JSON-снимок");
    }

    @Test
    void labelsAreWrittenForTheInsertedRow() {
        ingest(VacancyFixtures.incoming().labels(List.of("Быстрый ответ", "Проверенный")).build());

        UUID insertedId = captureInsert().id();
        verify(vacancies).replaceLabels(insertedId, List.of("Быстрый ответ", "Проверенный"));
    }

    @Test
    void searchLinkIsCreatedWithTheReportedPosition() {
        ingest(VacancyFixtures.incoming().position(17).build());

        VacancySearchLink link = captureLink();
        assertNotNull(link.id());
        assertEquals(captureInsert().id(), link.vacancyId());
        assertEquals(searchId, link.searchId());
        assertEquals(17, link.position());
        assertEquals(VacancyFixtures.NOW, link.createdAt());
        assertEquals(VacancyFixtures.NOW, link.updatedAt());
    }

    @Test
    void ownApplicationOnHhOutranksAClosedVacancy() {
        ingest(VacancyFixtures.incoming().alreadyApplied(true).closed(true).build());

        Vacancy inserted = captureInsert();
        assertEquals(VacancyStatus.ALREADY_APPLIED, inserted.status());
        assertNull(inserted.banScope());
    }

    @Test
    void closedVacancyIsStoredAsClosed() {
        ingest(VacancyFixtures.incoming().closed(true).build());

        assertEquals(VacancyStatus.CLOSED, captureInsert().status());
    }

    @ParameterizedTest
    @EnumSource(CompanyBanScope.class)
    void blockedEmployerMakesTheNewVacancyBannedWithTheApplicableScope(CompanyBanScope scope) {
        when(banLookup.findApplicableScopes(any(), any()))
                .thenReturn(Map.of(VacancyFixtures.EMPLOYER_ID, scope));

        ingest(VacancyFixtures.incoming().build());

        Vacancy inserted = captureInsert();
        assertEquals(VacancyStatus.COMPANY_BANNED, inserted.status());
        assertEquals(scope, inserted.banScope());
        assertFalse(inserted.queuedForApplication());
    }

    @Test
    void ownApplicationOutranksTheStopList() {
        when(banLookup.findApplicableScopes(any(), any()))
                .thenReturn(Map.of(VacancyFixtures.EMPLOYER_ID, CompanyBanScope.GLOBAL));

        ingest(VacancyFixtures.incoming().alreadyApplied(true).build());

        Vacancy inserted = captureInsert();
        assertEquals(VacancyStatus.ALREADY_APPLIED, inserted.status());
        assertNull(inserted.banScope(), "область блокировки хранится только вместе с COMPANY_BANNED");
    }

    @Test
    void stopListIsQueriedOnlyForKnownEmployersOfTheRequestedResume() {
        ingest(VacancyFixtures.incoming().hhVacancyId("118000001").employerId("3529").build(),
                VacancyFixtures.incoming().hhVacancyId("118000002").employerId(null).companyName("Без ID").build());

        verify(banLookup).findApplicableScopes(Set.of("3529"), resumeId);
    }

    @Test
    void vacancyWithoutEmployerIsNeverBlocked() {
        when(banLookup.findApplicableScopes(any(), any()))
                .thenReturn(Map.of(VacancyFixtures.EMPLOYER_ID, CompanyBanScope.GLOBAL));

        ingest(VacancyFixtures.incoming().employerId(null).companyName("Без ID").build());

        Vacancy inserted = captureInsert();
        assertEquals(VacancyStatus.NEW, inserted.status());
        assertNull(inserted.banScope());
    }

    @Test
    void rowCreatedByAParallelTransactionIsUpdatedInsteadOfInserted() {
        UUID storedId = UUID.randomUUID();
        Vacancy stored = VacancyFixtures.active(storedId, resumeId);
        doReturn(Optional.empty()).when(vacancies).insertIfAbsent(any());
        when(vacancies.findAllByResumeIdAndHhVacancyIds(any(), any()))
                .thenReturn(List.of(), List.of(stored));

        var result = ingest(VacancyFixtures.incoming().build());

        assertEquals(1, result.received());
        assertEquals(0, result.created(), "проигранная гонка вставки не считается созданием");
        assertEquals(1, result.updated());
        verify(vacancies).updateExternalData(any());
        assertEquals(storedId, captureLink().vacancyId());
    }

    @Test
    void vacancyThatDisappearedAfterALostInsertRaceIsReported() {
        doReturn(Optional.empty()).when(vacancies).insertIfAbsent(any());

        assertInvalidVacancyData(() -> ingest(VacancyFixtures.incoming().build()));

        verify(vacancies, never()).updateExternalData(any());
    }

    @Test
    void countersSeparateCreatedAndUpdatedVacancies() {
        Vacancy stored = VacancyFixtures.stored(UUID.randomUUID(), resumeId, "118000002",
                VacancyFixtures.EMPLOYER_ID, VacancyStatus.NEW, null, false);
        when(vacancies.findAllByResumeIdAndHhVacancyIds(any(), any())).thenReturn(List.of(stored));
        when(links.countVacanciesBySearchId(searchId)).thenReturn(119L, 130L);

        var result = ingest(VacancyFixtures.incoming().hhVacancyId("118000001").build(),
                VacancyFixtures.incoming().hhVacancyId("118000002").build());

        assertEquals(searchId, result.searchId());
        assertEquals(2, result.received());
        assertEquals(1, result.created());
        assertEquals(1, result.updated());
        assertEquals(11, result.newVacancies());
        assertEquals(130, result.totalVacancies());
        verify(links, times(2)).countVacanciesBySearchId(searchId);
        verify(links, times(2)).upsert(any());
    }

    @Test
    void descriptionSnapshotIsNeverTouchedByIngestion() {
        Vacancy stored = VacancyFixtures.active(UUID.randomUUID(), resumeId);
        when(vacancies.findAllByResumeIdAndHhVacancyIds(any(), any())).thenReturn(List.of(stored));

        ingest(VacancyFixtures.incoming().build());

        verify(vacancies, never()).saveText(any(), any());
        verify(vacancies, never()).findText(any());
    }

    @Test
    void catalogIsQueriedOnceForEveryIdentifierOfTheBatch() {
        ingest(VacancyFixtures.incoming().hhVacancyId("118000001").build(),
                VacancyFixtures.incoming().hhVacancyId("118000002").build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(vacancies).findAllByResumeIdAndHhVacancyIds(eq(resumeId), captor.capture());
        assertEquals(Set.of("118000001", "118000002"), new HashSet<>(captor.getValue()));
    }
}
