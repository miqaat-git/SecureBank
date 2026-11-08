package com.securebank.gateway.ratelimit;

import com.securebank.gateway.error.ErrorResponseWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.DefaultCorsProcessor;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Writes the 429 response: standard error body, Retry-After, and CORS headers so browsers can read it. */
public class RateLimitRejection {

    private final ErrorResponseWriter writer;
    private final CorsConfigurationSource cors;
    private final DefaultCorsProcessor corsProcessor = new DefaultCorsProcessor();

    public RateLimitRejection(ErrorResponseWriter writer, CorsConfigurationSource cors) {
        this.writer = writer;
        this.cors = cors;
    }

    public Mono<Void> reject(ServerWebExchange exchange, RateLimitDecision decision) {
        return Mono.defer(() -> {
            var config = cors.getCorsConfiguration(exchange);

            // Preserve rejected CORS responses rather than overwriting them with 429.
            if (!corsProcessor.process(config, exchange)) {
                return Mono.empty();
            }

            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER,
                    Long.toString(decision.retryAfterSeconds()));

            return writer.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests. Retry after the indicated delay.");
        });
    }
}
