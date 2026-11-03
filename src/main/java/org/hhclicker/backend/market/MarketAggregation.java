package org.hhclicker.backend.market;

import org.hhclicker.backend.market.MarketAnalysisRepository.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/** Deterministic frequency counting of exact terms per vacancy and category. */
public final class MarketAggregation {
    private MarketAggregation() { }

    public record Evidence(Source source, Fact fact) { }
    public record Group(UUID id, String name, String kind, int vacancies, int companies,
                        List<String> spellings, List<Evidence> evidence) { }
    public record TitleGroup(String name, int vacancies, int companies, double weight) { }

    public static List<Group> aggregate(List<Source> sources, List<Fact> facts) {
        return aggregate(sources, facts, false);
    }

    public static List<Group> aggregateIgnoreCase(List<Source> sources, List<Fact> facts) {
        return aggregate(sources, facts, true);
    }

    /** Each additional vacancy from the same employer contributes exponentially less. */
    public static double companyDampedWeight(Group group) {
        Map<String, Long> vacanciesByCompany = group.evidence().stream()
                .collect(Collectors.groupingBy(item -> item.source().employerKey(), Collectors.counting()));
        return companyDampedWeight(vacanciesByCompany);
    }

    /** Vacancy titles are source data, not extracted facts; group only case variants. */
    public static List<TitleGroup> aggregateTitles(List<Source> sources) {
        Map<String, List<Source>> grouped = new LinkedHashMap<>();
        for (Source source : sources) {
            String title = source.title().strip();
            if (title.isEmpty()) continue;
            grouped.computeIfAbsent(title.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(source);
        }
        return grouped.values().stream().map(group -> {
            Map<String, Long> vacanciesByCompany = group.stream()
                    .collect(Collectors.groupingBy(Source::employerKey, Collectors.counting()));
            return new TitleGroup(group.getFirst().title().strip(), group.size(), vacanciesByCompany.size(),
                    companyDampedWeight(vacanciesByCompany));
        }).sorted(Comparator.comparingDouble(TitleGroup::weight).reversed()
                .thenComparing(Comparator.comparingInt(TitleGroup::vacancies).reversed())
                .thenComparing(TitleGroup::name)).toList();
    }

    private static double companyDampedWeight(Map<String, Long> vacanciesByCompany) {
        return vacanciesByCompany.values().stream()
                .mapToDouble(count -> (1.0 - Math.exp(-count)) / (1.0 - Math.exp(-1.0)))
                .sum();
    }

    private static List<Group> aggregate(List<Source> sources, List<Fact> facts, boolean ignoreCase) {
        Map<UUID, Source> byId = sources.stream().collect(Collectors.toMap(Source::id, source -> source));
        Map<String, LinkedHashMap<UUID, Evidence>> grouped = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> spellings = new LinkedHashMap<>();
        for (Fact fact : facts) {
            Source source = byId.get(fact.vacancyId());
            if (source == null || !Set.of("TECHNOLOGY", "SKILL", "DOMAIN", "RESPONSIBILITY", "ROLE").contains(fact.kind())) continue;
            String name = fact.name();
            if (name.isBlank()) continue;
            String key = fact.kind() + "|" + (ignoreCase ? name.toLowerCase(Locale.ROOT) : name);
            spellings.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(name);
            grouped.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(source.id(), new Evidence(source, fact));
        }
        List<Group> result = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            List<Evidence> evidence = List.copyOf(entry.getValue().values());
            long companies = evidence.stream().map(item -> item.source().employerKey()).distinct().count();
            String[] parts = entry.getKey().split("\\|", 2);
            UUID id = UUID.nameUUIDFromBytes(entry.getKey().getBytes(StandardCharsets.UTF_8));
            String displayName = ignoreCase ? evidence.getFirst().fact().name() : parts[1];
            result.add(new Group(id, displayName, parts[0], evidence.size(), (int) companies,
                    List.copyOf(spellings.get(entry.getKey())), evidence));
        }
        result.sort(Comparator.comparingInt(Group::vacancies).reversed()
                .thenComparing(Comparator.comparingInt(Group::companies).reversed())
                .thenComparing(Group::name));
        return List.copyOf(result);
    }

}
