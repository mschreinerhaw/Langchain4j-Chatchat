package com.chatchat.agents.runtime.trace;

import java.util.LinkedHashMap;
import java.util.Map;

public record EvidenceTrace(
    String refId,
    String evidenceType,
    String source,
    String toolName,
    Boolean citationUsed,
    String policyStatus,
    String contentPreview,
    Map<String, Object> metadata
) {

    public EvidenceTrace {
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
