package org.hhclicker.backend.vacancy;

import tools.jackson.databind.JsonNode;
import org.hhclicker.backend.vacancy.catalog.model.*;
import org.hhclicker.backend.vacancy.catalog.service.NormalizedSalary;
import org.hhclicker.backend.vacancy.catalog.service.NormalizedVacancy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Общие синтетические данные пакета {@code vacancy}: конструкторы моделей длинные,
 * поэтому тесты собирают их через эти фабрики и переопределяют только значимые поля.
 */
final class VacancyFixtures {

    static final Instant NOW = Instant.parse("2026-09-04T09:00:00Z");
    static final Instant EARLIER = Instant.parse("2026-09-01T08:00:00Z");
    static final String HH_VACANCY_ID = "118123456";
    static final String EMPLOYER_ID = "3529";

    private VacancyFixtures() {
    }

    static Vacancy stored(UUID id, UUID resumeId, String hhVacancyId, String employerId,
                          VacancyStatus status, CompanyBanScope banScope, boolean queued) {
        return new Vacancy(id, resumeId, hhVacancyId, "Java-разработчик", employerId, "Синтетическая компания",
                new VacancySalary(100_000L, 200_000L, SalaryCurrency.RUR, true), "Москва", "Удалённо", "1-3 года",
                EARLIER, "от 100 000 ₽", List.of("Быстрый ответ"), Boolean.FALSE,
                status, banScope, queued, EARLIER, EARLIER, EARLIER, EARLIER, null);
    }

    static Vacancy active(UUID id, UUID resumeId) {
        return stored(id, resumeId, HH_VACANCY_ID, EMPLOYER_ID, VacancyStatus.NEW, null, false);
    }

    static VacancyListing listing(Vacancy vacancy, UUID searchId) {
        return new VacancyListing(vacancy, searchId, false);
    }

    static Incoming incoming() {
        return new Incoming();
    }

    static NormalizedSalary salary(Long from, Long to, String currency, boolean gross) {
        return new NormalizedSalary(from, to, currency, gross);
    }

    /**
     * Мутабельный билдер входной вакансии: полей пятнадцать, а в тесте меняется одно-два.
     */
    static final class Incoming {
        private String hhVacancyId = HH_VACANCY_ID;
        private String title = "Java-разработчик";
        private String employerId = EMPLOYER_ID;
        private String companyName = "Синтетическая компания";
        private NormalizedSalary salary;
        private String location = "Москва";
        private String workFormat = "Удалённо";
        private String experience = "1-3 года";
        private Instant publishedAt = EARLIER;
        private String salaryText = "от 100 000 ₽";
        private List<String> labels = List.of("Быстрый ответ");
        private Boolean hasQuestionnaire = Boolean.FALSE;
        private boolean alreadyApplied;
        private boolean closed;
        private int position;
        private JsonNode enrichment;

        Incoming hhVacancyId(String value) {
            this.hhVacancyId = value;
            return this;
        }

        Incoming title(String value) {
            this.title = value;
            return this;
        }

        Incoming employerId(String value) {
            this.employerId = value;
            return this;
        }

        Incoming companyName(String value) {
            this.companyName = value;
            return this;
        }

        Incoming salary(NormalizedSalary value) {
            this.salary = value;
            return this;
        }

        Incoming location(String value) {
            this.location = value;
            return this;
        }

        Incoming workFormat(String value) {
            this.workFormat = value;
            return this;
        }

        Incoming experience(String value) {
            this.experience = value;
            return this;
        }

        Incoming publishedAt(Instant value) {
            this.publishedAt = value;
            return this;
        }

        Incoming salaryText(String value) {
            this.salaryText = value;
            return this;
        }

        Incoming labels(List<String> value) {
            this.labels = value;
            return this;
        }

        Incoming hasQuestionnaire(Boolean value) {
            this.hasQuestionnaire = value;
            return this;
        }

        Incoming alreadyApplied(boolean value) {
            this.alreadyApplied = value;
            return this;
        }

        Incoming closed(boolean value) {
            this.closed = value;
            return this;
        }

        Incoming position(int value) {
            this.position = value;
            return this;
        }

        Incoming enrichment(JsonNode value) {
            this.enrichment = value;
            return this;
        }

        NormalizedVacancy build() {
            return new NormalizedVacancy(hhVacancyId, title, employerId, companyName, salary, location,
                    workFormat, experience, publishedAt, salaryText, labels, hasQuestionnaire,
                    alreadyApplied, closed, position, enrichment);
        }
    }
}
