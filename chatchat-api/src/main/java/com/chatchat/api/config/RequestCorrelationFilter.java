package com.chatchat.api.config;

import com.chatchat.common.constants.AppConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Establishes stable request correlation data for diagnostics and client-abort attribution. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE = "chatchat.requestId";
    public static final String TRACE_ID_ATTRIBUTE = "chatchat.traceId";
    public static final String STARTED_NANOS_ATTRIBUTE = "chatchat.requestStartedNanos";
    public static final String TRACE_ID_HEADER = "X-Trace-ID";
    private static final String PUBLISHED_AGENT_API_PREFIX = "/api/v1/published-agents/";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = safeCorrelationId(request.getHeader(AppConstants.HEADER_X_REQUEST_ID));
        if (requestId == null) requestId = UUID.randomUUID().toString();
        String traceId = safeCorrelationId(request.getHeader(TRACE_ID_HEADER));
        if (traceId == null) traceId = traceIdFromTraceparent(request.getHeader("traceparent"));
        if (traceId == null) traceId = requestId;

        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        request.setAttribute(STARTED_NANOS_ATTRIBUTE, System.nanoTime());
        response.setHeader(AppConstants.HEADER_X_REQUEST_ID, requestId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        String applicationPath = request.getRequestURI().substring(request.getContextPath().length());
        if (applicationPath.startsWith(PUBLISHED_AGENT_API_PREFIX)) {
            response.setHeader("Cache-Control", CacheControl.noStore().getHeaderValue());
            response.setHeader("Pragma", "no-cache");
        }

        String previousRequestId = MDC.get("requestId");
        String previousTraceId = MDC.get("traceId");
        MDC.put("requestId", requestId);
        MDC.put("traceId", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            restoreMdc("requestId", previousRequestId);
            restoreMdc("traceId", previousTraceId);
        }
    }

    private String safeCorrelationId(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 128) return null;
        return normalized.matches("[A-Za-z0-9._:-]+") ? normalized : null;
    }

    private String traceIdFromTraceparent(String traceparent) {
        if (traceparent == null) return null;
        String[] parts = traceparent.trim().split("-");
        return parts.length >= 4 && parts[1].matches("[0-9a-fA-F]{32}") ? parts[1].toLowerCase() : null;
    }

    private void restoreMdc(String key, String previousValue) {
        if (previousValue == null) MDC.remove(key);
        else MDC.put(key, previousValue);
    }
}
