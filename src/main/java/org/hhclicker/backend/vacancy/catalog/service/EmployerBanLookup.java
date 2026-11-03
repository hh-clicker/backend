package org.hhclicker.backend.vacancy.catalog.service;

import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Порт каталога к стоп-листу работодателей.
 * <p>
 * Каталог не импортирует подсценарий {@code stoplist}: реализацию порта предоставляет он сам,
 * поэтому зависимость остаётся односторонней stoplist → catalog.
 */
public interface EmployerBanLookup {

    /**
     * Возвращает применимые области блокировки только для заблокированных работодателей
     * в контексте резюме. Глобальная блокировка имеет приоритет над блокировкой резюме.
     */
    Map<String, CompanyBanScope> findApplicableScopes(Set<String> employerIds, UUID resumeId);
}
