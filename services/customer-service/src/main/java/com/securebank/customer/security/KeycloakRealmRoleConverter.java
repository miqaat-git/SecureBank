package com.securebank.customer.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps Keycloak realm roles (claim realm_access.roles) to Spring authorities.
 * Only the two configured roles are recognised; every other role is ignored.
 *   admin role  -> ROLE_ADMIN
 *   staff role  -> ROLE_STAFF
 *   account-service role (optional) -> ROLE_ACCOUNT_SERVICE, only for the internal eligibility endpoint
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_STAFF = "ROLE_STAFF";
    public static final String ROLE_ACCOUNT_SERVICE = "ROLE_ACCOUNT_SERVICE";

    private final String adminRole;
    private final String staffRole;
    private final String accountServiceRole;

    public KeycloakRealmRoleConverter(String adminRole, String staffRole) {
        this(adminRole, staffRole, null);
    }

    public KeycloakRealmRoleConverter(String adminRole, String staffRole, String accountServiceRole) {
        this.adminRole = adminRole;
        this.staffRole = staffRole;
        this.accountServiceRole = accountServiceRole;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (!(realmAccess instanceof Map<?, ?> realmAccessMap)) {
            return List.of();
        }
        Object roles = realmAccessMap.get("roles");
        if (!(roles instanceof Collection<?> roleCollection)) {
            return List.of();
        }
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (Object role : roleCollection) {
            if (adminRole.equals(role)) {
                authorities.add(new SimpleGrantedAuthority(ROLE_ADMIN));
            } else if (staffRole.equals(role)) {
                authorities.add(new SimpleGrantedAuthority(ROLE_STAFF));
            } else if (accountServiceRole != null && !accountServiceRole.isBlank()
                    && accountServiceRole.equals(role)) {
                authorities.add(new SimpleGrantedAuthority(ROLE_ACCOUNT_SERVICE));
            }
        }
        return authorities;
    }
}
