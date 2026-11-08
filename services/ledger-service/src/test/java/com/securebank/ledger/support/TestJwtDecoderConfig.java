package com.securebank.ledger.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Test-only (profile "test", test classpath only): replaces the Keycloak-backed decoder so no network or
 * identity provider is needed. It rejects every raw bearer token, which lets tests verify that invalid
 * tokens give 401. Authenticated requests in tests use spring-security-test's jwt() post-processor, which
 * bypasses decoding but still runs the real authorization rules. Production security is not weakened.
 */
@Configuration
@Profile("test")
public class TestJwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            throw new BadJwtException("Test decoder accepts no raw tokens");
        };
    }
}
