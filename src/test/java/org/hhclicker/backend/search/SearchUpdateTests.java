package org.hhclicker.backend.search;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.hhclicker.backend.search.exception.SearchAlreadyExistsException;
import org.hhclicker.backend.search.exception.SearchNotFoundException;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;
import org.hhclicker.backend.search.service.UpdateSearchCommand;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Редактирование поиска: блокирующее чтение, сохранение накопленных данных и конфликты.
 */
class SearchUpdateTests extends SearchServiceTestSupport {

    private static final String NEW_URL = "https://hh.ru/search/vacancy?text=kotlin";
    private static final String NEW_CANONICAL_URL =
            "https://hh.ru/search/vacancy?items_on_page=100&page=0&text=kotlin";

    private UpdateSearchCommand command(String name, String sourceUrl, int pagesLimit) {
        return new UpdateSearchCommand(name, sourceUrl, pagesLimit);
    }

    private UpdateSearchCommand validCommand() {
        return command("Kotlin в Москве", NEW_URL, 12);
    }

    @Test
    void definitionIsReadUnderLockAndRewrittenWithoutLosingCollectedData() {
        stubResume();
        VacancySearch existing = search(SearchStatus.PAUSED);
        stubLocked(existing);
        when(repository.updateDefinition(any())).thenReturn(true);

        var profile = service.update(searchId, validCommand());

        VacancySearch written = captureDefinitionUpdate();
        assertEquals("Kotlin в Москве", written.name());
        assertEquals(NEW_CANONICAL_URL, written.sourceUrl());
        assertNotEquals(existing.sourceHash(), written.sourceHash());
        assertEquals(12, written.pagesLimit());
        assertEquals(SearchStatus.ACTIVE, written.status(), "после редактирования поиск снова активен");
        assertNull(written.lastError());
        assertEquals(searchId, written.id());
        assertEquals(resumeId, written.resumeId(), "выбранное резюме поиска изменить нельзя");
        assertEquals(existing.newVacancies(), written.newVacancies());
        assertEquals(existing.totalVacancies(), written.totalVacancies());
        assertEquals(LAST_SCANNED_AT, written.lastScannedAt());
        assertEquals(CREATED_AT, written.createdAt());
        assertEquals(NOW, written.updatedAt());
        assertSame(written, profile.search());
        assertEquals(RESUME_NAME, profile.resumeName());
        verify(repository).findByIdForUpdate(searchId);
        verify(repository, never()).findById(any());
    }

    @Test
    void editingAFailedSearchClearsTheStoredError() {
        stubResume();
        VacancySearch failed = search(SearchStatus.ERROR);
        stubLocked(failed);
        when(repository.updateDefinition(any())).thenReturn(true);

        service.update(searchId, validCommand());

        assertNull(captureDefinitionUpdate().lastError());
    }

    @Test
    void searchBeingScannedCannotBeEdited() {
        stubLocked(search(SearchStatus.SCANNING));

        assertInvalidSearchData(() -> service.update(searchId, validCommand()));

        verify(repository, never()).updateDefinition(any());
    }

    @Test
    void missingSearchIsReportedAsNotFound() {
        when(repository.findByIdForUpdate(searchId)).thenReturn(Optional.empty());

        assertThrows(SearchNotFoundException.class, () -> service.update(searchId, validCommand()));

        verify(repository, never()).updateDefinition(any());
    }

    @Test
    void linkIsValidatedAgainstTheResumeOfTheStoredSearch() {
        stubResume(resumeId, "ffffffffffffffffffff", RESUME_NAME);
        stubLocked(search(SearchStatus.ACTIVE));

        assertInvalidSearchData(() -> service.update(searchId,
                command("Kotlin в Москве", "https://hh.ru/search/vacancy?resume=" + HH_RESUME_ID, 12)));

        verify(resumes).findById(resumeId);
    }

    @Test
    void anotherSearchOfTheSameResumeWithTheSameLinkIsAConflict() {
        stubResume();
        stubLocked(search(SearchStatus.ACTIVE));
        when(repository.findByResumeIdAndSourceHash(eq(resumeId), anyString())).thenReturn(Optional.of(
                search(UUID.randomUUID(), resumeId, SearchStatus.ACTIVE, NEW_CANONICAL_URL, "b".repeat(64))));

        assertSearchAlreadyExists(() -> service.update(searchId, validCommand()));

        verify(repository, never()).updateDefinition(any());
    }

    @Test
    void findingTheEditedSearchItselfIsNotAConflict() {
        stubResume();
        VacancySearch existing = search(SearchStatus.ACTIVE);
        stubLocked(existing);
        when(repository.findByResumeIdAndSourceHash(eq(resumeId), anyString())).thenReturn(Optional.of(existing));
        when(repository.updateDefinition(any())).thenReturn(true);

        service.update(searchId, validCommand());

        verify(repository).updateDefinition(any());
    }

    @Test
    void duplicateIsLookedUpOnlyWithinTheOwningResume() {
        stubResume();
        stubLocked(search(SearchStatus.ACTIVE));
        when(repository.updateDefinition(any())).thenReturn(true);

        service.update(searchId, validCommand());

        // Такой же sourceHash у поиска другого резюме не виден этому запросу и конфликтом не является.
        verify(repository).findByResumeIdAndSourceHash(eq(resumeId), anyString());
    }

    @Test
    void updateThatChangedNoRowIsReportedAsNotFound() {
        stubResume();
        stubLocked(search(SearchStatus.ACTIVE));
        when(repository.updateDefinition(any())).thenReturn(false);

        assertThrows(SearchNotFoundException.class, () -> service.update(searchId, validCommand()));
    }

    @Test
    void conflictRaisedByTheDatabaseDuringUpdateIsTranslatedAndKeepsTheCause() {
        stubResume();
        stubLocked(search(SearchStatus.ACTIVE));
        var cause = new DuplicateKeyException("uq_vacancy_search_resume_source");
        when(repository.updateDefinition(any())).thenThrow(cause);

        var exception = assertThrows(SearchAlreadyExistsException.class, () -> service.update(searchId, validCommand()));

        assertSame(cause, exception.getCause());
        assertEquals("VACANCY_SEARCH_ALREADY_EXISTS", exception.machineCode());
    }

    @Test
    void nameLinkAndPagesLimitFollowTheSameRulesAsOnCreation() {
        stubResume();
        stubLocked(search(SearchStatus.ACTIVE));

        assertInvalidSearchData(() -> service.update(searchId, command(null, NEW_URL, 12)));
        assertInvalidSearchData(() -> service.update(searchId, command("ab", NEW_URL, 12)));
        assertInvalidSearchData(() -> service.update(searchId, command("и".repeat(121), NEW_URL, 12)));
        assertInvalidSearchData(() -> service.update(searchId, command("Kotlin в Москве", "ftp://hh.ru", 12)));
        assertInvalidSearchData(() -> service.update(searchId, command("Kotlin в Москве", NEW_URL, 0)));
        assertInvalidSearchData(() -> service.update(searchId, command("Kotlin в Москве", NEW_URL, 21)));

        verify(repository, never()).updateDefinition(any());
    }
}
