package org.hhclicker.backend.vacancy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.hhclicker.backend.vacancy.catalog.model.HhEmployerId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Канонический вид идентификатора работодателя: вакансии и стоп-лист сопоставляются по нему,
 * поэтому {@code /employer/0003529} и работодатель {@code 3529} обязаны совпасть.
 */
class HhEmployerIdTests {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "3529, 3529",
            "0003529, 3529",
            "0, 0",
            "000, 0",
            "0000000000000000000000000000000000000000000000000000000000000001, 1"
    })
    void leadingZeroesOfANumericIdentifierAreDropped(String raw, String expected) {
        assertEquals(expected, HhEmployerId.canonical(raw));
    }

    @Test
    void emptyIdentifierIsReturnedUnchanged() {
        assertEquals("", HhEmployerId.canonical(""));
    }

    /**
     * Нечисловые значения не канонизируются: сопоставление остаётся посимвольным.
     */
    @ParameterizedTest
    @CsvSource({
            "abc, abc",
            "00abc, 00abc",
            "35a29, 35a29",
            "'3529 ', '3529 '"
    })
    void nonNumericIdentifierIsLeftAsIs(String raw, String expected) {
        assertEquals(expected, HhEmployerId.canonical(raw));
    }
}
