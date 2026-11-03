package org.hhclicker.backend.search;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.resume.exception.ResumeNotFoundException;
import org.hhclicker.backend.search.exception.SearchNotFoundException;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Чтение поисков и разрешение владельца: сервис не переупорядочивает выдачу репозитория.
 */
class SearchQueryTests extends SearchServiceTestSupport {

    private final UUID secondSearchId = UUID.randomUUID();

    @Test
    void searchesOfOneResumeKeepRepositoryOrderAndResolveResumeNameOnce() {
        stubResume();
        VacancySearch first = search(SearchStatus.ACTIVE);
        VacancySearch second = search(secondSearchId, resumeId, SearchStatus.PAUSED, CANONICAL_URL, "b".repeat(64));
        when(repository.findAllByResumeId(resumeId)).thenReturn(List.of(first, second));

        var profiles = service.findAll(resumeId);

        assertEquals(List.of(searchId, secondSearchId), profiles.stream().map(p -> p.search().id()).toList());
        assertEquals(List.of(RESUME_NAME, RESUME_NAME), profiles.stream().map(p -> p.resumeName()).toList());
        assertSame(first, profiles.getFirst().search());
        verify(resumes, times(1)).findById(resumeId);
        verify(repository, never()).findAll();
    }

    @Test
    void resumeIsValidatedBeforeAnySearchIsRead() {
        when(resumes.findById(resumeId)).thenThrow(new ResumeNotFoundException(resumeId));

        assertThrows(ResumeNotFoundException.class, () -> service.findAll(resumeId));

        verifyNoInteractions(repository);
    }

    @Test
    void resumeWithoutSearchesProducesAnEmptyList() {
        stubResume();
        when(repository.findAllByResumeId(resumeId)).thenReturn(List.of());

        assertTrue(service.findAll(resumeId).isEmpty());
    }

    @Test
    void searchesOfAllResumesResolveTheirOwnResumeName() {
        UUID otherResumeId = UUID.randomUUID();
        stubResume();
        stubResume(otherResumeId, "0123456789abcdef0123", "Backend-инженер");
        when(repository.findAll()).thenReturn(List.of(
                search(SearchStatus.ACTIVE),
                search(secondSearchId, otherResumeId, SearchStatus.ACTIVE, CANONICAL_URL, "b".repeat(64))));

        var profiles = service.findAll(null);

        assertEquals(List.of(RESUME_NAME, "Backend-инженер"), profiles.stream().map(p -> p.resumeName()).toList());
        verify(repository, never()).findAllByResumeId(any());
    }

    @Test
    void singleSearchIsReturnedWithItsResumeName() {
        stubResume();
        VacancySearch stored = search(SearchStatus.ACTIVE);
        when(repository.findById(searchId)).thenReturn(Optional.of(stored));

        var profile = service.findById(searchId);

        assertSame(stored, profile.search());
        assertEquals(RESUME_NAME, profile.resumeName());
    }

    @Test
    void missingSearchIsReportedAsNotFound() {
        when(repository.findById(searchId)).thenReturn(Optional.empty());

        var exception = assertThrows(SearchNotFoundException.class, () -> service.findById(searchId));

        assertEquals(ApplicationErrorCategory.NOT_FOUND, exception.category());
        assertEquals("VACANCY_SEARCH_NOT_FOUND", exception.machineCode());
        verifyNoInteractions(resumes);
    }

    @Test
    void ownerAccountOfTheSearchIsResolvedThroughItsResume() {
        stubResume();
        when(repository.findById(searchId)).thenReturn(Optional.of(search(SearchStatus.ACTIVE)));

        assertEquals(accountId, service.accountIdForSearch(searchId));
    }

    @Test
    void ownerAccountOfAMissingSearchIsReportedAsNotFound() {
        when(repository.findById(searchId)).thenReturn(Optional.empty());

        assertThrows(SearchNotFoundException.class, () -> service.accountIdForSearch(searchId));

        verifyNoInteractions(resumes);
    }

    @Test
    void ownerAccountIsUnavailableWhenTheResumeIsGone() {
        when(repository.findById(searchId)).thenReturn(Optional.of(search(SearchStatus.ACTIVE)));
        when(resumes.findById(resumeId)).thenThrow(new ResumeNotFoundException(resumeId));

        assertThrows(ResumeNotFoundException.class, () -> service.accountIdForSearch(searchId));
    }
}
