package org.hhclicker.backend.vacancy.catalog.model;

/**
 * Канонический вид идентификатора работодателя HH.
 * <p>
 * Вакансии и стоп-лист сопоставляются по этому значению, поэтому числовой идентификатор
 * приводится к записи без ведущих нулей: иначе ссылка {@code /employer/0003529} и вакансия
 * работодателя {@code 3529} считались бы разными компаниями.
 */
public final class HhEmployerId {

    private static final char ZERO = '0';

    private HhEmployerId() {
    }

    public static String canonical(String employerId) {
        if (employerId.isEmpty() || !employerId.chars().allMatch(Character::isDigit)) {
            return employerId;
        }
        int firstSignificant = 0;
        while (firstSignificant < employerId.length() - 1 && employerId.charAt(firstSignificant) == ZERO) {
            firstSignificant++;
        }
        return employerId.substring(firstSignificant);
    }
}
