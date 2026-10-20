package com.securebank.account.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * The only customer data Account Service reads: identity and status. Everything else the Customer Service might
 * return is ignored and never stored here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerSummary(UUID id, String status) {
}
