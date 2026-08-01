package com.chatchat.api.config;

import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Rejects oversized JSON before Jackson allocates a multi-megabyte String. */
@Slf4j
@Component
public class JsonRequestSizeFilter extends OncePerRequestFilter {
    private final JsonRequestSizeProperties properties;
    private final ObjectMapper objectMapper;

    public JsonRequestSizeFilter(JsonRequestSizeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (!properties.isEnabled() || !isJson(request)) {
            chain.doFilter(request, response);
            return;
        }
        long declared = request.getContentLengthLong();
        if (declared >= properties.safeWarningBytes()) {
            log.warn("Large JSON request method={} uri={} contentLength={} maxBytes={}",
                request.getMethod(), request.getRequestURI(), declared, properties.safeMaxBytes());
        }
        if (declared > properties.safeMaxBytes()) {
            reject(response, declared);
            return;
        }
        chain.doFilter(new LimitedRequest(request, properties.safeMaxBytes()), response);
    }

    private boolean isJson(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && (contentType.toLowerCase().contains("application/json")
            || contentType.toLowerCase().contains("+json"));
    }

    private void reject(HttpServletResponse response, long bytes) throws IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), ApiResponse.builder()
            .code(HttpStatus.PAYLOAD_TOO_LARGE.value())
            .message("JSON request exceeds the configured " + properties.safeMaxBytes() + " byte limit")
            .data(java.util.Map.of("receivedBytes", bytes, "maxBytes", properties.safeMaxBytes()))
            .timestamp(System.currentTimeMillis())
            .build());
    }

    public static final class RequestBodyTooLargeException extends IOException {
        public RequestBodyTooLargeException(int maxBytes) {
            super("JSON request exceeded " + maxBytes + " bytes while streaming");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final int maxBytes;
        private LimitedRequest(HttpServletRequest request, int maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }
        @Override public ServletInputStream getInputStream() throws IOException {
            return new LimitedInputStream(super.getInputStream(), maxBytes);
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final int maxBytes;
        private int readBytes;
        private LimitedInputStream(ServletInputStream delegate, int maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }
        @Override public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) increment(1);
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, Math.min(length, maxBytes - readBytes + 1));
            if (count > 0) increment(count);
            return count;
        }
        private void increment(int count) throws RequestBodyTooLargeException {
            readBytes += count;
            if (readBytes > maxBytes) throw new RequestBodyTooLargeException(maxBytes);
        }
        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
    }
}
