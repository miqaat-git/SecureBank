package com.securebank.gateway.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/** CORS is enforced by Spring Security's CORS filter (so preflights never need a token). */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecureBankProperties props) {
        SecureBankProperties.Cors c = props.cors();
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(clean(c.allowedOrigins()));
        cfg.setAllowedMethods(clean(c.allowedMethods()));
        cfg.setAllowedHeaders(clean(c.allowedHeaders()));
        cfg.setExposedHeaders(clean(c.exposedHeaders()));
        cfg.setAllowCredentials(c.allowCredentials());
        if (c.maxAge() != null) {
            cfg.setMaxAge(c.maxAge());
        }
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
}
