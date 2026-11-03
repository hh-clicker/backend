package org.hhclicker.backend.vacancy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.hhclicker.backend.vacancy.catalog.exception.VacancyNotFoundException;
import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;
import org.hhclicker.backend.vacancy.stoplist.exception.CompanyBanAlreadyExistsException;
import org.hhclicker.backend.vacancy.stoplist.model.CompanyBan;
import org.hhclicker.backend.vacancy.stoplist.service.CreateCompanyBanCommand;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Добавление работодателя в стоп-лист через строгий endpoint /company-bans.
 */
class CompanyBanCreationTests extends CompanyBanTestSupport {

    private CreateCompanyBanCommand command(String url, String companyName, CompanyBanScope scope,
                                            UUID owner, UUID sourceVacancyId) {
        return new CreateCompanyBanCommand(url, companyName, scope, owner, sourceVacancyId);
    }

    @Test
    void globalBanIsStoredWithACanonicalEmployerAndServerTime() {
        var view = service.create(command(EMPLOYER_URL, "  ООО Синтетика  ", CompanyBanScope.GLOBAL, null, null));

        CompanyBan inserted = captureInsert();
        assertNotNull(inserted.id());
        assertEquals("3529", inserted.employerId());
        assertEquals("https://hh.ru/employer/3529", inserted.employerUrl());
        assertEquals("ООО Синтетика", inserted.companyName());
        assertEquals(CompanyBanScope.GLOBAL, inserted.scope());
        assertNull(inserted.resumeId());
        assertNull(inserted.sourceVacancyId());
        assertEquals(VacancyFixtures.NOW, inserted.createdAt());
        assertEquals(VacancyFixtures.NOW, inserted.updatedAt());
        assertSame(inserted, view.ban());
        assertNull(view.resumeName(), "у глобальной блокировки нет резюме");
        assertEquals(0, view.hiddenVacanciesCount());
    }

    @Test
    void resumeBanKeepsItsResumeAndExposesTheResolvedResumeName() {
        stubResume();

        var view = service.create(command(EMPLOYER_URL, "ООО Синтетика", CompanyBanScope.RESUME, resumeId, null));

        assertEquals(resumeId, captureInsert().resumeId());
        assertEquals("Java-разработчик", view.resumeName(), "имя резюме берётся из ResumeService");
        verify(resumes, atLeastOnce()).findById(resumeId);
    }

    @Test
    void leadingZeroesOfTheEmployerIdentifierAreDropped() {
        service.create(command("https://hh.ru/employer/0003529", null, CompanyBanScope.GLOBAL, null, null));

        CompanyBan inserted = captureInsert();
        assertEquals("3529", inserted.employerId());
        assertEquals("https://hh.ru/employer/3529", inserted.employerUrl());
    }

    @Test
    void regionalSubdomainAndTrailingSlashAreAcceptedAndNormalized() {
        service.create(command("  https://spb.hh.ru/employer/3529/  ", null, CompanyBanScope.GLOBAL, null, null));

        assertEquals("https://hh.ru/employer/3529", captureInsert().employerUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "hh.ru/employer/3529",
            "ftp://hh.ru/employer/3529",
            "https://example.com/employer/3529",
            "https://evilhh.ru/employer/3529",
            "https://hh.ru/employers/3529",
            "https://hh.ru/employer/abc",
            "https://hh.ru/employer/3529/vacancies",
            "https://hh .ru/employer/3529"
    })
    void malformedEmployerLinkIsRejected(String employerUrl) {
        assertInvalidBanData(() -> service.create(command(employerUrl, null, CompanyBanScope.GLOBAL, null, null)));

        verify(bans, never()).insert(any());
    }

    @Test
    void missingAndOverlongEmployerLinksAreRejected() {
        assertInvalidBanData(() -> service.create(command(null, null, CompanyBanScope.GLOBAL, null, null)));
        assertInvalidBanData(() -> service.create(
                command("https://hh.ru/employer/" + "1".repeat(500), null, CompanyBanScope.GLOBAL, null, null)));
    }

    @Test
    void scopeIsMandatory() {
        assertInvalidBanData(() -> service.create(command(EMPLOYER_URL, null, null, null, null)));

        verify(bans, never()).insert(any());
    }

    @Test
    void resumeScopeRequiresAResumeAndGlobalScopeForbidsIt() {
        assertInvalidBanData(() -> service.create(command(EMPLOYER_URL, null, CompanyBanScope.RESUME, null, null)));
        assertInvalidBanData(() -> service.create(command(EMPLOYER_URL, null, CompanyBanScope.GLOBAL, resumeId, null)));

        verify(bans, never()).insert(any());
    }

    @Test
    void companyNameFallsBackToTheLatestNameKnownFromVacancies() {
        when(vacancies.findLatestCompanyName("3529")).thenReturn(Optional.of("ООО Из вакансии"));

        service.create(command(EMPLOYER_URL, "   ", CompanyBanScope.GLOBAL, null, null));

        assertEquals("ООО Из вакансии", captureInsert().companyName());
    }

    @Test
    void companyNameFallsBackToTheEmployerIdentifierWhenNothingIsKnown() {
        when(vacancies.findLatestCompanyName("3529")).thenReturn(Optional.of("   "));

        service.create(command(EMPLOYER_URL, null, CompanyBanScope.GLOBAL, null, null));

        assertEquals("Employer ID 3529", captureInsert().companyName());
    }

    @Test
    void overlongCompanyNameIsRejected() {
        assertInvalidBanData(() -> service.create(
                command(EMPLOYER_URL, "и".repeat(301), CompanyBanScope.GLOBAL, null, null)));
    }

    @Test
    void sourceVacancyMustBelongToTheBlockedEmployer() {
        when(vacancies.findById(vacancyId)).thenReturn(Optional.of(VacancyFixtures.stored(
                vacancyId, resumeId, "118123456", "9999", VacancyStatus.NEW, null, false)));

        assertInvalidBanData(() -> service.create(
                command(EMPLOYER_URL, null, CompanyBanScope.GLOBAL, null, vacancyId)));

        verify(bans, never()).insert(any());
    }

    @Test
    void sourceVacancyOfTheSameEmployerIsStored() {
        when(vacancies.findById(vacancyId)).thenReturn(Optional.of(VacancyFixtures.stored(
                vacancyId, resumeId, "118123456", "3529", VacancyStatus.NEW, null, false)));

        service.create(command(EMPLOYER_URL, null, CompanyBanScope.GLOBAL, null, vacancyId));

        assertEquals(vacancyId, captureInsert().sourceVacancyId());
    }

    @Test
    void missingSourceVacancyIsReportedAsNotFound() {
        when(vacancies.findById(vacancyId)).thenReturn(Optional.empty());

        assertThrows(VacancyNotFoundException.class, () -> service.create(
                command(EMPLOYER_URL, null, CompanyBanScope.GLOBAL, null, vacancyId)));
    }

    @Test
    void strictEndpointRejectsAnExactDuplicateOfTheSameScope() {
        when(bans.findAllByEmployerId("3529"))
                .thenReturn(List.of(ban(UUID.randomUUID(), "3529", CompanyBanScope.GLOBAL, null)));

        assertBanAlreadyExists(() -> service.create(command(EMPLOYER_URL, null, CompanyBanScope.GLOBAL, null, null)));

        verify(bans, never()).insert(any());
    }

    @Test
    void resumeBanOfTheSameEmployerIsAllowedNextToAGlobalOne() {
        stubResume();
        when(bans.findAllByEmployerId("3529"))
                .thenReturn(List.of(ban(UUID.randomUUID(), "3529", CompanyBanScope.GLOBAL, null)));

        service.create(command(EMPLOYER_URL, null, CompanyBanScope.RESUME, resumeId, null));

        assertEquals(CompanyBanScope.RESUME, captureInsert().scope());
    }

    @Test
    void resumeBanOfAnotherResumeDoesNotBlockThisOne() {
        stubResume();
        when(bans.findAllByEmployerId("3529"))
                .thenReturn(List.of(ban(UUID.randomUUID(), "3529", CompanyBanScope.RESUME, UUID.randomUUID())));

        service.create(command(EMPLOYER_URL, null, CompanyBanScope.RESUME, resumeId, null));

        verify(bans).insert(any());
    }

    @Test
    void concurrentInsertConflictIsTranslatedAndKeepsTheCause() {
        var cause = new DuplicateKeyException("uq_company_ban_global_employer");
        doThrow(cause).when(bans).insert(any());

        var exception = assertThrows(CompanyBanAlreadyExistsException.class,
                () -> service.create(command(EMPLOYER_URL, null, CompanyBanScope.GLOBAL, null, null)));

        assertSame(cause, exception.getCause());
    }

    @Test
    void everyChangeOfTheStopListRecalculatesTheEmployerState() {
        service.create(command(EMPLOYER_URL, null, CompanyBanScope.GLOBAL, null, null));

        verify(bans, atLeastOnce()).findAllByEmployerId("3529");
        verify(vacancies).banVacanciesOfEmployer(anyString(), any(), any(), any());
    }
}
