package com.chatchat.common.runtime.analysis.execution;

import java.util.Map;

/** Model-authored intent, shared by the Harness and its existing execution consumers. */
public final class ModelAnalysisIntent {
    public static final String VERSION = "model_native_analysis.v2";
    public enum Action { CONTINUE, WAIT, COMPLETE, PUBLISH, PARTIAL_COMPLETE }
    private ModelAnalysisIntent() {}
    public static boolean active(Map<String, Object> metadata) {
        return metadata != null && VERSION.equals(metadata.get("modelAnalysisProtocol"));
    }
    public static Action action(Map<String, Object> metadata) {
        Object raw = metadata.get("modelDecision");
        if (!(raw instanceof Map<?, ?> decision)) throw new IllegalArgumentException("Model decision is required");
        return Action.valueOf(String.valueOf(decision.get("action")));
    }
    public static boolean publishRequested(Map<String, Object> metadata) {
        return active(metadata) && finalDeclared(metadata)
            && "REQUESTED".equals(metadata.get("publicationState"));
    }
    /** Content semantics are model declarations. PUBLISH is the explicit legacy session-final declaration. */
    public static boolean finalDeclared(Map<String, Object> metadata) {
        if (!active(metadata)) return false;
        if (metadata.get("modelOutput") instanceof Map<?,?> output)
            return "MODEL".equals(output.get("author")) && "FINAL".equals(output.get("type"));
        return metadata.get("modelDecision") instanceof Map<?,?> && action(metadata) == Action.PUBLISH;
    }
    public static String retainedContent(Map<String, Object> metadata) {
        return String.valueOf(metadata.getOrDefault("modelAnalysisOutput",
            metadata.getOrDefault("modelNativeReportDraft", "")));
    }
    public static boolean continuing(Map<String, Object> metadata) {
        return active(metadata) && !finalDeclared(metadata) && action(metadata) == Action.CONTINUE
            && !"RESOURCE_BUDGET_EXHAUSTED".equals(metadata.get("executionStopReason"));
    }
}
