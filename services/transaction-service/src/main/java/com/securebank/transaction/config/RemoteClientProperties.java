package com.securebank.transaction.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Remote service endpoints, timeouts and service-to-service auth (prefix securebank.clients). */
@ConfigurationProperties(prefix = "securebank.clients")
public record RemoteClientProperties(
        @DefaultValue Account account,
        @DefaultValue Ledger ledger,
        @DefaultValue Auth auth) {

    public record Account(
            @DefaultValue("http://localhost:8082") String baseUrl,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("5s") Duration readTimeout,
            @DefaultValue("/internal/accounts/{accountId}") String accountPath,
            @DefaultValue("/internal/accounts/{accountId}/operations") String operationsPath,
            @DefaultValue("/internal/accounts/{accountId}/operations/{operationId}") String operationPath) {
    }

    public record Ledger(
            @DefaultValue("http://localhost:8083") String baseUrl,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("5s") Duration readTimeout,
            @DefaultValue("/internal/journal-entries") String postPath,
            @DefaultValue("/internal/journal-entries/{businessReference}/reversals") String reversalPath,
            @DefaultValue("/internal/journal-entries/by-reference/{businessReference}") String lookupPath) {
    }

    /**
     * Service-to-service authentication. NONE sends no Authorization header (secured Account/Ledger endpoints will
     * answer 401, which is classified as a retryable failure, never as success). OAUTH2_CLIENT_CREDENTIALS obtains,
     * caches and renews a Keycloak service-account token. Secrets come from the environment only.
     */
    public record Auth(
            @DefaultValue("NONE") Mode mode,
            @DefaultValue("") String staticToken,
            @DefaultValue("") String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue("") String tokenUri,
            @DefaultValue("") String issuerUri,
            @DefaultValue("") String scope) {

        public enum Mode { NONE, STATIC_BEARER, OAUTH2_CLIENT_CREDENTIALS }

        /** Token endpoint: explicit token-uri, else derived from the Keycloak issuer URI. Blank when neither set. */
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
