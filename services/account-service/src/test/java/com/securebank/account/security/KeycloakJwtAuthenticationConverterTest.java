package com.securebank.account.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakJwtAuthenticationConverterTest {

    private static final String CLIENT = "securebank-account-service";

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter(CLIENT);

    private static Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject("user-1");
    }

    private static List<String> authorities(KeycloakJwtAuthenticationConverter converter, Jwt jwt) {
        return converter.convert(jwt).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).sorted().collect(Collectors.toList());
    }

    @Test
    void mapsRealmRolesAndConfiguredClientRoles() {
        Jwt jwt = baseJwt()
                .claim("realm_access", Map.of("roles", List.of("admin", "transaction-service")))
                .claim("resource_access", Map.of(CLIENT, Map.of("roles", List.of("customer"))))
                .build();

        assertThat(authorities(converter, jwt))
                .containsExactly("ROLE_ADMIN", "ROLE_CUSTOMER", "ROLE_TRANSACTION_SERVICE");
    }

    @Test
    void ignoresRolesOfOtherClientsAndScopes() {
        Jwt jwt = baseJwt()
                .claim("scope", "openid admin")
                .claim("resource_access", Map.of("some-other-client", Map.of("roles", List.of("admin"))))
                .build();

        assertThat(authorities(converter, jwt)).isEmpty();
    }

    @Test
    void toleratesMissingOrMalformedClaims() {
        Jwt jwt = baseJwt()
                .claim("realm_access", "not-a-map")
                .claim("resource_access", Map.of(CLIENT, "not-a-map"))
                .build();

        assertThat(authorities(converter, jwt)).isEmpty();
    }

    @Test
    void normalizesRoleNames() {
        assertThat(KeycloakJwtAuthenticationConverter.toAuthority("Transaction-Service"))
                .isEqualTo("ROLE_TRANSACTION_SERVICE");
        assertThat(KeycloakJwtAuthenticationConverter.toAuthority(" account admin "))
                .isEqualTo("ROLE_ACCOUNT_ADMIN");
    }

    @Test
    void usesTheSubjectAsPrincipalName() {
        Jwt jwt = baseJwt().claim("realm_access", Map.of("roles", List.of("admin"))).build();
        assertThat(converter.convert(jwt).getName()).isEqualTo("user-1");
    }
}
