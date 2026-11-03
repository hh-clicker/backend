package org.hhclicker.backend.search;

import org.mockito.ArgumentCaptor;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.resume.model.ResumeStatistics;
import org.hhclicker.backend.resume.service.ResumeProfile;
import org.hhclicker.backend.resume.service.ResumeService;
import org.hhclicker.backend.search.exception.InvalidSearchDataException;
import org.hhclicker.backend.search.exception.SearchAlreadyExistsException;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;
import org.hhclicker.backend.search.repository.SearchRepository;
import org.hhclicker.backend.search.service.SearchService;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.vacancy.catalog.service.VacancyIngestionService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Общая обвязка unit-тестов {@code SearchService}.
 * <p>
 * Часы фиксированы; приём вакансий подменяется моком — его внутренняя логика относится к пакету
 * {@code vacancy}. Настоящий SQL проверяется отдельно в {@code SearchRepositoryIT}.
 */
abstract class SearchServiceTestSupport {

    protected static final Instant NOW = Instant.parse("2026-09-04T09:00:00Z");
    protected static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00Z");
    protected static final Instant LAST_SCANNED_AT = Instant.parse("2026-09-02T12:00:00Z");
    protected static final String HH_RESUME_ID = "abcdef0123456789abcd";
    protected static final String RESUME_NAME = "Java-разработчик";
    protected static final String SOURCE_HASH = "a".repeat(64);

    /**
     * Каноническая форма ссылки: только https, только hh.ru, параметры отсортированы.
     */
    protected static final String CANONICAL_URL =
            "https://hh.ru/search/vacancy?items_on_page=100&page=0&text=java";

    protected final SearchRepository repository = mock(SearchRepository.class);
    protected final ResumeService resumes = mock(ResumeService.class);
    protected final VacancyIngestionService ingestion = mock(VacancyIngestionService.class);
    protected final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    protected final SearchService service = new SearchService(repository, resumes, ingestion, clock);

    protected final UUID searchId = UUID.randomUUID();
    protected final UUID resumeId = UUID.randomUUID();
    protected final UUID accountId = UUID.randomUUID();

    protected void stubResume() {
        stubResume(resumeId, HH_RESUME_ID, RESUME_NAME);
    }

    protected void stubResume(UUID id, String hhResumeId, String name) {
        HhResume resume = new HhResume(id, accountId, hhResumeId, name,
                ResumeAutomationSettings.defaults(), CREATED_AT, CREATED_AT);
        when(resumes.findById(id)).thenReturn(new ResumeProfile(resume, "Основной аккаунт", ResumeStatistics.empty()));
    }

    protected VacancySearch search(SearchStatus status) {
        return search(searchId, resumeId, status, CANONICAL_URL, SOURCE_HASH);
    }

    protected VacancySearch search(UUID id, UUID owner, SearchStatus status, String sourceUrl, String sourceHash) {
        return new VacancySearch(id, owner, "Java в Москве", sourceUrl, sourceHash, 5, status,
                7, 42, LAST_SCANNED_AT, status == SearchStatus.ERROR ? "Прошлая ошибка" : null, CREATED_AT, CREATED_AT);
    }

    /**
     * Строка читается сервисом только через блокирующее чтение.
     */
    protected void stubLocked(VacancySearch stored) {
        when(repository.findByIdForUpdate(stored.id())).thenReturn(Optional.of(stored));
    }

    protected VacancySearch captureInsert() {
        return capture(captor -> verify(repository).insert(captor.capture()));
    }

    protected VacancySearch captureStatusUpdate() {
        return capture(captor -> verify(repository).updateStatus(captor.capture()));
    }

    protected VacancySearch captureDefinitionUpdate() {
        return capture(captor -> verify(repository).updateDefinition(captor.capture()));
    }

    protected VacancySearch captureIngestionUpdate() {
        return capture(captor -> verify(repository).updateAfterIngestion(captor.capture()));
    }

    private VacancySearch capture(java.util.function.Consumer<ArgumentCaptor<VacancySearch>> verification) {
        ArgumentCaptor<VacancySearch> captor = ArgumentCaptor.forClass(VacancySearch.class);
        verification.accept(captor);
        return captor.getValue();
    }

    protected void assertInvalidSearchData(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(InvalidSearchDataException.class, executable);
        assertEquals(ApplicationErrorCategory.VALIDATION, exception.category());
        assertEquals("INVALID_SEARCH_DATA", exception.machineCode());
    }

    protected void assertSearchAlreadyExists(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(SearchAlreadyExistsException.class, executable);
        assertEquals(ApplicationErrorCategory.CONFLICT, exception.category());
        assertEquals("VACANCY_SEARCH_ALREADY_EXISTS", exception.machineCode());
    }
}
