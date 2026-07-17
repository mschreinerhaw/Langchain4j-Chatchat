package com.chatchat.runtime.news.config;

import com.chatchat.common.security.InternalCredentialProperties;
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

@Component
public class NewsInternalAuthFilter extends OncePerRequestFilter {
    private final InternalCredentialProperties credentials;

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
        if (!credentials.isEnabled() || valid(request.getHeader("Authorization"))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"Invalid internal credential\"}");
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
