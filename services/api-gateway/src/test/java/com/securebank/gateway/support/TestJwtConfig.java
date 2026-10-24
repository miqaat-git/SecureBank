package com.securebank.gateway.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * Replaces the IdP-backed decoder with an in-memory one so no live Keycloak is needed.
 * The real security filter chain stays fully active: only the token-to-Jwt step is faked.
 * {@link #VALID_TOKEN} is an opaque placeholder string, not a real JWT.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtConfig {

    public static final String VALID_TOKEN = "test-valid-token";
    public static final String BEARER = "Bearer " + VALID_TOKEN;

    /*
     * Token strings stay within the RFC 6750 bearer alphabet (letters, digits and - . _ ~ + /): a ':' or ',' would be
     * rejected as a malformed Authorization header (401) before the decoder or any role rule runs. Realm role and user
     * names contain no '.', so '.' is a safe separator.
     */
    /** Realm users from the tracked export: tokens carry exactly the roles the export assigns that user. */
    private static final String REALM_USER_PREFIX = "test.realm-user.";
    /** Synthetic token with an explicit, period-separated list of realm roles. */
    private static final String ROLES_PREFIX = "test.roles.";

    /** dev-admin as exported: admin, securebank-admin, securebank-staff, bank-admin, ledger-reader. */
    public static final String ADMIN_TOKEN = realmUserToken("dev-admin");
    public static final String ADMIN_BEARER = "Bearer " + ADMIN_TOKEN;

    /** dev-staff as exported: securebank-staff, ledger-reader (no "admin"). */
    public static final String STAFF_BEARER = "Bearer " + realmUserToken("dev-staff");

    /** Client-credentials identity of the Transaction Service, as exported for its service account. */
    public static final String SERVICE_TOKEN = realmUserToken("service-account-securebank-transaction-service");
    public static final String SERVICE_BEARER = "Bearer " + SERVICE_TOKEN;

    public static String realmUserToken(String username) {
        return REALM_USER_PREFIX + username;
    }

    /** Bearer header for a token holding exactly the given realm roles (nothing else). */
    public static String bearerWithRoles(String... roles) {
        return "Bearer " + ROLES_PREFIX + String.join(".", roles);
    }

    @Bean
    @Primary
    ReactiveJwtDecoder testJwtDecoder() {
        return token -> {
            List<String> roles;
            String subject;
            if (VALID_TOKEN.equals(token)) {
                roles = RealmExport.rolesOf("dev-customer");   // exactly the exported customer user
                subject = "test-user";
            } else if (token.startsWith(REALM_USER_PREFIX)) {
                subject = token.substring(REALM_USER_PREFIX.length());
                roles = RealmExport.rolesOf(subject);
            } else if (token.startsWith(ROLES_PREFIX)) {
                subject = "test-roles-user";
                String list = token.substring(ROLES_PREFIX.length());
                roles = list.isEmpty() ? List.of() : List.of(list.split("\\."));
            } else {
                return Mono.error(new BadJwtException("rejected by test decoder"));
            }
            Instant now = Instant.now();
            return Mono.just(Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject(subject)
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(300))
                    .claim("scope", "openid")
                    .claim("realm_access", Map.of("roles", roles))
                    .build());
        };
    }
}
