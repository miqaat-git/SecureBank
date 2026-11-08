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
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Contract between the tracked realm export and this service's authorization rules (Blueprint D2: the only human
 * roles are CUSTOMER and ADMIN, one per user). Reads the JSON file only; it never contacts or imports into a
 * Keycloak. Fails (does not skip) if the export cannot be found.
 */
class AdminRealmContractTest {

    private static final Set<String> HUMAN_ROLES = Set.of("customer", "admin");

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
    void theRealmDefinesBothHumanRolesAndNoLegacyHumanRole() throws IOException {
        List<String> roles = names(realm().path("roles").path("realm"));

        assertThat(roles).contains("customer", "admin");
        assertThat(roles).doesNotContain("transfer-operator", "securebank-staff", "securebank-admin", "bank-admin");
    }

    @Test
    void theAdminRealmRoleMapsToTheAuthorityTheTransactionEndpointsRequire() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("adm")
                .claim("realm_access", Map.of("roles", List.of("admin")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

        assertThat(new KeycloakJwtAuthenticationConverter().convert(jwt).getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactly(CallerResolver.ADMIN_AUTHORITY);
    }

    @Test
    void everyHumanUserHasExactlyOneHumanRoleAndMachineIdentitiesHaveNone() throws IOException {
        for (JsonNode user : realm().path("users")) {
            String name = user.path("username").asText();
            List<String> humanRoles = new ArrayList<>(names(user.path("realmRoles")));
            humanRoles.retainAll(HUMAN_ROLES);
            if (name.startsWith("service-account-")) {
                assertThat(humanRoles).as("machine identity " + name).isEmpty();
            } else {
                assertThat(humanRoles).as("human roles of " + name).hasSize(1);
                // one role per user: nothing but the human role (no staff/operator/ledger extras)
                assertThat(names(user.path("realmRoles"))).as("roles of " + name).hasSize(1);
            }
            assertThat(names(user.path("groups"))).isEmpty();
        }
    }

    @Test
    void theAdminRoleIsNotInheritedOrDefaulted() throws IOException {
        JsonNode realm = realm();
        assertThat(realm.path("groups")).isEmpty();
        assertThat(realm.path("defaultRole").toString()).doesNotContain("admin");
        assertThat(realm.path("defaultRoles").toString()).doesNotContain("admin");
        for (JsonNode r : realm.path("roles").path("realm")) {
            assertThat(r.path("composites").toString()).as("composites of " + r.path("name").asText())
                    .doesNotContain("admin");
        }
    }
}
