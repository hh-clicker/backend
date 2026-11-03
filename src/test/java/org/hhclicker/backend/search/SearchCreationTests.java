package org.hhclicker.backend.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.hhclicker.backend.search.exception.SearchAlreadyExistsException;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;
import org.hhclicker.backend.search.service.CreateSearchCommand;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Создание поиска: снимок полей, границы имени и лимита страниц, конфликты по sourceHash.
 */
class SearchCreationTests extends SearchServiceTestSupport {

    private static final String RAW_URL = "https://hh.ru/search/vacancy?text=java";

    private CreateSearchCommand command(String name, String sourceUrl, int pagesLimit) {
        return new CreateSearchCommand(name, sourceUrl, resumeId, pagesLimit);
    }

    @Test
    void createStoresActiveSearchWithZeroCountersAndServerTime() {
        stubResume();

        var profile = service.create(command("  Java в Москве  ", "  " + RAW_URL + "  ", 5));

        VacancySearch inserted = captureInsert();
        assertNotNull(inserted.id());
        assertEquals(resumeId, inserted.resumeId());
        assertEquals("Java в Москве", inserted.name());
        assertEquals(CANONICAL_URL, inserted.sourceUrl());
        assertTrue(inserted.sourceHash().matches("[0-9a-f]{64}"));
        assertEquals(5, inserted.pagesLimit());
        assertEquals(SearchStatus.ACTIVE, inserted.status());
        assertEquals(0, inserted.newVacancies());
        assertEquals(0, inserted.totalVacancies());
        assertNull(inserted.lastScannedAt());
        assertNull(inserted.lastError());
        assertEquals(NOW, inserted.createdAt());
        assertEquals(NOW, inserted.updatedAt());
        assertSame(inserted, profile.search());
        assertEquals(RESUME_NAME, profile.resumeName());
    }

    @Test
    void resumeIsRequiredAndCheckedBeforeAnythingElse() {
        assertInvalidSearchData(() -> service.create(new CreateSearchCommand("Java в Москве", RAW_URL, null, 5)));

        verifyNoInteractions(resumes, repository);
    }

    @Test
    void linkIsValidatedAgainstTheActualResumeNotTheRequest() {
        stubResume(resumeId, "ffffffffffffffffffff", RESUME_NAME);

        assertInvalidSearchData(() -> service.create(
                command("Java в Москве", "https://hh.ru/search/vacancy?resume=" + HH_RESUME_ID, 5)));

        verify(resumes).findById(resumeId);
        verify(repository, never()).insert(any());
    }

    @Test
    void everyNewSearchGetsItsOwnIdentifier() {
        stubResume();

        service.create(command("Java в Москве", RAW_URL, 5));
        service.create(command("Kotlin в Москве", "https://hh.ru/search/vacancy?text=kotlin", 5));

        ArgumentCaptor<VacancySearch> captor = ArgumentCaptor.forClass(VacancySearch.class);
        verify(repository, times(2)).insert(captor.capture());
        assertNotEquals(captor.getAllValues().get(0).id(), captor.getAllValues().get(1).id());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 120})
    void nameLengthBoundsAreInclusive(int length) {
        stubResume();

        service.create(command("и".repeat(length), RAW_URL, 5));

        assertEquals(length, captureInsert().name().length());
    }

    @Test
    void nameOutsideTheAllowedRangeIsRejected() {
        stubResume();

        assertInvalidSearchData(() -> service.create(command(null, RAW_URL, 5)));
        assertInvalidSearchData(() -> service.create(command("   ", RAW_URL, 5)));
        assertInvalidSearchData(() -> service.create(command("ab", RAW_URL, 5)));
        assertInvalidSearchData(() -> service.create(command("и".repeat(121), RAW_URL, 5)));

        verify(repository, never()).insert(any());
    }

    @Test
    void nameIsTrimmedBeforeItsLengthIsChecked() {
        stubResume();

        // Два значащих символа с пробелами по краям всё равно короче минимума.
        assertInvalidSearchData(() -> service.create(command("  ab  ", RAW_URL, 5)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    void pagesLimitBoundsAreInclusive(int pagesLimit) {
        stubResume();

        service.create(command("Java в Москве", RAW_URL, pagesLimit));

        assertEquals(pagesLimit, captureInsert().pagesLimit());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 21})
    void pagesLimitOutsideTheAllowedRangeIsRejected(int pagesLimit) {
        stubResume();

        assertInvalidSearchData(() -> service.create(command("Java в Москве", RAW_URL, pagesLimit)));

        verify(repository, never()).insert(any());
    }

    @Test
    void nameIsValidatedBeforeTheLink() {
        stubResume();

        var exception = assertThrows(ApplicationException.class,
                () -> service.create(command("   ", "ftp://example.com", 5)));

        assertTrue(exception.safeMessage().contains("Название"),
                "первым должно сообщаться нарушение названия, получено: " + exception.safeMessage());
    }

    @Test
    void searchAlreadySavedForThisResumeIsRejectedBeforeInsert() {
        stubResume();
        when(repository.findByResumeIdAndSourceHash(eq(resumeId), anyString()))
                .thenReturn(Optional.of(search(SearchStatus.ACTIVE)));

        assertSearchAlreadyExists(() -> service.create(command("Java в Москве", RAW_URL, 5)));

        verify(repository, never()).insert(any());
    }

    @Test
    void concurrentInsertConflictIsTranslatedAndKeepsTheCause() {
        stubResume();
        var cause = new DuplicateKeyException("uq_vacancy_search_resume_source");
        doThrow(cause).when(repository).insert(any());

        var exception = assertThrows(SearchAlreadyExistsException.class,
                () -> service.create(command("Java в Москве", RAW_URL, 5)));

        assertSame(cause, exception.getCause());
        assertEquals("VACANCY_SEARCH_ALREADY_EXISTS", exception.machineCode());
    }

    @Test
    void duplicateIsLookedUpWithinTheSelectedResumeOnly() {
        stubResume();
        clearInvocations(repository);

        service.create(command("Java в Москве", RAW_URL, 5));

        verify(repository).findByResumeIdAndSourceHash(eq(resumeId), anyString());
    }
}
