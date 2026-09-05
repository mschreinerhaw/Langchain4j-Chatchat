package com.chatchat.common.runtime.summary.analysis.governance;

import java.util.LinkedHashMap;
import java.util.Map;

/** Domain-neutral budget and deterministic termination policy for analysis repair requests. */
public final class DataAnalysisRepairExecutionPolicy {

    public static final String SCHEMA_VERSION = "analysis_repair_execution.v1";

    public enum Status { ACTIVE, RESOLVED, TERMINAL }

    public enum TerminalReason {
        NONE,
        RESOLVED,
        NO_NEW_EVIDENCE,
        GAP_UNCHANGED,
        ATTEMPT_BUDGET_EXHAUSTED,
        MODEL_BUDGET_EXHAUSTED,
        TOOL_BUDGET_EXHAUSTED,
        TIME_BUDGET_EXHAUSTED
    }

    public record Budget(int maximumAttempts, int maximumModelCalls,
                         int maximumToolCalls, long maximumElapsedMs) {
        public static final Budget DEFAULT = new Budget(2, 2, 2, 300_000L);

        public Budget {
            maximumAttempts = Math.max(1, maximumAttempts);
            maximumModelCalls = Math.max(0, maximumModelCalls);
            maximumToolCalls = Math.max(0, maximumToolCalls);
            maximumElapsedMs = Math.max(1L, maximumElapsedMs);
        }

        public Map<String, Object> toMap() {
            return Map.of("maximumAttempts", maximumAttempts,
                "maximumModelCalls", maximumModelCalls,
                "maximumToolCalls", maximumToolCalls,
                "maximumElapsedMs", maximumElapsedMs);
        }
    }

    public record State(String requestId, int round, int attemptCount,
                        int modelCallCount, int toolCallCount, long elapsedMs,
                        String gapFingerprint, String evidenceVersion,
                        Status status, TerminalReason terminalReason, Budget budget) {
        public State {
            requestId = text(requestId);
            round = Math.max(1, round);
            attemptCount = Math.max(1, attemptCount);
            modelCallCount = Math.max(0, modelCallCount);
            toolCallCount = Math.max(0, toolCallCount);
            elapsedMs = Math.max(0L, elapsedMs);
            gapFingerprint = text(gapFingerprint);
            evidenceVersion = text(evidenceVersion);
            status = status == null ? Status.TERMINAL : status;
            terminalReason = terminalReason == null ? TerminalReason.NONE : terminalReason;
            budget = budget == null ? Budget.DEFAULT : budget;
        }

        public boolean executable() { return status == Status.ACTIVE; }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", SCHEMA_VERSION);
            value.put("requestId", requestId);
            value.put("round", round);
            value.put("attemptCount", attemptCount);
            value.put("modelCallCount", modelCallCount);
            value.put("toolCallCount", toolCallCount);
            value.put("elapsedMs", elapsedMs);
            value.put("gapFingerprint", gapFingerprint);
            value.put("evidenceVersion", evidenceVersion);
            value.put("status", status.name());
            value.put("terminalReason", terminalReason.name());
            value.put("executable", executable());
            value.put("budget", budget.toMap());
            return Map.copyOf(value);
        }
    }

    public State evaluate(String requestId, String gapFingerprint, String evidenceVersion,
                          int modelCalls, int toolCalls, long elapsedMs,
                          boolean resolved, State previous, Budget budget) {
        if (previous != null && previous.status() != Status.ACTIVE) return previous;
        Budget limit = budget == null ? Budget.DEFAULT : budget;
        int attempts = previous == null ? 1 : previous.attemptCount() + 1;
        int round = previous == null ? 1 : previous.round() + 1;
        int totalModelCalls = Math.max(0, modelCalls)
            + (previous == null ? 0 : previous.modelCallCount());
        int totalToolCalls = Math.max(0, toolCalls)
            + (previous == null ? 0 : previous.toolCallCount());
        long totalElapsedMs = Math.max(0L, elapsedMs)
            + (previous == null ? 0L : previous.elapsedMs());
        TerminalReason reason = TerminalReason.NONE;
        Status status = Status.ACTIVE;
        if (resolved) {
            status = Status.RESOLVED;
            reason = TerminalReason.RESOLVED;
        } else if (attempts > limit.maximumAttempts()) {
            status = Status.TERMINAL;
            reason = TerminalReason.ATTEMPT_BUDGET_EXHAUSTED;
        } else if (totalModelCalls > limit.maximumModelCalls()) {
            status = Status.TERMINAL;
            reason = TerminalReason.MODEL_BUDGET_EXHAUSTED;
        } else if (totalToolCalls > limit.maximumToolCalls()) {
            status = Status.TERMINAL;
            reason = TerminalReason.TOOL_BUDGET_EXHAUSTED;
        } else if (totalElapsedMs > limit.maximumElapsedMs()) {
            status = Status.TERMINAL;
            reason = TerminalReason.TIME_BUDGET_EXHAUSTED;
        } else if (previous != null && previous.gapFingerprint().equals(text(gapFingerprint))) {
            status = Status.TERMINAL;
            reason = previous.evidenceVersion().equals(text(evidenceVersion))
                ? TerminalReason.NO_NEW_EVIDENCE : TerminalReason.GAP_UNCHANGED;
        }
        return new State(requestId, round, attempts, totalModelCalls, totalToolCalls, totalElapsedMs,
            gapFingerprint, evidenceVersion, status, reason, limit);
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}
