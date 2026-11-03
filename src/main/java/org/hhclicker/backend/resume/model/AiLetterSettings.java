package org.hhclicker.backend.resume.model;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public record AiLetterSettings(boolean enabled, String template, int maxLength) {
    public static final String MARKER = "#AI_CHANGE#";
    public static final String DEFAULT_TEMPLATE = "Здравствуйте!\n\n#AI_CHANGE#\n\nБуду рад обсудить задачи и подробнее рассказать о своём опыте.";

    public AiLetterSettings {
        template = template == null ? DEFAULT_TEMPLATE : template;
    }

    public static AiLetterSettings defaults() {
        return new AiLetterSettings(false, DEFAULT_TEMPLATE, 1500);
    }

    public static void validateFallback(String letter) {
        if (letter == null || letter.length() > 10000 || letter.contains(MARKER) || letter.indexOf('\0') >= 0)
            throw invalid("Обычное письмо: до 10 000 символов, без маркера #AI_CHANGE#.");
    }

    private static ApplicationException invalid(String message) {
        return new ApplicationException(ApplicationErrorCategory.CONFLICT, "INVALID_AI_LETTER_SETTINGS", message);
    }

    public void validate() {
        if (maxLength < 200 || maxLength > 5000 || template.length() > 5000 || template.indexOf('\0') >= 0)
            throw invalid("Лимит AI-письма должен быть от 200 до 5000 символов, шаблон — не длиннее 5000.");
        if (enabled && (template.indexOf(MARKER) < 0 || template.indexOf(MARKER) != template.lastIndexOf(MARKER)
                || maxLength - template.length() + MARKER.length() < 200))
            throw invalid("Добавьте ровно один #AI_CHANGE# и оставьте не менее 200 символов для генерации в лимите письма.");
    }
}
