package com.securebank.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Administrative reason recorded when an account is frozen or unfrozen. */
public record StatusChangeRequest(
        @NotBlank(message = "reason is required") @Size(max = 255, message = "reason must be at most 255 characters")
        String reason) {
}
