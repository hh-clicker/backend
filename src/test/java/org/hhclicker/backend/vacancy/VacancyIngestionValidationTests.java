package org.hhclicker.backend.vacancy;

import tools.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.hhclicker.backend.vacancy.catalog.model.SalaryCurrency;
import org.hhclicker.backend.vacancy.catalog.model.Vacancy;
import org.hhclicker.backend.vacancy.catalog.service.IngestSearchResultsCommand;
import org.hhclicker.backend.vacancy.catalog.service.NormalizedVacancy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Валидация команды приёма и нормализация каждой вакансии до записи в каталог.
 */
class VacancyIngestionValidationTests extends VacancyIngestionTestSupport {

    @Test
    void searchAndResumeAreRequiredBeforeAnythingIsRead() {
        assertInvalidVacancyData(() -> service.ingest(
                new IngestSearchResultsCommand(null, resumeId, List.of(VacancyFixtures.incoming().build()))));
        assertInvalidVacancyData(() -> service.ingest(
                new IngestSearchResultsCommand(searchId, null, List.of(VacancyFixtures.incoming().build()))));

        verifyNoInteractions(banLookup);
        verify(vacancies, never()).insertIfAbsent(any());
    }

    @Test
    void missingVacancyListIsTreatedAsAnEmptyBatch() {
        var result = service.ingest(new IngestSearchResultsCommand(searchId, resumeId, null));

        assertEquals(searchId, result.searchId());
        assertEquals(0, result.received());
        assertEquals(0, result.created());
        assertEquals(0, result.updated());
        verify(vacancies, never()).insertIfAbsent(any());
        verify(links, never()).upsert(any());
    }

    @Test
    void batchLargerThanTheAllowedMaximumIsRejected() {
        assertInvalidVacancyData(() -> ingest(batchOf(2001)));

        verify(vacancies, never()).insertIfAbsent(any());
    }

    @Test
    void batchOfExactlyTwoThousandIsAccepted() {
        var result = ingest(batchOf(2000));

        assertEquals(2000, result.received());
        assertEquals(2000, result.created());
    }

    @Test
    void emptyElementOfTheListIsRejected() {
        List<NormalizedVacancy> withHole = Arrays.asList(VacancyFixtures.incoming().build(), null);

        assertInvalidVacancyData(() -> ingest(withHole));

        verify(vacancies, never()).insertIfAbsent(any());
    }

    @Test
    void theSameVacancyListedTwiceInOneBatchIsRejected() {
        assertInvalidVacancyData(() -> ingest(
                VacancyFixtures.incoming().hhVacancyId("118123456").build(),
                VacancyFixtures.incoming().hhVacancyId("  118123456  ").build()));

        verify(vacancies, never()).insertIfAbsent(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "118-123", "118 123", "١١٨"})
    void malformedVacancyIdentifierIsRejected(String hhVacancyId) {
        assertRejected(VacancyFixtures.incoming().hhVacancyId(hhVacancyId));
    }

    @Test
    void vacancyIdentifierIsTrimmedAndBoundedBySixtyFourCharacters() {
        assertRejected(VacancyFixtures.incoming().hhVacancyId(null));
        assertRejected(VacancyFixtures.incoming().hhVacancyId("1".repeat(65)));

        assertAccepted(VacancyFixtures.incoming().hhVacancyId("  118123456  "));
        assertEquals("118123456", captureInsert().hhVacancyId());
    }

    @Test
    void titleIsRequiredAndBoundedByFiveHundredCharacters() {
        assertRejected(VacancyFixtures.incoming().title(null));
        assertRejected(VacancyFixtures.incoming().title("   "));
        assertRejected(VacancyFixtures.incoming().title("и".repeat(501)));

        assertAccepted(VacancyFixtures.incoming().title("  Java-разработчик  "));
        assertEquals("Java-разработчик", captureInsert().title());
    }

    @Test
    void employerIdentifierIsCanonicalizedAndMayBeAbsent() {
        assertAccepted(VacancyFixtures.incoming().employerId("  0003529  "));
        assertEquals("3529", captureInsert().employerId(), "ведущие нули отбрасываются");
    }

    @Test
    void blankEmployerIdentifierBecomesNullWithoutAnError() {
        assertAccepted(VacancyFixtures.incoming().employerId("   ").companyName(null));

        Vacancy inserted = captureInsert();
        assertNull(inserted.employerId());
        assertEquals("Работодатель не указан", inserted.companyName());
    }

    @Test
    void tooLongEmployerIdentifierIsRejected() {
        assertRejected(VacancyFixtures.incoming().employerId("1".repeat(65)));
    }

    /**
     * Фиксирует текущий контракт: канонизация трогает только числовые значения, нечисловой
     * идентификатор работодателя принимается как есть.
     */
    @Test
    void nonNumericEmployerIdentifierIsStoredAsIs() {
        assertAccepted(VacancyFixtures.incoming().employerId("employer-3529"));

        assertEquals("employer-3529", captureInsert().employerId());
    }

    @Test
    void missingCompanyNameFallsBackToTheEmployerIdentifier() {
        assertAccepted(VacancyFixtures.incoming().companyName("   ").employerId("3529"));

        assertEquals("Employer ID 3529", captureInsert().companyName());
    }

    @Test
    void companyNameIsBoundedByThreeHundredCharacters() {
        assertRejected(VacancyFixtures.incoming().companyName("и".repeat(301)));

        assertAccepted(VacancyFixtures.incoming().companyName("и".repeat(300)));
        assertEquals(300, captureInsert().companyName().length());
    }

    @Test
    void optionalTextFieldsBecomeEmptyStringsAndAreBounded() {
        assertAccepted(VacancyFixtures.incoming().location(null).workFormat(null).experience(null).salaryText(null));

        Vacancy inserted = captureInsert();
        assertEquals("", inserted.location());
        assertEquals("", inserted.workFormat());
        assertEquals("", inserted.experience());
        assertEquals("", inserted.salaryText());

        assertRejected(VacancyFixtures.incoming().location("и".repeat(301)));
        assertRejected(VacancyFixtures.incoming().workFormat("и".repeat(121)));
        assertRejected(VacancyFixtures.incoming().experience("и".repeat(121)));
        assertRejected(VacancyFixtures.incoming().salaryText("и".repeat(501)));
    }

    @Test
    void labelsAreTrimmedDeduplicatedAndKeepTheirOrder() {
        assertAccepted(VacancyFixtures.incoming()
                .labels(Arrays.asList("  Быстрый   ответ  ", null, "   ", "Быстрый ответ", "Проверенный")));

        assertEquals(List.of("Быстрый ответ", "Проверенный"), captureInsert().labels(),
                "пробелы схлопываются, пустые и повторяющиеся метки отбрасываются");
    }

    @Test
    void missingLabelListBecomesAnEmptyList() {
        assertAccepted(VacancyFixtures.incoming().labels(null));

        assertEquals(List.of(), captureInsert().labels());
    }

    @Test
    void labelBoundsAreEnforced() {
        assertRejected(VacancyFixtures.incoming().labels(List.of("и".repeat(201))));
        assertRejected(VacancyFixtures.incoming().labels(distinctLabels(101)));

        assertAccepted(VacancyFixtures.incoming().labels(distinctLabels(100)));
        assertEquals(100, captureInsert().labels().size());
    }

    @Test
    void negativePositionIsRejected() {
        assertRejected(VacancyFixtures.incoming().position(-1));

        assertAccepted(VacancyFixtures.incoming().position(0));
        assertEquals(0, captureLink().position());
    }

    @Test
    void salaryIsOptionalAndFullyOmittedWhenMissing() {
        assertAccepted(VacancyFixtures.incoming().salary(null));

        assertNull(captureInsert().salary());
    }

    @Test
    void currencyIsRequiredParsedCaseInsensitivelyAndLimitedToKnownValues() {
        assertRejected(VacancyFixtures.incoming().salary(VacancyFixtures.salary(1L, 2L, null, false)));
        assertRejected(VacancyFixtures.incoming().salary(VacancyFixtures.salary(1L, 2L, "  ", false)));
        assertRejected(VacancyFixtures.incoming().salary(VacancyFixtures.salary(1L, 2L, "KZT", false)));

        assertAccepted(VacancyFixtures.incoming().salary(VacancyFixtures.salary(1L, 2L, " rur ", true)));
        assertEquals(SalaryCurrency.RUR, captureInsert().salary().currency());
    }

    @Test
    void salaryBoundsAreValidated() {
        assertRejected(VacancyFixtures.incoming().salary(VacancyFixtures.salary(-1L, 100L, "RUR", false)));
        assertRejected(VacancyFixtures.incoming().salary(VacancyFixtures.salary(100L, -1L, "RUR", false)));
        assertRejected(VacancyFixtures.incoming().salary(VacancyFixtures.salary(200L, 100L, "RUR", false)));

        assertAccepted(VacancyFixtures.incoming().salary(VacancyFixtures.salary(100L, 100L, "RUR", false)));
        assertEquals(100L, captureInsert().salary().from());
    }

    @Test
    void openEndedSalaryIsAccepted() {
        assertAccepted(VacancyFixtures.incoming().salary(VacancyFixtures.salary(null, 200_000L, "USD", true)));

        var salary = captureInsert().salary();
        assertNull(salary.from());
        assertEquals(200_000L, salary.to());
        assertEquals(SalaryCurrency.USD, salary.currency());
        assertTrue(salary.gross());
    }

    @Test
    void enrichmentMustBeAReasonablySizedJsonObject() {
        assertRejected(VacancyFixtures.incoming()
                .enrichment(JsonNodeFactory.instance.arrayNode().add("REMOTE")));
        assertRejected(VacancyFixtures.incoming()
                .enrichment(JsonNodeFactory.instance.objectNode().put("value", "x".repeat(20_001))));

        var enrichment = JsonNodeFactory.instance.objectNode().put("trustedEmployer", true);
        assertAccepted(VacancyFixtures.incoming().enrichment(enrichment));
        assertEquals(enrichment, captureInsert().enrichment());
    }

    private List<String> distinctLabels(int count) {
        List<String> labels = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            labels.add("Метка " + index);
        }
        return labels;
    }

    private List<NormalizedVacancy> batchOf(int count) {
        List<NormalizedVacancy> batch = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            batch.add(VacancyFixtures.incoming().hhVacancyId(String.valueOf(100_000_000 + index)).build());
        }
        return batch;
    }
}
