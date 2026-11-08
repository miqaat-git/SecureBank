package com.securebank.gateway.ratelimit;

import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Runs AFTER Spring Security, so the identity is the validated token's subject (or client id for a token without
 * one), never a request header. Each identity has its own counter per rule.
 */
public class AuthenticatedRateLimitWebFilter implements WebFilter, Ordered {

    public static final int ORDER = -50;

    @Override
    public int getOrder() {
        return ORDER;
    }

    private final RateLimitService limits;
    private final RateLimitRejection rejection;

    public AuthenticatedRateLimitWebFilter(RateLimitService limits, RateLimitRejection rejection) {
        this.limits = limits;
        this.rejection = rejection;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var rule = limits.ruleFor(exchange.getRequest());
        if (rule.isEmpty()) {
            return chain.filter(exchange);
        }

        // Wrap the downstream Mono<Void> in an outer Mono value.
        // A successful Mono<Void> completes empty; switchIfEmpty after
        // chain.filter(exchange) would therefore invoke the chain twice.
        Mono<Mono<Void>> action = ReactiveSecurityContextHolder.getContext()
                .flatMap(ctx -> Mono.justOrEmpty(ctx.getAuthentication()))
                .filter(Authentication::isAuthenticated)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .map(AuthenticatedRateLimitWebFilter::identity)
                .map(id -> {
                    RateLimitDecision decision = limits.checkAuthenticated(rule.get(), id);
                    return decision.allowed()
                            ? Mono.defer(() -> chain.filter(exchange))
                            : Mono.defer(() -> rejection.reject(exchange, decision));
                });

        return action
                .defaultIfEmpty(Mono.defer(() -> chain.filter(exchange)))
                .flatMap(next -> next);
    }

    private static String identity(JwtAuthenticationToken auth) {
        String subject = auth.getToken().getSubject();
        if (subject != null && !subject.isBlank()) {
            return subject;
        }
        String client = auth.getToken().getClaimAsString("azp");
        return client == null || client.isBlank() ? "unknown-subject" : client;
    }
}
