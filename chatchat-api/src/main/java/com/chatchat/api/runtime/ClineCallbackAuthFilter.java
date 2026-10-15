package com.chatchat.api.runtime;

import com.chatchat.common.security.InternalRequestSigner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Authenticates Node-to-Runtime callbacks before any Coding authority is consulted. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@ConditionalOnProperty(prefix = "chatchat.coding.cline", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
public final class ClineCallbackAuthFilter extends OncePerRequestFilter {
    private static final String PREFIX = "/internal/coding/cline/";
    private final String secret;
    private final Set<String> allowedPeers;
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();

    @Autowired
    public ClineCallbackAuthFilter(@Value("${chatchat.coding.cline.shared-secret:}") String secret,
            @Value("${chatchat.coding.cline.allowed-peer-addresses:127.0.0.1,::1,0:0:0:0:0:0:0:1}")
            String allowedPeerAddresses) {
        if (secret == null || secret.length() < 32)
            throw new IllegalArgumentException("Cline callback secret must contain at least 32 characters");
        this.allowedPeers = Arrays.stream(allowedPeerAddresses.split(","))
            .map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (allowedPeers.isEmpty()) throw new IllegalArgumentException("A Coding callback peer is required");
        this.secret = secret;
    }

    ClineCallbackAuthFilter(String secret) {
        this(secret, "127.0.0.1,::1,0:0:0:0:0:0:0:1");
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        if (!allowedPeers.contains(request.getRemoteAddr())
                || !"2".equals(request.getHeader("X-Chatchat-Signature-Version"))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(1024 * 1024 + 1);
        if (body.length > 1024 * 1024) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        if (!valid(request, body)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        throw new UnsupportedOperationException("Coding callbacks use blocking JSON reads");
                    }
                };
            }
            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        }, response);
    }

    private boolean valid(HttpServletRequest request, byte[] body) {
        String address = request.getRemoteAddr();
        if (!"POST".equalsIgnoreCase(request.getMethod())
                || !allowedPeers.contains(address)
                || !InternalRequestSigner.matches(request.getHeader("Authorization"), "Bearer " + secret))
            return false;
        String timestamp = request.getHeader(InternalRequestSigner.TIMESTAMP_HEADER);
        String nonce = request.getHeader(InternalRequestSigner.NONCE_HEADER);
        String signature = request.getHeader(InternalRequestSigner.SIGNATURE_HEADER);
        if (timestamp == null || nonce == null || !nonce.matches("[0-9a-fA-F-]{32,64}") || signature == null)
            return false;
        try {
            long seconds = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - seconds) > 60) return false;
            nonces.entrySet().removeIf(entry -> now - entry.getValue() > 60);
            if (nonces.size() >= 100_000 || nonces.putIfAbsent(nonce, seconds) != null) return false;
            boolean accepted = InternalRequestSigner.matches(signature, InternalRequestSigner.signBody(
                secret, request.getMethod(), request.getRequestURI(), timestamp, nonce, body));
            if (!accepted) nonces.remove(nonce);
            return accepted;
        } catch (RuntimeException rejected) {
            return false;
        }
    }
}
