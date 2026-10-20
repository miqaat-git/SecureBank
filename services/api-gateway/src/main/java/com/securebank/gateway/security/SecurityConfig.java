package com.securebank.gateway.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

/**
 * Reactive, stateless JWT resource server.
 * <ul>
 *   <li>{@code GET /actuator/health[/**]} is the only public surface.</li>
 *   <li>Every other {@code /actuator/**} path is denied.</li>
 *   <li>All other requests, including {@code /api/**}, require a valid bearer token.
 *       Per-role rules are intentionally left to the downstream services, which remain
 *       authoritative for authorization.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                  ReactiveJwtDecoder jwtDecoder,
                                                  SecurityErrorHandlers errorHandlers,
                                                  CorsConfigurationSource corsSource) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable) // stateless bearer-token API, no cookies/sessions
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(c -> c.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .cors(c -> c.configurationSource(corsSource))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                .authorizeExchange(a -> a
                        .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers("/actuator", "/actuator/**").denyAll()
                        .pathMatchers("/api/customers/**", "/api/accounts/**",
                                "/api/transactions/**", "/api/ledger/**").authenticated()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers)
                        .jwt(j -> j
                                .jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(
                                        new ReactiveJwtAuthenticationConverterAdapter(jwtAuthenticationConverter()))))
                .build();
    }

    /** Scope authorities ({@code SCOPE_x}) plus Keycloak realm roles ({@code ROLE_X}). */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        KeycloakAuthorityConverter roles = new KeycloakAuthorityConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Set<GrantedAuthority> all = new LinkedHashSet<>();
            Collection<GrantedAuthority> scopeAuthorities = scopes.convert(jwt);
            if (scopeAuthorities != null) {
                all.addAll(scopeAuthorities);
            }
            all.addAll(roles.convert(jwt));
            return all;
        });
        return converter;
    }
}
