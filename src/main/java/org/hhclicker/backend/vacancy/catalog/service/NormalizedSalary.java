package org.hhclicker.backend.vacancy.catalog.service;

/**
 * Валюта принимается строкой и проверяется прикладным сервисом, чтобы нарушение контракта
 * возвращалось стабильным машинным кодом, а не ошибкой разбора HTTP-тела.
 */
public record NormalizedSalary(
        Long from,
        Long to,
        String currency,
        boolean gross
) {
}
