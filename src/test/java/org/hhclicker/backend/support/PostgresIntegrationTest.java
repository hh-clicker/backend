package org.hhclicker.backend.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Базовый класс интеграционных тестов на настоящем PostgreSQL.
 * <p>
 * Контейнер поднимается один раз на JVM: контекст Spring кэшируется между тестовыми классами,
 * а Liquibase применяет тот же changelog, что и production. H2 сознательно не используется —
 * репозитории опираются на advisory-блокировки, частичные уникальные индексы, {@code filter (where ...)}
 * и арифметику часового пояса Europe/Moscow.
 * <p>
 * Тестовые классы-наследники НЕ должны помечаться {@code @Transactional}: часть проверок
 * наблюдает коммиты фоновых потоков, которые не видны внутри откатываемой тестовой транзакции.
 */
public abstract class PostgresIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("hh_clicker")
            .withUsername("hh_clicker")
            .withPassword("hh_clicker");

    static {
        POSTGRES.start();
    }

    /**
     * Переопределяет только координаты подключения. Схема {@code hhclicker}, Liquibase и пул
     * остаются такими же, как в {@code application.yaml}, поэтому тест проверяет реальную конфигурацию.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
