package com.securebank.gateway.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

/**
 * Reactive, stateless JWT resource server.
 * <ul>
 *   <li>{@code GET /actuator/health[/**]} is the only public surface.</li>
 *   <li>Every other {@code /actuator/**} path is denied.</li>
 *   <li>{@code /internal/**}, OpenAPI and Swagger paths are always denied.</li>
 *   <li>{@code /api/admin/**} requires ADMIN (realm role "admin") and no machine-only role.</li>
 *   <li>{@code /api/ledger/**} is GET-only through the Gateway.</li>
 *   <li>Machine-only service-account tokens are refused on the public API.</li>
 *   <li>All other requests require a valid bearer token. Fine-grained per-role
 *       and ownership rules remain the responsibility of downstream services.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * Dedicated machine-only roles. Tokens with any of these roles must not gain
     * public API or admin access, even if they also carry other roles.
     */
    static final Set<String> SERVICE_ROLES = Set.of(
            "ROLE_TRANSACTION_SERVICE",
            "ROLE_LEDGER_WRITER",
            "ROLE_LEDGER_REVERSER");

    static final String ADMIN_ROLE = "ROLE_ADMIN";

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder jwtDecoder,
            SecurityErrorHandlers errorHandlers,
            CorsConfigurationSource corsSource) {

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
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
                        .pathMatchers(
                                HttpMethod.GET,
                                "/actuator/health",
                                "/actuator/health/**").permitAll()
                        .pathMatchers("/actuator", "/actuator/**").denyAll()

                        // Service-to-service and documentation paths are never public.
                        .pathMatchers(
                                "/internal",
                                "/internal/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**").denyAll()

                        // Admin access requires ADMIN and excludes machine-only identities.
                        .pathMatchers("/api/admin/**").access(adminCaller())

                        // Ledger is read-only through the Gateway.
                        .pathMatchers(HttpMethod.GET, "/api/ledger/**")
                        .access(publicApiCaller())
                        .pathMatchers("/api/ledger", "/api/ledger/**").denyAll()

                        .pathMatchers(
                                "/api/customers/**",
                                "/api/accounts/**",
                                "/api/account-numbers/**",
                                "/api/transactions/**").access(publicApiCaller())

                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers)
                        .jwt(j -> j
                                .jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(
                                        new ReactiveJwtAuthenticationConverterAdapter(
                                                jwtAuthenticationConverter()))))
                .build();
    }

    /**
     * Public API callers must be authenticated and must not carry a
     * machine-only role.
     */
    static ReactiveAuthorizationManager<AuthorizationContext> publicApiCaller() {
        return (authentication, context) -> authentication.map(a ->
                new AuthorizationDecision(
                        a.isAuthenticated()
                                && !hasServiceRole(a.getAuthorities())));
    }

    /**
     * Admin routes require an authenticated token with ROLE_ADMIN and reject
     * tokens carrying any machine-only role.
     */
    static ReactiveAuthorizationManager<AuthorizationContext> adminCaller() {
        return (authentication, context) -> authentication.map(a ->
                new AuthorizationDecision(
                        a.isAuthenticated()
                                && a.getAuthorities().stream()
                                        .anyMatch(g ->
                                                ADMIN_ROLE.equals(g.getAuthority()))
                                && !hasServiceRole(a.getAuthorities())));
    }

    private static boolean hasServiceRole(
            Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .anyMatch(g -> SERVICE_ROLES.contains(g.getAuthority()));
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
