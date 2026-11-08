package com.securebank.gateway.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixed-window counters in a size-bounded LRU map (memory use is capped by {@code maxKeys}; under a flood of distinct
 * keys the least recently used counters are dropped, which can only forgive callers, never block unrelated ones).
 * Per-instance only: it does not coordinate with other gateway instances.
 */
public class InMemoryRateLimitStore implements RateLimitStore {

    private static final class Window {
        long startMillis;
        int count;
    }

    private final Clock clock;
    private final Map<String, Window> windows;

    public InMemoryRateLimitStore(Clock clock, int maxKeys) {
        this.clock = clock;
        this.windows = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
                return size() > maxKeys;
            }
        };
    }

    @Override
    public synchronized RateLimitDecision tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        Window w = windows.get(key);
        if (w == null || now - w.startMillis >= window.toMillis()) {
            w = new Window();
            w.startMillis = now;
            windows.put(key, w);
        }
        if (w.count >= limit) {
            return RateLimitDecision.deny(secondsLeft(w, window, now));
        }
        w.count++;
        return RateLimitDecision.permit();
    }

    synchronized void clear() {
        windows.clear();
    }

    synchronized int size() {
        return windows.size();
    }

    private static long secondsLeft(Window w, Duration window, long now) {
        long remainingMillis = w.startMillis + window.toMillis() - now;
        return (remainingMillis + 999) / 1000;
    }
}
