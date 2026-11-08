package com.securebank.account.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Contract between the tracked realm export and the Account Service's own service-to-service configuration.
 * Reads files only; it never contacts or imports into a Keycloak. Fails (does not skip) if the export cannot be found.
 */
class AccountServiceRealmContractTest {

    private static final String CLIENT_ID = "securebank-account-service";
    private static final String ROLE = "account-service";
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[A-Z0-9_]+(:[^}]*)?}$");

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

    private static JsonNode client() throws IOException {
        for (JsonNode c : realm().path("clients")) {
            if (CLIENT_ID.equals(c.path("clientId").asText())) {
                return c;
            }
        }
        throw new AssertionError("client " + CLIENT_ID + " missing from the realm export");
    }

    @Test
    void theRealmDefinesTheAccountServiceRole() throws IOException {
        JsonNode role = null;
        for (JsonNode r : realm().path("roles").path("realm")) {
            if (ROLE.equals(r.path("name").asText())) {
                role = r;
            }
        }
        assertThat(role).as("realm role " + ROLE).isNotNull();
        assertThat(role.path("composite").asBoolean(false)).isFalse();
    }

    @Test
    void theClientIsAConfidentialClientCredentialsClientOnly() throws IOException {
        JsonNode c = client();
        assertThat(c.path("publicClient").asBoolean(true)).isFalse();
        assertThat(c.path("serviceAccountsEnabled").asBoolean(false)).isTrue();
        assertThat(c.path("standardFlowEnabled").asBoolean(true)).isFalse();
        assertThat(c.path("implicitFlowEnabled").asBoolean(false)).isFalse();
        assertThat(c.path("directAccessGrantsEnabled").asBoolean(true)).isFalse();
        assertThat(c.path("secret").asText()).isEqualTo("${SECUREBANK_ACCOUNT_CLIENT_SECRET}");
    }

    @Test
    void theServiceAccountHoldsExactlyTheAccountServiceRole() throws IOException {
        JsonNode user = null;
        for (JsonNode u : realm().path("users")) {
            if (CLIENT_ID.equals(u.path("serviceAccountClientId").asText())) {
                user = u;
            }
        }
        assertThat(user).as("service-account user of " + CLIENT_ID).isNotNull();
        List<String> roles = new ArrayList<>();
        user.path("realmRoles").forEach(r -> roles.add(r.asText()));
        assertThat(roles).containsExactly(ROLE);   // never customer, admin, ledger or transaction roles
    }

    @Test
    void noSecretOrPasswordInTheExportIsALiteral() throws IOException {
        List<String> literals = new ArrayList<>();
        for (JsonNode c : realm().path("clients")) {
            if (c.has("secret") && !PLACEHOLDER.matcher(c.path("secret").asText()).matches()) {
                literals.add("client secret of " + c.path("clientId").asText());
            }
        }
        for (JsonNode u : realm().path("users")) {
            for (JsonNode cred : u.path("credentials")) {
                if (cred.has("value") && !PLACEHOLDER.matcher(cred.path("value").asText()).matches()) {
                    literals.add("credential of " + u.path("username").asText());
                }
            }
        }
        assertThat(literals).as("literal secrets in the tracked realm export").isEmpty();
    }

    @Test
    void theServiceDefaultClientIdMatchesTheRealmClient() throws IOException {
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        assertThat(props).contains("account.customer-service.auth.client-id=${ACCOUNT_SERVICE_AUTH_CLIENT_ID:" + CLIENT_ID + "}");
        assertThat(props).contains("${ACCOUNT_SERVICE_AUTH_CLIENT_SECRET:}");   // read from the environment only, no default value
    }
}
