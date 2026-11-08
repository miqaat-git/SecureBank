package com.securebank.gateway.ratelimit;

import java.time.Duration;

/**
 * Counter store behind the limiter. The bundled implementation is per-instance memory; a deployment that needs
 * limits shared across gateway instances must provide its own implementation backed by a provisioned shared store.
 */
public interface RateLimitStore {

    /** Counts one request for the key; denies once the window's limit is exhausted. */
    RateLimitDecision tryAcquire(String key, int limit, Duration window);
}
