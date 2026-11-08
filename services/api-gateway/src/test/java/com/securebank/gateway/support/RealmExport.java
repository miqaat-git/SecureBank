package com.securebank.gateway.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.json.JsonParserFactory;

/**
 * Reads the tracked realm export (identity/keycloak/securebank-realm.json) so test identities carry exactly the
 * realm roles the real dev users hold. Read-only; never touches a live Keycloak. Fails (does not skip) if the file
 * cannot be found, so fixtures cannot silently drift from the realm.
 */
public final class RealmExport {

    private static final Map<String, Object> REALM = load();

    private RealmExport() {
    }

    private static Map<String, Object> load() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("identity/keycloak/securebank-realm.json");
            if (Files.isRegularFile(candidate)) {
                try {
                    return JsonParserFactory.getJsonParser().parseMap(Files.readString(candidate, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("identity/keycloak/securebank-realm.json not found above "
                + Path.of("").toAbsolutePath());
    }

    /** Realm roles assigned to the named user in the export. */
    public static List<String> rolesOf(String username) {
        for (Object u : list(REALM.get("users"))) {
            Map<?, ?> user = (Map<?, ?>) u;
            if (username.equals(user.get("username"))) {
                List<String> roles = new ArrayList<>();
                list(user.get("realmRoles")).forEach(r -> roles.add(String.valueOf(r)));
                return roles;
            }
        }
        throw new IllegalArgumentException("user not in realm export: " + username);
    }

    /** Names of all realm roles defined in the export. */
    public static List<String> definedRoles() {
        List<String> roles = new ArrayList<>();
        Map<?, ?> roleSection = (Map<?, ?>) REALM.get("roles");
        for (Object r : list(roleSection.get("realm"))) {
            roles.add(String.valueOf(((Map<?, ?>) r).get("name")));
        }
        return roles;
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }
}
