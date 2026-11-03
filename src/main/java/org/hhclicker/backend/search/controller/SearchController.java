package org.hhclicker.backend.search.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.hhclicker.backend.search.dto.*;
import org.hhclicker.backend.search.service.CreateSearchCommand;
import org.hhclicker.backend.search.service.SearchScanGateway;
import org.hhclicker.backend.search.service.SearchService;
import org.hhclicker.backend.search.service.UpdateSearchCommand;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/searches")
@Tag(name = "Vacancy searches", description = "Сохранённые ссылки поиска вакансий HH")
public class SearchController {

    private final SearchService searchService;
    private final SearchScanGateway scanGateway;

    public SearchController(SearchService searchService, SearchScanGateway scanGateway) {
        this.searchService = searchService;
        this.scanGateway = scanGateway;
    }

    @GetMapping
    @Operation(summary = "Получить сохранённые поиски", description = "Без resumeId возвращает поиски всех резюме")
    public List<SearchResponse> findAll(@RequestParam(required = false) UUID resumeId) {
        return searchService.findAll(resumeId).stream()
                .map(SearchResponse::from)
                .toList();
    }

    @GetMapping("/{searchId}")
    @Operation(summary = "Получить сохранённый поиск")
    public SearchResponse findById(@PathVariable UUID searchId) {
        return SearchResponse.from(searchService.findById(searchId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Сохранить поиск HH")
    @ApiResponse(responseCode = "201", description = "Поиск сохранён")
    @ApiResponse(responseCode = "400", description = "Некорректная ссылка или параметры", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Такой поиск уже сохранён", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public SearchResponse create(@Valid @RequestBody CreateSearchRequest request) {
        return SearchResponse.from(searchService.create(new CreateSearchCommand(
                request.name(), request.sourceUrl(), request.resumeId(), request.pagesLimit()
        )));
    }

    @PutMapping("/{searchId}")
    @Operation(summary = "Изменить название, ссылку и лимит страниц поиска")
    public SearchResponse update(@PathVariable UUID searchId, @Valid @RequestBody UpdateSearchRequest request) {
        return SearchResponse.from(searchService.update(searchId,
                new UpdateSearchCommand(request.name(), request.sourceUrl(), request.pagesLimit())));
    }

    @PutMapping("/{searchId}/status")
    @Operation(summary = "Поставить поиск на паузу или продолжить")
    public SearchResponse setStatus(
            @PathVariable UUID searchId,
            @RequestBody SetSearchStatusRequest request
    ) {
        return SearchResponse.from(searchService.setPaused(searchId, request.paused()));
    }

    @PostMapping("/{searchId}/scan")
    @Operation(
            summary = "Запустить ручное сканирование",
            description = "Собрать карточки выдачи через открытый браузер аккаунта. Отклики не отправляются."
    )
    public SearchResponse scan(@PathVariable UUID searchId) {
        return SearchResponse.from(scanGateway.scanSearch(searchId));
    }

    @PostMapping("/{searchId}/vacancies")
    @Operation(
            summary = "Принять нормализованный результат поиска",
            description = "Дополнительный приём до 500 вакансий вне запущенного сканирования. SCAN_SEARCH передаёт полный результат через протокол команд агента."
    )
    @ApiResponse(responseCode = "400", description = "Некорректные данные вакансий", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Поиск не найден", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public IngestVacanciesResponse ingestVacancies(
            @PathVariable UUID searchId,
            @Valid @RequestBody IngestVacanciesRequest request
    ) {
        return IngestVacanciesResponse.from(
                searchService.ingestVacancies(searchId, request.toNormalizedVacancies())
        );
    }

    @DeleteMapping("/{searchId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Удалить сохранённый поиск")
    public void delete(@PathVariable UUID searchId) {
        searchService.delete(searchId);
    }
}
