package com.chatchat.runtime.skill.api.skill;

import java.util.Map;

/** L1 discovery projection. Full instructions and resources are deliberately excluded. */
public record SkillDescriptor(String id, String version, String name, String description,
                              String category, String sourceType, String sourceId,
                              String sourceUri, String sourceDigest, double score,
                              Map<String, Object> metadata) {
    public SkillDescriptor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("skill id is required");
        id = id.trim();
        version = clean(version);
        name = clean(name);
        description = clean(description);
        category = clean(category);
        sourceType = clean(sourceType);
        sourceId = clean(sourceId);
        sourceUri = clean(sourceUri);
        sourceDigest = clean(sourceDigest);
        score = Math.max(0D, Math.min(1D, score));
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
