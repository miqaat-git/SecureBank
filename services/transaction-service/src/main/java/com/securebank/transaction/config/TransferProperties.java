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
        @DefaultValue Recovery recovery,
        @DefaultValue Funding funding) {

    /**
     * Administrator-only simulated funding (Blueprint D7). There is no external source: a funding event credits a
     * customer account against a ledger-only contra reference and is never initiated by a customer.
     * {@code maxAmount} bounds one funding event (INR, whole paise).
     */
    public record Funding(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("100000.00") BigDecimal maxAmount) {

        public Funding {
            if (maxAmount == null || maxAmount.signum() <= 0) {
                throw new IllegalStateException("securebank.transfer.funding.max-amount must be positive");
            }
        }
    }

    public record Recovery(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("25") int batchSize) {
    }
}
