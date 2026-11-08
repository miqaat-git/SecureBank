package com.securebank.gateway.ratelimit;

import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Runs BEFORE Spring Security (CORS preflights are skipped, so CORS and authentication are unaffected):
 * requests without a bearer token are limited per client address. Requests with a bearer token pass through here;
 * valid tokens are limited per subject afterwards by {@link AuthenticatedRateLimitWebFilter}, and repeated invalid
 * tokens are limited by the 401 entry point (failed-authentication budget).
 */
public class AnonymousRateLimitWebFilter implements WebFilter, Ordered {

    /** Security's WebFilterChainProxy is at -100; this must run first, after the gateway's hygiene filters. */
    public static final int ORDER = -150;

    private final RateLimitService limits;
    private final RateLimitRejection rejection;

    public AnonymousRateLimitWebFilter(RateLimitService limits, RateLimitRejection rejection) {
        this.limits = limits;
        this.rejection = rejection;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return limits.ruleFor(exchange.getRequest()).map(rule -> {
            if (RateLimitService.hasBearer(exchange.getRequest())) {
                return chain.filter(exchange);          // limited per subject after validation
            }
            RateLimitDecision decision = limits.checkAnonymous(exchange, rule);
            return decision.allowed() ? chain.filter(exchange) : rejection.reject(exchange, decision);
        }).orElseGet(() -> chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
