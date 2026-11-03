package org.hhclicker.backend.vacancy;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.account.exception.AccountNotFoundException;
import org.hhclicker.backend.resume.exception.ResumeNotFoundException;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.resume.model.ResumeStatistics;
import org.hhclicker.backend.resume.service.ResumeProfile;
import org.hhclicker.backend.resume.service.ResumeService;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.vacancy.catalog.exception.InvalidVacancyDataException;
import org.hhclicker.backend.vacancy.catalog.exception.VacancyNotFoundException;
import org.hhclicker.backend.vacancy.catalog.model.Vacancy;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;
import org.hhclicker.backend.vacancy.catalog.service.VacancyCatalogService;
import org.hhclicker.backend.vacancy.favorites.model.VacancyFavorites;
import org.hhclicker.backend.vacancy.favorites.repository.VacancyFavoritesRepository;
import org.hhclicker.backend.vacancy.favorites.service.VacancyFavoritesService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Локальные звёздочки вакансий и работодателей: аккаунт определяется по вакансии и её резюме.
 */
class VacancyFavoritesServiceTests {

    private final VacancyFavoritesRepository repository = mock(VacancyFavoritesRepository.class);
    private final VacancyCatalogService catalog = mock(VacancyCatalogService.class);
    private final ResumeService resumes = mock(ResumeService.class);
    private final VacancyFavoritesService service = new VacancyFavoritesService(repository, catalog, resumes);

    private final UUID accountId = UUID.randomUUID();
    private final UUID resumeId = UUID.randomUUID();
    private final UUID vacancyId = UUID.randomUUID();

    private final VacancyFavorites favorites =
            new VacancyFavorites(accountId, List.of("118123456"), List.of("3529"));

    private void catalogHas(String employerId) {
        Vacancy vacancy = VacancyFixtures.stored(vacancyId, resumeId, VacancyFixtures.HH_VACANCY_ID,
                employerId, VacancyStatus.NEW, null, false);
        when(catalog.findById(vacancyId)).thenReturn(VacancyFixtures.listing(vacancy, null));
        when(resumes.findById(resumeId)).thenReturn(new ResumeProfile(
                new HhResume(resumeId, accountId, "abcdef0123456789abcd", "Java-разработчик",
                        ResumeAutomationSettings.defaults(), VacancyFixtures.EARLIER, VacancyFixtures.EARLIER),
                "Основной аккаунт", ResumeStatistics.empty()));
        when(repository.findByAccount(accountId)).thenReturn(favorites);
    }

    @Test
    void readingFavoritesValidatesTheAccountThroughThePublicResumeService() {
        when(repository.findByAccount(accountId)).thenReturn(favorites);

        assertSame(favorites, service.find(accountId));

        var order = inOrder(resumes, repository);
        order.verify(resumes).findAll(accountId);
        order.verify(repository).findByAccount(accountId);
    }

    @Test
    void unknownAccountIsReportedBeforeAnyFavoriteIsRead() {
        when(resumes.findAll(accountId)).thenThrow(new AccountNotFoundException(accountId));

        assertThrows(AccountNotFoundException.class, () -> service.find(accountId));

        verify(repository, never()).findByAccount(any());
    }

    @Test
    void vacancyFavoriteIsStoredUnderTheAccountOfItsResume() {
        catalogHas(VacancyFixtures.EMPLOYER_ID);

        assertSame(favorites, service.set(vacancyId, false, true));

        verify(repository).set(accountId, VacancyFixtures.HH_VACANCY_ID, false, true);
    }

    @Test
    void vacancyFavoriteIsRemovedWithTheSameCall() {
        catalogHas(VacancyFixtures.EMPLOYER_ID);

        service.set(vacancyId, false, false);

        verify(repository).set(accountId, VacancyFixtures.HH_VACANCY_ID, false, false);
    }

    @Test
    void employerFavoriteUsesTheEmployerIdentifierOfTheVacancy() {
        catalogHas(VacancyFixtures.EMPLOYER_ID);

        service.set(vacancyId, true, true);

        verify(repository).set(accountId, VacancyFixtures.EMPLOYER_ID, true, true);
    }

    @Test
    void employerFavoriteIsImpossibleWithoutAnEmployerIdentifier() {
        catalogHas(null);

        var exception = assertThrows(InvalidVacancyDataException.class, () -> service.set(vacancyId, true, true));

        assertEquals(ApplicationErrorCategory.VALIDATION, exception.category());
        assertEquals("INVALID_VACANCY_DATA", exception.machineCode());
        verify(repository, never()).set(any(), anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void vacancyFavoriteStillWorksWithoutAnEmployerIdentifier() {
        catalogHas(null);

        service.set(vacancyId, false, true);

        verify(repository).set(accountId, VacancyFixtures.HH_VACANCY_ID, false, true);
    }

    @Test
    void missingVacancyIsReportedAsNotFound() {
        when(catalog.findById(vacancyId)).thenThrow(new VacancyNotFoundException(vacancyId));

        assertThrows(VacancyNotFoundException.class, () -> service.set(vacancyId, false, true));

        verify(repository, never()).set(any(), anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void missingResumeOfTheVacancyIsReportedAsNotFound() {
        catalogHas(VacancyFixtures.EMPLOYER_ID);
        when(resumes.findById(resumeId)).thenThrow(new ResumeNotFoundException(resumeId));

        assertThrows(ResumeNotFoundException.class, () -> service.set(vacancyId, false, true));

        verify(repository, never()).set(any(), anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void favoritesAreReReadAfterEveryChange() {
        catalogHas(VacancyFixtures.EMPLOYER_ID);

        service.set(vacancyId, true, true);

        var order = inOrder(repository);
        order.verify(repository).set(accountId, VacancyFixtures.EMPLOYER_ID, true, true);
        order.verify(repository).findByAccount(accountId);
    }
}
