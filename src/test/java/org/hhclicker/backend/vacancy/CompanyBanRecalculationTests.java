package org.hhclicker.backend.vacancy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.hhclicker.backend.vacancy.catalog.exception.VacancyNotFoundException;
import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;
import org.hhclicker.backend.vacancy.catalog.model.Vacancy;
import org.hhclicker.backend.vacancy.catalog.model.VacancyListing;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;
import org.hhclicker.backend.vacancy.stoplist.exception.CompanyBanNotFoundException;
import org.hhclicker.backend.vacancy.stoplist.model.CompanyBan;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Блокировка через вакансию, смена области, снятие и пересчёт состояния вакансий работодателя.
 */
class CompanyBanRecalculationTests extends CompanyBanTestSupport {

    private final UUID otherResumeId = UUID.randomUUID();

    private void catalogHasVacancy(String employerId) {
        Vacancy vacancy = VacancyFixtures.stored(vacancyId, resumeId, "118123456", employerId,
                VacancyStatus.NEW, null, false);
        when(vacancies.findById(vacancyId)).thenReturn(Optional.of(vacancy));
    }

    @Test
    void banThroughAVacancyStoresAResumeScopedBanForItsOwner() {
        catalogHasVacancy("3529");

        service.banVacancyCompany(vacancyId, CompanyBanScope.RESUME);

        CompanyBan inserted = captureInsert();
        assertEquals("3529", inserted.employerId());
        assertEquals("https://hh.ru/employer/3529", inserted.employerUrl());
        assertEquals("Синтетическая компания", inserted.companyName());
        assertEquals(CompanyBanScope.RESUME, inserted.scope());
        assertEquals(resumeId, inserted.resumeId());
        assertEquals(vacancyId, inserted.sourceVacancyId());
        assertEquals(VacancyFixtures.NOW, inserted.createdAt());
    }

    @Test
    void banThroughAVacancyStoresAGlobalBanWithoutAResume() {
        catalogHasVacancy("3529");

        service.banVacancyCompany(vacancyId, CompanyBanScope.GLOBAL);

        assertNull(captureInsert().resumeId());
    }

    @Test
    void repeatedBanOfTheSameScopeIsIdempotentAndStillReturnsTheCurrentState() {
        catalogHasVacancy("3529");
        when(bans.findAllByEmployerId("3529"))
                .thenReturn(List.of(ban(banId, "3529", CompanyBanScope.RESUME, resumeId)));

        var changed = service.banVacancyCompany(vacancyId, CompanyBanScope.RESUME);

        verify(bans, never()).insert(any());
        verify(vacancies).banVacanciesOfEmployer(eq("3529"), eq(CompanyBanScope.RESUME), anyCollection(), any());
        assertTrue(changed.isEmpty());
    }

    @Test
    void banThroughAVacancyWithoutAnEmployerIdentifierIsRejected() {
        catalogHasVacancy(null);

        assertInvalidBanData(() -> service.banVacancyCompany(vacancyId, CompanyBanScope.GLOBAL));

        verify(bans, never()).insert(any());
    }

    @Test
    void banThroughAVacancyRequiresAScope() {
        catalogHasVacancy("3529");

        assertInvalidBanData(() -> service.banVacancyCompany(vacancyId, null));
    }

    @Test
    void banThroughAMissingVacancyIsReportedAsNotFound() {
        when(vacancies.findById(vacancyId)).thenReturn(Optional.empty());

        assertThrows(VacancyNotFoundException.class,
                () -> service.banVacancyCompany(vacancyId, CompanyBanScope.GLOBAL));
    }

    @Test
    void changedEmployerWhileWaitingForLockRequiresRefreshingTheVacancy() {
        Vacancy before = VacancyFixtures.stored(vacancyId, resumeId, "118123456", "3529",
                VacancyStatus.NEW, null, false);
        Vacancy after = VacancyFixtures.stored(vacancyId, resumeId, "118123456", "9999",
                VacancyStatus.NEW, null, false);
        when(vacancies.findById(vacancyId)).thenReturn(Optional.of(before), Optional.of(after));

        assertInvalidBanData(() -> service.banVacancyCompany(vacancyId, CompanyBanScope.GLOBAL));

        verify(bans, never()).insert(any());
    }

    @Test
    void changedVacanciesAreReturnedInTheOrderReportedByTheRecalculation() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID vanished = UUID.randomUUID();
        catalogHasVacancy("3529");
        when(vacancies.banVacanciesOfEmployer(anyString(), any(), any(), any()))
                .thenReturn(List.of(second, vanished, first));
        when(vacancies.findListingsByIds(any())).thenReturn(List.of(
                listingOf(first), listingOf(second)));

        List<VacancyListing> changed = service.banVacancyCompany(vacancyId, CompanyBanScope.GLOBAL);

        assertEquals(List.of(second, first), changed.stream().map(listing -> listing.vacancy().id()).toList(),
                "порядок задаёт пересчёт, исчезнувшие строки отбрасываются");
    }

    @Test
    void globalBanBlocksEveryResumeAndSkipsTheReleasePass() {
        catalogHasVacancy("3529");
        when(bans.findAllByEmployerId("3529")).thenReturn(List.of(
                ban(banId, "3529", CompanyBanScope.GLOBAL, null),
                ban(UUID.randomUUID(), "3529", CompanyBanScope.RESUME, resumeId)));

        service.banVacancyCompany(vacancyId, CompanyBanScope.GLOBAL);

        verify(vacancies).banVacanciesOfEmployer("3529", CompanyBanScope.GLOBAL, null, VacancyFixtures.NOW);
        verify(vacancies, never()).releaseVacanciesOfEmployer(anyString(), anyCollection(), any());
    }

    @Test
    void resumeBansBlockTheirOwnResumesAndReleaseEveryoneElse() {
        catalogHasVacancy("3529");
        when(bans.findAllByEmployerId("3529")).thenReturn(List.of(
                ban(banId, "3529", CompanyBanScope.RESUME, resumeId),
                ban(UUID.randomUUID(), "3529", CompanyBanScope.RESUME, otherResumeId)));

        service.banVacancyCompany(vacancyId, CompanyBanScope.RESUME);

        verify(vacancies).banVacanciesOfEmployer("3529", CompanyBanScope.RESUME,
                Set.of(resumeId, otherResumeId), VacancyFixtures.NOW);
        verify(vacancies).releaseVacanciesOfEmployer("3529", Set.of(resumeId, otherResumeId), VacancyFixtures.NOW);
    }

    @Test
    void removingTheLastBanReleasesEveryVacancyOfTheEmployer() {
        when(bans.findById(banId)).thenReturn(Optional.of(ban(banId, "3529", CompanyBanScope.GLOBAL, null)));
        when(bans.deleteById(banId)).thenReturn(true);

        service.delete(banId);

        verify(bans).deleteById(banId);
        verify(vacancies).banVacanciesOfEmployer("3529", CompanyBanScope.RESUME, Set.of(), VacancyFixtures.NOW);
        verify(vacancies).releaseVacanciesOfEmployer("3529", Set.of(), VacancyFixtures.NOW);
    }

    @Test
    void deleteOfAMissingBanIsReportedAsNotFound() {
        when(bans.findById(banId)).thenReturn(Optional.empty());

        assertThrows(CompanyBanNotFoundException.class, () -> service.delete(banId));

        verify(bans, never()).deleteById(any());
    }

    @Test
    void deleteThatRemovedNoRowIsReportedAsNotFound() {
        when(bans.findById(banId)).thenReturn(Optional.of(ban(banId, "3529", CompanyBanScope.GLOBAL, null)));
        when(bans.deleteById(banId)).thenReturn(false);

        assertThrows(CompanyBanNotFoundException.class, () -> service.delete(banId));

        verify(vacancies, never()).banVacanciesOfEmployer(anyString(), any(), any(), any());
    }

    @Test
    void scopeIsRaisedFromResumeToGlobal() {
        when(bans.findById(banId)).thenReturn(Optional.of(ban(banId, "3529", CompanyBanScope.RESUME, resumeId)));
        when(bans.updateScope(any())).thenReturn(true);

        var view = service.changeScope(banId, CompanyBanScope.GLOBAL, null);

        CompanyBan written = captureScopeUpdate();
        assertEquals(CompanyBanScope.GLOBAL, written.scope());
        assertNull(written.resumeId());
        assertEquals(banId, written.id());
        assertEquals("3529", written.employerId());
        assertEquals(VacancyFixtures.EARLIER, written.createdAt(), "дата создания блокировки не меняется");
        assertEquals(VacancyFixtures.NOW, written.updatedAt());
        assertEquals(CompanyBanScope.GLOBAL, view.ban().scope());
    }

    @Test
    void scopeIsLoweredFromGlobalToResume() {
        stubResume();
        when(bans.findById(banId)).thenReturn(Optional.of(ban(banId, "3529", CompanyBanScope.GLOBAL, null)));
        when(bans.updateScope(any())).thenReturn(true);

        service.changeScope(banId, CompanyBanScope.RESUME, resumeId);

        CompanyBan written = captureScopeUpdate();
        assertEquals(CompanyBanScope.RESUME, written.scope());
        assertEquals(resumeId, written.resumeId());
    }

    @Test
    void newScopeIsValidatedTheSameWayAsOnCreation() {
        when(bans.findById(banId)).thenReturn(Optional.of(ban(banId, "3529", CompanyBanScope.RESUME, resumeId)));

        assertInvalidBanData(() -> service.changeScope(banId, null, null));
        assertInvalidBanData(() -> service.changeScope(banId, CompanyBanScope.RESUME, null));
        assertInvalidBanData(() -> service.changeScope(banId, CompanyBanScope.GLOBAL, resumeId));

        verify(bans, never()).updateScope(any());
    }

    @Test
    void banThatAlreadyOccupiesTheRequestedScopeIsNotAConflictWithItself() {
        stubResume();
        CompanyBan existing = ban(banId, "3529", CompanyBanScope.RESUME, resumeId);
        when(bans.findById(banId)).thenReturn(Optional.of(existing));
        when(bans.findAllByEmployerId("3529")).thenReturn(List.of(existing));
        when(bans.updateScope(any())).thenReturn(true);

        service.changeScope(banId, CompanyBanScope.RESUME, resumeId);

        verify(bans).updateScope(any());
    }

    @Test
    void scopeAlreadyTakenByAnotherBanIsRejected() {
        when(bans.findById(banId)).thenReturn(Optional.of(ban(banId, "3529", CompanyBanScope.RESUME, resumeId)));
        when(bans.findAllByEmployerId("3529")).thenReturn(List.of(
                ban(banId, "3529", CompanyBanScope.RESUME, resumeId),
                ban(UUID.randomUUID(), "3529", CompanyBanScope.GLOBAL, null)));

        assertBanAlreadyExists(() -> service.changeScope(banId, CompanyBanScope.GLOBAL, null));

        verify(bans, never()).updateScope(any());
    }

    @Test
    void scopeChangeOfAMissingBanIsReportedAsNotFound() {
        when(bans.findById(banId)).thenReturn(Optional.empty());

        assertThrows(CompanyBanNotFoundException.class,
                () -> service.changeScope(banId, CompanyBanScope.GLOBAL, null));
    }

    @Test
    void scopeChangeThatChangedNoRowIsReportedAsNotFound() {
        when(bans.findById(banId)).thenReturn(Optional.of(ban(banId, "3529", CompanyBanScope.RESUME, resumeId)));
        when(bans.updateScope(any())).thenReturn(false);

        assertThrows(CompanyBanNotFoundException.class,
                () -> service.changeScope(banId, CompanyBanScope.GLOBAL, null));
    }

    @Test
    void concurrentScopeConflictIsTranslatedIntoAConflict() {
        when(bans.findById(banId)).thenReturn(Optional.of(ban(banId, "3529", CompanyBanScope.RESUME, resumeId)));
        when(bans.updateScope(any())).thenThrow(new DuplicateKeyException("uq_company_ban_global_employer"));

        assertBanAlreadyExists(() -> service.changeScope(banId, CompanyBanScope.GLOBAL, null));
    }

    @Test
    void scopeChangeAlsoRecalculatesTheEmployerState() {
        when(bans.findById(banId)).thenReturn(Optional.of(ban(banId, "3529", CompanyBanScope.RESUME, resumeId)));
        when(bans.updateScope(any())).thenReturn(true);

        service.changeScope(banId, CompanyBanScope.GLOBAL, null);

        verify(vacancies).banVacanciesOfEmployer(eq("3529"), any(), any(), eq(VacancyFixtures.NOW));
    }

    private VacancyListing listingOf(UUID id) {
        return VacancyFixtures.listing(VacancyFixtures.stored(id, resumeId, "118" + id.hashCode(),
                "3529", VacancyStatus.COMPANY_BANNED, CompanyBanScope.GLOBAL, false), null);
    }
}
