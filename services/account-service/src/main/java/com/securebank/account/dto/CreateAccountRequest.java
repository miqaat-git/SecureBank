package com.securebank.account.dto;

import com.securebank.account.domain.AccountType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * Request to open an account. There is intentionally no balance field: accounts always open with a zero balance
 * and unknown JSON properties are rejected, so a client cannot smuggle in a balance.
 *
 * @param currency optional ISO-4217 code in upper case; defaults to INR (the only supported currency)
 */
public record CreateAccountRequest(
        @NotNull(message = "customerId is required") UUID customerId,
        @NotNull(message = "accountType is required (SAVINGS or CURRENT)") AccountType accountType,
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter upper-case ISO code") String currency) {
}
