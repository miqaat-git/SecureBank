package com.securebank.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class JwtConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtDecoderConfig.class);

    @Test
    void decoderIsBuiltFromIssuerUriWithoutContactingTheIdentityProvider() {
        runner.withPropertyValues("securebank.security.jwt-issuer-uri=https://idp.securebank.invalid/realms/securebank")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(ReactiveJwtDecoder.class)).isInstanceOf(SupplierReactiveJwtDecoder.class);
                });
    }

    @Test
    void startupFailsWhenIssuerIsMissing() {
        runner.run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                .hasMessageContaining("SECUREBANK_JWT_ISSUER_URI"));
    }

    @Test
    void startupFailsWhenIssuerIsBlank() {
        runner.withPropertyValues("securebank.security.jwt-issuer-uri=   ")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void keycloakRealmRolesAreMappedToRoleAuthorities() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("customer", "bank-admin"))));
        Collection<GrantedAuthority> authorities = new KeycloakAuthorityConverter().convert(jwt);
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_BANK_ADMIN");
    }

    @Test
    void missingOrMalformedRoleClaimsYieldNoAuthorities() {
        assertThat(new KeycloakAuthorityConverter().convert(jwt(Map.of("scope", "openid")))).isEmpty();
        assertThat(new KeycloakAuthorityConverter().convert(jwt(Map.of("realm_access", "not-a-map")))).isEmpty();
        assertThat(new KeycloakAuthorityConverter().convert(jwt(Map.of("realm_access", Map.of("roles", "oops")))))
                .isEmpty();
    }

    @Test
    void authenticationCombinesScopesAndRealmRoles() {
        Jwt jwt = jwt(Map.of("scope", "openid profile",
                "realm_access", Map.of("roles", List.of("customer"))));
        JwtAuthenticationToken token = (JwtAuthenticationToken) SecurityConfig.jwtAuthenticationConverter().convert(jwt);
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("SCOPE_openid", "SCOPE_profile", "ROLE_CUSTOMER");
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("in-memory-placeholder")
                .header("alg", "none")
                .subject("test-user")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
