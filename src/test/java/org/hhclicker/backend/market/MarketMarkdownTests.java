package org.hhclicker.backend.market;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.market.MarketAnalysisRepository.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MarketMarkdownTests {
    @Test
    void reportContainsEvidenceButNotFullResumeSnapshot() {
        Source source = new Source(UUID.randomUUID(), "123", "Java-разработчик", "id:1", "Компания",
                "Нужен Kubernetes", Instant.now());
        var fact = new Fact(source.id(), "Kubernetes", "TECHNOLOGY", "MENTIONED", "Kubernetes");
        var groups = MarketAggregation.aggregate(List.of(source), List.of(fact));
        String privateResume = "Мой личный черновик резюме и контакты";
        Run run = new Run(UUID.randomUUID(), UUID.randomUUID(), "Java", privateResume, Instant.now(),
                "COMPLETED", "DONE", 1, null, List.of(source), List.of(fact),
                List.of(),
                List.of(new Diagnostic("EXTRACT", source.id(), "EVIDENCE_NOT_IN_SOURCE", "Docker", "Docker")),
                null, null);

        String markdown = MarketMarkdown.render(run, groups);

        assertTrue(markdown.contains("HH 123"));
        assertTrue(markdown.contains("Kubernetes"));
        assertTrue(markdown.contains("### Технологии"));
        assertFalse(markdown.contains(privateResume));
        assertTrue(markdown.contains("нет оценки резюме"));
        assertTrue(markdown.contains("EVIDENCE_NOT_IN_SOURCE"));
        assertTrue(markdown.contains("цитата модели"));
        assertTrue(markdown.contains("## Исходные тексты всех вакансий"));
        assertTrue(markdown.contains("Нужен Kubernetes"));
    }

    @Test
    void ordinaryReportContainsFrequencyTablesAndVacancyTitlesWithoutDescriptions() {
        Source source = new Source(UUID.randomUUID(), "123", "Java-разработчик", "id:1", "Компания",
                "Нужен Kubernetes. Служебная подробность текста вакансии.", Instant.now());
        var fact = new Fact(source.id(), "Kubernetes", "TECHNOLOGY", "MENTIONED", "Нужен Kubernetes");
        Run run = new Run(UUID.randomUUID(), UUID.randomUUID(), "Java", "личный текст резюме", Instant.now(),
                "COMPLETED", "DONE", 1, null, List.of(source), List.of(fact), List.of(),
                List.of(new Diagnostic("EXTRACT", source.id(), "EVIDENCE_NOT_IN_SOURCE", "Docker", "Docker")),
                null, null);

        String markdown = MarketMarkdown.renderForAgent(run,
                MarketAggregation.aggregateIgnoreCase(run.sources(), run.facts()));

        assertTrue(markdown.contains("| Термин | Вакансий | Компаний | Вес* |"));
        assertTrue(markdown.contains("| Kubernetes | 1 | 1 | 1.00 |"));
        assertTrue(markdown.contains("*Вес — сумма вкладов компаний"));
        assertTrue(markdown.contains("## Названия вакансий в выборке"));
        assertTrue(markdown.contains("| Название | Вакансий | Компаний | Вес* |"));
        assertTrue(markdown.contains("| Java-разработчик | 1 | 1 | 1.00 |"));
        assertFalse(markdown.contains("Нужен Kubernetes"));
        assertFalse(markdown.contains("HH 123"));
        assertFalse(markdown.contains("Служебная подробность текста вакансии"));
        assertFalse(markdown.contains("EVIDENCE_NOT_IN_SOURCE"));
        assertFalse(markdown.contains("личный текст резюме"));
    }

    @Test
    void ordinaryReportMergesCaseVariantsWithoutDuplicatingFrequency() {
        Source first = new Source(UUID.randomUUID(), "123", "Java", "id:1", "Компания",
                "PostgreSQL", Instant.now());
        Source second = new Source(UUID.randomUUID(), "456", "Java", "id:2", "Другая компания",
                "postgresql", Instant.now());
        var facts = List.of(
                new Fact(first.id(), "PostgreSQL", "TECHNOLOGY", "MENTIONED", "PostgreSQL"),
                new Fact(second.id(), "postgresql", "TECHNOLOGY", "MENTIONED", "postgresql"));
        Run run = new Run(UUID.randomUUID(), UUID.randomUUID(), "Java", "", Instant.now(),
                "COMPLETED", "DONE", 2, null, List.of(first, second), facts, List.of(), List.of(), null, null);

        String markdown = MarketMarkdown.renderForAgent(run,
                MarketAggregation.aggregateIgnoreCase(run.sources(), run.facts()));

        assertTrue(markdown.contains("| PostgreSQL | 2 | 2 | 2.00 |"));
        assertFalse(markdown.contains("| postgresql |"));
        assertFalse(markdown.contains("HH 123"));
        assertFalse(markdown.contains("HH 456"));
    }

    @Test
    void ordinaryReportCountsRepeatedVacancyTitlesWithoutFullText() {
        Source first = new Source(UUID.randomUUID(), "1", "Java-разработчик", "id:1", "Компания",
                "Скрытый текст первой вакансии", Instant.now());
        Source second = new Source(UUID.randomUUID(), "2", "Java-разработчик", "id:2", "Другая компания",
                "Скрытый текст второй вакансии", Instant.now());
        Run run = new Run(UUID.randomUUID(), UUID.randomUUID(), "Java", "", Instant.now(),
                "COMPLETED", "DONE", 2, null, List.of(first, second), List.of(), List.of(), List.of(),
                null, null, null, 0, UUID.randomUUID(), "Java remote");

        String markdown = MarketMarkdown.renderForAgent(run, List.of());

        assertTrue(markdown.contains("- Поиск: Java remote"));
        assertTrue(markdown.contains("| Java-разработчик | 2 | 2 | 2.00 |"));
        assertEquals(1, markdown.split("\\| Java-разработчик \\|", -1).length - 1);
        assertFalse(markdown.contains("Скрытый текст первой вакансии"));
        assertFalse(markdown.contains("Скрытый текст второй вакансии"));
    }

    @Test
    void ordinaryReportGroupsTitleCaseVariantsAndDampsRepeatedCompany() {
        Source first = new Source(UUID.randomUUID(), "1", "Java Developer", "same", "Компания", "Первый текст", Instant.now());
        Source second = new Source(UUID.randomUUID(), "2", "java developer", "same", "Компания", "Второй текст", Instant.now());
        Source third = new Source(UUID.randomUUID(), "3", "Backend Developer", "other", "Другая компания", "Третий текст", Instant.now());
        Run run = new Run(UUID.randomUUID(), UUID.randomUUID(), "Java", "", Instant.now(),
                "COMPLETED", "DONE", 3, null, List.of(first, second, third), List.of(), List.of(), List.of(), null, null);

        String markdown = MarketMarkdown.renderForAgent(run, List.of());

        assertTrue(markdown.contains("| Java Developer | 2 | 1 | 1.37 |"));
        assertTrue(markdown.contains("| Backend Developer | 1 | 1 | 1.00 |"));
        assertTrue(markdown.indexOf("| Java Developer |") < markdown.indexOf("| Backend Developer |"));
    }

    @Test
    void ordinaryReportSortsByCompanyDampedWeight() {
        Source first = new Source(UUID.randomUUID(), "1", "Java", "same", "Одна компания", "Java", Instant.now());
        Source second = new Source(UUID.randomUUID(), "2", "Java", "same", "Одна компания", "Java", Instant.now());
        Source third = new Source(UUID.randomUUID(), "3", "Java", "same", "Одна компания", "Java", Instant.now());
        Source fourth = new Source(UUID.randomUUID(), "4", "Java", "other", "Другая компания", "Java", Instant.now());
        var facts = List.of(
                new Fact(first.id(), "Первый", "SKILL", "MENTIONED", "Первый"),
                new Fact(second.id(), "Первый", "SKILL", "MENTIONED", "Первый"),
                new Fact(third.id(), "Первый", "SKILL", "MENTIONED", "Первый"),
                new Fact(first.id(), "Второй", "SKILL", "MENTIONED", "Второй"),
                new Fact(fourth.id(), "Второй", "SKILL", "MENTIONED", "Второй"));
        Run run = new Run(UUID.randomUUID(), UUID.randomUUID(), "Java", "", Instant.now(),
                "COMPLETED", "DONE", 4, null, List.of(first, second, third, fourth), facts,
                List.of(), List.of(), null, null);

        String markdown = MarketMarkdown.renderForAgent(run,
                MarketAggregation.aggregateIgnoreCase(run.sources(), run.facts()));

        assertTrue(markdown.contains("| Первый | 3 | 1 | 1.50 |"));
        assertTrue(markdown.contains("| Второй | 2 | 2 | 2.00 |"));
        assertTrue(markdown.indexOf("| Второй |") < markdown.indexOf("| Первый |"));
    }
}
