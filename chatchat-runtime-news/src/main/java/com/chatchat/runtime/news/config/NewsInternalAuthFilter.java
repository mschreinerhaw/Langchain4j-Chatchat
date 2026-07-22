package com.chatchat.runtime.news.config;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalRequestSigner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NewsInternalAuthFilter extends OncePerRequestFilter {
    private final InternalCredentialProperties credentials;
    private final Map<String, Long> usedNonces = new ConcurrentHashMap<>();

    public NewsInternalAuthFilter(InternalCredentialProperties credentials) {
        this.credentials = credentials;
        if (credentials.isEnabled() && credentials.resolvedSecret().isBlank()) {
            throw new IllegalStateException("News Runtime internal credential secret must be configured");
        }
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        if (!credentials.isEnabled() || validSignature(request) || valid(request.getHeader("Authorization"))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"Invalid internal credential\"}");
    }

    private boolean validSignature(HttpServletRequest request) {
        String user = request.getHeader(InternalRequestSigner.USER_HEADER);
        String timestamp = request.getHeader(InternalRequestSigner.TIMESTAMP_HEADER);
        String nonce = request.getHeader(InternalRequestSigner.NONCE_HEADER);
        String signature = request.getHeader(InternalRequestSigner.SIGNATURE_HEADER);
        if (!constantEquals(user == null ? "" : user, credentials.resolvedUsername())
            || timestamp == null || nonce == null || nonce.length() < 16 || signature == null) return false;
        try {
            long seconds = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - seconds) > 300) return false;
            usedNonces.entrySet().removeIf(entry -> now - entry.getValue() > 300);
            if (usedNonces.putIfAbsent(nonce, seconds) != null) return false;
            String expected = InternalRequestSigner.sign(credentials.resolvedSecret(), request.getMethod(),
                request.getRequestURI(), timestamp, nonce);
            if (InternalRequestSigner.matches(signature, expected)) return true;
            usedNonces.remove(nonce);
            return false;
        } catch (RuntimeException ex) { return false; }
    }

    private boolean valid(String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) return false;
        try {
            String pair = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
            int separator = pair.indexOf(':');
            if (separator < 0) return false;
            return constantEquals(pair.substring(0, separator), credentials.resolvedUsername())
                && constantEquals(pair.substring(separator + 1), credentials.resolvedSecret());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
