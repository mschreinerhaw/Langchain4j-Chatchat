package com.chatchat.api.config;

import com.chatchat.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiResponseContractAdviceTest {

    @Test
    void addsRequestIdAndDisablesCachingForPublishedAgentResponses() {
        ApiResponseContractAdvice advice = new ApiResponseContractAdvice();
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET", "/api/v1/published-agents/finance-agent/questions/task-1/status");
        request.setAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE, "request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletServerHttpResponse serverResponse = new ServletServerHttpResponse(response);
        ApiResponse<String> body = ApiResponse.success("ok");

        advice.beforeBodyWrite(
            body, null, null, null,
            new ServletServerHttpRequest(request), serverResponse
        );

        assertEquals("request-123", body.getRequestId());
        assertEquals("no-store", serverResponse.getHeaders().getFirst("Cache-Control"));
        assertEquals("no-cache", serverResponse.getHeaders().getFirst("Pragma"));
    }
}
