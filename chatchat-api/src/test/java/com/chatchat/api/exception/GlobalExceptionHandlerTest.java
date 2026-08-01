package com.chatchat.api.exception;

import com.chatchat.api.config.JsonRequestSizeFilter;
import com.chatchat.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

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

        ResponseEntity<Void> response = handler.handleAsyncRequestNotUsableException(
            new AsyncRequestNotUsableException("ServletOutputStream failed to write"),
            new ServletWebRequest(new MockHttpServletRequest())
        );

        assertEquals(499, response.getStatusCode().value());
        assertNull(response.getBody());
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
