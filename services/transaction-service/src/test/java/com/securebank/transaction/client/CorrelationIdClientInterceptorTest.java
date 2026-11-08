package com.securebank.transaction.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.securebank.transaction.config.CorrelationIdFilter;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

class CorrelationIdClientInterceptorTest {

    private final CorrelationIdClientInterceptor interceptor = new CorrelationIdClientInterceptor();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    private String forwardedHeader() throws Exception {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://localhost/x"));
        AtomicReference<String> sent = new AtomicReference<>();
        interceptor.intercept(request, new byte[0], (r, b) -> {
            sent.set(r.getHeaders().getFirst(CorrelationIdFilter.HEADER));
            return new MockClientHttpResponse(new byte[0], 200);
        });
        return sent.get();
    }

    @Test
    void forwardsTheCurrentCorrelationId() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY, "abc-12345.XYZ");
        assertThat(forwardedHeader()).isEqualTo("abc-12345.XYZ");
    }

    @Test
    void sendsNothingWhenThereIsNoCurrentRequest() throws Exception {
        assertThat(forwardedHeader()).isNull();
    }

    @Test
    void neverForwardsAMalformedValue() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY, "bad value\r\nX-Injected: 1");
        assertThat(forwardedHeader()).isNull();
    }
}
