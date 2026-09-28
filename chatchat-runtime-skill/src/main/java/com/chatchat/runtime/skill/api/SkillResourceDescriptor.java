package com.chatchat.runtime.skill.api;

import java.util.Map;

public record SkillResourceDescriptor(String resourceId, String relativePath, String mediaType,
                                      String digest, Map<String, Object> metadata) {
    public SkillResourceDescriptor {
        if (resourceId == null || resourceId.isBlank()) throw new IllegalArgumentException("resourceId is required");
        resourceId = resourceId.trim();
        relativePath = relativePath == null ? "" : relativePath.trim();
        mediaType = mediaType == null ? "application/octet-stream" : mediaType.trim();
        digest = digest == null ? "" : digest.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
