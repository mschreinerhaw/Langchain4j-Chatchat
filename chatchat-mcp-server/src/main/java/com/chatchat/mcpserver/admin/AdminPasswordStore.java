package com.chatchat.mcpserver.admin;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPasswordStore {

    static final String DEFAULT_USERNAME = "admin";
    static final String DEFAULT_PASSWORD = "admin123";
    static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;

    private final AdminUserRepository repository;
    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    public void initializeDefaultUser() {
        if (repository.findByUsernameIgnoreCase(DEFAULT_USERNAME).isPresent()) {
            return;
        }
        try {
            repository.saveAndFlush(newUser(DEFAULT_USERNAME, DEFAULT_PASSWORD));
            log.info("Initialized MCP default administrator '{}' in mcp_admin_user", DEFAULT_USERNAME);
        } catch (DataIntegrityViolationException ex) {
            if (repository.findByUsernameIgnoreCase(DEFAULT_USERNAME).isEmpty()) {
                throw ex;
            }
            log.debug("MCP default administrator was initialized concurrently");
        }
    }

    /**
     * Authenticates an enabled database account.
     *
     * @return the canonical database username, or an empty value when authentication fails
     */
    public Optional<String> authenticate(String username, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null) {
            return Optional.empty();
        }
        return repository.findByUsernameIgnoreCase(username.trim())
            .filter(AdminUser::isEnabled)
            .filter(user -> matches(user, rawPassword))
            .map(AdminUser::getUsername);
    }

    public boolean matches(String username, String rawPassword) {
        return authenticate(username, rawPassword).isPresent();
    }

    public void save(String username, String newPassword) {
        AdminUser user = repository.findByUsernameIgnoreCase(username)
            .orElseThrow(() -> new IllegalStateException("MCP administrator does not exist"));
        applyPassword(user, newPassword);
        repository.saveAndFlush(user);
    }

    private AdminUser newUser(String username, String rawPassword) {
        AdminUser user = new AdminUser();
        user.setUsername(username);
        user.setEnabled(true);
        applyPassword(user, rawPassword);
        return user;
    }

    private void applyPassword(AdminUser user, String rawPassword) {
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        user.setPasswordAlgorithm(ALGORITHM);
        user.setPasswordIterations(ITERATIONS);
        user.setPasswordSalt(encode(salt));
        user.setPasswordHash(encode(hash(rawPassword, salt, ITERATIONS, ALGORITHM)));
    }

    private boolean matches(AdminUser user, String rawPassword) {
        try {
            byte[] salt = decode(user.getPasswordSalt());
            byte[] expected = decode(user.getPasswordHash());
            byte[] actual = hash(rawPassword, salt, user.getPasswordIterations(), user.getPasswordAlgorithm());
            return constantTimeEquals(expected, actual);
        } catch (RuntimeException ex) {
            log.warn("Invalid password hash data for MCP administrator '{}'", user.getUsername());
            return false;
        }
    }

    private byte[] hash(String password, byte[] salt, int iterations, String algorithm) {
        if (password == null || salt == null || salt.length == 0 || iterations <= 0
            || algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException("Invalid password hash parameters");
        }
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
            try {
                return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to calculate administrator password hash", ex);
        }
    }

    private boolean constantTimeEquals(byte[] expected, byte[] actual) {
        if (expected == null || actual == null) {
            return false;
        }
        int diff = expected.length ^ actual.length;
        int max = Math.max(expected.length, actual.length);
        for (int i = 0; i < max; i++) {
            byte left = i < expected.length ? expected[i] : 0;
            byte right = i < actual.length ? actual[i] : 0;
            diff |= left ^ right;
        }
        return diff == 0;
    }

    private String encode(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private byte[] decode(String value) {
        return Base64.getDecoder().decode(value.getBytes(StandardCharsets.US_ASCII));
    }
}
