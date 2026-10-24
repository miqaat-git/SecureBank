package com.securebank.gateway.config;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.Buildable;
import org.springframework.cloud.gateway.route.builder.BooleanSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * Explicit route table. The documented PUBLIC prefixes are kept; each is rewritten to the path the owning service
 * actually serves (verified against the service controllers):
 *
 * <pre>
 * /api/customers/**        -> customer-service     /api/v1/customers/**
 * /api/accounts/**         -> account-service      /api/v1/accounts/**
 * /api/account-numbers/**  -> account-service      /api/v1/account-numbers/**
 * /api/admin/accounts/**   -> account-service      /api/v1/admin/accounts/**        (realm role admin, see SecurityConfig)
 * /api/transactions/**     -> transaction-service  /api/v1/transfers/**
 * GET /api/ledger/{resource}[/**] -> ledger-service /api/v1/{resource}[/**]       (read only; the bare
 *                                                   /api/ledger and /api/ledger/ are not routed -> 404)
 * </pre>
 *
 * There is deliberately NO route for {@code /internal/**} (Account operations, Ledger posting/reversal): those are
 * service-to-service endpoints reached directly by Transaction Service, never through the Gateway. Ledger writes
 * (journal posting, reversal) are likewise not public: only GET is routed, so money can only move through the
 * Transaction Service saga.
 * <p>
 * No retry filter is configured on purpose: financial POSTs must never be retried without end-to-end idempotency.
 * Headers (including {@code Idempotency-Key}), bodies and query strings pass through unchanged.
 */
@Configuration(proxyBeanMethods = false)
public class RouteConfig {

    private static final String DEDUPE_HEADERS =
            "Access-Control-Allow-Origin Access-Control-Allow-Credentials";

    @Bean
    RouteLocator secureBankRoutes(RouteLocatorBuilder builder, SecureBankProperties props) {
        SecureBankProperties.Services s = props.services();
        String customer = target("CUSTOMER_SERVICE_BASE_URL", s.customerBaseUrl());
        String account = target("ACCOUNT_SERVICE_BASE_URL", s.accountBaseUrl());
        String transaction = target("TRANSACTION_SERVICE_BASE_URL", s.transactionBaseUrl());
        String ledger = target("LEDGER_SERVICE_BASE_URL", s.ledgerBaseUrl());
        return builder.routes()
                .route("customer-service", r -> forward(r.path("/api/customers/**"),
                        "/api/customers(?<segment>/?.*)", "/api/v1/customers${segment}", customer))
                .route("account-service", r -> forward(r.path("/api/accounts/**"),
                        "/api/accounts(?<segment>/?.*)", "/api/v1/accounts${segment}", account))
                .route("account-numbers", r -> forward(r.path("/api/account-numbers/**"),
                        "/api/account-numbers(?<segment>/?.*)", "/api/v1/account-numbers${segment}", account))
                .route("account-admin", r -> forward(r.path("/api/admin/accounts/**"),
                        "/api/admin/accounts(?<segment>/?.*)", "/api/v1/admin/accounts${segment}", account))
                .route("transaction-service", r -> forward(r.path("/api/transactions/**"),
                        "/api/transactions(?<segment>/?.*)", "/api/v1/transfers${segment}", transaction))
                .route("ledger-service", r -> forward(r.path("/api/ledger/{resource}", "/api/ledger/{resource}/**").and().method(HttpMethod.GET),
                        "/api/ledger(?<segment>/.*)", "/api/v1${segment}", ledger))
                .build();
    }

    private static Buildable<Route> forward(BooleanSpec spec, String regexp, String replacement, String uri) {
        return spec
                // The gateway owns CORS; avoid duplicate CORS headers if a service adds its own.
                .filters(f -> f.rewritePath(regexp, replacement)
                        .dedupeResponseHeader(DEDUPE_HEADERS, "RETAIN_FIRST"))
                .uri(uri);
    }

    /** Validates a base URL (scheme://host[:port], no path) and fails startup if malformed. */
    static String target(String envName, String value) {
        String message = envName + " must be an absolute http(s) base URL without a path or query";
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(message);
        }
        boolean httpScheme = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        boolean noPath = uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath());
        if (!httpScheme || uri.getHost() == null || !noPath || uri.getRawQuery() != null) {
            throw new IllegalStateException(message);
        }
        return uri.getScheme().toLowerCase() + "://" + uri.getRawAuthority();
    }
}
