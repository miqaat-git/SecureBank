package com.securebank.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Typed gateway configuration (prefix {@code securebank}). The JWT issuer is read by
 * {@code JwtDecoderConfig} directly so that startup fails fast when it is missing.
 */
@ConfigurationProperties(prefix = "securebank")
public record SecureBankProperties(
        Services services,
        Timeouts timeouts,
        Cors cors,
        Limits limits,
        Headers headers) {

    public record Services(
            String customerBaseUrl,
            String accountBaseUrl,
            String ledgerBaseUrl,
            String transactionBaseUrl) {
    }

    public record Timeouts(Duration connect, Duration response) {
    }

    public record Cors(
            List<String> allowedOrigins,
            List<String> allowedMethods,
            List<String> allowedHeaders,
            List<String> exposedHeaders,
            boolean allowCredentials,
            Duration maxAge) {
    }

    public record Limits(DataSize maxRequestBodySize) {
    }

    public record Headers(
            List<String> stripRequestHeaders,
            List<String> stripRequestHeaderPrefixes) {
    }
}
