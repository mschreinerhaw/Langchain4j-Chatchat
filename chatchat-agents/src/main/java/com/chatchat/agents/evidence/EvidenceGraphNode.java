package com.chatchat.agents.evidence;

import java.util.LinkedHashMap;
import java.util.Map;

public record EvidenceGraphNode(
    String id,
    EvidenceGraphNodeType type,
    String sourceRef,
    String rawContent,
    String normalizedContent,
    double confidence,
    boolean validated,
    Map<String, Object> metadata
) {

    public EvidenceGraphNode(String id,
                             EvidenceGraphNodeType type,
                             String sourceRef,
                             String rawContent,
                             String normalizedContent,
                             double confidence,
                             Map<String, Object> metadata) {
        this(id, type, sourceRef, rawContent, normalizedContent, confidence, true, metadata);
    }

    public EvidenceGraphNode {
        rawContent = rawContent == null ? "" : rawContent;
        normalizedContent = normalizedContent == null ? "" : normalizedContent;
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
