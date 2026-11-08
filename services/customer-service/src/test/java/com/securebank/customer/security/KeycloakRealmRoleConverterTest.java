package com.securebank.customer.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter =
            new KeycloakRealmRoleConverter("securebank-admin", "securebank-staff");

    private static Jwt jwtWithClaims(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .subject("tester");
        claims.forEach(builder::claim);
        return builder.build();
    }

    private List<String> authorities(Jwt jwt) {
        return converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void mapsAdminAndStaffRealmRoles() {
        Jwt jwt = jwtWithClaims(Map.of("realm_access",
                Map.of("roles", List.of("securebank-admin", "securebank-staff"))));

        assertThat(authorities(jwt)).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_STAFF");
    }

    @Test
    void ignoresUnknownRoles() {
        Jwt jwt = jwtWithClaims(Map.of("realm_access",
                Map.of("roles", List.of("offline_access", "uma_authorization", "ADMIN"))));

        assertThat(authorities(jwt)).isEmpty();
    }

    @Test
    void staffOnlyTokenDoesNotGetAdminAuthority() {
        Jwt jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles", List.of("securebank-staff"))));

        assertThat(authorities(jwt)).containsExactly("ROLE_STAFF");
    }

    @Test
    void missingOrMalformedClaimGivesNoAuthorities() {
        assertThat(authorities(jwtWithClaims(Map.of()))).isEmpty();
        assertThat(authorities(jwtWithClaims(Map.of("realm_access", "not-a-map")))).isEmpty();
        assertThat(authorities(jwtWithClaims(Map.of("realm_access", Map.of("roles", "not-a-list"))))).isEmpty();
    }

    @Test
    void accountServiceRoleIsMappedOnlyWhenConfiguredAndNeverFromLookalikes() {
        KeycloakRealmRoleConverter withService =
                new KeycloakRealmRoleConverter("securebank-admin", "securebank-staff", "account-service");
        Jwt jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles",
                List.of("account-service", "Account-Service", "transaction-service", "admin"))));

        assertThat(withService.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList())
                .containsExactly("ROLE_ACCOUNT_SERVICE");
        assertThat(authorities(jwt)).isEmpty();   // two-argument converter never grants it
    }
}
