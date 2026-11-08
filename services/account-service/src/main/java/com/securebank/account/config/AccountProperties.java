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
     * @param auth               this service's own credential for the Customer Service; the caller's token is
     *                           never forwarded
     */
    public record CustomerService(
            @NotBlank @DefaultValue("http://localhost:8081") String baseUrl,
            @NotBlank @DefaultValue("/internal/v1/customers/{customerId}/eligibility") String customerPath,
            @NotNull @DefaultValue("2s") Duration connectTimeout,
            @NotNull @DefaultValue("3s") Duration readTimeout,
            @NotEmpty @DefaultValue("ACTIVE") List<String> eligibleStatuses,
            @DefaultValue Auth auth) {

        /**
         * Service-to-service authentication towards the Customer Service. OAUTH2_CLIENT_CREDENTIALS obtains, caches
         * and renews a Keycloak service-account token for the dedicated {@code securebank-account-service} client
         * (realm role {@code account-service}). NONE sends no Authorization header; it exists for tests and the
         * Customer Service will refuse such calls. Secrets come from the environment only.
         */
        public record Auth(
                @DefaultValue("NONE") Mode mode,
                @DefaultValue("") String staticToken,
                @DefaultValue("") String clientId,
                @DefaultValue("") String clientSecret,
                @DefaultValue("") String tokenUri,
                @DefaultValue("") String issuerUri,
                @DefaultValue("") String scope) {

            public enum Mode { NONE, OAUTH2_CLIENT_CREDENTIALS }

            /** Token endpoint: explicit token-uri, else derived from the Keycloak issuer URI. Blank when neither. */
            public String effectiveTokenUri() {
                if (tokenUri != null && !tokenUri.isBlank()) {
                    return tokenUri.trim();
                }
                if (issuerUri != null && !issuerUri.isBlank()) {
                    String issuer = issuerUri.trim();
                    return (issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer)
                            + "/protocol/openid-connect/token";
                }
                return "";
            }

            /** Never prints secrets. */
            @Override
            public String toString() {
                return "Auth[mode=" + mode + ", clientId=" + clientId + ", tokenUri=" + effectiveTokenUri()
                        + ", secrets=<redacted>]";
            }
        }
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
