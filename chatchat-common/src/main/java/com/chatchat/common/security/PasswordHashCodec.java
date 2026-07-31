package com.chatchat.common.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encodes user passwords as self-contained, salted PBKDF2 hashes.
 */
public final class PasswordHashCodec {

    private static final String PREFIX = "{PBKDF2-SHA256}";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PasswordHashCodec() {
    }

    public static String encode(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("password must not be empty");
        }
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = hash(rawPassword, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$"
            + Base64.getEncoder().encodeToString(salt) + "$"
            + Base64.getEncoder().encodeToString(hash);
    }

    public static boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || !isEncoded(encodedPassword)) {
            return false;
        }
        try {
            String[] parts = encodedPassword.split("\\$", -1);
            if (parts.length != 4 || !PREFIX.equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = hash(rawPassword, salt, iterations);
            return constantTimeEquals(expected, actual);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public static boolean isEncoded(String value) {
        return value != null && value.startsWith(PREFIX + "$");
    }

    private static byte[] hash(String password, byte[] salt, int iterations) {
        if (salt == null || salt.length == 0 || iterations <= 0) {
            throw new IllegalArgumentException("invalid password hash parameters");
        }
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("failed to calculate password hash", ex);
        } finally {
            spec.clearPassword();
        }
    }

    private static boolean constantTimeEquals(byte[] expected, byte[] actual) {
        if (expected == null || actual == null) {
            return false;
        }
        int diff = expected.length ^ actual.length;
        int max = Math.max(expected.length, actual.length);
        for (int index = 0; index < max; index++) {
            byte left = index < expected.length ? expected[index] : 0;
            byte right = index < actual.length ? actual[index] : 0;
            diff |= left ^ right;
        }
        return diff == 0;
    }
}
