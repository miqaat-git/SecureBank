package com.securebank.ledger.security;

import com.securebank.ledger.config.LedgerProperties;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Maps Keycloak roles to Spring authorities.
 *
 * <ul>
 *   <li>Realm roles: claim {@code realm_access.roles}</li>
 *   <li>Client roles (optional): claim {@code resource_access.<ledger.security.resource-client-id>.roles}</li>
 * </ul>
 * A role name is upper-cased, '-' becomes '_' and it is prefixed with ROLE_:
 * {@code ledger-writer -> ROLE_LEDGER_WRITER}. Scopes are deliberately not used for authorization.
 */
@Component
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String resourceClientId;

    public KeycloakJwtAuthenticationConverter(LedgerProperties properties) {
        this.resourceClientId = properties.security().resourceClientId();
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();

        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null) {
            addRoles(realmAccess.get("roles"), authorities);
        }

        if (resourceClientId != null && !resourceClientId.isBlank()) {
            Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
            if (resourceAccess != null && resourceAccess.get(resourceClientId) instanceof Map<?, ?> client) {
                addRoles(client.get("roles"), authorities);
            }
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private static void addRoles(Object roles, Set<GrantedAuthority> authorities) {
        if (roles instanceof Collection<?> collection) {
            for (Object role : collection) {
                if (role instanceof String name && !name.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority(
                            "ROLE_" + name.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
                }
            }
        }
    }
}
