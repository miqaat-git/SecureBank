package com.securebank.gateway.web;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Ensures every request has a safe {@code X-Correlation-Id}: a well-formed inbound value is
 * kept, anything else is replaced by a fresh UUID. The id is forwarded downstream and always
 * returned on the response (including gateway-generated errors).
 */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = "securebank.correlationId";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String inbound = exchange.getRequest().getHeaders().getFirst(HEADER);
        String id = (inbound != null && VALID.matcher(inbound).matches()) ? inbound : UUID.randomUUID().toString();
        exchange.getAttributes().put(ATTRIBUTE, id);

        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> h.set(HEADER, id)))
                .build();
        // Set at commit time so a downstream-supplied copy of the header cannot override it.
        mutated.getResponse().beforeCommit(() -> {
            mutated.getResponse().getHeaders().set(HEADER, id);
            return Mono.empty();
        });
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
