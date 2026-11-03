package org.hhclicker.backend.vacancy.stoplist.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.resume.service.ResumeService;
import org.hhclicker.backend.vacancy.catalog.exception.VacancyNotFoundException;
import org.hhclicker.backend.vacancy.catalog.model.*;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;
import org.hhclicker.backend.vacancy.catalog.repository.EmployerStateLock;
import org.hhclicker.backend.vacancy.catalog.service.EmployerBanLookup;
import org.hhclicker.backend.vacancy.stoplist.exception.CompanyBanAlreadyExistsException;
import org.hhclicker.backend.vacancy.stoplist.exception.CompanyBanNotFoundException;
import org.hhclicker.backend.vacancy.stoplist.exception.InvalidCompanyBanDataException;
import org.hhclicker.backend.vacancy.stoplist.model.CompanyBan;
import org.hhclicker.backend.vacancy.stoplist.repository.CompanyBanRepository;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Стоп-лист работодателей. Блокировка напрямую меняет доступность вакансий, поэтому подсценарий
 * живёт внутри модуля {@code vacancy} и зависит от каталога, а не наоборот.
 */
@Service
@Transactional(readOnly = true)
public class CompanyBanService implements EmployerBanLookup {

    private static final Pattern EMPLOYER_PATH = Pattern.compile("^/employer/([0-9]{1,64})/?$");
    private static final int MAXIMUM_URL_LENGTH = 500;
    private static final int MAXIMUM_COMPANY_NAME_LENGTH = 300;

    private final CompanyBanRepository companyBanRepository;
    private final VacancyRepository vacancyRepository;
    private final ResumeService resumeService;
    private final Clock clock;
    private final EmployerStateLock employerStateLock;

    public CompanyBanService(
            CompanyBanRepository companyBanRepository,
            VacancyRepository vacancyRepository,
            ResumeService resumeService,
            Clock clock,
            EmployerStateLock employerStateLock
    ) {
        this.companyBanRepository = companyBanRepository;
        this.vacancyRepository = vacancyRepository;
        this.resumeService = resumeService;
        this.clock = clock;
        this.employerStateLock = employerStateLock;
    }

    private static CompanyBanScope strongerScope(CompanyBanScope left, CompanyBanScope right) {
        return left == CompanyBanScope.GLOBAL || right == CompanyBanScope.GLOBAL
                ? CompanyBanScope.GLOBAL
                : CompanyBanScope.RESUME;
    }

    private static String canonicalUrl(String employerId) {
        return "https://hh.ru/employer/%s".formatted(employerId);
    }

    /**
     * Для выбранного резюме возвращает его блокировки вместе с глобальными, без резюме — все.
     */
    public List<CompanyBanView> findAll(UUID resumeId) {
        if (resumeId != null) {
            resumeService.findById(resumeId);
            return toViews(companyBanRepository.findAllVisibleForResume(resumeId));
        }
        return toViews(companyBanRepository.findAll());
    }

    @Transactional
    public CompanyBanView create(CreateCompanyBanCommand command) {
        HhEmployerLink link = parseEmployerUrl(command.employerUrl());
        employerStateLock.acquire(List.of(link.employerId()));
        CompanyBanScope scope = requireScope(command.scope());
        UUID resumeId = resolveResumeId(scope, command.resumeId());
        UUID sourceVacancyId = resolveSourceVacancyId(command.sourceVacancyId(), link.employerId());
        Instant now = clock.instant();

        CompanyBan ban = new CompanyBan(
                UUID.randomUUID(),
                link.employerId(),
                link.canonicalUrl(),
                resolveCompanyName(command.companyName(), link.employerId()),
                scope,
                resumeId,
                sourceVacancyId,
                now,
                now
        );
        save(ban);
        recomputeEmployerState(ban.employerId(), now);
        return toView(ban);
    }

    /**
     * Блокирует работодателя найденной вакансии и возвращает вакансии, состояние которых изменилось.
     */
    @Transactional
    public List<VacancyListing> banVacancyCompany(UUID vacancyId, CompanyBanScope requestedScope) {
        Vacancy vacancy = vacancyRepository.findById(vacancyId)
                .orElseThrow(() -> new VacancyNotFoundException(vacancyId));
        if (vacancy.employerId() == null)
            throw new InvalidCompanyBanDataException("HH не указал ID работодателя этой вакансии.");
        employerStateLock.acquire(List.of(vacancy.employerId()));
        Vacancy current = vacancyRepository.findById(vacancyId)
                .orElseThrow(() -> new VacancyNotFoundException(vacancyId));
        if (!vacancy.employerId().equals(current.employerId())) {
            throw new InvalidCompanyBanDataException("Работодатель вакансии изменился. Обновите список и повторите блокировку.");
        }
        CompanyBanScope scope = requireScope(requestedScope);
        UUID resumeId = scope == CompanyBanScope.RESUME ? vacancy.resumeId() : null;
        Instant now = clock.instant();

        if (findSameScopeBan(vacancy.employerId(), scope, resumeId).isEmpty()) {
            save(new CompanyBan(
                    UUID.randomUUID(),
                    vacancy.employerId(),
                    canonicalUrl(vacancy.employerId()),
                    vacancy.companyName(),
                    scope,
                    resumeId,
                    vacancy.id(),
                    now,
                    now
            ));
        }
        return orderedListings(recomputeEmployerState(vacancy.employerId(), now));
    }

    @Transactional
    public CompanyBanView changeScope(UUID banId, CompanyBanScope requestedScope, UUID requestedResumeId) {
        CompanyBan existing = findBan(banId);
        employerStateLock.acquire(List.of(existing.employerId()));
        CompanyBanScope scope = requireScope(requestedScope);
        UUID resumeId = resolveResumeId(scope, requestedResumeId);
        ensureScopeIsFree(existing.employerId(), scope, resumeId, banId);

        Instant now = clock.instant();
        CompanyBan updated = new CompanyBan(
                existing.id(),
                existing.employerId(),
                existing.employerUrl(),
                existing.companyName(),
                scope,
                resumeId,
                existing.sourceVacancyId(),
                existing.createdAt(),
                now
        );
        try {
            if (!companyBanRepository.updateScope(updated)) {
                throw new CompanyBanNotFoundException(banId);
            }
        } catch (DuplicateKeyException exception) {
            throw new CompanyBanAlreadyExistsException(
                    "Такая блокировка работодателя уже существует.", exception
            );
        }
        recomputeEmployerState(updated.employerId(), now);
        return toView(updated);
    }

    @Transactional
    public void delete(UUID banId) {
        CompanyBan ban = findBan(banId);
        employerStateLock.acquire(List.of(ban.employerId()));
        if (!companyBanRepository.deleteById(banId)) {
            throw new CompanyBanNotFoundException(banId);
        }
        recomputeEmployerState(ban.employerId(), clock.instant());
    }

    @Override
    public Map<String, CompanyBanScope> findApplicableScopes(Set<String> employerIds, UUID resumeId) {
        Map<String, CompanyBanScope> applicable = new HashMap<>();
        for (CompanyBan ban : companyBanRepository.findApplicable(employerIds, resumeId)) {
            applicable.merge(ban.employerId(), ban.scope(), CompanyBanService::strongerScope);
        }
        return applicable;
    }

    /**
     * Пересчитывает состояние вакансий работодателя после любого изменения стоп-листа.
     * Терминальные статусы не трогаются, снятие блокировки возвращает вакансию в {@code NEW}
     * без автоматической постановки в очередь.
     */
    private List<UUID> recomputeEmployerState(String employerId, Instant now) {
        List<CompanyBan> bans = companyBanRepository.findAllByEmployerId(employerId);
        boolean bannedGlobally = bans.stream().anyMatch(ban -> ban.scope() == CompanyBanScope.GLOBAL);
        Set<UUID> bannedResumeIds = bans.stream()
                .filter(ban -> ban.scope() == CompanyBanScope.RESUME)
                .map(CompanyBan::resumeId)
                .collect(Collectors.toSet());

        if (bannedGlobally) {
            return vacancyRepository.banVacanciesOfEmployer(
                    employerId, CompanyBanScope.GLOBAL, null, now
            );
        }
        List<UUID> changed = new ArrayList<>(vacancyRepository.banVacanciesOfEmployer(
                employerId, CompanyBanScope.RESUME, bannedResumeIds, now
        ));
        changed.addAll(vacancyRepository.releaseVacanciesOfEmployer(employerId, bannedResumeIds, now));
        return changed;
    }

    private void save(CompanyBan ban) {
        ensureScopeIsFree(ban.employerId(), ban.scope(), ban.resumeId(), null);
        try {
            companyBanRepository.insert(ban);
        } catch (DuplicateKeyException exception) {
            throw new CompanyBanAlreadyExistsException(
                    "Такая блокировка работодателя уже существует.", exception
            );
        }
    }

    /**
     * Дубль запрещён только внутри одинаковой области: {@code RESUME} и {@code GLOBAL} —
     * разные блокировки, и перекрытая глобальной блокировка резюме должна пережить её снятие.
     */
    private void ensureScopeIsFree(
            String employerId,
            CompanyBanScope scope,
            UUID resumeId,
            UUID ignoredBanId
    ) {
        boolean occupied = findSameScopeBan(employerId, scope, resumeId).stream()
                .anyMatch(ban -> !ban.id().equals(ignoredBanId));
        if (!occupied) {
            return;
        }
        throw new CompanyBanAlreadyExistsException(scope == CompanyBanScope.GLOBAL
                ? "Работодатель уже заблокирован глобально."
                : "Работодатель уже заблокирован для выбранного резюме.");
    }

    private Optional<CompanyBan> findSameScopeBan(
            String employerId,
            CompanyBanScope scope,
            UUID resumeId
    ) {
        return companyBanRepository.findAllByEmployerId(employerId).stream()
                .filter(ban -> ban.scope() == scope)
                .filter(ban -> scope == CompanyBanScope.GLOBAL || Objects.equals(ban.resumeId(), resumeId))
                .findFirst();
    }

    private CompanyBan findBan(UUID banId) {
        return companyBanRepository.findById(banId)
                .orElseThrow(() -> new CompanyBanNotFoundException(banId));
    }

    private CompanyBanScope requireScope(CompanyBanScope scope) {
        if (scope == null) {
            throw new InvalidCompanyBanDataException("Не указана область блокировки: RESUME или GLOBAL.");
        }
        return scope;
    }

    private UUID resolveResumeId(CompanyBanScope scope, UUID resumeId) {
        if (scope == CompanyBanScope.RESUME) {
            if (resumeId == null) {
                throw new InvalidCompanyBanDataException(
                        "Для блокировки в области резюме нужно указать резюме."
                );
            }
            resumeService.findById(resumeId);
            return resumeId;
        }
        if (resumeId != null) {
            throw new InvalidCompanyBanDataException(
                    "Глобальная блокировка не относится к отдельному резюме."
            );
        }
        return null;
    }

    private UUID resolveSourceVacancyId(UUID sourceVacancyId, String employerId) {
        if (sourceVacancyId == null) {
            return null;
        }
        Vacancy sourceVacancy = vacancyRepository.findById(sourceVacancyId)
                .orElseThrow(() -> new VacancyNotFoundException(sourceVacancyId));
        if (!java.util.Objects.equals(sourceVacancy.employerId(), employerId)) {
            throw new InvalidCompanyBanDataException(
                    "Исходная вакансия принадлежит другому работодателю."
            );
        }
        return sourceVacancy.id();
    }

    private String resolveCompanyName(String requestedName, String employerId) {
        if (requestedName != null && !requestedName.isBlank()) {
            String normalized = requestedName.trim();
            if (normalized.length() > MAXIMUM_COMPANY_NAME_LENGTH) {
                throw new InvalidCompanyBanDataException(
                        "Название компании не должно быть длиннее %d символов."
                                .formatted(MAXIMUM_COMPANY_NAME_LENGTH)
                );
            }
            return normalized;
        }
        return vacancyRepository.findLatestCompanyName(employerId)
                .filter(name -> !name.isBlank())
                .orElseGet(() -> "Employer ID %s".formatted(employerId));
    }

    /**
     * Идентификатор работодателя извлекается на backend: значению клиента не доверяем.
     */
    private HhEmployerLink parseEmployerUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidCompanyBanDataException("Ссылка на работодателя HH не должна быть пустой.");
        }
        String normalized = value.trim();
        if (normalized.length() > MAXIMUM_URL_LENGTH) {
            throw new InvalidCompanyBanDataException("Ссылка на работодателя HH слишком длинная.");
        }
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https"))) {
                throw new InvalidCompanyBanDataException(
                        "Ссылка на работодателя должна использовать HTTP или HTTPS."
                );
            }
            if (!(host.equals("hh.ru") || host.endsWith(".hh.ru"))) {
                throw new InvalidCompanyBanDataException("Ссылка на работодателя должна вести на hh.ru.");
            }
            Matcher matcher = EMPLOYER_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
            if (!matcher.matches()) {
                throw new InvalidCompanyBanDataException(
                        "Нужна ссылка вида https://hh.ru/employer/3529."
                );
            }
            String employerId = HhEmployerId.canonical(matcher.group(1));
            return new HhEmployerLink(employerId, canonicalUrl(employerId));
        } catch (InvalidCompanyBanDataException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new InvalidCompanyBanDataException(
                    "Проверьте формат ссылки на работодателя HH.", exception
            );
        }
    }

    private List<VacancyListing> orderedListings(List<UUID> vacancyIds) {
        Map<UUID, VacancyListing> listings = vacancyRepository.findListingsByIds(vacancyIds).stream()
                .collect(Collectors.toMap(listing -> listing.vacancy().id(), Function.identity()));
        return vacancyIds.stream()
                .map(listings::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private CompanyBanView toView(CompanyBan ban) {
        return toViews(List.of(ban)).getFirst();
    }

    private List<CompanyBanView> toViews(List<CompanyBan> bans) {
        if (bans.isEmpty()) {
            return List.of();
        }
        List<EmployerBannedCount> counts = vacancyRepository.countBannedVacanciesByEmployerAndResume(
                bans.stream().map(CompanyBan::employerId).collect(Collectors.toSet())
        );
        Map<String, Long> globalCounts = counts.stream().collect(Collectors.groupingBy(
                EmployerBannedCount::employerId, Collectors.summingLong(EmployerBannedCount::count)
        ));
        Map<EmployerResumeKey, Long> resumeCounts = counts.stream().collect(Collectors.toMap(
                count -> new EmployerResumeKey(count.employerId(), count.resumeId()),
                EmployerBannedCount::count
        ));
        Map<UUID, String> vacancyTitles = vacancyRepository.findAllByIds(bans.stream()
                        .map(CompanyBan::sourceVacancyId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Vacancy::id, Vacancy::title));
        Map<UUID, String> resumeNames = new HashMap<>();

        return bans.stream()
                .map(ban -> new CompanyBanView(
                        ban,
                        ban.resumeId() == null ? null : resumeNames.computeIfAbsent(
                                ban.resumeId(),
                                resumeId -> resumeService.findById(resumeId).resume().name()
                        ),
                        ban.sourceVacancyId() == null ? null : vacancyTitles.get(ban.sourceVacancyId()),
                        hiddenVacanciesCount(ban, globalCounts, resumeCounts)
                ))
                .toList();
    }

    private long hiddenVacanciesCount(
            CompanyBan ban,
            Map<String, Long> globalCounts,
            Map<EmployerResumeKey, Long> resumeCounts
    ) {
        if (ban.scope() == CompanyBanScope.GLOBAL) {
            return Optional.ofNullable(globalCounts.get(ban.employerId())).orElse(0L);
        }
        return Optional
                .ofNullable(resumeCounts.get(new EmployerResumeKey(ban.employerId(), ban.resumeId())))
                .orElse(0L);
    }

    private record EmployerResumeKey(String employerId, UUID resumeId) {
    }
}
