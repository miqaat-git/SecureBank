package com.securebank.gateway.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * Replaces the IdP-backed decoder with an in-memory one so no live Keycloak is needed.
 * The real security filter chain stays fully active: only the token-to-Jwt step is faked.
 * {@link #VALID_TOKEN} is an opaque placeholder string, not a real JWT.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtConfig {

    public static final String VALID_TOKEN = "test-valid-token";
    public static final String BEARER = "Bearer " + VALID_TOKEN;

    @Bean
    @Primary
    ReactiveJwtDecoder testJwtDecoder() {
        return token -> {
            if (!VALID_TOKEN.equals(token)) {
                return Mono.error(new BadJwtException("rejected by test decoder"));
            }
            Instant now = Instant.now();
            return Mono.just(Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("test-user")
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(300))
                    .claim("scope", "openid")
                    .claim("realm_access", Map.of("roles", List.of("customer")))
                    .build());
        };
    }
}
