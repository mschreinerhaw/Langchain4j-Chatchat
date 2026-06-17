package com.chatchat.agents.evidence;

import java.util.LinkedHashMap;
import java.util.Map;

public record EvidenceChunk(
    EvidenceType evidenceType,
    String contractVersion,
    EvidenceSource source,
    String content,
    Double score,
    Map<String, Object> citation,
    EvidenceGovernance governance,
    Map<String, Object> trace
) {

    public static final String CONTRACT_VERSION = "evidence_v1";

    public EvidenceChunk {
        contractVersion = contractVersion == null || contractVersion.isBlank() ? CONTRACT_VERSION : contractVersion;
        content = content == null ? "" : content;
        citation = citation == null ? Map.of() : new LinkedHashMap<>(citation);
        trace = trace == null ? Map.of() : new LinkedHashMap<>(trace);
    }
}
