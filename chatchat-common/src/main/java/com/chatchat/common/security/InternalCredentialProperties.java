package com.chatchat.common.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
        String encrypted = text(encryptedSecret);
        if (!encrypted.isBlank()) {
            return InternalSecretCipher.decryptIfNecessary(encrypted, resolvedCryptoKey());
        }
        return InternalSecretCipher.decryptIfNecessary(text(secret), resolvedCryptoKey());
    }

    public String resolveSecret(String encryptedValue, String plainValue) {
        String encrypted = text(encryptedValue);
        if (!encrypted.isBlank()) {
            return InternalSecretCipher.decryptIfNecessary(encrypted, resolvedCryptoKey());
        }
        return InternalSecretCipher.decryptIfNecessary(text(plainValue), resolvedCryptoKey());
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
            throw new IllegalStateException("Failed to read internal credential crypto key file: " + path, ex);
        }
    }

    private String text(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
