package com.securebank.gateway.ratelimit;

import com.securebank.gateway.error.ErrorResponseWriter;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

/** Wires the limiter. Only the in-memory store exists; any other configured store fails startup (fail fast). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(RateLimitStore.class)
    RateLimitStore rateLimitStore(RateLimitProperties props) {
        if (!"memory".equals(props.store())) {
            throw new IllegalStateException("Unsupported securebank.rate-limit.store '" + props.store()
                    + "': only 'memory' is built in. Provide your own RateLimitStore bean for a shared store.");
        }
        return new InMemoryRateLimitStore(Clock.systemUTC(), props.maxKeys());
    }

    @Bean
    ClientAddressResolver clientAddressResolver(RateLimitProperties props) {
        return new ClientAddressResolver(props.trustedProxies());
    }

    @Bean
    RateLimitService rateLimitService(RateLimitProperties props, RateLimitStore store, ClientAddressResolver addresses) {
        return new RateLimitService(props, store, addresses);
    }

    @Bean
    RateLimitRejection rateLimitRejection(ErrorResponseWriter writer, CorsConfigurationSource cors) {
        return new RateLimitRejection(writer, cors);
    }

    @Bean
    AnonymousRateLimitWebFilter anonymousRateLimitWebFilter(RateLimitService limits, RateLimitRejection rejection) {
        return new AnonymousRateLimitWebFilter(limits, rejection);
    }

    @Bean
    AuthenticatedRateLimitWebFilter authenticatedRateLimitWebFilter(RateLimitService limits,
                                                                    RateLimitRejection rejection) {
        return new AuthenticatedRateLimitWebFilter(limits, rejection);
    }
}
