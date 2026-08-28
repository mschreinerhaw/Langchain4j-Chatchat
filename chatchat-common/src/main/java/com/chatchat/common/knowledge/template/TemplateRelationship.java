package com.chatchat.common.knowledge.template;

import java.util.Map;

/** Expected business relationship between two admitted template datasets. */
public record TemplateRelationship(
    String fromTemplateId,
    String toTemplateId,
    String relationType,
    String description
) {
    public TemplateRelationship {
        if (fromTemplateId == null || fromTemplateId.isBlank()) {
            throw new IllegalArgumentException("fromTemplateId is required");
        }
        if (toTemplateId == null || toTemplateId.isBlank()) {
            throw new IllegalArgumentException("toTemplateId is required");
        }
        fromTemplateId = fromTemplateId.trim();
        toTemplateId = toTemplateId.trim();
        relationType = clean(relationType, "BUSINESS_RELATED");
        description = clean(description, "");
    }

    public Map<String, Object> toMap() {
        return Map.of(
            "fromTemplateId", fromTemplateId,
            "toTemplateId", toTemplateId,
            "relationType", relationType,
            "description", description
        );
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
