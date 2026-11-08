package com.securebank.gateway.security;

import com.securebank.gateway.error.ErrorResponseWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Sanitized 401/403 responses. Never reveals why a token was rejected. */
@Component
public class SecurityErrorHandlers implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private final ErrorResponseWriter writer;
    private final org.springframework.beans.factory.ObjectProvider<com.securebank.gateway.ratelimit.RateLimitService>
            rateLimits;

    public SecurityErrorHandlers(ErrorResponseWriter writer,
                                 org.springframework.beans.factory.ObjectProvider<
                                         com.securebank.gateway.ratelimit.RateLimitService> rateLimits) {
        this.writer = writer;
        this.rateLimits = rateLimits;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        if (ex instanceof AuthenticationServiceException) {
            // Identity provider metadata could not be resolved: not the caller's fault.
            return writer.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "AUTH_PROVIDER_UNAVAILABLE",
                    "Unable to validate credentials at this time.");
        }
        // A presented-but-invalid token counts against the caller address' failed-authentication budget; once that
        // is exhausted the answer becomes 429 (valid tokens are never affected).
        com.securebank.gateway.ratelimit.RateLimitService limits = rateLimits.getIfAvailable();
        if (limits != null) {
            com.securebank.gateway.ratelimit.RateLimitDecision decision = limits.recordAuthFailure(exchange);
            if (!decision.allowed()) {
                exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER,
                        Long.toString(decision.retryAfterSeconds()));
                return writer.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Too many requests. Retry after the indicated delay.");
            }
        }
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return writer.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required or the supplied credentials are invalid.");
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return writer.write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to access this resource.");
    }
}
