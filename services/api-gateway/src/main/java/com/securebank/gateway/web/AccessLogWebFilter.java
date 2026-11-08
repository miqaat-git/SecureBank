package com.securebank.gateway.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * One log line per request. Deliberately excludes path, query string, headers (so no bearer
 * tokens) and bodies (so no customer or financial data): only method, matched route id,
 * status, duration and correlation id.
 */
@Component
public class AccessLogWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AccessLogWebFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long start = System.nanoTime();
        // Logged at commit time, when the final status (including gateway errors) is known.
        exchange.getResponse().beforeCommit(() -> {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            log.info("gateway_request method={} route={} status={} durationMs={} correlationId={}",
                    exchange.getRequest().getMethod(),
                    route == null ? "none" : route.getId(),
                    exchange.getResponse().getStatusCode() == null ? "-" : exchange.getResponse().getStatusCode().value(),
                    (System.nanoTime() - start) / 1_000_000,
                    exchange.getAttributeOrDefault(CorrelationIdWebFilter.ATTRIBUTE, "unknown"));
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
