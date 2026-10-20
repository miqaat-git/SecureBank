package com.securebank.transaction.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Business and workflow tuning for transfers (prefix securebank.transfer). */
@ConfigurationProperties(prefix = "securebank.transfer")
public record TransferProperties(
        @DefaultValue("INR") Set<String> supportedCurrencies,
        @DefaultValue("1000000.00") BigDecimal maxAmount,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("2s") Duration retryBaseDelay,
        @DefaultValue("5m") Duration retryMaxDelay,
        @DefaultValue("2m") Duration leaseDuration,
        @DefaultValue Recovery recovery) {

    public record Recovery(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("25") int batchSize) {
    }
}
