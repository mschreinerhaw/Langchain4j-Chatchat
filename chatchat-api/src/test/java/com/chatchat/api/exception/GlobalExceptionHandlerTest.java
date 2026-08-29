package com.chatchat.api.exception;

import com.chatchat.api.config.JsonRequestSizeFilter;
import com.chatchat.api.config.RequestCorrelationFilter;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    @Test
    void unsupportedHttpMethodReturnsMethodNotAllowedInsteadOfInternalServerError() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleHttpRequestMethodNotSupportedException(
            new HttpRequestMethodNotSupportedException("PUT"),
            new ServletWebRequest(new MockHttpServletRequest())
        );

        assertEquals(405, response.getStatusCode().value());
        assertEquals(405, response.getBody().getCode());
        assertEquals("Request method 'PUT' is not supported", response.getBody().getMessage());
    }

    @Test
    void streamedOversizedJsonReturnsPayloadTooLargeInHttpStatusAndBody() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
            "failed to read",
            new JsonRequestSizeFilter.RequestBodyTooLargeException(2_097_152)
        );

        ResponseEntity<ApiResponse<Void>> response = handler.handleHttpMessageNotReadableException(
            exception,
            new ServletWebRequest(new MockHttpServletRequest())
        );

        assertEquals(413, response.getStatusCode().value());
        assertEquals(413, response.getBody().getCode());
    }

    @Test
    void malformedJsonReturnsBadRequestInsteadOfInternalServerError() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleHttpMessageNotReadableException(
            mock(HttpMessageNotReadableException.class),
            new ServletWebRequest(new MockHttpServletRequest())
        );

        assertEquals(400, response.getStatusCode().value());
        assertEquals(
            "Request body is not valid JSON. Use double-quoted field names and do not escape the outer object.",
            response.getBody().getMessage()
        );
    }

    @Test
    void asyncRequestNotUsableReturnsClientClosedRequest() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET", "/api/v1/agent/tasks/task-123/result");
        request.setQueryString("tenantId=tenant-1&timeoutMs=5000");
        request.setParameter("tenantId", "tenant-1");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-1");
        request.setAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE, "request-1");
        request.setAttribute(RequestCorrelationFilter.TRACE_ID_ATTRIBUTE, "trace-1");
        request.setAttribute(RequestCorrelationFilter.STARTED_NANOS_ATTRIBUTE, System.nanoTime() - 10_000_000L);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        servletResponse.setStatus(200);

        ResponseEntity<Void> response = handler.handleAsyncRequestNotUsableException(
            new AsyncRequestNotUsableException("ServletOutputStream failed to write"),
            new ServletWebRequest(request, servletResponse)
        );

        assertEquals(499, response.getStatusCode().value());
        assertNull(response.getBody());
        GlobalExceptionHandler.ClientAbortDetails details = handler.clientAbortDetails(
            new AsyncRequestNotUsableException("ServletOutputStream failed to write", new IOException("Broken pipe")),
            new ServletWebRequest(request, servletResponse)
        );
        assertEquals("GET", details.method());
        assertEquals("/api/v1/agent/tasks/task-123/result", details.uri());
        assertEquals("[tenantId, timeoutMs]", details.queryKeys());
        assertEquals("request-1", details.requestId());
        assertEquals("trace-1", details.traceId());
        assertEquals("tenant-1", details.tenantId());
        assertEquals("task-123", details.taskId());
        assertEquals("java.io.IOException", details.rootCauseType());
        assertEquals("Broken pipe", details.rootCauseMessage());
    }

    @Test
    void globalHandlerTreatsConnectionResetAsClientClosedRequest() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleGlobalException(
            new RuntimeException("write failed", new IOException("Connection reset by peer")),
            new ServletWebRequest(new MockHttpServletRequest())
        );

        assertEquals(499, response.getStatusCode().value());
        assertNull(response.getBody());
    }

    @Test
    void globalHandlerTreatsWindowsChineseAbortMessageAsClientClosedRequest() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleGlobalException(
            new RuntimeException("write failed", new IOException("你的主机中的软件中止了一个已建立的连接")),
            new ServletWebRequest(new MockHttpServletRequest())
        );

        assertEquals(499, response.getStatusCode().value());
        assertNull(response.getBody());
    }
}
