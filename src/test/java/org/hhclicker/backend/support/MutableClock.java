package org.hhclicker.backend.support;

import java.time.*;

/**
 * Управляемые часы для тестов таймаутов: время двигается явным вызовом, а не ожиданием.
 * Ни один тест не должен зависеть от скорости машины или использовать {@code sleep}.
 */
public final class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    public MutableClock(Instant instant) {
        this(instant, ZoneOffset.UTC);
    }

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return new MutableClock(instant, other);
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    public void advanceSeconds(long seconds) {
        advance(Duration.ofSeconds(seconds));
    }
}
