package org.hhclicker.backend.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Конкретные bean-значения shared-конфигурации без запуска Spring context.
 */
class SharedConfigurationTests {

    @Test
    void applicationClockIsAWorkingNonFixedUtcSystemClock() {
        Clock clock = new TimeConfiguration().applicationClock();

        assertNotNull(clock);
        assertEquals(ZoneOffset.UTC, clock.getZone());
        assertDoesNotThrow(clock::instant);
        assertNotEquals(Clock.fixed(clock.instant(), ZoneOffset.UTC), clock,
                "application clock не должен быть зафиксирован на тестовом моменте");
    }

    @Test
    void openApiCarriesThePublishedServiceMetadata() {
        OpenAPI openApi = new OpenApiConfiguration().hhClickerOpenApi();

        assertNotNull(openApi);
        assertNotNull(openApi.getInfo());
        assertEquals("HH Clicker API", openApi.getInfo().getTitle());
        assertEquals("v1", openApi.getInfo().getVersion());
        assertEquals("Локальный API управления поисками вакансий и автоматическими откликами.",
                openApi.getInfo().getDescription());
    }
}
