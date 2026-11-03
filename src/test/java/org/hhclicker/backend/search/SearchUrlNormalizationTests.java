package org.hhclicker.backend.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.hhclicker.backend.search.model.VacancySearch;
import org.hhclicker.backend.search.service.CreateSearchCommand;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.when;

/**
 * Нормализация и проверка ссылки поиска HH. Правила проверяются через публичный {@code create},
 * private-методы напрямую не вызываются.
 */
class SearchUrlNormalizationTests extends SearchServiceTestSupport {

    static Stream<Arguments> rejectedUrls() {
        String resume = "abcdef0123456789abcd";
        return Stream.of(
                Arguments.of("null", null),
                Arguments.of("пустая строка", ""),
                Arguments.of("только пробелы", "   "),
                Arguments.of("нет схемы", "hh.ru/search/vacancy"),
                Arguments.of("чужая схема", "ftp://hh.ru/search/vacancy"),
                Arguments.of("чужой домен", "https://example.com/search/vacancy"),
                Arguments.of("похожий домен", "https://evilhh.ru/search/vacancy"),
                Arguments.of("домен-приманка", "https://hh.ru.evil.com/search/vacancy"),
                Arguments.of("другой путь", "https://hh.ru/vacancies"),
                Arguments.of("путь со слэшем", "https://hh.ru/search/vacancy/"),
                Arguments.of("корень сайта", "https://hh.ru/"),
                Arguments.of("логин в ссылке", "https://user@hh.ru/search/vacancy"),
                Arguments.of("нестандартный порт", "https://hh.ru:8443/search/vacancy"),
                Arguments.of("битый URI", "https://hh .ru/search/vacancy"),
                Arguments.of("битое percent-encoding", "https://hh.ru/search/vacancy?a=%zz"),
                Arguments.of("пустой resume", "https://hh.ru/search/vacancy?resume="),
                Arguments.of("resume без значения", "https://hh.ru/search/vacancy?resume"),
                Arguments.of("чужой resume", "https://hh.ru/search/vacancy?resume=deadbeef0123456789ab"),
                Arguments.of("resume дважды",
                        "https://hh.ru/search/vacancy?resume=" + resume + "&resume=" + resume),
                Arguments.of("resume дважды с закодированным именем",
                        "https://hh.ru/search/vacancy?resume=" + resume + "&%72esume=" + resume));
    }

    @BeforeEach
    void setUp() {
        stubResume();
        when(repository.findByResumeIdAndSourceHash(any(), anyString())).thenReturn(Optional.empty());
    }

    private CreateSearchCommand command(String sourceUrl) {
        return new CreateSearchCommand("Java в Москве", sourceUrl, resumeId, 5);
    }

    private VacancySearch storedFor(String sourceUrl) {
        clearInvocations(repository);
        service.create(command(sourceUrl));
        return captureInsert();
    }

    // --- принятые ссылки -------------------------------------------------------------------

    private String urlFor(String sourceUrl) {
        return storedFor(sourceUrl).sourceUrl();
    }

    @Test
    void bothHttpAndHttpsAreAcceptedAndAlwaysStoredAsHttps() {
        assertEquals(CANONICAL_URL, urlFor("http://hh.ru/search/vacancy?text=java"));
        assertEquals(CANONICAL_URL, urlFor("https://hh.ru/search/vacancy?text=java"));
    }

    @Test
    void regionalSubdomainOfHhIsAccepted() {
        assertEquals("https://spb.hh.ru/search/vacancy?items_on_page=100&page=0&text=java",
                urlFor("https://spb.hh.ru/search/vacancy?text=java"));
    }

    @Test
    void hostIsLoweredAndSurroundingSpacesAreTrimmed() {
        assertEquals(CANONICAL_URL, urlFor("  https://HH.RU/search/vacancy?text=java  "));
    }

    @Test
    void linkWithoutQueryGetsOnlyTheMandatoryParameters() {
        assertEquals("https://hh.ru/search/vacancy?items_on_page=100&page=0",
                urlFor("https://hh.ru/search/vacancy"));
    }

    @Test
    void resumeParameterMatchingTheSelectedResumeIsAcceptedAndKept() {
        assertEquals("https://hh.ru/search/vacancy?items_on_page=100&page=0"
                        + "&resume=" + HH_RESUME_ID,
                urlFor("https://hh.ru/search/vacancy?resume=" + HH_RESUME_ID));
    }

    @Test
    void percentEncodedResumeValueIsDecodedBeforeComparisonAndStoredCanonically() {
        assertEquals("https://hh.ru/search/vacancy?items_on_page=100&page=0"
                        + "&resume=" + HH_RESUME_ID,
                urlFor("https://hh.ru/search/vacancy?resume=%61bcdef0123456789abcd"));
    }

    @Test
    void equivalentPercentEncodedValuesProduceTheSameUrlAndHash() {
        VacancySearch plain = storedFor("https://hh.ru/search/vacancy?text=java");
        VacancySearch encoded = storedFor("https://hh.ru/search/vacancy?text=jav%61");

        assertEquals(plain.sourceUrl(), encoded.sourceUrl());
        assertEquals(plain.sourceHash(), encoded.sourceHash());
    }

    @Test
    void technicalParametersAreReplacedButHhSortOrderIsPreserved() {
        assertEquals("https://hh.ru/search/vacancy?items_on_page=100&order_by=relevance&page=0&text=java",
                urlFor("https://hh.ru/search/vacancy?order_by=relevance&items_on_page=20&page=7&text=java"));
    }

    @Test
    void missingSortOrderIsNotReplacedWithPublicationTime() {
        assertFalse(urlFor("https://hh.ru/search/vacancy?text=java").contains("order_by="));
    }

    @Test
    void otherParametersArePreservedAndSorted() {
        assertEquals("https://hh.ru/search/vacancy?area=1&items_on_page=100&page=0&schedule=remote&text=java",
                urlFor("https://hh.ru/search/vacancy?text=java&schedule=remote&area=1"));
    }

    @Test
    void fragmentIsNotStored() {
        assertEquals(CANONICAL_URL, urlFor("https://hh.ru/search/vacancy?text=java#results"));
    }

    @Test
    void standardHttpsPortIsAcceptedAndDropped() {
        assertEquals(CANONICAL_URL, urlFor("https://hh.ru:443/search/vacancy?text=java"));
    }

    @Test
    void equivalentLinksCollapseIntoOneStoredSearch() {
        List<String> equivalent = List.of(
                "https://hh.ru/search/vacancy?text=java",
                "http://hh.ru/search/vacancy?text=java",
                "https://HH.ru/search/vacancy?text=java",
                "https://hh.ru:443/search/vacancy?text=java",
                "https://hh.ru/search/vacancy?page=9&text=java&items_on_page=20",
                "https://hh.ru/search/vacancy?text=java#anchor",
                "  https://hh.ru/search/vacancy?text=java  ");

        String hash = null;
        for (String variant : equivalent) {
            VacancySearch stored = storedFor(variant);
            assertEquals(CANONICAL_URL, stored.sourceUrl(), "не совпала нормализованная ссылка для " + variant);
            if (hash == null) {
                hash = stored.sourceHash();
            }
            assertEquals(hash, stored.sourceHash(), "эквивалентные ссылки обязаны давать один sourceHash: " + variant);
        }
    }

    @Test
    void differentLinksProduceDifferentHashes() {
        String first = storedFor("https://hh.ru/search/vacancy?text=java").sourceHash();
        String second = storedFor("https://hh.ru/search/vacancy?text=kotlin").sourceHash();

        assertNotEquals(first, second);
    }

    // --- отклонённые ссылки ----------------------------------------------------------------

    @Test
    void hashIsAHexDigestOfFixedLength() {
        String hash = storedFor("https://hh.ru/search/vacancy?text=java").sourceHash();

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"), "ожидался шестнадцатеричный SHA-256, получено: " + hash);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedUrls")
    void invalidLinkIsRejected(String description, String sourceUrl) {
        assertInvalidSearchData(() -> service.create(command(sourceUrl)));
    }

    @Test
    void linkLongerThanTheLimitIsRejectedBeforeParsing() {
        String tooLong = "https://hh.ru/search/vacancy?text=" + "a".repeat(4063);
        assertEquals(4097, tooLong.length());

        assertInvalidSearchData(() -> service.create(command(tooLong)));
    }

    @Test
    void linkThatOnlyOverflowsAfterNormalizationIsAlsoRejected() {
        // Исходная ссылка укладывается в лимит, но обязательные параметры выводят её за 4096.
        String input = "https://hh.ru/search/vacancy?text=" + "a".repeat(4050);
        assertTrue(input.length() <= 4096);

        assertInvalidSearchData(() -> service.create(command(input)));
    }

    /**
     * Фиксирует текущее поведение: явный стандартный порт 80 для http отвергается как
     * «нестандартный», хотя http://hh.ru:80/... эквивалентен http://hh.ru/...
     */
    @Test
    void explicitHttpPortEightyIsRejectedAlthoughItIsTheStandardOne() {
        assertInvalidSearchData(() -> service.create(command("http://hh.ru:80/search/vacancy?text=java")));
    }
}
