package org.hhclicker.backend.shared.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.hhclicker.backend.shared.config.MigrationIdentityCompatibility.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationIdentityCompatibilityTests {

    @Test
    void emptyHistoryUsesNeutralAuthors() {
        var authors = resolve(List.of());

        assertEquals(DEFAULT_AUTHOR, authors.get(SCHEMA_AUTHOR));
        assertEquals(DEFAULT_AUTHOR, authors.get(NOTIFICATIONS_AUTHOR));
    }

    @Test
    void existingAuthorsAreResolvedIndependentlyWithoutEmbeddingAnAliasList() {
        var authors = resolve(List.of(
                entry("001-create-schema", "previous-schema-maintainer"),
                entry("019-relevance-notifications", "previous-notifications-maintainer")));

        assertEquals("previous-schema-maintainer", authors.get(SCHEMA_AUTHOR));
        assertEquals("previous-notifications-maintainer", authors.get(NOTIFICATIONS_AUTHOR));
    }

    @Test
    void interruptedNeutralInstallationCanResumeFromAContiguousPrefix() {
        var authors = resolve(List.of(
                entry("001-create-schema", DEFAULT_AUTHOR),
                entry("002-create-hh-account", DEFAULT_AUTHOR)));

        assertEquals(DEFAULT_AUTHOR, authors.get(SCHEMA_AUTHOR));
        assertEquals(DEFAULT_AUTHOR, authors.get(NOTIFICATIONS_AUTHOR));
    }

    @Test
    void incompletePreviousInstallationIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> resolve(List.of(entry("001-create-schema", "previous-schema-maintainer"))));
    }

    @Test
    void aGapInNeutralHistoryIsNotTreatedAsAnInterruptedInstallation() {
        assertThrows(IllegalStateException.class, () -> resolve(List.of(
                entry("001-create-schema", DEFAULT_AUTHOR),
                entry("003-create-hh-resume", DEFAULT_AUTHOR))));
    }

    @Test
    void laterChangesWithoutTheNotificationsBaselineAreRejected() {
        assertThrows(IllegalStateException.class, () -> resolve(List.of(
                entry("001-create-schema", DEFAULT_AUTHOR),
                entry("020-vacancy-enrichment", DEFAULT_AUTHOR))));
    }

    @Test
    void duplicateBaselineIdentitiesAreRejectedRatherThanPickingAnAuthor() {
        assertThrows(IllegalStateException.class, () -> resolve(List.of(
                entry("001-create-schema", DEFAULT_AUTHOR),
                entry("019-relevance-notifications", "previous-maintainer"),
                entry("019-relevance-notifications", DEFAULT_AUTHOR))));
    }

    @Test
    void aDifferentBaselinePathIsRejected() {
        assertThrows(IllegalStateException.class, () -> resolve(List.of(
                new HistoryEntry("001-create-schema", DEFAULT_AUTHOR,
                        "another/changelog/001-create-schema.yaml", "EXECUTED"),
                entry("019-relevance-notifications", DEFAULT_AUTHOR))));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void missingOrBlankAuthorIsRejected(String author) {
        assertThrows(IllegalStateException.class, () -> resolve(List.of(
                entry("001-create-schema", author), entry("019-relevance-notifications", DEFAULT_AUTHOR))));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"MARK_RAN", "FAILED"})
    void unexecutedBaselineIsRejected(String executionType) {
        assertThrows(IllegalStateException.class, () -> resolve(List.of(
                entry("001-create-schema", DEFAULT_AUTHOR),
                new HistoryEntry("019-relevance-notifications", DEFAULT_AUTHOR,
                        "db/changelog/changes/019-relevance-notifications.yaml", executionType))));
    }

    private static HistoryEntry entry(String id, String author) {
        return new HistoryEntry(id, author, "db/changelog/changes/" + id + ".yaml", "EXECUTED");
    }
}
