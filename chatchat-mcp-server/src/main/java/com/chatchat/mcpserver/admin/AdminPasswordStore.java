package com.chatchat.mcpserver.admin;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPasswordStore {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;

    private final AdminAuthProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile PasswordHash passwordHash;

    @PostConstruct
    public void load() {
        Path path = storePath();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (InputStream input = Files.newInputStream(path)) {
            Properties file = new Properties();
            file.load(input);
            passwordHash = new PasswordHash(
                Integer.parseInt(file.getProperty("iterations", String.valueOf(ITERATIONS))),
                decode(file.getProperty("salt", "")),
                decode(file.getProperty("hash", ""))
            );
            log.info("MCP admin password override loaded from {}", path);
        } catch (Exception ex) {
            log.warn("Failed to load MCP admin password override from {}", path, ex);
        }
    }

    public boolean hasOverride() {
        return passwordHash != null;
    }

    public boolean matches(String rawPassword, String fallbackPlainPassword) {
        if (rawPassword == null) {
            return false;
        }
        PasswordHash current = passwordHash;
        if (current != null && current.valid()) {
            return constantTimeEquals(current.hash(), hash(rawPassword, current.salt(), current.iterations()));
        }
        return constantTimeEquals(fallbackPlainPassword, rawPassword);
    }

    public synchronized void save(String newPassword) {
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        byte[] hash = hash(newPassword, salt, ITERATIONS);
        PasswordHash next = new PasswordHash(ITERATIONS, salt, hash);
        Path path = storePath();
        try {
            Files.createDirectories(path.getParent());
            Properties file = new Properties();
            file.setProperty("version", "1");
            file.setProperty("algorithm", ALGORITHM);
            file.setProperty("iterations", String.valueOf(ITERATIONS));
            file.setProperty("salt", encode(salt));
            file.setProperty("hash", encode(hash));
            file.setProperty("updatedAt", Instant.now().toString());
            try (OutputStream output = Files.newOutputStream(path)) {
                file.store(output, "ChatChat MCP admin password override");
            }
            passwordHash = next;
            log.info("MCP admin password override saved to {}", path);
        } catch (IOException ex) {
            throw new IllegalStateException("保存管理员密码失败：" + ex.getMessage(), ex);
        }
    }

    private Path storePath() {
        return Path.of(text(properties.getPasswordStorePath(), "./data/admin-password.properties"))
            .toAbsolutePath()
            .normalize();
    }

    private byte[] hash(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("管理员密码哈希计算失败：" + ex.getMessage(), ex);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return constantTimeEquals(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private boolean constantTimeEquals(byte[] expected, byte[] actual) {
        if (expected == null || actual == null) {
            return false;
        }
        int diff = expected.length ^ actual.length;
        int max = Math.max(expected.length, actual.length);
        for (int i = 0; i < max; i++) {
            byte a = i < expected.length ? expected[i] : 0;
            byte b = i < actual.length ? actual[i] : 0;
            diff |= a ^ b;
        }
        return diff == 0;
    }

    private String encode(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private byte[] decode(String value) {
        return Base64.getDecoder().decode(text(value, ""));
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record PasswordHash(int iterations, byte[] salt, byte[] hash) {
        private boolean valid() {
            return iterations > 0 && salt != null && salt.length > 0 && hash != null && hash.length > 0;
        }
    }
}
