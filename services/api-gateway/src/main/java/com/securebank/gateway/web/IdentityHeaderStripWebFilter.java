package com.securebank.gateway.web;

import com.securebank.gateway.config.SecureBankProperties;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Removes identity-related headers supplied by external callers so they can never be mistaken
 * for trusted internal identity. The gateway does not inject identity headers: downstream
 * services must derive identity from the validated JWT.
 */
@Component
public class IdentityHeaderStripWebFilter implements WebFilter, Ordered {

    private final Set<String> names;
    private final List<String> prefixes;

    public IdentityHeaderStripWebFilter(SecureBankProperties props) {
        SecureBankProperties.Headers h = props.headers();
        this.names = nz(h == null ? null : h.stripRequestHeaders()).stream()
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        this.prefixes = nz(h == null ? null : h.stripRequestHeaderPrefixes()).stream()
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        boolean present = exchange.getRequest().getHeaders().keySet().stream().anyMatch(this::shouldStrip);
        if (!present) {
            return chain.filter(exchange);
        }
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> h.keySet().removeIf(this::shouldStrip)))
                .build();
        return chain.filter(mutated);
    }

    boolean shouldStrip(String headerName) {
        String lower = headerName.toLowerCase(Locale.ROOT);
        return names.contains(lower) || prefixes.stream().anyMatch(lower::startsWith);
    }

    private static List<String> nz(List<String> list) {
        return list == null ? List.of() : list;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
