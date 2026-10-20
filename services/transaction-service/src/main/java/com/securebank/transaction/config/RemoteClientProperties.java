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
            @DefaultValue("/api/v1/accounts/{accountId}") String accountPath,
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

    public record Auth(
            @DefaultValue("NONE") Mode mode,
            @DefaultValue("") String staticToken) {

        public enum Mode { NONE, STATIC_BEARER }
    }
}
