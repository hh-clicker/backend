package org.hhclicker.backend.vacancy.stoplist.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.util.UUID;

public class CompanyBanNotFoundException extends ApplicationException {

    public CompanyBanNotFoundException(UUID banId) {
        super(
                ApplicationErrorCategory.NOT_FOUND,
                "COMPANY_BAN_NOT_FOUND",
                "Блокировка %s не найдена.".formatted(banId)
        );
    }
}
