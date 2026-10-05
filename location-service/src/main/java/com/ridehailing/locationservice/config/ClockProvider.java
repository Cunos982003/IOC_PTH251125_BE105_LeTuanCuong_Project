package com.ridehailing.locationservice.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

public interface ClockProvider {
    Instant now();
    long epochMilli();

    static ClockProvider system() {
        return new SystemClockProvider();
    }

    class SystemClockProvider implements ClockProvider {
        private final Clock clock = Clock.systemUTC();

        @Override
        public Instant now() {
            return clock.instant();
        }

        @Override
        public long epochMilli() {
            return clock.millis();
        }
    }

    class FixedClockProvider implements ClockProvider {
        private final Clock clock;

        public FixedClockProvider(Instant fixedInstant) {
            this.clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        }

        public FixedClockProvider(long epochMilli) {
            this.clock = Clock.fixed(Instant.ofEpochMilli(epochMilli), ZoneOffset.UTC);
        }

        @Override
        public Instant now() {
            return clock.instant();
        }

        @Override
        public long epochMilli() {
            return clock.millis();
        }
    }
}