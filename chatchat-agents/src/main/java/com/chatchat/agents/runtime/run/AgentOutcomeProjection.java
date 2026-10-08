package com.chatchat.agents.runtime.run;

import java.util.LinkedHashMap;
import java.util.Map;

/** Projects one authoritative runtime outcome into run, answer, workflow and UI states. */
public final class AgentOutcomeProjection {

    public static final String CONTRACT_VERSION = "ui_response_v2";

    public Outcome project(Map<String, Object> metadata, String answer) {
        Map<String, Object> values = metadata == null ? Map.of() : metadata;
        boolean confirmationRequired = booleanValue(values.get("confirmationRequired"));
        boolean fatalExecutionBlocked = booleanValue(values.get("fatalExecutionBlocked"));
        boolean mandatoryBlocked = booleanValue(values.get("mandatoryWorkflowBlocked"))
            || fatalExecutionBlocked;
        String stopReason = text(values.get("stopReason")).toUpperCase();
        boolean hasAnswer = answer != null && !answer.isBlank();

        if (confirmationRequired) {
            return new Outcome("WAITING_CONFIRMATION", "PENDING", "WAITING_CONFIRMATION",
                "WAITING_CONFIRMATION", CONTRACT_VERSION);
        }
        if (booleanValue(values.get("planningAdmissionFailed"))) {
            return new Outcome("FAILED", "FAILED", "PLANNING_FAILED", "FAILED", CONTRACT_VERSION);
        }
        if (stopReason.contains("CANCEL")) {
            return new Outcome("CANCELLED", hasAnswer ? "PARTIAL" : "EMPTY", "CANCELLED",
                "CANCELLED", CONTRACT_VERSION);
        }
        if (stopReason.contains("TIME_BUDGET") || stopReason.contains("TIMED_OUT")) {
            return new Outcome("COMPLETED", hasAnswer ? "PARTIAL" : "EMPTY", "BUDGET_EXHAUSTED",
                "TIME_BUDGET_EXHAUSTED", CONTRACT_VERSION);
        }
        if (booleanValue(values.get("rawAnalysisOutputWithheld"))
            || booleanValue(values.get("analysisSynthesisBlocked"))) {
            // Retrieval may have completed, but an analysis request is not successful unless a
            // publishable synthesis exists. The explanatory message is an error report, not a
            // substitute business answer.
            return new Outcome("FAILED", "FAILED", "ANALYSIS_SYNTHESIS_FAILED",
                "FAILED", CONTRACT_VERSION);
        }
        if (mandatoryBlocked) {
            // An unscheduled dependent node is not active work. Once the owner
            // has exhausted recovery and declared a fatal block, close the run.
            if (fatalExecutionBlocked) {
                return new Outcome("FAILED", "FAILED", "FAILED_REQUIRED_EVIDENCE",
                    "FAILED", CONTRACT_VERSION);
            }
            boolean mandatoryPending = booleanValue(values.get("mandatoryWorkflowPending"))
                && !booleanValue(values.get("mandatoryWorkflowTerminal"));
            if (mandatoryPending) {
                return new Outcome("RUNNING", hasAnswer ? "PARTIAL" : "PENDING",
                    "PENDING_REQUIRED_EVIDENCE", "RUNNING", CONTRACT_VERSION);
            }
            return new Outcome("COMPLETED", hasAnswer ? "PARTIAL" : "EMPTY",
                "FAILED_REQUIRED_EVIDENCE", hasAnswer ? "PARTIAL_SUCCESS" : "FAILED",
                CONTRACT_VERSION);
        }
        if (booleanValue(values.get("runtimeExecutionFailed")) && !hasAnswer) {
            return new Outcome("FAILED", "FAILED", "FAILED", "FAILED", CONTRACT_VERSION);
        }
        if ("FAIL".equalsIgnoreCase(text(values.get("claimCoverageStatus")))
            || "false".equalsIgnoreCase(text(values.get("answerClaimAuditPassed")))) {
            return new Outcome("COMPLETED", hasAnswer ? "PARTIAL" : "EMPTY",
                "ANSWER_EVIDENCE_AUDIT_FAILED",
                hasAnswer ? "PARTIAL_SUCCESS" : "NO_PRESENTABLE_RESULT", CONTRACT_VERSION);
        }
        return new Outcome("COMPLETED", hasAnswer ? "SUCCESS" : "EMPTY", "COMPLETED",
            hasAnswer ? "SUCCESS" : "NO_PRESENTABLE_RESULT", CONTRACT_VERSION);
    }

    public Map<String, Object> enrich(Map<String, Object> metadata, String answer) {
        Map<String, Object> enriched = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        Outcome outcome = project(enriched, answer);
        enriched.put("runStatus", outcome.runStatus());
        enriched.put("answerStatus", outcome.answerStatus());
        enriched.put("workflowStatus", outcome.workflowStatus());
        enriched.put("publicStatus", outcome.publicStatus());
        enriched.put("contractVersion", outcome.contractVersion());
        enriched.put("outcomeProjection", outcome.asMap());
        return enriched;
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool
            ? bool
            : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record Outcome(String runStatus,
                          String answerStatus,
                          String workflowStatus,
                          String publicStatus,
                          String contractVersion) {
        public Map<String, Object> asMap() {
            return Map.of(
                "runStatus", runStatus,
                "answerStatus", answerStatus,
                "workflowStatus", workflowStatus,
                "publicStatus", publicStatus,
                "contractVersion", contractVersion
            );
        }
    }
}
