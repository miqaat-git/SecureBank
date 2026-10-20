package com.securebank.transaction.workflow;

import com.securebank.transaction.config.TransferProperties;
import java.time.Duration;

final class Backoff {

    private Backoff() {
    }

    /** Exponential backoff: base * 2^(attempts-1), capped at retryMaxDelay. */
    static Duration delay(TransferProperties props, int attempts) {
        long base = props.retryBaseDelay().toMillis();
        long max = props.retryMaxDelay().toMillis();
        if (base >= max) {
            return Duration.ofMillis(max);
        }
        int shift = Math.min(Math.max(attempts - 1, 0), 20);
        return Duration.ofMillis(Math.min(max, base << shift));
    }
}
