package com.securebank.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Direct tests of the sanitizer's decision. Characters such as a literal backslash cannot be carried by a
 * java.net.URI, so they are checked here rather than through the HTTP client.
 */
class PathSanitizationWebFilterTest {

    @ParameterizedTest(name = "unsafe: {0}")
    @ValueSource(strings = {
            "/api/accounts/../../../internal/accounts/1/operations",
            "/api/accounts/..",
            "/api/accounts/./1",
            "/./api/accounts",
            "//internal/accounts/1/operations",
            "/api//accounts/1",
            "/api/accounts/1;x=y",
            "/api/accounts/1\\..\\2",
            "\\internal",
            "/api/accounts/%2e%2e/internal",
            "/api/accounts/%2E%2E/internal",
            "/api/accounts/..%2finternal",
            "/api/accounts/1%2F..",
            "/api/accounts/1%5c..",
            "/api/accounts/1%5C..",
            "/api/accounts/%252e%252e/internal",
            "/api/accounts/1%00"
    })
    void attackPathsAreUnsafe(String rawPath) {
        assertThat(PathSanitizationWebFilter.isUnsafe(rawPath)).isTrue();
    }

    @ParameterizedTest(name = "safe: {0}")
    @ValueSource(strings = {
            "/",
            "/api/accounts",
            "/api/accounts/",
            "/api/accounts/7/balance",
            "/api/accounts/3f2b8c1e-0a4d-4c7e-9a11-5b6c7d8e9f00/status",
            "/api/transactions/by-idempotency-key/key-1",
            "/api/ledger/journal-entries/by-reference/TRANSFER-ABC",
            "/api/account-numbers/123456789012",
            "/api/accounts/a.b",
            "/api/accounts/..x",
            "/api/accounts/x..",
            "/actuator/health"
    })
    void ordinaryPathsAreSafe(String rawPath) {
        assertThat(PathSanitizationWebFilter.isUnsafe(rawPath)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void nullAndEmptyAreLeftToRouting() {
        assertThat(PathSanitizationWebFilter.isUnsafe(null)).isFalse();
        assertThat(PathSanitizationWebFilter.isUnsafe("")).isFalse();
    }
}
