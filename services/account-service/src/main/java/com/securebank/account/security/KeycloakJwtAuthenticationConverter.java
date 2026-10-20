package com.securebank.account.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Maps Keycloak roles to Spring Security authorities.
 *
 * <ul>
 *   <li>Realm roles: claim {@code realm_access.roles}</li>
 *   <li>Client roles of the configured client: claim {@code resource_access.<clientId>.roles}</li>
 *   <li>Each role {@code some-role} becomes authority {@code ROLE_SOME_ROLE} (upper case, '-' and ' ' to '_').</li>
 * </ul>
 * Scopes are intentionally NOT mapped: authorization is role based. Roles of other clients are ignored.
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String resourceClientId;

    public KeycloakJwtAuthenticationConverter(String resourceClientId) {
        this.resourceClientId = resourceClientId;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, extractAuthorities(jwt), jwt.getSubject());
    }

    Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        Set<String> roles = new LinkedHashSet<>();
        roles.addAll(rolesOf(jwt.getClaims().get("realm_access")));
        Object resourceAccess = jwt.getClaims().get("resource_access");
        if (resourceAccess instanceof Map<?, ?> clients) {
            roles.addAll(rolesOf(clients.get(resourceClientId)));
        }
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority(toAuthority(role)));
        }
        return authorities;
    }

    static String toAuthority(String role) {
        return "ROLE_" + role.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static Set<String> rolesOf(Object accessClaim) {
        Set<String> result = new LinkedHashSet<>();
        if (accessClaim instanceof Map<?, ?> map && map.get("roles") instanceof List<?> list) {
            for (Object role : list) {
                if (role instanceof String text && !text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return result;
    }
}
