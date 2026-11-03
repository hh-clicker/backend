package org.hhclicker.backend.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hhclicker.backend.search.controller.SearchController;
import org.hhclicker.backend.search.exception.InvalidSearchDataException;
import org.hhclicker.backend.search.exception.SearchAlreadyExistsException;
import org.hhclicker.backend.search.exception.SearchNotFoundException;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;
import org.hhclicker.backend.search.service.*;
import org.hhclicker.backend.shared.exception.GlobalExceptionHandler;
import org.hhclicker.backend.vacancy.catalog.service.NormalizedVacancy;
import org.hhclicker.backend.vacancy.catalog.service.VacancyIngestionResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-контракт сохранённых поисков. Контроллер поднимается с настоящим {@link GlobalExceptionHandler},
 * поэтому проверяются и Bean Validation, и отображение доменных ошибок в ProblemDetail.
 */
class SearchControllerTests {

    private static final String CANONICAL_URL =
            "https://hh.ru/search/vacancy?items_on_page=100&order_by=publication_time&page=0&text=java";
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant LAST_SCANNED_AT = Instant.parse("2026-09-02T12:00:00Z");

    private final SearchService searchService = mock(SearchService.class);
    private final SearchScanGateway scanGateway = mock(SearchScanGateway.class);

    private final UUID searchId = UUID.randomUUID();
    private final UUID resumeId = UUID.randomUUID();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SearchController(searchService, scanGateway))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private SearchProfile profile(SearchStatus status) {
        var search = new VacancySearch(searchId, resumeId, "Java in Moscow", CANONICAL_URL, "a".repeat(64), 5,
                status, 7, 42, LAST_SCANNED_AT, status == SearchStatus.ERROR ? "Scan failed" : null,
                CREATED_AT, CREATED_AT);
        return new SearchProfile(search, "Java Developer");
    }

    @Test
    void listWithoutResumeIdReturnsEverySearchWithItsFullPayload() throws Exception {
        when(searchService.findAll(null)).thenReturn(List.of(profile(SearchStatus.ERROR)));

        mockMvc.perform(get("/api/v1/searches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(searchId.toString()))
                .andExpect(jsonPath("$[0].name").value("Java in Moscow"))
                .andExpect(jsonPath("$[0].sourceUrl").value(CANONICAL_URL))
                .andExpect(jsonPath("$[0].resumeId").value(resumeId.toString()))
                .andExpect(jsonPath("$[0].resumeName").value("Java Developer"))
                .andExpect(jsonPath("$[0].pagesLimit").value(5))
                .andExpect(jsonPath("$[0].status").value("ERROR"))
                .andExpect(jsonPath("$[0].newVacancies").value(7))
                .andExpect(jsonPath("$[0].totalVacancies").value(42))
                .andExpect(jsonPath("$[0].lastError").value("Scan failed"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].lastScannedAt").exists());

        verify(searchService).findAll(null);
    }

    @Test
    void listWithResumeIdPassesTheFilterToTheService() throws Exception {
        when(searchService.findAll(resumeId)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/searches").param("resumeId", resumeId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(searchService).findAll(resumeId);
    }

    @Test
    void singleSearchIsReturned() throws Exception {
        when(searchService.findById(searchId)).thenReturn(profile(SearchStatus.ACTIVE));

        mockMvc.perform(get("/api/v1/searches/{id}", searchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(searchId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void scanningSearchExposesItsPersistedProgress() throws Exception {
        Instant startedAt = Instant.parse("2026-09-04T08:58:00Z");
        VacancySearch search = new VacancySearch(searchId, resumeId, "Java in Moscow", CANONICAL_URL,
                "a".repeat(64), 5, SearchStatus.SCANNING, 7, 42, LAST_SCANNED_AT, null,
                startedAt, 2, 4, 200, 360, CREATED_AT, startedAt);
        when(searchService.findById(searchId)).thenReturn(new SearchProfile(search, "Java Developer"));

        mockMvc.perform(get("/api/v1/searches/{id}", searchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanProgress.startedAt").exists())
                .andExpect(jsonPath("$.scanProgress.currentPage").value(2))
                .andExpect(jsonPath("$.scanProgress.totalPages").value(4))
                .andExpect(jsonPath("$.scanProgress.collected").value(200))
                .andExpect(jsonPath("$.scanProgress.totalResults").value(360));
    }

    @Test
    void missingSearchIsReportedAsNotFoundProblemDetail() throws Exception {
        when(searchService.findById(searchId)).thenThrow(new SearchNotFoundException(searchId));

        mockMvc.perform(get("/api/v1/searches/{id}", searchId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VACANCY_SEARCH_NOT_FOUND"))
                .andExpect(jsonPath("$.category").value("NOT_FOUND"));
    }

    @Test
    void malformedSearchIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/searches/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void createReturnsCreatedStatusAndIgnoresTheResumeNameFromTheRequest() throws Exception {
        when(searchService.create(any())).thenReturn(profile(SearchStatus.ACTIVE));
        String body = """
                {"name":"Java in Moscow","sourceUrl":"https://hh.ru/search/vacancy?text=java",
                 "resumeId":"%s","resumeName":"ПОДДЕЛЬНОЕ ИМЯ","pagesLimit":5}
                """.formatted(resumeId);

        mockMvc.perform(post("/api/v1/searches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resumeName").value("Java Developer"));

        ArgumentCaptor<CreateSearchCommand> captor = ArgumentCaptor.forClass(CreateSearchCommand.class);
        verify(searchService).create(captor.capture());
        CreateSearchCommand command = captor.getValue();
        assertEquals("Java in Moscow", command.name());
        assertEquals("https://hh.ru/search/vacancy?text=java", command.sourceUrl());
        assertEquals(resumeId, command.resumeId());
        assertEquals(5, command.pagesLimit());
    }

    @Test
    void createWithoutResumeIdIsRejected() throws Exception {
        String body = """
                {"name":"Java in Moscow","sourceUrl":"https://hh.ru/search/vacancy?text=java","pagesLimit":5}
                """;

        expectValidationFailure(post("/api/v1/searches").contentType(MediaType.APPLICATION_JSON).content(body));
        verify(searchService, never()).create(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "ab"})
    void createWithATooShortNameIsRejected(String name) throws Exception {
        expectValidationFailure(post("/api/v1/searches").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(name, "https://hh.ru/search/vacancy?text=java", 5)));
        verify(searchService, never()).create(any());
    }

    @Test
    void createWithATooLongNameIsRejected() throws Exception {
        expectValidationFailure(post("/api/v1/searches").contentType(MediaType.APPLICATION_JSON)
                .content(createBody("n".repeat(121), "https://hh.ru/search/vacancy?text=java", 5)));
    }

    @Test
    void createWithAnEmptyOrTooLongLinkIsRejected() throws Exception {
        expectValidationFailure(post("/api/v1/searches").contentType(MediaType.APPLICATION_JSON)
                .content(createBody("Java in Moscow", "   ", 5)));
        expectValidationFailure(post("/api/v1/searches").contentType(MediaType.APPLICATION_JSON)
                .content(createBody("Java in Moscow", "https://hh.ru/search/vacancy?text=" + "a".repeat(4063), 5)));
        verify(searchService, never()).create(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 21})
    void createWithAPagesLimitOutsideTheAllowedRangeIsRejected(int pagesLimit) throws Exception {
        expectValidationFailure(post("/api/v1/searches").contentType(MediaType.APPLICATION_JSON)
                .content(createBody("Java in Moscow", "https://hh.ru/search/vacancy?text=java", pagesLimit)));
        verify(searchService, never()).create(any());
    }

    @Test
    void duplicateSearchIsReportedAsConflictProblemDetail() throws Exception {
        when(searchService.create(any())).thenThrow(new SearchAlreadyExistsException());

        mockMvc.perform(post("/api/v1/searches").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Java in Moscow", "https://hh.ru/search/vacancy?text=java", 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VACANCY_SEARCH_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void domainValidationFromTheServiceIsReportedAsBadRequestProblemDetail() throws Exception {
        when(searchService.create(any())).thenThrow(new InvalidSearchDataException("Link must point to hh.ru."));

        mockMvc.perform(post("/api/v1/searches").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Java in Moscow", "https://example.com", 5)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEARCH_DATA"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void updateMapsTheRequestIntoTheServiceCommand() throws Exception {
        when(searchService.update(eq(searchId), any())).thenReturn(profile(SearchStatus.ACTIVE));
        String body = """
                {"name":"Kotlin in Moscow","sourceUrl":"https://hh.ru/search/vacancy?text=kotlin","pagesLimit":12}
                """;

        mockMvc.perform(put("/api/v1/searches/{id}", searchId).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(searchId.toString()));

        ArgumentCaptor<UpdateSearchCommand> captor = ArgumentCaptor.forClass(UpdateSearchCommand.class);
        verify(searchService).update(eq(searchId), captor.capture());
        assertEquals("Kotlin in Moscow", captor.getValue().name());
        assertEquals("https://hh.ru/search/vacancy?text=kotlin", captor.getValue().sourceUrl());
        assertEquals(12, captor.getValue().pagesLimit());
    }

    @Test
    void updateWithAnInvalidBodyIsRejected() throws Exception {
        expectValidationFailure(put("/api/v1/searches/{id}", searchId).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"ab","sourceUrl":"https://hh.ru/search/vacancy?text=kotlin","pagesLimit":12}"""));
        expectValidationFailure(put("/api/v1/searches/{id}", searchId).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Kotlin in Moscow","sourceUrl":"","pagesLimit":12}"""));
        expectValidationFailure(put("/api/v1/searches/{id}", searchId).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Kotlin in Moscow","sourceUrl":"https://hh.ru/search/vacancy","pagesLimit":21}"""));

        verify(searchService, never()).update(any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void statusRequestPassesThePausedFlagAsIs(boolean paused) throws Exception {
        when(searchService.setPaused(searchId, paused)).thenReturn(profile(paused ? SearchStatus.PAUSED : SearchStatus.ACTIVE));

        mockMvc.perform(put("/api/v1/searches/{id}/status", searchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paused\":%s}".formatted(paused)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(paused ? "PAUSED" : "ACTIVE"));

        verify(searchService).setPaused(searchId, paused);
    }

    @Test
    void scanIsDelegatedToTheScanGatewayAndNotToTheSearchService() throws Exception {
        when(scanGateway.scanSearch(searchId)).thenReturn(profile(SearchStatus.SCANNING));

        mockMvc.perform(post("/api/v1/searches/{id}/scan", searchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCANNING"));

        verify(scanGateway).scanSearch(searchId);
        verifyNoInteractions(searchService);
    }

    @Test
    void ingestRequestIsMappedIntoNormalizedVacancies() throws Exception {
        when(searchService.ingestVacancies(any(), anyList()))
                .thenReturn(new VacancyIngestionResult(searchId, 2, 1, 1, 11, 130));
        String body = """
                {"vacancies":[
                  {"hhVacancyId":"118123456","title":"Java Developer","employerId":"3529","companyName":"Example",
                   "salary":{"from":100000,"to":200000,"currency":"RUR","gross":true},
                   "location":"Moscow","workFormat":"Remote","experience":"1-3 years",
                   "publishedAt":"2026-09-01T08:00:00Z","salaryText":"from 100000",
                   "labels":["Quick response","Trusted"],"hasQuestionnaire":true,
                   "alreadyApplied":true,"closed":false,"position":3},
                  {"hhVacancyId":"118123457","title":"Kotlin Developer","companyName":"Example 2","position":7}
                ]}
                """;

        mockMvc.perform(post("/api/v1/searches/{id}/vacancies", searchId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NormalizedVacancy>> captor = ArgumentCaptor.forClass(List.class);
        verify(searchService).ingestVacancies(eq(searchId), captor.capture());
        List<NormalizedVacancy> mapped = captor.getValue();
        assertEquals(2, mapped.size());

        NormalizedVacancy first = mapped.getFirst();
        assertEquals("118123456", first.hhVacancyId());
        assertEquals("Java Developer", first.title());
        assertEquals("3529", first.employerId());
        assertEquals("Example", first.companyName());
        assertEquals(100000L, first.salary().from());
        assertEquals(200000L, first.salary().to());
        assertEquals("RUR", first.salary().currency());
        assertTrue(first.salary().gross());
        assertEquals("Moscow", first.location());
        assertEquals("Remote", first.workFormat());
        assertEquals("1-3 years", first.experience());
        assertEquals(Instant.parse("2026-09-01T08:00:00Z"), first.publishedAt());
        assertEquals("from 100000", first.salaryText());
        assertEquals(List.of("Quick response", "Trusted"), first.labels());
        assertEquals(Boolean.TRUE, first.hasQuestionnaire());
        assertTrue(first.alreadyApplied());
        assertFalse(first.closed());
        assertEquals(3, first.position());

        NormalizedVacancy second = mapped.get(1);
        assertNull(second.salary(), "отсутствующая зарплата не должна превращаться в пустой объект");
        assertNull(second.labels());
        assertNull(second.hasQuestionnaire(), "неизвестное наличие анкеты остаётся неизвестным");
        assertFalse(second.alreadyApplied());
        assertFalse(second.closed());
        assertEquals(7, second.position());
    }

    @Test
    void ingestResponseCarriesEveryCounter() throws Exception {
        when(searchService.ingestVacancies(any(), anyList()))
                .thenReturn(new VacancyIngestionResult(searchId, 5, 3, 2, 11, 130));

        mockMvc.perform(post("/api/v1/searches/{id}/vacancies", searchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vacancies\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.searchId").value(searchId.toString()))
                .andExpect(jsonPath("$.received").value(5))
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.newVacancies").value(11))
                .andExpect(jsonPath("$.totalVacancies").value(130));
    }

    @Test
    void nullElementInsideTheVacancyListIsRejectedAtTheHttpBoundary() throws Exception {
        expectValidationFailure(post("/api/v1/searches/{id}/vacancies", searchId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"vacancies\":[null]}"));
        verify(searchService, never()).ingestVacancies(any(), anyList());
    }

    @Test
    void ingestWithoutTheVacancyListIsRejected() throws Exception {
        expectValidationFailure(post("/api/v1/searches/{id}/vacancies", searchId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        verify(searchService, never()).ingestVacancies(any(), anyList());
    }

    @Test
    void ingestOfMoreThanFiveHundredVacanciesIsRejected() throws Exception {
        String items = IntStream.range(0, 501)
                .mapToObj(index -> """
                        {"hhVacancyId":"%d","title":"Java Developer","companyName":"Example","position":%d}"""
                        .formatted(118000000 + index, index))
                .collect(Collectors.joining(","));

        expectValidationFailure(post("/api/v1/searches/{id}/vacancies", searchId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"vacancies\":[%s]}".formatted(items)));
        verify(searchService, never()).ingestVacancies(any(), anyList());
    }

    @Test
    void deletingASearchReturnsNoContentWithoutABody() throws Exception {
        doNothing().when(searchService).delete(searchId);

        mockMvc.perform(delete("/api/v1/searches/{id}", searchId))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString().isEmpty(),
                        "204 не должен содержать тело ответа"));

        verify(searchService).delete(searchId);
    }

    @Test
    void deletingAMissingSearchIsReportedAsNotFound() throws Exception {
        doThrow(new SearchNotFoundException(searchId)).when(searchService).delete(searchId);

        mockMvc.perform(delete("/api/v1/searches/{id}", searchId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VACANCY_SEARCH_NOT_FOUND"));
    }

    @Test
    void malformedJsonBodyIsRejected() throws Exception {
        expectValidationFailure(post("/api/v1/searches")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":"));
    }

    private void expectValidationFailure(RequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    private String createBody(String name, String sourceUrl, int pagesLimit) {
        return """
                {"name":"%s","sourceUrl":"%s","resumeId":"%s","pagesLimit":%d}
                """.formatted(name, sourceUrl, resumeId, pagesLimit);
    }
}
