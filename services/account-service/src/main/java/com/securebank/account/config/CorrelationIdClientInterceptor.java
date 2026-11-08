package com.securebank.account.config;

import com.securebank.account.config.CorrelationIdFilter;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Forwards the current request's correlation id on internal service-to-service calls so one customer request can be
 * followed through the logs of every service. Only a well-formed id taken from the logging MDC is sent; nothing about
 * the caller's identity or token is forwarded here.
 */
public class CorrelationIdClientInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String id = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (CorrelationIdFilter.isValid(id)) {
            request.getHeaders().set(CorrelationIdFilter.HEADER, id);
        }
        return execution.execute(request, body);
    }
}
