package com.securebank.customer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void keepsWellFormedInboundIdAndExposesItDuringTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc-12345.XYZ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            }
        });

        assertThat(seenInChain.get()).isEqualTo("abc-12345.XYZ");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-12345.XYZ");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatesAnIdWhenAbsent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).matches("[0-9a-f-]{36}");
    }

    @Test
    void replacesMalformedOrForgedIds() throws Exception {
        for (String bad : new String[] {"short", "has space in it", "line\nbreak-injection-1234", "x".repeat(65),
                "<script>alert(1)</script>"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(CorrelationIdFilter.HEADER, bad);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotEqualTo(bad).matches("[0-9a-f-]{36}");
        }
    }

    @Test
    void clearsTheMdcEvenWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc-12345.XYZ");
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                throw new IllegalStateException("boom");
            }
        })).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
