package com.chatchat.common.knowledge;

import java.util.List;
import java.util.Map;

/** Source-neutral request for ingestion-time extraction. Raw bytes remain outside common. */
public record KnowledgeExtractionRequest(
    String sourceId,
    String domain,
    String contentReference,
    String normalizedText,
    List<KnowledgeType> allowedTypes,
    Map<String, Object> metadata
) {
    public KnowledgeExtractionRequest {
        if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId is required");
        if (contentReference == null || contentReference.isBlank()) throw new IllegalArgumentException("contentReference is required");
        if (normalizedText == null || normalizedText.isBlank()) throw new IllegalArgumentException("normalizedText is required");
        normalizedText = normalizedText.trim();
        allowedTypes = allowedTypes == null || allowedTypes.isEmpty()
            ? List.of(KnowledgeType.values()) : List.copyOf(allowedTypes);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
