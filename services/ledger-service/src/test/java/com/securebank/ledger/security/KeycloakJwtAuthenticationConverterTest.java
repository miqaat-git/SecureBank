package com.securebank.ledger.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.securebank.ledger.config.LedgerProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakJwtAuthenticationConverterTest {

    private static KeycloakJwtAuthenticationConverter converter(String clientId) {
        return new KeycloakJwtAuthenticationConverter(new LedgerProperties(
                new LedgerProperties.Accounting(Set.of("INR"), 2, new BigDecimal("100000000"), 200),
                new LedgerProperties.Security(true, clientId)));
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("svc-transaction")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2026-01-01T01:00:00Z"));
    }

    private static List<String> names(Iterable<? extends GrantedAuthority> authorities) {
        return java.util.stream.StreamSupport.stream(authorities.spliterator(), false)
                .map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void mapsRealmRolesToUpperCaseRoleAuthorities() {
        Jwt token = jwt().claim("realm_access", Map.of("roles", List.of("ledger-writer", "ledger-reverser", "offline_access")))
                .build();

        var authentication = converter("").convert(token);

        assertThat(names(authentication.getAuthorities()))
                .contains("ROLE_LEDGER_WRITER", "ROLE_LEDGER_REVERSER", "ROLE_OFFLINE_ACCESS");
        assertThat(authentication.getName()).isEqualTo("svc-transaction");
    }

    @Test
    void mapsClientRolesOnlyWhenClientIdConfigured() {
        Jwt token = jwt().claim("resource_access",
                Map.of("ledger-api", Map.of("roles", List.of("ledger-reader")))).build();

        assertThat(names(converter("ledger-api").convert(token).getAuthorities())).contains("ROLE_LEDGER_READER");
        assertThat(names(converter("").convert(token).getAuthorities())).isEmpty();
        assertThat(names(converter("other-client").convert(token).getAuthorities())).isEmpty();
    }

    @Test
    void tokenWithoutRoleClaimsHasNoAuthorities() {
        assertThat(names(converter("ledger-api").convert(jwt().claim("scope", "read write").build()).getAuthorities()))
                .isEmpty();
    }

    @Test
    void ignoresMalformedRoleClaims() {
        Jwt token = jwt().claim("realm_access", Map.of("roles", "not-a-list")).build();
        assertThat(names(converter("").convert(token).getAuthorities())).isEmpty();
    }
}
