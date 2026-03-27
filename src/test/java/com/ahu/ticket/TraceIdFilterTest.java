package com.ahu.ticket;

import com.ahu.ticket.common.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TraceIdFilterTest {

    @Test
    public void testGenerateTraceIdAndSetResponseHeader() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rag/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        String traceId = response.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        assertNotNull(traceId);
        assertFalse(traceId.isBlank());
        assertEquals(traceId, request.getAttribute(TraceIdFilter.TRACE_ID_KEY));
    }

    @Test
    public void testReuseIncomingTraceId() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rag/ask");
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "trace-from-client");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals("trace-from-client", response.getHeader(TraceIdFilter.TRACE_ID_HEADER));
        assertEquals("trace-from-client", request.getAttribute(TraceIdFilter.TRACE_ID_KEY));
    }
}
