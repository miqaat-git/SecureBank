package com.securebank.transaction.security;

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

/**
 * Maps Keycloak realm roles (claim realm_access.roles) to Spring authorities: role "customer" becomes
 * ROLE_CUSTOMER, "transfer_operator" becomes ROLE_TRANSFER_OPERATOR. Roles are upper-cased.
 * Resource-client roles and scopes are intentionally NOT mapped.
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String s && !s.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + s.trim().toUpperCase(Locale.ROOT)));
                }
            }
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
