package com.chatchat.mcpserver.market;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalRequestSigner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MarketInternalAuthFilter extends OncePerRequestFilter {
    private final InternalCredentialProperties credentials;
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();

    public MarketInternalAuthFilter(InternalCredentialProperties credentials) {
        this.credentials = credentials;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/v1/market/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        if (!credentials.isEnabled() || valid(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"Invalid internal credential\"}");
    }

    private boolean valid(HttpServletRequest request) {
        String timestamp = request.getHeader(InternalRequestSigner.TIMESTAMP_HEADER);
        String nonce = request.getHeader(InternalRequestSigner.NONCE_HEADER);
        String signature = request.getHeader(InternalRequestSigner.SIGNATURE_HEADER);
        if (!credentials.resolvedUsername().equals(request.getHeader(InternalRequestSigner.USER_HEADER))
            || timestamp == null || nonce == null || nonce.length() < 16 || signature == null) return false;
        try {
            long seconds = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - seconds) > 300) return false;
            nonces.entrySet().removeIf(entry -> now - entry.getValue() > 300);
            if (nonces.putIfAbsent(nonce, seconds) != null) return false;
            boolean valid = InternalRequestSigner.matches(signature, InternalRequestSigner.sign(
                credentials.resolvedSecret(), request.getMethod(), request.getRequestURI(), timestamp, nonce));
            if (!valid) nonces.remove(nonce);
            return valid;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
