package com.chatchat.api.config;

import com.chatchat.common.constants.AppConstants;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestCorrelationFilterTest {

    @Test
    void propagatesCorrelationAndMakesTimingAvailableDuringRequest() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agent/tasks/task-1/result");
        request.addHeader(AppConstants.HEADER_X_REQUEST_ID, "request-123");
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertEquals("request-123", req.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE));
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", req.getAttribute(RequestCorrelationFilter.TRACE_ID_ATTRIBUTE));
            assertNotNull(req.getAttribute(RequestCorrelationFilter.STARTED_NANOS_ATTRIBUTE));
            assertEquals("request-123", MDC.get("requestId"));
        });

        assertEquals("request-123", response.getHeader(AppConstants.HEADER_X_REQUEST_ID));
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", response.getHeader(RequestCorrelationFilter.TRACE_ID_HEADER));
        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("traceId"));
    }

    @Test
    void rejectsLogInjectionInIncomingCorrelationId() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        request.addHeader(AppConstants.HEADER_X_REQUEST_ID, "bad\r\nforged-log=true");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
            assertNotNull(req.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE)));

        String generated = response.getHeader(AppConstants.HEADER_X_REQUEST_ID);
        assertNotNull(generated);
        assertEquals(36, generated.length());
    }

    @Test
    void disablesCachingBeforeAuthenticationForPublishedAgentApi() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET", "/api/v1/published-agents/demo/questions/task-1/status");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no-cache", response.getHeader("Pragma"));
    }
}
