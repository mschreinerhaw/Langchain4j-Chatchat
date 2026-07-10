package com.chatchat.common.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class InternalSecretCipher {

    public static final String WRAPPER_PREFIX = "ENC(";
    public static final String WRAPPER_SUFFIX = ")";

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private InternalSecretCipher() {
    }

    public static boolean isEncrypted(String value) {
        String text = value == null ? "" : value.trim();
        return text.startsWith(WRAPPER_PREFIX) && text.endsWith(WRAPPER_SUFFIX);
    }

    public static String decryptIfNecessary(String value, String keyMaterial) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String text = value.trim();
        if (!isEncrypted(text)) {
            return text;
        }
        if (keyMaterial == null || keyMaterial.isBlank()) {
            throw new IllegalStateException("Encrypted internal credential requires chatchat.internal-credential.crypto-key");
        }
        String payload = text.substring(WRAPPER_PREFIX.length(), text.length() - WRAPPER_SUFFIX.length());
        String[] parts = payload.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Encrypted internal credential must use ENC(base64Iv:base64CipherText)");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] cipherText = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key(keyMaterial), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to decrypt internal credential: " + ex.getMessage(), ex);
        }
    }

    public static String encrypt(String value, String keyMaterial) {
        if (value == null) {
            value = "";
        }
        if (keyMaterial == null || keyMaterial.isBlank()) {
            throw new IllegalArgumentException("crypto key is required");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key(keyMaterial), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return WRAPPER_PREFIX
                + Base64.getEncoder().encodeToString(iv)
                + ":"
                + Base64.getEncoder().encodeToString(cipherText)
                + WRAPPER_SUFFIX;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to encrypt internal credential: " + ex.getMessage(), ex);
        }
    }

    private static SecretKeySpec key(String keyMaterial) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(keyMaterial.trim().getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
