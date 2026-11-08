package com.securebank.gateway.ratelimit;

/** Outcome of one acquire/peek. retryAfterSeconds is meaningful only when not allowed (always at least 1). */
public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {

    public static RateLimitDecision permit() {
        return new RateLimitDecision(true, 0);
    }

    public static RateLimitDecision deny(long retryAfterSeconds) {
        return new RateLimitDecision(false, Math.max(1, retryAfterSeconds));
    }
}
