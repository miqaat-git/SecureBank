package com.securebank.ledger.security;

import com.securebank.ledger.config.LedgerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless OAuth2 Resource Server. JWT signature and issuer are validated by Spring Security using
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} (LEDGER_JWT_ISSUER_URI).
 *
 * <p>Authorization (deny by default):
 * <ul>
 *   <li>health endpoints: public (details hidden)</li>
 *   <li>OpenAPI docs / Swagger UI: public unless ledger.security.public-api-docs=false</li>
 *   <li>POST /api/v1/journal-entries: LEDGER_WRITER</li>
 *   <li>POST /api/v1/journal-entries/{id}/reversals: LEDGER_REVERSER</li>
 *   <li>GET /api/v1/**: LEDGER_READER, LEDGER_WRITER or LEDGER_REVERSER</li>
 *   <li>POST /internal/journal-entries: LEDGER_WRITER</li>
 *   <li>POST /internal/journal-entries/{businessReference}/reversals: LEDGER_REVERSER</li>
 *   <li>GET /internal/journal-entries/by-reference/{businessReference}: any Ledger role</li>
 *   <li>everything else: denied</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String ROLE_READER = "LEDGER_READER";
    public static final String ROLE_WRITER = "LEDGER_WRITER";
    public static final String ROLE_REVERSER = "LEDGER_REVERSER";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   LedgerProperties properties,
                                                   KeycloakJwtAuthenticationConverter jwtConverter,
                                                   RestAuthenticationEntryPoint entryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        boolean publicDocs = properties.security().publicApiDocs();

        http
                .csrf(AbstractHttpConfigurer::disable)   // stateless bearer-token API, no cookies/sessions
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    if (publicDocs) {
                        auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll();
                    } else {
                        auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**")
                                .hasAnyRole(ROLE_READER, ROLE_WRITER, ROLE_REVERSER);
                    }
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/journal-entries").hasRole(ROLE_WRITER);
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/journal-entries/*/reversals")
                            .hasRole(ROLE_REVERSER);
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/**")
                            .hasAnyRole(ROLE_READER, ROLE_WRITER, ROLE_REVERSER);
                    // Internal adapter for Transaction Service (same roles as the public API; never public).
                    auth.requestMatchers(HttpMethod.POST, "/internal/journal-entries").hasRole(ROLE_WRITER);
                    auth.requestMatchers(HttpMethod.POST, "/internal/journal-entries/*/reversals")
                            .hasRole(ROLE_REVERSER);
                    auth.requestMatchers(HttpMethod.GET, "/internal/journal-entries/by-reference/*")
                            .hasAnyRole(ROLE_READER, ROLE_WRITER, ROLE_REVERSER);
                    auth.anyRequest().denyAll();
                })
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));

        return http.build();
    }
}
