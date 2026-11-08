package com.securebank.transaction.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Contract between the tracked realm export and this service's authorization rules. Reads the JSON file only; it
 * never contacts or imports into a Keycloak. Fails (does not skip) if the export cannot be found.
 */
class TransferOperatorRealmContractTest {

    private static final String ROLE = "transfer-operator";

    private static JsonNode realm() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("identity/keycloak/securebank-realm.json");
            if (Files.isRegularFile(candidate)) {
                return new ObjectMapper().readTree(Files.readString(candidate));
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("identity/keycloak/securebank-realm.json not found");
    }

    private static List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            array.forEach(n -> out.add(n.isTextual() ? n.asText() : n.path("name").asText()));
        }
        return out;
    }

    @Test
    void theRealmDefinesTheTransferOperatorRole() throws IOException {
        JsonNode role = null;
        for (JsonNode r : realm().path("roles").path("realm")) {
            if (ROLE.equals(r.path("name").asText())) {
                role = r;
            }
        }
        assertThat(role).as("realm role " + ROLE).isNotNull();
        assertThat(role.path("description").asText()).isNotBlank();
        assertThat(role.path("composite").asBoolean(false)).isFalse();
        assertThat(role.has("composites")).isFalse();
    }

    @Test
    void theRealmRoleMapsToTheAuthorityTheTransactionEndpointsRequire() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("ops")
                .claim("realm_access", Map.of("roles", List.of(ROLE)))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

        assertThat(new KeycloakJwtAuthenticationConverter().convert(jwt).getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactly(CallerResolver.OPERATOR_AUTHORITY);
    }

    @Test
    void theRoleIsGrantedToNoUserAndIsNotInheritedThroughAnyOtherRole() throws IOException {
        JsonNode realm = realm();
        for (JsonNode user : realm.path("users")) {
            assertThat(names(user.path("realmRoles")))
                    .as("roles of " + user.path("username").asText()).doesNotContain(ROLE);
            assertThat(names(user.path("groups"))).isEmpty();
        }
        assertThat(realm.path("groups")).isEmpty();
        assertThat(realm.path("defaultRole").toString()).as("defaultRole").doesNotContain(ROLE);
        assertThat(realm.path("defaultRoles").toString()).doesNotContain(ROLE);
        for (JsonNode r : realm.path("roles").path("realm")) {
            assertThat(r.path("composites").toString()).as("composites of " + r.path("name").asText())
                    .doesNotContain(ROLE);
        }
        // the machine identity and the customer account in particular
        for (JsonNode user : realm.path("users")) {
            String name = user.path("username").asText();
            if (name.startsWith("service-account-") || name.equals("dev-customer")) {
                assertThat(names(user.path("realmRoles"))).doesNotContain(ROLE);
            }
        }
    }
}
