package org.hhclicker.backend.vacancy;

import org.mockito.ArgumentCaptor;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.resume.model.ResumeStatistics;
import org.hhclicker.backend.resume.service.ResumeProfile;
import org.hhclicker.backend.resume.service.ResumeService;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;
import org.hhclicker.backend.vacancy.catalog.repository.EmployerStateLock;
import org.hhclicker.backend.vacancy.stoplist.exception.CompanyBanAlreadyExistsException;
import org.hhclicker.backend.vacancy.stoplist.exception.InvalidCompanyBanDataException;
import org.hhclicker.backend.vacancy.stoplist.model.CompanyBan;
import org.hhclicker.backend.vacancy.stoplist.repository.CompanyBanRepository;
import org.hhclicker.backend.vacancy.stoplist.service.CompanyBanService;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Общая обвязка unit-тестов стоп-листа. Резюме — внешний модуль и подменяется моком;
 * каталог вакансий подменяется репозиторием, поскольку стоп-лист пишет состояние напрямую.
 */
abstract class CompanyBanTestSupport {

    protected static final String EMPLOYER_URL = "https://hh.ru/employer/3529";

    protected final CompanyBanRepository bans = mock(CompanyBanRepository.class);
    protected final VacancyRepository vacancies = mock(VacancyRepository.class);
    protected final ResumeService resumes = mock(ResumeService.class);
    protected final Clock clock = Clock.fixed(VacancyFixtures.NOW, ZoneOffset.UTC);
    protected final EmployerStateLock employerStateLock = mock(EmployerStateLock.class);
    protected final CompanyBanService service = new CompanyBanService(bans, vacancies, resumes, clock, employerStateLock);

    protected final UUID banId = UUID.randomUUID();
    protected final UUID resumeId = UUID.randomUUID();
    protected final UUID accountId = UUID.randomUUID();
    protected final UUID vacancyId = UUID.randomUUID();

    protected CompanyBan ban(UUID id, String employerId, CompanyBanScope scope, UUID owner) {
        return new CompanyBan(id, employerId, "https://hh.ru/employer/" + employerId, "Синтетическая компания",
                scope, owner, null, VacancyFixtures.EARLIER, VacancyFixtures.EARLIER);
    }

    protected void stubResume() {
        stubResume(resumeId, "Java-разработчик");
    }

    protected void stubResume(UUID id, String name) {
        when(resumes.findById(id)).thenReturn(new ResumeProfile(
                new HhResume(id, accountId, "abcdef0123456789abcd", name, ResumeAutomationSettings.defaults(),
                        VacancyFixtures.EARLIER, VacancyFixtures.EARLIER),
                "Основной аккаунт", ResumeStatistics.empty()));
    }

    protected CompanyBan captureInsert() {
        ArgumentCaptor<CompanyBan> captor = ArgumentCaptor.forClass(CompanyBan.class);
        verify(bans).insert(captor.capture());
        return captor.getValue();
    }

    protected CompanyBan captureScopeUpdate() {
        ArgumentCaptor<CompanyBan> captor = ArgumentCaptor.forClass(CompanyBan.class);
        verify(bans).updateScope(captor.capture());
        return captor.getValue();
    }

    protected void assertInvalidBanData(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(InvalidCompanyBanDataException.class, executable);
        assertEquals(ApplicationErrorCategory.VALIDATION, exception.category());
        assertEquals("INVALID_COMPANY_BAN_DATA", exception.machineCode());
    }

    protected void assertBanAlreadyExists(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(CompanyBanAlreadyExistsException.class, executable);
        assertEquals(ApplicationErrorCategory.CONFLICT, exception.category());
        assertEquals("COMPANY_BAN_ALREADY_EXISTS", exception.machineCode());
    }
}
