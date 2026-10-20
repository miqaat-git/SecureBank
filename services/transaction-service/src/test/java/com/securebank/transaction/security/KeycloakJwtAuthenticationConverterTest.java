package com.securebank.transaction.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();

    private Jwt.Builder jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject("user-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
    }

    @Test
    void realmRolesBecomeUpperCaseRoleAuthorities() {
        var auth = converter.convert(jwt().claim("realm_access",
                Map.of("roles", List.of("customer", "transfer_operator", "offline_access"))).build());

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_TRANSFER_OPERATOR", "ROLE_OFFLINE_ACCESS");
        assertThat(auth.getName()).isEqualTo("user-1");
    }

    @Test
    void missingOrMalformedRoleClaimsGrantNoAuthorities() {
        assertThat(converter.convert(jwt().claim("sub2", "x").build()).getAuthorities()).isEmpty();
        assertThat(converter.convert(jwt().claim("realm_access", "oops").build()).getAuthorities()).isEmpty();
        assertThat(converter.convert(jwt().claim("realm_access", Map.of("roles", "customer")).build())
                .getAuthorities()).isEmpty();
    }

    @Test
    void scopesAndClientRolesAreNotMapped() {
        var auth = converter.convert(jwt().claim("scope", "admin")
                .claim("resource_access", Map.of("c", Map.of("roles", List.of("transfer_operator")))).build());
        assertThat(auth.getAuthorities()).isEmpty();
    }
}
