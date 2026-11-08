package com.securebank.transaction.web.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Administrator funding request (Blueprint D7). There is deliberately no source, balance or customer field: the
 * source is the fixed ledger-only contra reference and the actor is identified by the JWT. Any extra property
 * (for example a smuggled sourceAccountId or businessReference) is rejected with 400 instead of being ignored.
 *
 * <p>Strictness is enforced by {@link StrictFundingRequestDeserializer}, scoped to this type only: Spring Boot's
 * ObjectMapper disables FAIL_ON_UNKNOWN_PROPERTIES and {@code @JsonIgnoreProperties(ignoreUnknown = false)} is only
 * the default, so neither would reject unknown fields.
 */
@JsonDeserialize(using = StrictFundingRequestDeserializer.class)
public record FundingRequest(
        @NotNull UUID targetAccountId,
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter ISO currency code") String currency,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.:-]{8,64}$",
                message = "must be 8-64 characters of letters, digits, '_', '-', '.', ':'") String fundingReference,
        @NotBlank @Size(max = 255) String reason) {
}
