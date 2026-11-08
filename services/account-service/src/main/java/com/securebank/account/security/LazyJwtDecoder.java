package com.securebank.account.security;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Defers the OIDC discovery request (issuer metadata / JWKS lookup) until the first token has to be validated, so
 * the service can start - and answer health checks - before Keycloak is reachable. Until the delegate has been
 * created successfully, tokens are rejected (fail closed) and creation is retried on the next request.
 */
final class LazyJwtDecoder implements JwtDecoder {

    private static final Logger log = LoggerFactory.getLogger(LazyJwtDecoder.class);

    private final Supplier<JwtDecoder> factory;
    private volatile JwtDecoder delegate;

    LazyJwtDecoder(Supplier<JwtDecoder> factory) {
        this.factory = factory;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        JwtDecoder current = delegate;
        if (current == null) {
            synchronized (this) {
                if (delegate == null) {
                    try {
                        delegate = factory.get();
                    } catch (RuntimeException ex) {
                        log.error("JWT decoder could not be initialised from the issuer URI: {}", ex.getMessage());
                        throw new JwtException("Token validation is temporarily unavailable.", ex);
                    }
                }
                current = delegate;
            }
        }
        return current.decode(token);
    }
}
