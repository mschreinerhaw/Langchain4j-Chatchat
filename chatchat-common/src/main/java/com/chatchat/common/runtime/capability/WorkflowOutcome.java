package com.chatchat.common.runtime.capability;

import java.util.List;

/** Canonical evidence-aware outcome; answer text is deliberately not an input. */
public record WorkflowOutcome(Type type, String reason, List<String> missingRequiredCapabilities,
                              List<String> missingOptionalCapabilities, boolean usableResult) {
    public static final String METADATA_KEY = "workflowOutcome";
    public enum Type { PLAN_READY, DATA_REQUIRED, DOCUMENT_REQUIRED, ASSET_CONTEXT_REQUIRED, SKILL_REQUIRED,
        ACTION_REQUIRED, EVIDENCE_REQUIRED, INPUT_REQUIRED, CONFIRMATION_REQUIRED,
        PARTIAL_RESULT, READY_TO_ANSWER, INSUFFICIENT_EVIDENCE, NO_EXECUTABLE_PLAN, FAILED,
        CANCELLED, TIME_BUDGET_EXHAUSTED, MODEL_BUDGET_EXHAUSTED }
    public WorkflowOutcome {
        if (type == null) throw new IllegalArgumentException("Workflow outcome type is required");
        missingRequiredCapabilities = List.copyOf(missingRequiredCapabilities);
        missingOptionalCapabilities = List.copyOf(missingOptionalCapabilities);
        reason = reason == null ? "" : reason;
    }
    public String publicStatus() {
        if (type == Type.FAILED) return "FAILED";
        if (type == Type.CANCELLED) return "CANCELLED";
        if (type == Type.TIME_BUDGET_EXHAUSTED) return "TIME_BUDGET_EXHAUSTED";
        if (type == Type.MODEL_BUDGET_EXHAUSTED) return "MODEL_BUDGET_EXHAUSTED";
        if (type == Type.CONFIRMATION_REQUIRED) return "WAITING_CONFIRMATION";
        if (type == Type.READY_TO_ANSWER && usableResult && missingRequiredCapabilities.isEmpty()) return "SUCCESS";
        return usableResult ? "PARTIAL_SUCCESS" : "NO_PRESENTABLE_RESULT";
    }
}
