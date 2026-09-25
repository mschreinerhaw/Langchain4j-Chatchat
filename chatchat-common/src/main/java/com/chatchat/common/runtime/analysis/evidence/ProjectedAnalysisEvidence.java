package com.chatchat.common.runtime.analysis.evidence;

import com.chatchat.common.runtime.analysis.model.AnalysisCapability;

import java.util.Map;

/** Explicitly approved, minimal evidence payload for remote agent compute. */
public record ProjectedAnalysisEvidence(String evidenceId, AnalysisCapability capability,
                                        String content, Map<String, Object> attributes) implements AnalysisEvidence {
    public ProjectedAnalysisEvidence {
        if (evidenceId == null || evidenceId.isBlank()) throw new IllegalArgumentException("evidenceId is required");
        if (capability == null) throw new IllegalArgumentException("capability is required");
        content = content == null ? "" : content;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
