package com.securebank.gateway.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway rate limiting (prefix {@code securebank.rate-limit}); every value can be changed by configuration only.
 *
 * <p>The only supported store is {@code memory}: state is per gateway instance, so it does NOT protect a deployment
 * with several instances (each instance counts separately). A shared store requires a deliberately provisioned
 * component (for example Redis behind a custom {@link RateLimitStore}); none is introduced silently.
 */
@ConfigurationProperties(prefix = "securebank.rate-limit")
public record RateLimitProperties(
        Boolean enabled,
        String store,
        Integer maxKeys,
        List<String> trustedProxies,
        List<String> exemptPaths,
        Policy authFailure,
        List<Rule> rules) {

    /** At most {@code limit} requests per {@code window}. */
    public record Policy(Integer limit, Duration window) {
        public Policy {
            if (limit == null || limit < 1) {
                throw new IllegalArgumentException("rate-limit limit must be at least 1");
            }
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("rate-limit window must be positive");
            }
        }
    }

    /**
     * First matching rule wins. {@code anonymous} applies to requests without a bearer token (keyed by client
     * address); {@code authenticated} applies to validated tokens (keyed by subject). Buckets are per rule.
     */
    public record Rule(String name, List<String> paths, List<String> methods, Policy anonymous, Policy authenticated) {
    }

    public RateLimitProperties {
        enabled = enabled == null || enabled;
        store = store == null || store.isBlank() ? "memory" : store.trim().toLowerCase();
        maxKeys = maxKeys == null ? 10_000 : maxKeys;
        if (maxKeys < 100) {
            throw new IllegalArgumentException("securebank.rate-limit.max-keys must be at least 100");
        }
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        exemptPaths = exemptPaths == null ? List.of("/actuator/**", "/internal/**") : List.copyOf(exemptPaths);
        authFailure = authFailure == null ? new Policy(20, Duration.ofMinutes(1)) : authFailure;
        rules = rules == null || rules.isEmpty()
                ? List.of(new Rule("default", List.of("/api/**"), List.of(),
                        new Policy(60, Duration.ofMinutes(1)), new Policy(300, Duration.ofMinutes(1))))
                : List.copyOf(rules);
        for (Rule rule : rules) {
            if (rule.name() == null || rule.name().isBlank() || rule.paths() == null || rule.paths().isEmpty()
                    || rule.anonymous() == null || rule.authenticated() == null) {
                throw new IllegalArgumentException(
                        "every securebank.rate-limit rule needs name, paths, anonymous and authenticated");
            }
        }
    }
}
