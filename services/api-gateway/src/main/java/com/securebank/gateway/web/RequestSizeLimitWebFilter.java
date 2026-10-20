package com.securebank.gateway.web;

import com.securebank.gateway.config.SecureBankProperties;
import com.securebank.gateway.error.ErrorResponseWriter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Rejects requests whose declared Content-Length exceeds the configured limit (413).
 * Chunked requests have no declared length; see README "Known limitations".
 */
@Component
public class RequestSizeLimitWebFilter implements WebFilter, Ordered {

    private final long maxBytes;
    private final ErrorResponseWriter writer;

    public RequestSizeLimitWebFilter(SecureBankProperties props, ErrorResponseWriter writer) {
        this.maxBytes = props.limits().maxRequestBodySize().toBytes();
        this.writer = writer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long declared = exchange.getRequest().getHeaders().getContentLength();
        if (declared > maxBytes) {
            return writer.write(exchange, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "The request body exceeds the maximum allowed size.");
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }
}
