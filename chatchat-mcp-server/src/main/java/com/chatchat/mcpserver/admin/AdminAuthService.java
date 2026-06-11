package com.chatchat.mcpserver.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AdminAuthProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, TokenSession> sessions = new ConcurrentHashMap<>();

    /**
     * Performs the login operation.
     *
     * @param username the username value
     * @param password the password value
     * @return the operation result
     */
    public LoginResult login(String username, String password) {
        if (!constantTimeEquals(properties.getUsername(), username)
            || !constantTimeEquals(properties.getPassword(), password)) {
            throw new IllegalArgumentException("Invalid username or password");
        }

        String token = newToken();
        long ttlMinutes = Math.max(1, properties.getTokenTtlMinutes());
        Instant expiresAt = Instant.now().plusSeconds(ttlMinutes * 60);
        sessions.put(token, new TokenSession(properties.getUsername(), expiresAt));
        return new LoginResult(token, properties.getUsername(), expiresAt.toEpochMilli());
    }

    /**
     * Returns whether is valid.
     *
     * @param token the token value
     * @return whether the condition is satisfied
     */
    public boolean isValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        TokenSession session = sessions.get(token);
        if (session == null) {
            return false;
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    /**
     * Performs the logout operation.
     *
     * @param token the token value
     */
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sessions.remove(token);
        }
    }

    /**
     * Performs the new token operation.
     *
     * @return the operation result
     */
    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Returns whether constant time equals.
     *
     * @param expected the expected value
     * @param actual the actual value
     * @return whether the condition is satisfied
     */
    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] left = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int diff = left.length ^ right.length;
        int max = Math.max(left.length, right.length);
        for (int i = 0; i < max; i++) {
            byte a = i < left.length ? left[i] : 0;
            byte b = i < right.length ? right[i] : 0;
            diff |= a ^ b;
        }
        return diff == 0;
    }

    private record TokenSession(String username, Instant expiresAt) {
    }

    public record LoginResult(String token, String username, long expiresAt) {
    }
}
