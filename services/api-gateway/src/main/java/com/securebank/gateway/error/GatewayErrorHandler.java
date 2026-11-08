package com.securebank.gateway.error;

import com.securebank.gateway.web.CorrelationIdWebFilter;
import io.netty.channel.ConnectTimeoutException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Converts gateway-side failures (unknown route, downstream down/slow/invalid) into sanitized
 * JSON. Downstream HTTP responses (including 4xx/5xx business failures) never reach this
 * handler: they are passed through unchanged by the routing filter.
 */
@Component
@Order(-2) // ahead of Spring Boot's DefaultErrorWebExceptionHandler (-1)
public class GatewayErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);
    private static final int MAX_CAUSE_DEPTH = 10;

    record Failure(HttpStatus status, String code, String message) {
    }

    private final ErrorResponseWriter writer;

    public GatewayErrorHandler(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        Failure failure = classify(ex);
        // Class name only: exception messages may contain hostnames or other internals.
        log.warn("gateway_error code={} cause={} correlationId={}", failure.code(),
                ex.getClass().getSimpleName(),
                exchange.getAttributeOrDefault(CorrelationIdWebFilter.ATTRIBUTE, "unknown"));
        log.debug("gateway_error detail", ex);
        return writer.write(exchange, failure.status(), failure.code(), failure.message());
    }

    static Failure classify(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
            if (status != null) {
                switch (status) {
                    case NOT_FOUND:
                        return notFound();
                    case BAD_GATEWAY:
                        return badGateway();
                    case SERVICE_UNAVAILABLE:
                        return unavailable();
                    case GATEWAY_TIMEOUT:
                        return timeout();
                    default:
                        if (status.is4xxClientError()) {
                            return new Failure(status, status.name(), status.getReasonPhrase());
                        }
                }
            }
        }
        int depth = 0;
        for (Throwable t = ex; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            if (t instanceof io.netty.handler.timeout.TimeoutException
                    || t instanceof TimeoutException
                    || t instanceof ConnectTimeoutException) {
                return timeout();
            }
            if (t instanceof ConnectException
                    || t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException) {
                return unavailable();
            }
            String name = t.getClass().getSimpleName();
            if (name.equals("PrematureCloseException")
                    || name.equals("DecoderException")
                    || name.equals("TooLongFrameException")
                    || name.equals("CorruptedFrameException")
                    || t instanceof SSLException
                    || t instanceof IOException) {
                return badGateway();
            }
        }
        return new Failure(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "The gateway could not process the request.");
    }

    private static Failure notFound() {
        return new Failure(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "No route matches the requested resource.");
    }

    private static Failure unavailable() {
        return new Failure(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "The downstream service is currently unavailable.");
    }

    private static Failure timeout() {
        return new Failure(HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT",
                "The downstream service did not respond in time.");
    }

    private static Failure badGateway() {
        return new Failure(HttpStatus.BAD_GATEWAY, "BAD_GATEWAY",
                "The downstream service returned an invalid response.");
    }
}
