package org.hhclicker.backend.resume;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.resume.exception.InvalidResumeDataException;
import org.hhclicker.backend.resume.exception.ResumeNotFoundException;
import org.hhclicker.backend.resume.model.HhResume;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeTextUpdateTests extends ResumeServiceTestSupport {
    @Test
    void refreshUpdatesTextAndReturnsTheLatestResumeWithoutWritingSettings() {
        var before = resume();
        var after = new HhResume(before.id(), before.accountId(), before.hhResumeId(), before.name(), before.settings(),
                before.createdAt(), NOW, "Новый текст", NOW);
        when(repository.findById(resumeId)).thenReturn(Optional.of(before), Optional.of(after));
        when(repository.updateText(resumeId, "Новый текст", NOW)).thenReturn(true);
        stubAccount("Аккаунт"); stubStatistics();
        var result = service.updateText(resumeId, "  Новый текст  ");
        assertEquals("Новый текст", result.resume().plainText());
        assertEquals(NOW, result.resume().textLoadedAt());
        assertEquals(before.settings(), result.resume().settings());
        assertEquals(before.createdAt(), result.resume().createdAt());
        verify(repository).updateText(resumeId, "Новый текст", NOW);
        verify(repository, never()).updateSettings(any());
        verify(repository, never()).insertIfAbsent(any());
    }

    @Test
    void emptyAndOversizedTextNeverReachPersistence() {
        var before = resume();
        when(repository.findById(resumeId)).thenReturn(Optional.of(before));
        for (String text : new String[] { null, " \n ", "x".repeat(100001) })
            assertThrows(InvalidResumeDataException.class, () -> service.updateText(resumeId, text));
        verify(repository, never()).updateText(any(), any(), any());
        assertEquals(PLAIN_TEXT, before.plainText());
    }

    @Test
    void concurrentlyDeletedResumeIsNotRecreated() {
        var before = resume();
        when(repository.findById(resumeId)).thenReturn(Optional.of(before));
        when(repository.updateText(resumeId, "Новый текст", NOW)).thenReturn(false);
        assertThrows(ResumeNotFoundException.class, () -> service.updateText(resumeId, "Новый текст"));
        verify(repository, never()).insertIfAbsent(any());
    }
}
