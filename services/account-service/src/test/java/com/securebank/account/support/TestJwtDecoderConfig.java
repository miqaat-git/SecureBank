package com.securebank.account.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Test-only decoder: rejects every raw bearer token (there is no identity provider in tests). Authenticated
 * requests are created with spring-security-test's {@code jwt()} post-processor instead, so the production
 * SecurityFilterChain and its authorization rules are exercised unchanged.
 */
@TestConfiguration
public class TestJwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            throw new BadJwtException("Raw tokens are not accepted in tests");
        };
    }
}
