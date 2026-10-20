package com.securebank.customer.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT resource-server security.
 *   - /actuator/health (and sub-paths) are public
 *   - API docs are public only if securebank.security.public-api-docs=true
 *   - PATCH /api/v1/customers/{id}/status requires ROLE_ADMIN
 *   - every other /api/v1/customers endpoint requires ROLE_STAFF or ROLE_ADMIN
 *   - everything else requires authentication
 */
@Configuration
@EnableConfigurationProperties(SecureBankSecurityProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecureBankSecurityProperties properties,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler) throws Exception {

        http
                // Stateless bearer-token API: no cookies or sessions, so CSRF does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    if (properties.isPublicApiDocs()) {
                        auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                                .permitAll();
                    }
                    auth.requestMatchers(HttpMethod.PATCH, "/api/v1/customers/*/status").hasRole("ADMIN");
                    auth.requestMatchers("/api/v1/customers/**").hasAnyRole("STAFF", "ADMIN");
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter(SecureBankSecurityProperties properties) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter(
                properties.getJwt().getAdminRole(), properties.getJwt().getStaffRole()));
        return converter;
    }

    /**
     * With an issuer configured, tokens are verified (signature + issuer) against that provider.
     * Without one, every token is rejected: the service fails closed instead of trusting anything.
     */
    @Bean
    JwtDecoder jwtDecoder(SecureBankSecurityProperties properties) {
        String issuerUri = properties.getJwt().getIssuerUri();
        if (issuerUri == null || issuerUri.isBlank()) {
            log.warn("securebank.security.jwt.issuer-uri is not set: all bearer tokens will be rejected "
                    + "until an identity provider is configured.");
            return token -> {
                throw new BadJwtException("No identity provider is configured; token rejected.");
            };
        }
        return new LazyIssuerJwtDecoder(issuerUri);
    }
}
