package com.securebank.gateway.error;

import com.securebank.gateway.web.CorrelationIdWebFilter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Writes the single, sanitized JSON error shape used by every gateway-generated error.
 * All values are fixed strings or validated identifiers, so manual JSON assembly is safe.
 */
@Component
public class ErrorResponseWriter {

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        String correlationId = exchange.getAttributeOrDefault(CorrelationIdWebFilter.ATTRIBUTE, "unknown");
        String body = "{\"timestamp\":\"" + Instant.now()
                + "\",\"status\":" + status.value()
                + ",\"error\":\"" + status.getReasonPhrase()
                + "\",\"code\":\"" + code
                + "\",\"message\":\"" + message
                + "\",\"correlationId\":\"" + correlationId + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setContentLength(bytes.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
}
