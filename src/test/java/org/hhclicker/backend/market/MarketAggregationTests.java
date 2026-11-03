package org.hhclicker.backend.market;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.market.MarketAnalysisRepository.Fact;
import org.hhclicker.backend.market.MarketAnalysisRepository.Source;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MarketAggregationTests {
    private Source source(String employer, String hhId) {
        return new Source(UUID.randomUUID(), hhId, "Java-разработчик", employer, employer,
                "Опыт с Kubernetes и k8s", Instant.parse("2026-09-21T12:00:00Z"));
    }

    @Test
    void exactSpellingsStaySeparateAndRepeatsInOneVacancyCountOnce() {
        Source first = source("id:1", "1");
        Source second = source("id:2", "2");
        var groups = MarketAggregation.aggregate(List.of(first, second), List.of(
                new Fact(first.id(), "k8s", "TECHNOLOGY", "MENTIONED", "k8s"),
                new Fact(first.id(), "Kubernetes", "TECHNOLOGY", "MENTIONED", "Kubernetes"),
                new Fact(first.id(), "Kubernetes", "TECHNOLOGY", "MENTIONED", "Kubernetes"),
                new Fact(second.id(), "Kubernetes", "TECHNOLOGY", "MENTIONED", "Kubernetes")));
        assertEquals(2, groups.size());
        var kubernetes = groups.stream().filter(group -> group.name().equals("Kubernetes")).findFirst().orElseThrow();
        assertEquals(2, kubernetes.vacancies());
        assertEquals(2, kubernetes.companies());
        assertEquals(List.of("Kubernetes"), kubernetes.spellings());
        assertEquals(1, groups.stream().filter(group -> group.name().equals("k8s")).findFirst().orElseThrow().vacancies());
    }

    @Test
    void exactFrequencyIsSeparateFromCompanyCount() {
        List<Source> sources = new ArrayList<>();
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Source source = source("id:one", String.valueOf(i));
            sources.add(source);
            facts.add(new Fact(source.id(), "Postgres", "TECHNOLOGY", "MENTIONED", "Postgres"));
        }
        Source other = source("id:other", "other");
        sources.add(other);
        facts.add(new Fact(other.id(), "PostgreSQL", "TECHNOLOGY", "MENTIONED", "PostgreSQL"));
        facts.add(new Fact(other.id(), "SQL", "TECHNOLOGY", "MENTIONED", "SQL"));
        var groups = MarketAggregation.aggregate(sources, facts);
        var postgres = groups.stream().filter(group -> group.name().equals("Postgres")).findFirst().orElseThrow();
        assertEquals(10, postgres.vacancies());
        assertEquals(1, postgres.companies());
        assertTrue(groups.stream().anyMatch(group -> group.name().equals("PostgreSQL")));
        assertTrue(groups.stream().anyMatch(group -> group.name().equals("SQL")));
    }

    @Test
    void ordinaryFrequencyIgnoresCaseButKeepsSynonymsSeparate() {
        Source first = source("id:1", "1");
        Source second = source("id:2", "2");
        var groups = MarketAggregation.aggregateIgnoreCase(List.of(first, second), List.of(
                new Fact(first.id(), "Kubernetes", "TECHNOLOGY", "MENTIONED", "Kubernetes"),
                new Fact(first.id(), "kubernetes", "TECHNOLOGY", "MENTIONED", "kubernetes"),
                new Fact(second.id(), "kubernetes", "TECHNOLOGY", "MENTIONED", "kubernetes"),
                new Fact(second.id(), "k8s", "TECHNOLOGY", "MENTIONED", "k8s")));
        assertEquals(2, groups.size());
        var kubernetes = groups.stream().filter(group -> group.name().equals("Kubernetes")).findFirst().orElseThrow();
        assertEquals(2, kubernetes.vacancies());
        assertEquals(2, kubernetes.companies());
        assertEquals(List.of("Kubernetes", "kubernetes"), kubernetes.spellings());
        assertTrue(groups.stream().anyMatch(group -> group.name().equals("k8s")));
    }
}
