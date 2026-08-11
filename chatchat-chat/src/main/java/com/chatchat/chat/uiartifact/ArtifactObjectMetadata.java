package com.chatchat.chat.uiartifact;

import java.util.Map;

public record ArtifactObjectMetadata(String contentType,
                                     long contentLength,
                                     String sha256,
                                     Map<String, String> attributes) {

    public ArtifactObjectMetadata {
        contentType = contentType == null || contentType.isBlank()
            ? "application/octet-stream" : contentType.trim();
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength cannot be negative");
        }
        sha256 = sha256 == null ? "" : sha256.trim();
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
