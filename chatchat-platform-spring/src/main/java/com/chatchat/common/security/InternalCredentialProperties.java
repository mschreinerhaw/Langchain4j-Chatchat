package com.chatchat.common.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.internal-credential")
public class InternalCredentialProperties {

    private boolean enabled = true;

    private String username = "chatchat_mcp_internal";

    private String secret;

    private String encryptedSecret;

    private String cryptoKey;

    private String cryptoKeyFile;

    public String resolvedUsername() {
        return username == null || username.isBlank() ? "chatchat_mcp_internal" : username.trim();
    }

    public String resolvedSecret() {
        if (!enabled) {
            return "";
        }
        rejectPlaintext(secret, "chatchat.internal-credential.secret");
        return decryptRequired(encryptedSecret, "chatchat.internal-credential.encrypted-secret");
    }

    public String resolveSecret(String encryptedValue, String plainValue) {
        rejectPlaintext(plainValue, "plaintext internal credential");
        if (text(encryptedValue).isBlank()) {
            return "";
        }
        return decryptRequired(encryptedValue, "encrypted internal credential");
    }

    private String resolvedCryptoKey() {
        String value = text(cryptoKey);
        if (!value.isBlank()) {
            return value;
        }
        String path = text(cryptoKeyFile);
        if (path.isBlank()) {
            return "";
        }
        try {
            return Files.readString(Path.of(path)).trim();
        } catch (Exception ex) {
            String fileName = Path.of(path).getFileName().toString();
            try {
                ClassPathResource resource = new ClassPathResource(fileName);
                if (resource.exists()) {
                    try (InputStream inputStream = resource.getInputStream()) {
                        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
                    }
                }
            } catch (Exception classpathEx) {
                ex.addSuppressed(classpathEx);
            }
            throw new IllegalStateException("Failed to read internal credential crypto key file: " + path, ex);
        }
    }

    private String decryptRequired(String encryptedValue, String propertyName) {
        String encrypted = text(encryptedValue);
        if (encrypted.isBlank()) {
            return "";
        }
        if (!InternalSecretCipher.isEncrypted(encrypted)) {
            throw new IllegalStateException(propertyName + " must use ENC(...) format");
        }
        return InternalSecretCipher.decryptIfNecessary(encrypted, resolvedCryptoKey());
    }

    private void rejectPlaintext(String value, String propertyName) {
        if (!text(value).isBlank()) {
            throw new IllegalStateException(propertyName + " is no longer allowed; use encrypted-secret");
        }
    }

    private String text(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
