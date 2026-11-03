package org.hhclicker.backend.vacancy.catalog.model;

/**
 * Область блокировки работодателя. Хранится вместе с вакансией, поэтому объявлена в каталоге:
 * подсценарий stoplist зависит от каталога, обратной зависимости нет.
 */
public enum CompanyBanScope {
    RESUME,
    GLOBAL
}
