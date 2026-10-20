package com.securebank.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Phase 0 security baseline. It is deliberately minimal and is replaced in Phase 2.
 *
 * - CSRF protection and Spring Security's default response headers stay at their secure defaults.
 * - Only the GET of the Phase 0 proof page is public; every other request is denied.
 * - There is no authentication in Phase 0: no login form, no users, no fake accounts.
 *   The empty user store exists only to stop Spring Boot from creating a default user with a
 *   generated password that it would write to the log.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                // Container-internal dispatches (the JSP forward and the error page) are not
                // client requests; the original request has already been authorized.
                .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/phase0/proof").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
