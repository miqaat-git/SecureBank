package com.securebank.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.oauth2.jwt.SupplierReactiveJwtDecoder;
import org.springframework.util.StringUtils;

/**
 * JWT signature and issuer validation from the identity provider's issuer URI (OIDC discovery,
 * JWK set). Resolution is lazy, so the gateway starts without contacting the IdP; tokens are
 * rejected until the IdP metadata can be resolved. Startup fails if no issuer is configured:
 * there is deliberately no unauthenticated fallback.
 */
@Configuration(proxyBeanMethods = false)
public class JwtDecoderConfig {

    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(@Value("${securebank.security.jwt-issuer-uri:}") String issuerUri) {
        if (!StringUtils.hasText(issuerUri)) {
            throw new IllegalStateException(
                    "SECUREBANK_JWT_ISSUER_URI (securebank.security.jwt-issuer-uri) must be set; "
                            + "refusing to start without JWT validation");
        }
        String issuer = issuerUri.trim();
        return new SupplierReactiveJwtDecoder(() -> ReactiveJwtDecoders.fromIssuerLocation(issuer));
    }
}
