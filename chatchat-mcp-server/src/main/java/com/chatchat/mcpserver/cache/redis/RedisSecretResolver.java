package com.chatchat.mcpserver.cache.redis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class RedisSecretResolver {

    private static final Path SECRET_ROOT = Path.of("/run/secrets").toAbsolutePath().normalize();

    private RedisSecretResolver() {
    }

    static String resolve(String value) {
        String reference = value == null ? "" : value.trim();
        if (!reference.startsWith("file:")) return reference;

        Path path = Path.of(reference.substring("file:".length())).toAbsolutePath().normalize();
        if (!path.startsWith(SECRET_ROOT) || path.equals(SECRET_ROOT)) {
            throw new IllegalArgumentException("Redis secret file must be located under /run/secrets");
        }
        try {
            String secret = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (secret.isEmpty()) throw new IllegalStateException("Redis secret file is empty");
            return secret;
        } catch (IOException ex) {
            throw new IllegalStateException("Redis secret file cannot be read", ex);
        }
    }
}
