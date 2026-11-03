package org.hhclicker.backend.search.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.resume.service.ResumeProfile;
import org.hhclicker.backend.resume.service.ResumeService;
import org.hhclicker.backend.search.exception.InvalidSearchDataException;
import org.hhclicker.backend.search.exception.SearchAlreadyExistsException;
import org.hhclicker.backend.search.exception.SearchNotFoundException;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;
import org.hhclicker.backend.search.repository.SearchRepository;
import org.hhclicker.backend.vacancy.catalog.service.IngestSearchResultsCommand;
import org.hhclicker.backend.vacancy.catalog.service.NormalizedVacancy;
import org.hhclicker.backend.vacancy.catalog.service.VacancyIngestionResult;
import org.hhclicker.backend.vacancy.catalog.service.VacancyIngestionService;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class SearchService {

    private static final int MAXIMUM_URL_LENGTH = 4096;

    private final SearchRepository searchRepository;
    private final ResumeService resumeService;
    private final VacancyIngestionService vacancyIngestionService;
    private final Clock clock;

    public SearchService(
            SearchRepository searchRepository,
            ResumeService resumeService,
            VacancyIngestionService vacancyIngestionService,
            Clock clock
    ) {
        this.searchRepository = searchRepository;
        this.resumeService = resumeService;
        this.vacancyIngestionService = vacancyIngestionService;
        this.clock = clock;
    }

    public List<SearchProfile> findAll(UUID resumeId) {
        if (resumeId != null) {
            ResumeProfile resume = resumeService.findById(resumeId);
            return searchRepository.findAllByResumeId(resumeId).stream()
                    .map(search -> new SearchProfile(search, resume.resume().name()))
                    .toList();
        }
        return searchRepository.findAll().stream()
                .map(this::toProfile)
                .toList();
    }

    public SearchProfile findById(UUID searchId) {
        return toProfile(findSearch(searchId));
    }

    @Transactional
    public SearchProfile create(CreateSearchCommand command) {
        if (command.resumeId() == null) {
            throw new InvalidSearchDataException("Не указано резюме для поиска.");
        }
        ResumeProfile resume = resumeService.findById(command.resumeId());
        String name = normalizeName(command.name());
        String sourceUrl = normalizeAndValidateUrl(command.sourceUrl(), resume.resume().hhResumeId());
        int pagesLimit = validatePagesLimit(command.pagesLimit());
        String sourceHash = hash(sourceUrl);
        if (searchRepository.findByResumeIdAndSourceHash(command.resumeId(), sourceHash).isPresent()) {
            throw new SearchAlreadyExistsException();
        }

        Instant now = clock.instant();
        VacancySearch search = new VacancySearch(
                UUID.randomUUID(), command.resumeId(), name, sourceUrl, sourceHash, pagesLimit,
                SearchStatus.ACTIVE, 0, 0, null, null, now, now
        );
        try {
            searchRepository.insert(search);
        } catch (DuplicateKeyException exception) {
            throw new SearchAlreadyExistsException(exception);
        }
        return new SearchProfile(search, resume.resume().name());
    }

    @Transactional
    public SearchProfile update(UUID searchId, UpdateSearchCommand command) {
        VacancySearch existing = lockSearch(searchId);
        requireNotScanning(existing);
        ResumeProfile resume = resumeService.findById(existing.resumeId());
        String name = normalizeName(command.name());
        String sourceUrl = normalizeAndValidateUrl(command.sourceUrl(), resume.resume().hhResumeId());
        int pagesLimit = validatePagesLimit(command.pagesLimit());
        String sourceHash = hash(sourceUrl);
        if (searchRepository.findByResumeIdAndSourceHash(existing.resumeId(), sourceHash)
                .filter(other -> !other.id().equals(searchId)).isPresent()) {
            throw new SearchAlreadyExistsException();
        }
        // Preserve ownership, discovered vacancies, queue and the last successful scan.
        VacancySearch updated = new VacancySearch(existing.id(), existing.resumeId(), name, sourceUrl,
                sourceHash, pagesLimit, SearchStatus.ACTIVE, existing.newVacancies(), existing.totalVacancies(),
                existing.lastScannedAt(), null, existing.createdAt(), clock.instant());
        try {
            if (!searchRepository.updateDefinition(updated)) throw new SearchNotFoundException(searchId);
        } catch (DuplicateKeyException exception) {
            throw new SearchAlreadyExistsException(exception);
        }
        return new SearchProfile(updated, resume.resume().name());
    }

    @Transactional
    public SearchProfile setPaused(UUID searchId, boolean paused) {
        VacancySearch existing = lockSearch(searchId);
        requireNotScanning(existing);
        VacancySearch updated = copyWithState(
                existing,
                paused ? SearchStatus.PAUSED : SearchStatus.ACTIVE,
                existing.lastScannedAt(),
                null,
                clock.instant()
        );
        if (!searchRepository.updateStatus(updated)) {
            throw new SearchNotFoundException(searchId);
        }
        return toProfile(updated);
    }

    @Transactional
    public PreparedSearchScan scan(UUID searchId) {
        VacancySearch existing = lockSearch(searchId);
        requireNotScanning(existing);
        String sourceUrl = normalizeAndValidateUrl(existing.sourceUrl(),
                resumeService.findById(existing.resumeId()).resume().hhResumeId());
        Instant now = clock.instant();
        VacancySearch updated = new VacancySearch(
                existing.id(), existing.resumeId(), existing.name(), existing.sourceUrl(), existing.sourceHash(),
                existing.pagesLimit(), SearchStatus.SCANNING, existing.newVacancies(), existing.totalVacancies(),
                existing.lastScannedAt(), null, now, 0, null, 0, null, existing.createdAt(), now
        );
        if (!searchRepository.updateStatus(updated)) {
            throw new SearchNotFoundException(searchId);
        }
        return new PreparedSearchScan(toProfile(updated), sourceUrl);
    }

    /**
     * Принимает нормализованный результат поиска. Резюме берётся из самого поиска: значению
     * из HTTP-запроса доверять нельзя.
     */
    @Transactional
    public VacancyIngestionResult ingestVacancies(UUID searchId, List<NormalizedVacancy> vacancies) {
        VacancySearch existing = lockSearch(searchId);
        requireNotScanning(existing);
        return ingest(existing, vacancies);
    }

    @Transactional(timeout = 120)
    public VacancyIngestionResult completeScan(UUID searchId, List<NormalizedVacancy> vacancies) {
        VacancySearch existing = lockSearch(searchId);
        if (existing.status() != SearchStatus.SCANNING) {
            throw new InvalidSearchDataException("Сканирование уже завершено. Результат не сохранён.");
        }
        return ingest(existing, vacancies);
    }

    @Transactional
    public void updateScanProgress(UUID searchId, int currentPage, int totalPages, int collected, Integer totalResults) {
        VacancySearch existing = lockSearch(searchId);
        if (existing.status() != SearchStatus.SCANNING) {
            throw new InvalidSearchDataException("Сканирование уже завершено. Прогресс не сохранён.");
        }
        if (currentPage < 1 || totalPages < 1 || totalPages > existing.pagesLimit() || currentPage > totalPages
                || collected < 0 || collected > currentPage * 100
                || totalResults != null && (totalResults < 0 || collected > totalResults)) {
            throw new InvalidSearchDataException("Browser Agent передал некорректный прогресс сканирования.");
        }
        if ((existing.scanCurrentPage() != null && currentPage < existing.scanCurrentPage())
                || (existing.scanCollected() != null && collected < existing.scanCollected())) {
            throw new InvalidSearchDataException("Browser Agent передал устаревший прогресс сканирования.");
        }
        Instant now = clock.instant();
        VacancySearch updated = new VacancySearch(
                existing.id(), existing.resumeId(), existing.name(), existing.sourceUrl(), existing.sourceHash(),
                existing.pagesLimit(), existing.status(), existing.newVacancies(), existing.totalVacancies(),
                existing.lastScannedAt(), existing.lastError(), existing.scanStartedAt(), currentPage, totalPages,
                collected, totalResults, existing.createdAt(), now
        );
        if (!searchRepository.updateScanProgress(updated)) throw new SearchNotFoundException(searchId);
    }

    private VacancyIngestionResult ingest(VacancySearch existing, List<NormalizedVacancy> vacancies) {
        UUID searchId = existing.id();
        VacancyIngestionResult result = vacancyIngestionService.ingest(new IngestSearchResultsCommand(
                searchId, existing.resumeId(), vacancies
        ));

        Instant now = clock.instant();
        SearchStatus resultingStatus = existing.status() == SearchStatus.PAUSED
                ? SearchStatus.PAUSED
                : SearchStatus.ACTIVE;
        VacancySearch updated = new VacancySearch(
                existing.id(), existing.resumeId(), existing.name(), existing.sourceUrl(),
                existing.sourceHash(), existing.pagesLimit(), resultingStatus,
                result.newVacancies(), result.totalVacancies(), now, null, existing.createdAt(), now
        );
        if (!searchRepository.updateAfterIngestion(updated)) {
            throw new SearchNotFoundException(searchId);
        }
        return result;
    }

    @Transactional
    public void delete(UUID searchId) {
        requireNotScanning(lockSearch(searchId));
        if (!searchRepository.deleteById(searchId)) {
            throw new SearchNotFoundException(searchId);
        }
    }

    private VacancySearch findSearch(UUID searchId) {
        return searchRepository.findById(searchId)
                .orElseThrow(() -> new SearchNotFoundException(searchId));
    }

    private VacancySearch lockSearch(UUID searchId) {
        return searchRepository.findByIdForUpdate(searchId)
                .orElseThrow(() -> new SearchNotFoundException(searchId));
    }

    public UUID accountIdForSearch(UUID searchId) {
        return resumeService.findById(findSearch(searchId).resumeId()).resume().accountId();
    }

    @Transactional
    public void failScan(UUID searchId, String message) {
        var search = searchRepository.findByIdForUpdate(searchId).orElse(null);
        if (search == null) return; // Account/resume deletion may cascade while the agent is running.
        if (search.status() != SearchStatus.SCANNING) return;
        searchRepository.updateStatus(copyWithState(search, SearchStatus.ERROR, search.lastScannedAt(),
                message == null ? "Сканирование прервано. Повторите запуск." : message.substring(0, Math.min(1000, message.length())), clock.instant()));
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedScans() {
        for (var search : searchRepository.findAll()) {
            if (search.status() == SearchStatus.SCANNING) {
                searchRepository.updateStatus(copyWithState(search, SearchStatus.ERROR, search.lastScannedAt(),
                        "Backend был перезапущен. Повторите сканирование.", clock.instant()));
            }
        }
    }

    private void requireNotScanning(VacancySearch search) {
        if (search.status() == SearchStatus.SCANNING)
            throw new InvalidSearchDataException("Дождитесь завершения текущего сканирования.");
    }

    private SearchProfile toProfile(VacancySearch search) {
        return new SearchProfile(search, resumeService.findById(search.resumeId()).resume().name());
    }

    private String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidSearchDataException("Название поиска не должно быть пустым.");
        }
        String normalized = value.trim();
        if (normalized.length() < 3 || normalized.length() > 120) {
            throw new InvalidSearchDataException("Название поиска должно содержать от 3 до 120 символов.");
        }
        return normalized;
    }

    private int validatePagesLimit(int value) {
        if (value < 1 || value > 20) {
            throw new InvalidSearchDataException("Количество страниц должно быть от 1 до 20.");
        }
        return value;
    }

    private String normalizeAndValidateUrl(String value, String expectedHhResumeId) {
        if (value == null || value.isBlank()) {
            throw new InvalidSearchDataException("Ссылка поиска HH не должна быть пустой.");
        }
        String normalized = value.trim();
        if (normalized.length() > MAXIMUM_URL_LENGTH) {
            throw new InvalidSearchDataException("Ссылка поиска HH слишком длинная.");
        }

        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https"))) {
                throw new InvalidSearchDataException("Ссылка поиска должна использовать HTTP или HTTPS.");
            }
            if (!(host.equals("hh.ru") || host.endsWith(".hh.ru"))) {
                throw new InvalidSearchDataException("Ссылка поиска должна вести на hh.ru.");
            }
            if (!"/search/vacancy".equals(uri.getPath())) {
                throw new InvalidSearchDataException("Нужна ссылка со страницы поиска вакансий HH.");
            }
            String resumeId = findQueryParameter(uri.getRawQuery(), "resume");
            if (resumeId != null && resumeId.isBlank()) {
                throw new InvalidSearchDataException("Параметр resume не должен быть пустым. Удалите его или укажите резюме.");
            }
            if (resumeId != null && !resumeId.trim().equals(expectedHhResumeId)) {
                throw new InvalidSearchDataException("Резюме в ссылке не совпадает с выбранным резюме.");
            }
            if (uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new InvalidSearchDataException("Ссылка не должна содержать логин или нестандартный порт.");
            }
            var pairs = new java.util.ArrayList<String>();
            int resumeParameters = 0;
            for (String pair : (uri.getRawQuery() == null ? "" : uri.getRawQuery()).split("&")) {
                if (pair.isBlank()) continue;
                String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                if (key.equals("resume")) resumeParameters++;
                if (!java.util.Set.of("items_on_page", "page").contains(key)) {
                    String normalizedPair = URLEncoder.encode(key, StandardCharsets.UTF_8);
                    if (parts.length == 2) {
                        String decodedValue = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                        normalizedPair += "=" + URLEncoder.encode(decodedValue, StandardCharsets.UTF_8);
                    }
                    pairs.add(normalizedPair);
                }
            }
            if (resumeParameters > 1) throw new InvalidSearchDataException("Параметр resume не должен повторяться.");
            pairs.add("items_on_page=100");
            pairs.add("page=0");
            pairs.sort(String::compareTo);
            String result = "https://" + host + "/search/vacancy?" + String.join("&", pairs);
            if (result.length() > MAXIMUM_URL_LENGTH)
                throw new InvalidSearchDataException("Ссылка поиска HH слишком длинная.");
            return result;
        } catch (InvalidSearchDataException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new InvalidSearchDataException("Проверьте формат ссылки поиска HH.");
        }
    }

    private String findQueryParameter(String rawQuery, String expectedName) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (name.equals(expectedName)) {
                return parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private VacancySearch copyWithState(
            VacancySearch search,
            SearchStatus status,
            Instant lastScannedAt,
            String lastError,
            Instant updatedAt
    ) {
        return new VacancySearch(
                search.id(), search.resumeId(), search.name(), search.sourceUrl(), search.sourceHash(),
                search.pagesLimit(), status, search.newVacancies(), search.totalVacancies(),
                lastScannedAt, lastError, null, null, null, null, null, search.createdAt(), updatedAt
        );
    }
}
