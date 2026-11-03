package org.hhclicker.backend.market;

import org.hhclicker.backend.market.MarketAnalysisRepository.*;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.util.*;
import java.util.stream.Collectors;

final class MarketExtractionResults {
    record Accepted(List<Fact> facts, List<Diagnostic> diagnostics) { }

    static Accepted validate(List<Source> batch, MarketGenerationClient.ExtractionJob job) {
        if (!"done".equals(job.status()) || job.result() == null || job.result().items() == null
                || job.result().items().size() != batch.size()) throw invalid();
        Map<UUID, Source> byId = batch.stream().collect(Collectors.toMap(Source::id, item -> item));
        Set<UUID> seen = new HashSet<>();
        List<Fact> facts = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (job.result().diagnostics() != null) for (var item : job.result().diagnostics()) {
            if (item != null && item.reason() != null) diagnostics.add(new Diagnostic("EXTRACT", item.id(),
                    item.reason(), item.name(), item.evidence()));
        }
        for (var item : job.result().items()) {
            Source source = item == null ? null : byId.get(item.id());
            if (source == null || !seen.add(item.id()) || item.facts() == null) {
                diagnostics.add(new Diagnostic("EXTRACT", item == null ? null : item.id(), "ITEM_SHAPE", "", ""));
                continue;
            }
            for (var fact : item.facts()) {
                if (fact == null || fact.name() == null || fact.name().isBlank() || !fact.name().equals(fact.name().strip())
                        || fact.name().length() > 120 || fact.kind() == null
                        || !Set.of("TECHNOLOGY", "SKILL", "DOMAIN").contains(fact.kind())
                        || fact.evidence() == null || fact.evidence().isBlank() || fact.evidence().length() > 300) {
                    diagnostics.add(new Diagnostic("EXTRACT", source.id(), "FACT_SHAPE",
                            fact == null ? "" : fact.name(), fact == null ? "" : fact.evidence()));
                    continue;
                }
                String text = source.text().substring(0, Math.min(source.text().length(), 30_000)) + "\n" + source.title();
                if (!text.contains(fact.evidence())) {
                    diagnostics.add(new Diagnostic("EXTRACT", source.id(), "EVIDENCE_NOT_IN_SOURCE", fact.name(), fact.evidence()));
                } else if (!fact.evidence().contains(fact.name())) {
                    diagnostics.add(new Diagnostic("EXTRACT", source.id(), "TERM_NOT_IN_EVIDENCE", fact.name(), fact.evidence()));
                } else facts.add(new Fact(source.id(), fact.name(), fact.kind(), "MENTIONED", fact.evidence()));
            }
        }
        return new Accepted(List.copyOf(facts), List.copyOf(diagnostics));
    }

    private static ApplicationException invalid() {
        return new ApplicationException(ApplicationErrorCategory.INTERNAL, "MARKET_AGENT_INVALID_RESPONSE",
                "Некорректный ответ Generation Agent.");
    }
}
