package com.chatchat.api.exception;

import com.chatchat.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerTest {

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
