package com.securebank.account.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Typed configuration (prefix {@code account}). Secrets are never defaulted here. */
@ConfigurationProperties(prefix = "account")
@Validated
public record AccountProperties(
        @DefaultValue CustomerService customerService,
        @DefaultValue Security security,
        @DefaultValue Limits limits) {

    /**
     * Remote Customer Service used to validate that a customer exists and is eligible.
     *
     * @param baseUrl            e.g. http://localhost:8081 (env CUSTOMER_SERVICE_BASE_URL)
     * @param customerPath       path template of the customer lookup endpoint, {customerId} is substituted
     * @param eligibleStatuses   customer statuses that may open accounts (case-insensitive)
     * @param forwardCallerToken forward the caller's bearer token to Customer Service
     */
    public record CustomerService(
            @NotBlank @DefaultValue("http://localhost:8081") String baseUrl,
            @NotBlank @DefaultValue("/api/v1/customers/{customerId}") String customerPath,
            @NotNull @DefaultValue("2s") Duration connectTimeout,
            @NotNull @DefaultValue("3s") Duration readTimeout,
            @NotEmpty @DefaultValue("ACTIVE") List<String> eligibleStatuses,
            @DefaultValue("true") boolean forwardCallerToken) {
    }

    /**
     * JWT / Keycloak settings.
     *
     * @param issuerUri        mandatory; env ACCOUNT_JWT_ISSUER_URI (the Keycloak realm issuer)
     * @param audience         optional; when set, tokens must contain this value in their "aud" claim
     * @param customerIdClaim  claim carrying the caller's customer UUID for CUSTOMER tokens
     * @param resourceClientId Keycloak client whose client roles are also mapped to authorities
     */
    public record Security(
            @NotBlank String issuerUri,
            @DefaultValue("") String audience,
            @NotBlank @DefaultValue("customer_id") String customerIdClaim,
            @NotBlank @DefaultValue("securebank-account-service") String resourceClientId) {
    }

    /** Business limits. */
    public record Limits(@Min(1) @DefaultValue("5") int maxOpenAccountsPerCustomer) {
    }
}
