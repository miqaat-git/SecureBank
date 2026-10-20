package com.securebank.customer.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Validates token signature and issuer using the identity provider's published metadata.
 * The provider is contacted on the first token, not at startup, so the service can start
 * before the identity provider is reachable. A failed lookup is retried on the next request.
 */
public class LazyIssuerJwtDecoder implements JwtDecoder {

    private final String issuerUri;
    private volatile JwtDecoder delegate;

    public LazyIssuerJwtDecoder(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        return resolveDelegate().decode(token);
    }

    private JwtDecoder resolveDelegate() {
        JwtDecoder current = delegate;
        if (current == null) {
            synchronized (this) {
                current = delegate;
                if (current == null) {
                    try {
                        current = JwtDecoders.fromIssuerLocation(issuerUri);
                    } catch (RuntimeException ex) {
                        throw new JwtException("The identity provider could not be reached to validate the token.", ex);
                    }
                    delegate = current;
                }
            }
        }
        return current;
    }
}
