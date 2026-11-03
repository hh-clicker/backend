package org.hhclicker.backend.vacancy.catalog.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.vacancy.catalog.exception.InvalidVacancyDataException;
import org.hhclicker.backend.vacancy.catalog.model.*;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;
import org.hhclicker.backend.vacancy.catalog.repository.EmployerStateLock;
import org.hhclicker.backend.vacancy.catalog.repository.VacancySearchLinkRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Публичный прикладной сервис приёма нормализованных результатов поиска.
 * <p>
 * Сервис не зависит от модуля {@code search}: поиск и резюме приходят идентификаторами,
 * а владелец поиска обязан подставить {@code resumeId} самостоятельно.
 */
@Service
public class VacancyIngestionService {

    private static final int MAXIMUM_HH_VACANCY_ID_LENGTH = 64;
    private static final int MAXIMUM_TITLE_LENGTH = 500;
    private static final int MAXIMUM_EMPLOYER_ID_LENGTH = 64;
    private static final int MAXIMUM_COMPANY_NAME_LENGTH = 300;
    private static final int MAXIMUM_LOCATION_LENGTH = 300;
    private static final int MAXIMUM_WORK_FORMAT_LENGTH = 120;
    private static final int MAXIMUM_EXPERIENCE_LENGTH = 120;
    private static final int MAXIMUM_LABEL_LENGTH = 200;
    private static final int MAXIMUM_LABELS = 100;
    private static final int MAXIMUM_SALARY_TEXT_LENGTH = 500;
    private static final int MAXIMUM_BATCH_SIZE = 2000;
    private static final int MAXIMUM_ENRICHMENT_LENGTH = 20_000;

    private final VacancyRepository vacancyRepository;
    private final VacancySearchLinkRepository vacancySearchLinkRepository;
    private final EmployerBanLookup employerBanLookup;
    private final Clock clock;
    private final EmployerStateLock employerStateLock;

    public VacancyIngestionService(
            VacancyRepository vacancyRepository,
            VacancySearchLinkRepository vacancySearchLinkRepository,
            EmployerBanLookup employerBanLookup,
            Clock clock,
            EmployerStateLock employerStateLock
    ) {
        this.vacancyRepository = vacancyRepository;
        this.vacancySearchLinkRepository = vacancySearchLinkRepository;
        this.employerBanLookup = employerBanLookup;
        this.clock = clock;
        this.employerStateLock = employerStateLock;
    }

    /**
     * Принимает весь batch в одной транзакции: частичный результат не сохраняется.
     */
    @Transactional
    public VacancyIngestionResult ingest(IngestSearchResultsCommand command) {
        if (command.searchId() == null) {
            throw new InvalidVacancyDataException("Не указан поиск, в котором найдены вакансии.");
        }
        if (command.resumeId() == null) {
            throw new InvalidVacancyDataException("Не указано резюме, которому принадлежат вакансии.");
        }
        List<NormalizedVacancy> incoming = command.vacancies() == null ? List.of() : command.vacancies();
        if (incoming.size() > MAXIMUM_BATCH_SIZE) {
            throw new InvalidVacancyDataException(
                    "За один раз можно принять не больше %d вакансий.".formatted(MAXIMUM_BATCH_SIZE)
            );
        }

        List<NormalizedVacancy> normalized = normalizeBatch(incoming);
        employerStateLock.acquireIngestion(command.resumeId());
        Set<String> hhIds = normalized.stream().map(NormalizedVacancy::hhVacancyId).collect(Collectors.toSet());
        Set<String> employerIds = new HashSet<>(vacancyRepository
                .findEmployerIdsByResumeIdAndHhVacancyIds(command.resumeId(), hhIds));
        normalized.stream().map(NormalizedVacancy::employerId).filter(Objects::nonNull).forEach(employerIds::add);
        employerStateLock.acquire(employerIds);
        Map<String, Vacancy> existing = vacancyRepository
                .findAllByResumeIdAndHhVacancyIds(command.resumeId(), hhIds)
                .stream()
                .collect(Collectors.toMap(Vacancy::hhVacancyId, Function.identity()));
        Map<String, CompanyBanScope> bans = employerBanLookup.findApplicableScopes(
                normalized.stream().map(NormalizedVacancy::employerId).filter(java.util.Objects::nonNull).collect(Collectors.toSet()),
                command.resumeId()
        );

        long previousSearchVacancies = vacancySearchLinkRepository.countVacanciesBySearchId(command.searchId());
        Instant now = clock.instant();
        int created = 0;
        int updated = 0;
        for (NormalizedVacancy source : normalized) {
            Vacancy stored = existing.get(source.hhVacancyId());
            UUID vacancyId;
            if (stored == null) {
                vacancyId = create(command.resumeId(), source, source.employerId() == null ? null : bans.get(source.employerId()), now);
                if (vacancyId == null) {
                    // строку успела создать параллельная транзакция: обновляем её вместо вставки
                    stored = reload(command.resumeId(), source.hhVacancyId());
                    vacancyId = update(stored, source, source.employerId() == null ? null : bans.get(source.employerId()), now);
                    updated++;
                } else {
                    created++;
                }
            } else {
                vacancyId = update(stored, source, source.employerId() == null ? null : bans.get(source.employerId()), now);
                updated++;
            }
            vacancySearchLinkRepository.upsert(new VacancySearchLink(
                    UUID.randomUUID(), vacancyId, command.searchId(), source.position(), now, now
            ));
        }

        long totalSearchVacancies = vacancySearchLinkRepository.countVacanciesBySearchId(command.searchId());
        return new VacancyIngestionResult(
                command.searchId(),
                normalized.size(),
                created,
                updated,
                totalSearchVacancies - previousSearchVacancies,
                totalSearchVacancies
        );
    }

    private UUID create(UUID resumeId, NormalizedVacancy source, CompanyBanScope banScope, Instant now) {
        VacancyStatus status;
        CompanyBanScope appliedScope = null;
        if (source.alreadyApplied()) {
            status = VacancyStatus.ALREADY_APPLIED;
        } else if (source.closed()) {
            status = VacancyStatus.CLOSED;
        } else if (banScope != null) {
            status = VacancyStatus.COMPANY_BANNED;
            appliedScope = banScope;
        } else {
            status = VacancyStatus.NEW;
        }

        Vacancy vacancy = new Vacancy(
                UUID.randomUUID(),
                resumeId,
                source.hhVacancyId(),
                source.title(),
                source.employerId(),
                source.companyName(),
                toSalary(source.salary()),
                source.location(),
                source.workFormat(),
                source.experience(),
                source.publishedAt(),
                source.salaryText(),
                source.labels(),
                source.hasQuestionnaire(),
                status,
                appliedScope,
                false,
                now,
                now,
                now,
                now,
                null,
                source.enrichment()
        );
        return vacancyRepository.insertIfAbsent(vacancy)
                .map(insertedId -> {
                    vacancyRepository.replaceLabels(insertedId, vacancy.labels());
                    return insertedId;
                })
                .orElse(null);
    }

    private Vacancy reload(UUID resumeId, String hhVacancyId) {
        return vacancyRepository
                .findAllByResumeIdAndHhVacancyIds(resumeId, List.of(hhVacancyId))
                .stream()
                .findFirst()
                .orElseThrow(() -> new InvalidVacancyDataException(
                        "Не удалось сохранить вакансию %s, повторите приём результатов."
                                .formatted(hhVacancyId)
                ));
    }

    /**
     * Повторное обнаружение обновляет внешние данные HH и {@code last_seen_at}, но не сбрасывает
     * пользовательский выбор. Терминальные признаки HH при этом сильнее пользовательского статуса,
     * а смена работодателя заново решает, применима ли блокировка.
     */
    private UUID update(Vacancy stored, NormalizedVacancy source, CompanyBanScope banScope, Instant now) {
        Vacancy refreshed = new Vacancy(
                stored.id(),
                stored.resumeId(),
                stored.hhVacancyId(),
                source.title(),
                source.employerId() == null ? stored.employerId() : source.employerId(),
                source.companyName(),
                toSalary(source.salary()),
                source.location(),
                source.workFormat(),
                source.experience(),
                source.publishedAt() == null ? stored.publishedAt() : source.publishedAt(),
                source.salaryText(),
                source.labels(),
                source.hasQuestionnaire() == null ? stored.hasQuestionnaire() : source.hasQuestionnaire(),
                stored.status(),
                stored.banScope(),
                stored.queuedForApplication(),
                stored.firstSeenAt(),
                now,
                stored.createdAt(),
                now,
                stored.relevance(),
                source.enrichment() == null ? stored.enrichment() : source.enrichment()
        );
        vacancyRepository.updateExternalData(refreshed);
        if (!stored.labels().equals(source.labels())) {
            vacancyRepository.replaceLabels(stored.id(), source.labels());
        }

        if (source.alreadyApplied()) {
            vacancyRepository.updateState(
                    stored.id(),
                    EnumSet.complementOf(EnumSet.of(VacancyStatus.ALREADY_APPLIED)),
                    VacancyStatus.ALREADY_APPLIED, null, false, now
            );
        } else if (source.closed()) {
            vacancyRepository.updateState(
                    stored.id(),
                    EnumSet.of(VacancyStatus.NEW, VacancyStatus.USER_SKIPPED, VacancyStatus.COMPANY_BANNED),
                    VacancyStatus.CLOSED, null, false, now
            );
        } else if (source.employerId() != null && (banScope != null
                || !java.util.Objects.equals(stored.employerId(), source.employerId()))) {
            reapplyBan(stored, banScope, now);
        }
        return stored.id();
    }

    /**
     * Вакансию можно перепривязать к другому работодателю, поэтому применимость блокировки
     * решается заново: терминальные статусы при этом не затрагиваются.
     */
    private void reapplyBan(Vacancy stored, CompanyBanScope banScope, Instant now) {
        if (banScope != null) {
            vacancyRepository.updateState(
                    stored.id(),
                    EnumSet.of(VacancyStatus.NEW, VacancyStatus.USER_SKIPPED, VacancyStatus.COMPANY_BANNED),
                    VacancyStatus.COMPANY_BANNED, banScope, false, now
            );
        } else if (stored.status() == VacancyStatus.COMPANY_BANNED) {
            vacancyRepository.updateState(
                    stored.id(),
                    EnumSet.of(VacancyStatus.COMPANY_BANNED),
                    VacancyStatus.NEW, null, false, now
            );
        }
    }

    private List<NormalizedVacancy> normalizeBatch(List<NormalizedVacancy> incoming) {
        Set<String> seenHhVacancyIds = new HashSet<>();
        List<NormalizedVacancy> normalized = new ArrayList<>(incoming.size());
        for (NormalizedVacancy source : incoming) {
            if (source == null) {
                throw new InvalidVacancyDataException("Список вакансий содержит пустой элемент.");
            }
            NormalizedVacancy vacancy = normalize(source);
            if (!seenHhVacancyIds.add(vacancy.hhVacancyId())) {
                throw new InvalidVacancyDataException(
                        "Вакансия %s встречается в запросе несколько раз.".formatted(vacancy.hhVacancyId())
                );
            }
            normalized.add(vacancy);
        }
        return normalized;
    }

    private NormalizedVacancy normalize(NormalizedVacancy source) {
        String hhVacancyId = required(
                source.hhVacancyId(), MAXIMUM_HH_VACANCY_ID_LENGTH, "Идентификатор вакансии HH"
        );
        String employerId = source.employerId() == null || source.employerId().isBlank() ? null : HhEmployerId.canonical(required(
                source.employerId(), MAXIMUM_EMPLOYER_ID_LENGTH, "Идентификатор работодателя HH"
        ));
        if (!hhVacancyId.matches("[0-9]+")) throw new InvalidVacancyDataException("Некорректный ID вакансии HH.");
        if (source.position() < 0) {
            throw new InvalidVacancyDataException(
                    "Позиция вакансии %s в выдаче не может быть отрицательной.".formatted(hhVacancyId)
            );
        }
        return new NormalizedVacancy(
                hhVacancyId,
                required(source.title(), MAXIMUM_TITLE_LENGTH, "Название вакансии"),
                employerId,
                normalizeCompanyName(source.companyName(), employerId),
                normalizeSalary(source.salary(), hhVacancyId),
                optional(source.location(), MAXIMUM_LOCATION_LENGTH, "Регион вакансии"),
                optional(source.workFormat(), MAXIMUM_WORK_FORMAT_LENGTH, "Формат работы"),
                optional(source.experience(), MAXIMUM_EXPERIENCE_LENGTH, "Требуемый опыт"),
                source.publishedAt(),
                normalizeSalaryText(source.salaryText(), hhVacancyId),
                normalizeLabels(source.labels()),
                source.hasQuestionnaire(),
                source.alreadyApplied(),
                source.closed(),
                source.position(),
                normalizeEnrichment(source.enrichment(), hhVacancyId)
        );
    }

    private tools.jackson.databind.JsonNode normalizeEnrichment(
            tools.jackson.databind.JsonNode enrichment,
            String hhVacancyId
    ) {
        if (enrichment == null || enrichment.isNull()) return null;
        if (!enrichment.isObject()) {
            throw new InvalidVacancyDataException("Дополнительные данные вакансии %s должны быть объектом.".formatted(hhVacancyId));
        }
        if (enrichment.toString().length() > MAXIMUM_ENRICHMENT_LENGTH) {
            throw new InvalidVacancyDataException("Дополнительные данные вакансии %s слишком велики.".formatted(hhVacancyId));
        }
        return enrichment.deepCopy();
    }

    private String normalizeSalaryText(String value, String hhVacancyId) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > MAXIMUM_SALARY_TEXT_LENGTH) {
            throw new InvalidVacancyDataException(
                    "Зарплата вакансии %s не должна быть длиннее %d символов."
                            .formatted(hhVacancyId, MAXIMUM_SALARY_TEXT_LENGTH)
            );
        }
        return normalized;
    }

    private String normalizeCompanyName(String value, String employerId) {
        if (value == null || value.isBlank()) {
            return employerId == null ? "Работодатель не указан" : "Employer ID %s".formatted(employerId);
        }
        return required(value, MAXIMUM_COMPANY_NAME_LENGTH, "Название компании");
    }

    private NormalizedSalary normalizeSalary(NormalizedSalary salary, String hhVacancyId) {
        if (salary == null) {
            return null;
        }
        SalaryCurrency currency = parseCurrency(salary.currency(), hhVacancyId);
        if (salary.from() != null && salary.from() < 0) {
            throw new InvalidVacancyDataException(
                    "Зарплата вакансии %s не может быть отрицательной.".formatted(hhVacancyId)
            );
        }
        if (salary.to() != null && salary.to() < 0) {
            throw new InvalidVacancyDataException(
                    "Зарплата вакансии %s не может быть отрицательной.".formatted(hhVacancyId)
            );
        }
        if (salary.from() != null && salary.to() != null && salary.from() > salary.to()) {
            throw new InvalidVacancyDataException(
                    "Нижняя граница зарплаты вакансии %s больше верхней.".formatted(hhVacancyId)
            );
        }
        return new NormalizedSalary(salary.from(), salary.to(), currency.name(), salary.gross());
    }

    private SalaryCurrency parseCurrency(String value, String hhVacancyId) {
        if (value == null || value.isBlank()) {
            throw new InvalidVacancyDataException(
                    "Не указана валюта зарплаты вакансии %s.".formatted(hhVacancyId)
            );
        }
        try {
            return SalaryCurrency.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new InvalidVacancyDataException(
                    "Валюта зарплаты вакансии %s должна быть RUR, USD или EUR.".formatted(hhVacancyId),
                    exception
            );
        }
    }

    private List<String> normalizeLabels(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String label : labels) {
            if (label == null || label.isBlank()) {
                continue;
            }
            String value = label.trim().replaceAll("\\s+", " ");
            if (value.length() > MAXIMUM_LABEL_LENGTH) {
                throw new InvalidVacancyDataException(
                        "Метка не должна быть длиннее %d символов.".formatted(MAXIMUM_LABEL_LENGTH)
                );
            }
            normalized.add(value);
        }
        if (normalized.size() > MAXIMUM_LABELS) {
            throw new InvalidVacancyDataException(
                    "У вакансии не может быть больше %d меток.".formatted(MAXIMUM_LABELS)
            );
        }
        return List.copyOf(normalized);
    }

    private VacancySalary toSalary(NormalizedSalary salary) {
        if (salary == null) {
            return null;
        }
        return new VacancySalary(
                salary.from(), salary.to(), SalaryCurrency.valueOf(salary.currency()), salary.gross()
        );
    }

    private String required(String value, int maximumLength, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new InvalidVacancyDataException("%s не должен быть пустым.".formatted(fieldName));
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new InvalidVacancyDataException(
                    "%s не должен быть длиннее %d символов.".formatted(fieldName, maximumLength)
            );
        }
        return normalized;
    }

    private String optional(String value, int maximumLength, String fieldName) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximumLength) {
            throw new InvalidVacancyDataException(
                    "%s не должен быть длиннее %d символов.".formatted(fieldName, maximumLength)
            );
        }
        return normalized;
    }
}
