package com.securebank.gateway.ratelimit;

import com.securebank.gateway.support.AbstractGatewayTest;
import com.securebank.gateway.support.GatewayTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The limiter can be switched off by configuration alone. */
@GatewayTest
@TestPropertySource(properties = {
        "securebank.rate-limit.enabled=false",
        "securebank.rate-limit.rules[0].name=default",
        "securebank.rate-limit.rules[0].paths=/api/**",
        "securebank.rate-limit.rules[0].anonymous.limit=1",
        "securebank.rate-limit.rules[0].anonymous.window=1h",
        "securebank.rate-limit.rules[0].authenticated.limit=1",
        "securebank.rate-limit.rules[0].authenticated.window=1h"
})
class RateLimitingDisabledTest extends AbstractGatewayTest {

    @Test
    void nothingIsLimitedWhenDisabled() {
        for (int i = 0; i < 5; i++) {
            client.get().uri("/api/customers/1").exchange().expectStatus().isUnauthorized();
        }
    }
}
