package com.securebank.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RateLimitPropertiesTest {

    @Test
    void defaultsAreSafeEnabledAndInMemory() {
        RateLimitProperties p = new RateLimitProperties(null, null, null, null, null, null, null);
        assertThat(p.enabled()).isTrue();
        assertThat(p.store()).isEqualTo("memory");
        assertThat(p.trustedProxies()).isEmpty();
        assertThat(p.exemptPaths()).contains("/internal/**", "/actuator/**");
        assertThat(p.rules()).hasSize(1);
    }

    @Test
    void invalidPoliciesAndTinyStateBoundsAreRejected() {
        assertThatThrownBy(() -> new RateLimitProperties.Policy(0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Policy(5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties(true, "memory", 5, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties(true, "memory", 1000, null, null, null,
                List.of(new RateLimitProperties.Rule("x", List.of("/api/**"), List.of(), null, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnsupportedStoreFailsStartupInsteadOfSilentlyFallingBack() {
        RateLimitProperties p = new RateLimitProperties(true, "redis", null, null, null, null, null);
        assertThatThrownBy(() -> new RateLimitConfig().rateLimitStore(p))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("only 'memory'");
    }
}
