package org.hhclicker.backend.market;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.hhclicker.backend.market.MarketAnalysisRepository.*;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MarketAnalysisRepositoryIT extends PostgresIntegrationTest {
    @Autowired private MarketAnalysisRepository repository;
    @Autowired private JdbcClient jdbc;
    private UUID resumeId;
    private UUID searchId;

    @BeforeEach
    void setUp() {
        var fixtures = new ApplicationDatabaseFixtures(jdbc);
        fixtures.clean();
        var workspace = fixtures.workspace();
        resumeId = workspace.resumeId();
        searchId = workspace.searchId();
    }

    @Test
    void sourcesIncludeVacancyWithTextEvenWithoutApplicationAndOnlyOnceAcrossSearches() {
        var fixtures = new ApplicationDatabaseFixtures(jdbc);
        UUID vacancyId = fixtures.vacancy(resumeId, "76543210", "NEW", true);
        fixtures.link(vacancyId, searchId, 0);
        UUID anotherSearch = fixtures.search(resumeId, "another-search");
        fixtures.link(vacancyId, anotherSearch, 0);
        UUID withoutText = fixtures.vacancy(resumeId, "76543211", "NEW", true);
        fixtures.link(withoutText, searchId, 1);
        jdbc.sql("update vacancy set plain_text = :text, text_loaded_at = now() where id = :id")
                .param("text", "Требуется Java и Spring").param("id", vacancyId).update();

        assertEquals(List.of(vacancyId), repository.sources(resumeId).stream().map(Source::id).toList());
        assertEquals(List.of(vacancyId), repository.sources(resumeId, searchId).stream().map(Source::id).toList());
        assertEquals("Требуется Java и Spring", repository.sources(resumeId).getFirst().text());
        assertEquals(0, fixtures.count("application_item"));
    }

    @Test
    void closedVacancyWithPreviouslySavedTextIsExcludedFromNewAnalyses() {
        var fixtures = new ApplicationDatabaseFixtures(jdbc);
        UUID active = fixtures.vacancy(resumeId, "76543220", "NEW", true);
        UUID closed = fixtures.vacancy(resumeId, "76543221", "CLOSED", false);
        fixtures.link(active, searchId, 0);
        fixtures.link(closed, searchId, 1);
        for (UUID id : List.of(active, closed)) {
            jdbc.sql("update vacancy set plain_text = 'Сохранённое описание', text_loaded_at = now() where id = :id")
                    .param("id", id).update();
        }

        assertEquals(List.of(active), repository.sources(resumeId, searchId).stream().map(Source::id).toList());
    }

    @Test
    void sourcesFilterByRelevanceWithoutChangingTheUnfilteredSelection() {
        var fixtures = new ApplicationDatabaseFixtures(jdbc);
        UUID target = fixtures.vacancy(resumeId, "76543212", "NEW", true);
        UUID noise = fixtures.vacancy(resumeId, "76543213", "NEW", true);
        UUID unrated = fixtures.vacancy(resumeId, "76543214", "NEW", true);
        List<UUID> ids = List.of(target, noise, unrated);
        for (int index = 0; index < ids.size(); index++) {
            UUID id = ids.get(index);
            fixtures.link(id, searchId, index);
            jdbc.sql("update vacancy set plain_text = :text, text_loaded_at = now() where id = :id")
                    .param("text", "Текст вакансии").param("id", id).update();
        }
        jdbc.sql("""
                update vacancy set relevance_category = :category, relevance_confidence = 'high',
                    relevance_reason = 'Синтетическая оценка', relevance_analyzed_at = now()
                where id = :id
                """).param("category", "TARGET").param("id", target).update();
        jdbc.sql("""
                update vacancy set relevance_category = :category, relevance_confidence = 'high',
                    relevance_reason = 'Синтетическая оценка', relevance_analyzed_at = now()
                where id = :id
                """).param("category", "NOISE").param("id", noise).update();

        assertEquals(3, repository.sources(resumeId, searchId).size());
        assertEquals(List.of(target), repository.sources(resumeId, searchId, MarketRelevanceCategory.TARGET)
                .stream().map(Source::id).toList());
        assertEquals(List.of(noise), repository.sources(resumeId, null, MarketRelevanceCategory.NOISE)
                .stream().map(Source::id).toList());
        assertTrue(repository.sources(resumeId, searchId, MarketRelevanceCategory.UNKNOWN).isEmpty());
    }

    @Test
    void snapshotFactsCoverageAndReportSurviveRepositoryReload() {
        UUID id = UUID.randomUUID();
        UUID vacancyId = UUID.randomUUID();
        Source source = new Source(vacancyId, "123", "Java-разработчик", "id:3529", "Компания",
                "Требуется Kubernetes", Instant.parse("2026-09-21T10:00:00Z"));
        Run started = new Run(id, resumeId, "Java", "Опыт с Kubernetes", Instant.now(),
                "RUNNING", "EXTRACT", 0, null, List.of(source), List.of(), List.of(), null, null);
        repository.insert(started);

        Run loaded = repository.find(id).orElseThrow();
        assertEquals(List.of(source), loaded.sources());
        Run finished = new Run(id, resumeId, loaded.resumeName(), loaded.resumeText(), loaded.createdAt(),
                "COMPLETED", "DONE", 1, null, loaded.sources(),
                List.of(new Fact(vacancyId, "Kubernetes", "SKILL", "REQUIRED", "Kubernetes")),
                List.of(new Coverage(UUID.randomUUID(), "EXPLICIT", "Kubernetes")),
                List.of(new Diagnostic("EXTRACT", vacancyId, "EVIDENCE_NOT_IN_SOURCE", "Docker", "Docker")),
                "# Отчёт", null, Instant.parse("2026-09-21T12:00:00Z"), 1);
        repository.save(finished);

        Run restored = repository.find(id).orElseThrow();
        assertEquals("COMPLETED", restored.status());
        assertEquals(finished.facts(), restored.facts());
        assertEquals(finished.coverage(), restored.coverage());
        assertEquals(finished.estimateStartedAt(), restored.estimateStartedAt());
        assertEquals(finished.estimateStartPosition(), restored.estimateStartPosition());
        assertEquals(finished.diagnostics(), restored.diagnostics());
        assertEquals("# Отчёт", restored.markdown());
        assertEquals(1, repository.list(resumeId).size());
    }

    @Test
    void selectedSearchMetadataSurvivesSnapshotReload() {
        Run started = new Run(UUID.randomUUID(), resumeId, "Java", "", Instant.now(),
                "RUNNING", "EXTRACT", 0, null, List.of(), List.of(), List.of(), List.of(),
                null, null, null, 0, searchId, "Синтетический поиск");
        repository.insert(started);

        Run loaded = repository.find(started.id()).orElseThrow();
        assertEquals(searchId, loaded.searchId());
        assertEquals("Синтетический поиск", loaded.searchName());
        assertEquals("Синтетический поиск", repository.searchName(resumeId, searchId).orElseThrow());
    }

    @Test
    void cancelledRunDoesNotBlockAnotherAnalysisForTheSameResume() {
        Run first = new Run(UUID.randomUUID(), resumeId, "Java", "", Instant.now(),
                "RUNNING", "EXTRACT", 0, null, List.of(), List.of(), List.of(), null, null);
        repository.insert(first);
        assertTrue(repository.hasOpen(resumeId, null));
        repository.save(new Run(first.id(), first.resumeId(), first.resumeName(), first.resumeText(), first.createdAt(),
                "CANCELLED", first.phase(), first.position(), null, first.sources(), first.facts(), first.coverage(),
                first.diagnostics(), null, null));
        assertFalse(repository.hasOpen(resumeId, null));

        Run second = new Run(UUID.randomUUID(), resumeId, "Java", "", Instant.now(),
                "RUNNING", "EXTRACT", 0, null, List.of(), List.of(), List.of(), null, null);
        repository.insert(second);
        assertEquals("RUNNING", repository.find(second.id()).orElseThrow().status());
    }
}
