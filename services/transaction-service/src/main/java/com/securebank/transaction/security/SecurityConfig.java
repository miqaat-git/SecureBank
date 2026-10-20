package com.securebank.transaction.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless resource-server security. Only health endpoints and API documentation are public; everything not
 * explicitly listed is denied. Production security is never relaxed for tests: tests use Spring Security Test's
 * jwt() post-processor against this same chain.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JsonSecurityErrorHandler errors)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())   // stateless bearer-token API, no cookies or sessions
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/transfers").hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/transfers/*/retry",
                                "/api/v1/transfers/*/reconcile").hasRole("TRANSFER_OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/transfers/*/operations")
                        .hasRole("TRANSFER_OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/transfers", "/api/v1/transfers/**")
                        .hasAnyRole("CUSTOMER", "TRANSFER_OPERATOR")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakJwtAuthenticationConverter()))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors));
        return http.build();
    }
}
