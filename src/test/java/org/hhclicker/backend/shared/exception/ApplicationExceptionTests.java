package org.hhclicker.backend.shared.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Значимый контракт базового доменного исключения без искусственных subclasses.
 */
class ApplicationExceptionTests {

    @Test
    void constructorKeepsPublicErrorDataAndHasNoArtificialCause() {
        ApplicationException exception = new ApplicationException(
                ApplicationErrorCategory.CONFLICT, "TEST_CONFLICT", "Безопасное сообщение");

        assertEquals(ApplicationErrorCategory.CONFLICT, exception.category());
        assertEquals("TEST_CONFLICT", exception.machineCode());
        assertEquals("Безопасное сообщение", exception.safeMessage());
        assertEquals("Безопасное сообщение", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void constructorWithCauseKeepsTheExactCauseAndTheSafePublicMessage() {
        RuntimeException cause = new RuntimeException("внутренняя диагностическая информация");

        ApplicationException exception = new ApplicationException(
                ApplicationErrorCategory.INTERNAL, "TEST_INTERNAL", "Безопасное сообщение", cause);

        assertSame(cause, exception.getCause());
        assertEquals("Безопасное сообщение", exception.getMessage());
        assertEquals("Безопасное сообщение", exception.safeMessage());
    }

    @Test
    void requiredPublicErrorFieldsRejectNullInBothConstructors() {
        assertThrows(NullPointerException.class,
                () -> new ApplicationException(null, "CODE", "message"));
        assertThrows(NullPointerException.class,
                () -> new ApplicationException(ApplicationErrorCategory.VALIDATION, null, "message"));
        assertThrows(NullPointerException.class,
                () -> new ApplicationException(ApplicationErrorCategory.VALIDATION, "CODE", null));
        assertThrows(NullPointerException.class,
                () -> new ApplicationException(null, "CODE", "message", new RuntimeException()));
        assertThrows(NullPointerException.class,
                () -> new ApplicationException(ApplicationErrorCategory.VALIDATION, null, "message", new RuntimeException()));
        assertThrows(NullPointerException.class,
                () -> new ApplicationException(ApplicationErrorCategory.VALIDATION, "CODE", null, new RuntimeException()));
    }

    @Test
    void causeAwareConstructorAcceptsNullCauseWithoutCreatingOne() {
        ApplicationException exception = new ApplicationException(
                ApplicationErrorCategory.EXTERNAL_SYSTEM, "REMOTE_FAILURE", "Сервис недоступен", null);

        assertNull(exception.getCause());
        assertEquals("Сервис недоступен", exception.getMessage());
    }
}
