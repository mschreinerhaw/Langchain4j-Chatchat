package com.chatchat.chat.uiartifact;

import java.util.regex.Pattern;

public record ArtifactLocation(String tenantId, String artifactId, String objectKey) {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern SAFE_OBJECT_KEY = Pattern.compile("[A-Za-z0-9._/-]{1,512}");

    public ArtifactLocation {
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
        if (artifactId == null || !SAFE_ID.matcher(artifactId).matches()) {
            throw new IllegalArgumentException("artifactId is invalid");
        }
        if (objectKey == null || !SAFE_OBJECT_KEY.matcher(objectKey).matches()
            || objectKey.startsWith("/") || objectKey.contains("..")) {
            throw new IllegalArgumentException("objectKey is invalid");
        }
    }
}
