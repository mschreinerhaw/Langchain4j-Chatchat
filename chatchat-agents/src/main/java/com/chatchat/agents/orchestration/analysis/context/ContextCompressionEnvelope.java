package com.chatchat.agents.orchestration.analysis.context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Model-context-only representation of compressed evidence. Raw evidence remains
 * in the Runtime step record and tool trace.
 */
public record ContextCompressionEnvelope(
    String evidenceId,
    Object content,
    String strategy,
    String lossLevel,
    ContextTokenEstimator.Size before,
    ContextTokenEstimator.Size after,
    int availableEvidenceTokens
) {
    public Map<String, Object> asMap() {
        Map<String, Object> contextPayload = new LinkedHashMap<>();
        contextPayload.put("mode", "compressed");
        contextPayload.put("content", content);

        Map<String, Object> compression = new LinkedHashMap<>();
        compression.put("trigger", "CONTEXT_TOKEN_BUDGET");
        compression.put("strategy", strategy);
        compression.put("beforeChars", before.chars());
        compression.put("beforeTokens", before.tokens());
        compression.put("afterChars", after.chars());
        compression.put("afterTokens", after.tokens());
        compression.put("ratio", before.tokens() == 0
            ? 1.0
            : Math.round((after.tokens() / (double) before.tokens()) * 1_000_000d) / 1_000_000d);
        compression.put("lossLevel", lossLevel);
        compression.put("availableEvidenceTokens", availableEvidenceTokens);
        compression.put("rawEvidenceUnchanged", true);
        compression.put("rawEvidenceLocation", "runtime_step_record_and_tool_trace");

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", "context_compression_envelope.v1");
        envelope.put("evidenceId", evidenceId);
        envelope.put("contextPayload", contextPayload);
        envelope.put("compression", compression);
        return Map.copyOf(envelope);
    }
}
