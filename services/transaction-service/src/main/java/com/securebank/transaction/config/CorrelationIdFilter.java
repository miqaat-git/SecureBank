package com.securebank.transaction.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Correlation id for tracing one request across the Gateway and the services.
 * <p>
 * A well-formed inbound {@code X-Correlation-Id} (the Gateway always sets one) is kept; anything else is replaced by a
 * fresh UUID, so a caller can never inject log-forging characters. The id is put in the logging MDC (key
 * {@code correlationId}, printed by the log pattern), set on the response (including 401/403 and error responses,
 * because this filter runs before Spring Security) and cleared when the request ends. It is a tracing label only:
 * it is never used for authentication, authorization or ownership decisions.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    /** The inbound id when well-formed, otherwise a new random one. */
    public static String resolve(String inbound) {
        return isValid(inbound) ? inbound : UUID.randomUUID().toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = resolve(request.getHeader(HEADER));
        request.setAttribute(MDC_KEY, id);
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
