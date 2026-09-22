package com.chatchat.api.controller.search;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalRequestSigner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Authenticates MCP-to-API document evidence calls before they reach the search service. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class InternalDocumentSearchAuthFilter extends OncePerRequestFilter {
    private static final String SEARCH_PATH = "/internal/v1/document-search";
    private static final String AUTHORIZATION_PATH = "/internal/v1/resource-authorization";
    private final InternalCredentialProperties credentials;
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();

    public InternalDocumentSearchAuthFilter(InternalCredentialProperties credentials) {
        this.credentials = credentials;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SEARCH_PATH.equals(request.getRequestURI())
            && !AUTHORIZATION_PATH.equals(request.getRequestURI());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        if (!valid(request)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean valid(HttpServletRequest request) {
        if (!credentials.isEnabled()
            || !credentials.resolvedUsername().equals(request.getHeader(InternalRequestSigner.USER_HEADER))) {
            return false;
        }
        String timestamp = request.getHeader(InternalRequestSigner.TIMESTAMP_HEADER);
        String nonce = request.getHeader(InternalRequestSigner.NONCE_HEADER);
        String signature = request.getHeader(InternalRequestSigner.SIGNATURE_HEADER);
        if (timestamp == null || nonce == null || nonce.length() < 16 || signature == null) return false;
        try {
            String secret = credentials.resolvedSecret();
            if (secret.isBlank()) return false;
            long seconds = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - seconds) > 300) return false;
            nonces.entrySet().removeIf(entry -> now - entry.getValue() > 300);
            if (nonces.putIfAbsent(nonce, seconds) != null) return false;
            boolean valid = InternalRequestSigner.matches(signature, InternalRequestSigner.sign(
                secret, request.getMethod(), request.getRequestURI(), timestamp, nonce));
            if (!valid) nonces.remove(nonce);
            return valid;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
