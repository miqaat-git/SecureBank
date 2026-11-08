package com.securebank.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InMemoryRateLimitStoreTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final InMemoryRateLimitStore store = new InMemoryRateLimitStore(clock, 100);
    private final Duration minute = Duration.ofMinutes(1);

    @Test
    void allowsUpToTheLimitThenDeniesWithRetryAfterAndResetsAfterTheWindow() {
        assertThat(store.tryAcquire("k", 2, minute).allowed()).isTrue();
        assertThat(store.tryAcquire("k", 2, minute).allowed()).isTrue();
        clock.now = clock.now.plusSeconds(20);
        RateLimitDecision denied = store.tryAcquire("k", 2, minute);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(40);

        clock.now = clock.now.plusSeconds(41);
        assertThat(store.tryAcquire("k", 2, minute).allowed()).isTrue();
    }

    @Test
    void keysAreIndependent() {
        assertThat(store.tryAcquire("a", 1, minute).allowed()).isTrue();
        assertThat(store.tryAcquire("a", 1, minute).allowed()).isFalse();
        assertThat(store.tryAcquire("b", 1, minute).allowed()).isTrue();
    }

    @Test
    void stateIsBoundedByMaxKeys() {
        for (int i = 0; i < 10_000; i++) {
            store.tryAcquire("key-" + i, 5, minute);
        }
        assertThat(store.size()).isLessThanOrEqualTo(100);
    }
}
