package com.securebank.gateway.config;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.Buildable;
import org.springframework.cloud.gateway.route.builder.PredicateSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit route table. Paths are forwarded UNCHANGED:
 * {@code /api/customers/42?x=1} -> {@code <CUSTOMER_SERVICE_BASE_URL>/api/customers/42?x=1}.
 * <p>
 * No retry filter is configured on purpose: financial POSTs must never be retried
 * without end-to-end idempotency. Edit this class when downstream contracts are final.
 */
@Configuration(proxyBeanMethods = false)
public class RouteConfig {

    private static final String DEDUPE_HEADERS =
            "Access-Control-Allow-Origin Access-Control-Allow-Credentials";

    @Bean
    RouteLocator secureBankRoutes(RouteLocatorBuilder builder, SecureBankProperties props) {
        SecureBankProperties.Services s = props.services();
        return builder.routes()
                .route("customer-service", r -> forward(r, "/api/customers/**",
                        target("CUSTOMER_SERVICE_BASE_URL", s.customerBaseUrl())))
                .route("account-service", r -> forward(r, "/api/accounts/**",
                        target("ACCOUNT_SERVICE_BASE_URL", s.accountBaseUrl())))
                .route("transaction-service", r -> forward(r, "/api/transactions/**",
                        target("TRANSACTION_SERVICE_BASE_URL", s.transactionBaseUrl())))
                .route("ledger-service", r -> forward(r, "/api/ledger/**",
                        target("LEDGER_SERVICE_BASE_URL", s.ledgerBaseUrl())))
                .build();
    }

    private static Buildable<Route> forward(PredicateSpec spec, String pathPattern, String uri) {
        return spec.path(pathPattern)
                // The gateway owns CORS; avoid duplicate CORS headers if a service adds its own.
                .filters(f -> f.dedupeResponseHeader(DEDUPE_HEADERS, "RETAIN_FIRST"))
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
