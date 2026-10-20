package com.securebank.account.security;

import com.securebank.account.config.AccountProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless OAuth2 resource-server security (Keycloak JWTs).
 *
 * <pre>
 *   /actuator/health/**, OpenAPI docs      public
 *   /internal/**                           ROLE_TRANSACTION_SERVICE (service-to-service only)
 *   /api/v1/admin/**                       ROLE_ADMIN
 *   /api/v1/**                             ROLE_ADMIN or ROLE_CUSTOMER (ownership checked in the service layer)
 *   everything else                        denied
 * </pre>
 * The same filter chain is used in every profile (including tests); tests supply JWTs through
 * spring-security-test instead of weakening these rules.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AccountProperties properties,
                                                   RestSecurityErrorHandlers errorHandlers) throws Exception {
        KeycloakJwtAuthenticationConverter converter =
                new KeycloakJwtAuthenticationConverter(properties.security().resourceClientId());

        http
                // CSRF protects cookie-based browser sessions; this API is stateless and bearer-token only.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        .requestMatchers("/internal/**").hasRole("TRANSACTION_SERVICE")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").hasAnyRole("ADMIN", "CUSTOMER")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers));
        return http.build();
    }

    /**
     * Production JWT decoder: signature (JWKS discovered from the issuer), expiry, issuer and - when configured -
     * audience validation. Not active in the {@code test} profile, where tests provide their own decoder.
     */
    @Bean
    @Profile("!test")
    public JwtDecoder jwtDecoder(AccountProperties properties) {
        String issuerUri = properties.security().issuerUri();
        String audience = properties.security().audience();
        return new LazyJwtDecoder(() -> {
            NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);
            OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(issuerUri);
            if (audience != null && !audience.isBlank()) {
                OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                        JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience));
                validator = new DelegatingOAuth2TokenValidator<>(validator, audienceValidator);
            }
            decoder.setJwtValidator(validator);
            return decoder;
        });
    }
}
