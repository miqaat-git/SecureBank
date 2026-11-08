package com.securebank.ledger.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(
        @Valid @DefaultValue Accounting accounting,
        @Valid @DefaultValue Security security) {

    public record Accounting(
            @NotEmpty @DefaultValue("INR") Set<String> supportedCurrencies,
            @Min(0) @Max(4) @DefaultValue("2") int maxAmountScale,
            @NotNull @DecimalMin("0.0001") @DefaultValue("100000000") BigDecimal maxLineAmount,
            @Min(2) @Max(1000) @DefaultValue("200") int maxLinesPerEntry) {
    }

    public record Security(
            @DefaultValue("true") boolean publicApiDocs,
            @DefaultValue("") String resourceClientId) {
    }
}
