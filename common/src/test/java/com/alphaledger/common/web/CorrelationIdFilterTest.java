package com.alphaledger.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesIdWhenHeaderMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> seenInChain = run(new MockHttpServletRequest(), response);

        assertThat(response.getHeader(CorrelationId.HEADER)).isNotBlank();
        assertThat(seenInChain.get()).isEqualTo(response.getHeader(CorrelationId.HEADER));
    }

    @Test
    void propagatesValidIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> seenInChain = run(request, response);

        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("abc-123");
        assertThat(seenInChain.get()).isEqualTo("abc-123");
    }

    @Test
    void replacesUnsafeIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "bad\nvalue");
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request, response);

        assertThat(response.getHeader(CorrelationId.HEADER)).isNotEqualTo("bad\nvalue").isNotBlank();
    }

    @Test
    void clearsMdcAfterRequest() throws Exception {
        run(new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    private AtomicReference<String> run(MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                seen.set(CorrelationId.current());
            }
        };
        filter.doFilter(request, response, new MockFilterChain(servlet));
        return seen;
    }
}
