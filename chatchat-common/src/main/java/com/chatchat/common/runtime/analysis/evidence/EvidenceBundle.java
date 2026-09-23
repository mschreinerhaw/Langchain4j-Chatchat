package com.chatchat.common.runtime.analysis.evidence;

import java.util.List;
import java.util.Map;

public record EvidenceBundle(String schemaVersion, List<AnalysisEvidence> evidence,
                             List<String> limitations, Map<String, Object> metadata) {
    public static final String SCHEMA_VERSION = "analysis_evidence_bundle.v1";
    public EvidenceBundle {
        schemaVersion = SCHEMA_VERSION;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
    public static EvidenceBundle empty(String limitation) {
        return new EvidenceBundle(SCHEMA_VERSION, List.of(),
            limitation == null || limitation.isBlank() ? List.of() : List.of(limitation), Map.of());
    }
}
