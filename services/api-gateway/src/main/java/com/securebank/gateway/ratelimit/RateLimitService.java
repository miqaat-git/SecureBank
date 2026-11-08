package com.securebank.gateway.ratelimit;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Applies the configured policies. Holds no per-request state; counters live in the {@link RateLimitStore}. */
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private record CompiledRule(RateLimitProperties.Rule rule, List<PathPattern> patterns) {
        boolean matches(PathContainer path, HttpMethod method) {
            boolean methodOk = rule.methods() == null || rule.methods().isEmpty()
                    || rule.methods().stream().anyMatch(m -> m.equalsIgnoreCase(method.name()));
            return methodOk && patterns.stream().anyMatch(p -> p.matches(path));
        }
    }

    private final RateLimitProperties props;
    private final RateLimitStore store;
    private final ClientAddressResolver addresses;
    private final List<CompiledRule> rules;
    private final List<PathPattern> exempt;

    public RateLimitService(RateLimitProperties props, RateLimitStore store, ClientAddressResolver addresses) {
        this.props = props;
        this.store = store;
        this.addresses = addresses;
        PathPatternParser parser = PathPatternParser.defaultInstance;
        this.rules = props.rules().stream().map(r -> new CompiledRule(r,
                r.paths().stream().map(parser::parse).toList())).toList();
        this.exempt = props.exemptPaths().stream().map(parser::parse).toList();
        if (props.enabled()) {
            log.warn("Rate limiting uses a PER-INSTANCE in-memory store: limits are not shared between gateway "
                    + "instances and do not protect a multi-instance deployment on their own.");
        }
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** The rule governing this request, empty when the request is not limited (CORS preflight, exempt, no match). */
    public Optional<RateLimitProperties.Rule> ruleFor(ServerHttpRequest request) {
        if (!props.enabled() || HttpMethod.OPTIONS.equals(request.getMethod())) {
            return Optional.empty();
        }
        PathContainer path = request.getPath().pathWithinApplication();
        if (exempt.stream().anyMatch(p -> p.matches(path))) {
            return Optional.empty();
        }
        return rules.stream().filter(r -> r.matches(path, request.getMethod())).map(CompiledRule::rule).findFirst();
    }

    public static boolean hasBearer(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) && header.length() > 7
                && !header.substring(7).isBlank();
    }

    public RateLimitDecision checkAnonymous(ServerWebExchange exchange, RateLimitProperties.Rule rule) {
        String key = "anon:" + rule.name() + ":" + addresses.resolve(exchange.getRequest());
        return store.tryAcquire(key, rule.anonymous().limit(), rule.anonymous().window());
    }

    public RateLimitDecision checkAuthenticated(RateLimitProperties.Rule rule, String subject) {
        return store.tryAcquire("auth:" + rule.name() + ":" + subject, rule.authenticated().limit(),
                rule.authenticated().window());
    }

    /**
     * Charges one failed token validation to the client address and reports whether the address is still within its
     * failed-authentication budget. Called only from the 401 entry point, so valid tokens are never affected.
     */
    public RateLimitDecision recordAuthFailure(ServerWebExchange exchange) {
        if (!props.enabled() || !hasBearer(exchange.getRequest())) {
            return RateLimitDecision.permit();
        }
        RateLimitProperties.Policy p = props.authFailure();
        return store.tryAcquire("authfail:" + addresses.resolve(exchange.getRequest()), p.limit(), p.window());
    }
}
