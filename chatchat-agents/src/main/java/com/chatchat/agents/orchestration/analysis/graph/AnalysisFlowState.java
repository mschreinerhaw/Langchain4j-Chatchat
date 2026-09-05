package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.assessment.EvidenceAugmentationPolicy;
import java.util.Map;
import java.util.Objects;

/** Serializable control decision shared by evidence iteration and final synthesis. No raw data. */
public record AnalysisFlowState(EvidenceAugmentationPolicy.Decision decision, int iteration,
                                boolean loopClosed, String stopReason) {
    public static final String KEY = "analysisFlowState";
    public static final String VERSION = "analysis_flow_state.v1";
    public AnalysisFlowState {
        Objects.requireNonNull(decision, "Evidence decision is required");
        if (iteration < 0) throw new IllegalArgumentException("Negative evidence iteration");
        stopReason = stopReason == null ? "" : stopReason;
        if (loopClosed && decision == EvidenceAugmentationPolicy.Decision.RETRIEVE_MORE)
            throw new IllegalArgumentException("Closed evidence loop cannot request retrieval");
    }
    public Map<String, Object> toMap() {
        return Map.of("schemaVersion", VERSION, "decision", decision.name(), "iteration", iteration,
            "loopClosed", loopClosed, "stopReason", stopReason);
    }
    public static AnalysisFlowState read(Map<String, Object> metadata) {
        Object value = metadata.get(KEY);
        if (value == null) return null; // Legacy runs have no typed flow state.
        if (!(value instanceof Map<?, ?> map) || !VERSION.equals(map.get("schemaVersion")))
            throw new IllegalArgumentException("Unsupported analysis flow state");
        Object iteration = map.get("iteration");
        if (!(iteration instanceof Number number) || number.doubleValue() != number.intValue()
            || !(map.get("loopClosed") instanceof Boolean closed))
            throw new IllegalArgumentException("Malformed analysis flow state");
        return new AnalysisFlowState(EvidenceAugmentationPolicy.Decision.valueOf(String.valueOf(map.get("decision"))),
            number.intValue(), closed, map.get("stopReason") instanceof String reason ? reason : "");
    }
    public AnalysisExecutionGraph.Status admission() {
        return switch (decision) {
            case COMPLETE, ANALYZE_WITH_LIMITATIONS -> AnalysisExecutionGraph.Status.READY;
            case RETRIEVE_MORE -> AnalysisExecutionGraph.Status.NEEDS_MORE_EVIDENCE;
            case BLOCKED_AUTHORIZATION -> AnalysisExecutionGraph.Status.BLOCKED;
            case NO_EVIDENCE, EXACT_RESULT_UNAVAILABLE -> AnalysisExecutionGraph.Status.NO_EVIDENCE;
        };
    }
}
