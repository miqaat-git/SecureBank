package com.securebank.gateway.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps Keycloak realm roles ({@code realm_access.roles}) to Spring authorities:
 * {@code customer} -> {@code ROLE_CUSTOMER}, {@code bank-admin} -> {@code ROLE_BANK_ADMIN}.
 * Malformed or missing claims yield no authorities. Authorities are mapped for downstream
 * use. Gateway rules (SecurityConfig) use ROLE_ADMIN (realm role {@code admin}) and ROLE_TRANSACTION_SERVICE;
 * roles such as {@code bank-admin} are mapped but no rule grants them anything.
 */
public class KeycloakAuthorityConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String name && !name.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority(
                            "ROLE_" + name.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_")));
                }
            }
        }
        return authorities;
    }
}
