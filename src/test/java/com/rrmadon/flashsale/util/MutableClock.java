package com.rrmadon.flashsale.util;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** A clock you move by hand -- so TTL-adjacent behaviour is testable without sleeping. */
public class MutableClock extends Clock {
    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private MutableClock(Instant start, ZoneId zone) {
        this.instant = start;
        this.zone = zone;
    }

    public void advance(java.time.Duration d) {
        instant = instant.plus(d);
    }

    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId zone) { return new MutableClock(instant, zone); }
    @Override public Instant instant() { return instant; }
}
