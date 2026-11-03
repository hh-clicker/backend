package org.hhclicker.backend.vacancy;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.resume.exception.ResumeNotFoundException;
import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;
import org.hhclicker.backend.vacancy.catalog.model.EmployerBannedCount;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;
import org.hhclicker.backend.vacancy.stoplist.model.CompanyBan;
import org.hhclicker.backend.vacancy.stoplist.repository.CompanyBanRepository;
import org.hhclicker.backend.vacancy.stoplist.service.CompanyBanStatisticsService;
import org.hhclicker.backend.vacancy.stoplist.service.CompanyBanView;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * Чтение стоп-листа, вычисляемые поля представления и подбор применимых блокировок.
 */
class CompanyBanQueryTests extends CompanyBanTestSupport {

    private final UUID otherResumeId = UUID.randomUUID();
    private final UUID sourceVacancyId = UUID.randomUUID();

    @Test
    void withoutAResumeTheWholeStopListIsReturned() {
        when(bans.findAll()).thenReturn(List.of(ban(banId, "3529", CompanyBanScope.GLOBAL, null)));

        var views = service.findAll(null);

        assertEquals(1, views.size());
        verify(bans).findAll();
        verify(bans, never()).findAllVisibleForResume(any());
    }

    @Test
    void withAResumeOnlyItsOwnAndGlobalBansAreRequested() {
        stubResume();
        when(bans.findAllVisibleForResume(resumeId))
                .thenReturn(List.of(ban(banId, "3529", CompanyBanScope.GLOBAL, null)));

        service.findAll(resumeId);

        verify(resumes).findById(resumeId);
        verify(bans).findAllVisibleForResume(resumeId);
        verify(bans, never()).findAll();
    }

    @Test
    void unknownResumeIsReportedBeforeTheStopListIsRead() {
        when(resumes.findById(resumeId)).thenThrow(new ResumeNotFoundException(resumeId));

        assertThrows(ResumeNotFoundException.class, () -> service.findAll(resumeId));

        verify(bans, never()).findAllVisibleForResume(any());
    }

    @Test
    void emptyStopListIsReturnedWithoutQueryingTheCatalog() {
        when(bans.findAll()).thenReturn(List.of());

        assertTrue(service.findAll(null).isEmpty());

        verify(vacancies, never()).countBannedVacanciesByEmployerAndResume(anyCollection());
        verify(vacancies, never()).findAllByIds(anyCollection());
    }

    @Test
    void globalBanCountsHiddenVacanciesOfEveryResume() {
        when(bans.findAll()).thenReturn(List.of(ban(banId, "3529", CompanyBanScope.GLOBAL, null)));
        when(vacancies.countBannedVacanciesByEmployerAndResume(Set.of("3529"))).thenReturn(List.of(
                new EmployerBannedCount("3529", resumeId, 2),
                new EmployerBannedCount("3529", otherResumeId, 3)));

        CompanyBanView view = service.findAll(null).getFirst();

        assertEquals(5, view.hiddenVacanciesCount(), "глобальная блокировка суммирует все резюме");
        assertNull(view.resumeName());
    }

    @Test
    void resumeBanCountsOnlyHiddenVacanciesOfItsOwnResume() {
        stubResume();
        when(bans.findAll()).thenReturn(List.of(ban(banId, "3529", CompanyBanScope.RESUME, resumeId)));
        when(vacancies.countBannedVacanciesByEmployerAndResume(Set.of("3529"))).thenReturn(List.of(
                new EmployerBannedCount("3529", resumeId, 2),
                new EmployerBannedCount("3529", otherResumeId, 3)));

        CompanyBanView view = service.findAll(null).getFirst();

        assertEquals(2, view.hiddenVacanciesCount());
        assertEquals("Java-разработчик", view.resumeName());
    }

    @Test
    void employerWithoutBlockedVacanciesReportsZero() {
        when(bans.findAll()).thenReturn(List.of(ban(banId, "3529", CompanyBanScope.GLOBAL, null)));

        assertEquals(0, service.findAll(null).getFirst().hiddenVacanciesCount());
    }

    @Test
    void sourceVacancyTitleIsResolvedFromTheCatalog() {
        CompanyBan withSource = new CompanyBan(banId, "3529", "https://hh.ru/employer/3529",
                "Синтетическая компания", CompanyBanScope.GLOBAL, null, sourceVacancyId,
                VacancyFixtures.EARLIER, VacancyFixtures.EARLIER);
        when(bans.findAll()).thenReturn(List.of(withSource));
        when(vacancies.findAllByIds(Set.of(sourceVacancyId))).thenReturn(List.of(VacancyFixtures.stored(
                sourceVacancyId, resumeId, "118123456", "3529", VacancyStatus.COMPANY_BANNED,
                CompanyBanScope.GLOBAL, false)));

        CompanyBanView view = service.findAll(null).getFirst();

        assertEquals("Java-разработчик", view.sourceVacancyTitle());
    }

    @Test
    void banWithoutASourceVacancyExposesNoTitle() {
        when(bans.findAll()).thenReturn(List.of(ban(banId, "3529", CompanyBanScope.GLOBAL, null)));

        assertNull(service.findAll(null).getFirst().sourceVacancyTitle());
    }

    @Test
    void resumeNameIsResolvedOncePerResume() {
        stubResume();
        when(bans.findAll()).thenReturn(List.of(
                ban(banId, "3529", CompanyBanScope.RESUME, resumeId),
                ban(UUID.randomUUID(), "9999", CompanyBanScope.RESUME, resumeId)));

        var views = service.findAll(null);

        assertEquals(2, views.size());
        verify(resumes, times(1)).findById(resumeId);
    }

    @Test
    void globalBanOutranksAResumeBanOfTheSameEmployer() {
        when(bans.findApplicable(Set.of("3529", "9999"), resumeId)).thenReturn(List.of(
                ban(UUID.randomUUID(), "3529", CompanyBanScope.RESUME, resumeId),
                ban(UUID.randomUUID(), "3529", CompanyBanScope.GLOBAL, null),
                ban(UUID.randomUUID(), "9999", CompanyBanScope.RESUME, resumeId)));

        Map<String, CompanyBanScope> scopes = service.findApplicableScopes(Set.of("3529", "9999"), resumeId);

        assertEquals(CompanyBanScope.GLOBAL, scopes.get("3529"));
        assertEquals(CompanyBanScope.RESUME, scopes.get("9999"));
        assertEquals(2, scopes.size());
    }

    @Test
    void employerWithoutAnyApplicableBanIsAbsentFromTheResult() {
        when(bans.findApplicable(Set.of("3529"), resumeId)).thenReturn(List.of());

        assertTrue(service.findApplicableScopes(Set.of("3529"), resumeId).isEmpty());
    }

    @Test
    void blockedEmployerCountOfTheResumeIsDelegatedWithoutChange() {
        CompanyBanRepository repository = mock(CompanyBanRepository.class);
        var statistics = new CompanyBanStatisticsService(repository);
        when(repository.countDistinctVisibleEmployers(resumeId)).thenReturn(4L);

        assertEquals(4L, statistics.countVisibleForResume(resumeId));

        verify(repository).countDistinctVisibleEmployers(resumeId);
        verifyNoMoreInteractions(repository);
    }
}
