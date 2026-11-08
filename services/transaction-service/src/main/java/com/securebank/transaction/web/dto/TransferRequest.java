package com.securebank.transaction.web.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/** Transfer request. The caller's identity comes from the JWT, never from this body. */
public record TransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID destinationAccountId,
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter ISO currency code") String currency) {
}
