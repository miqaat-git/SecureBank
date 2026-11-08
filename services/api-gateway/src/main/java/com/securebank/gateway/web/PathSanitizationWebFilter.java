package com.securebank.gateway.web;

import com.securebank.gateway.error.ErrorResponseWriter;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Rejects request paths that could be used to steer a prefix rewrite onto a different upstream path
 * (for example {@code /api/accounts/../../../internal/accounts/1/operations}) before routing or authorization run.
 * The Gateway rewrites public prefixes to service paths, so dot segments, encoded dots/slashes/backslashes/percent,
 * NUL, path parameters ({@code ;}), backslashes and empty segments are never legitimate in this API's paths
 * (identifiers are UUIDs, numbers and short tokens).
 */
@Component
public class PathSanitizationWebFilter implements WebFilter, Ordered {

    private static final Pattern FORBIDDEN_ENCODING = Pattern.compile("%(2e|2f|5c|00|25)", Pattern.CASE_INSENSITIVE);

    private final ErrorResponseWriter writer;

    public PathSanitizationWebFilter(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isUnsafe(exchange.getRequest().getURI().getRawPath())) {
            return writer.write(exchange, HttpStatus.BAD_REQUEST, "INVALID_PATH", "The request path is not valid.");
        }
        return chain.filter(exchange);
    }

    static boolean isUnsafe(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return false;
        }
        if (rawPath.indexOf('\\') >= 0 || rawPath.indexOf(';') >= 0 || rawPath.contains("//")
                || FORBIDDEN_ENCODING.matcher(rawPath).find()) {
            return true;
        }
        for (String segment : rawPath.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /** Right after the correlation id filter, so the 400 still carries a correlation id. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
