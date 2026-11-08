package com.securebank.customer.dto;

import java.util.UUID;

/** Internal projection used by the Account Service: only what is needed to decide eligibility. */
public record CustomerEligibilityResponse(UUID id, String status) {
}
