package com.chatchat.chat.task.core;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical lifecycle for one durable Agent execution.
 *
 * <p>Legacy task statuses remain accepted at the API/storage boundary, but every status update is
 * normalized through this model so Task and Runtime no longer invent independent lifecycle
 * semantics.</p>
 */
public enum AgentExecutionState {
    SUBMITTED,
    AUTHORIZING,
    PLANNING,
    PLAN_VALIDATING,
    WAITING_APPROVAL,
    EXECUTING,
    EVALUATING,
    REPAIRING,
    DELIVERING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED,
    REJECTED;

    private static final Set<AgentExecutionState> TERMINAL = EnumSet.of(
        SUCCEEDED, PARTIAL, FAILED, CANCELLED, REJECTED
    );

    private static final Map<String, AgentExecutionState> LEGACY = Map.ofEntries(
        Map.entry("SUBMITTING", SUBMITTED),
        Map.entry("PENDING", SUBMITTED),
        Map.entry("RETRY_WAIT", SUBMITTED),
        Map.entry("CLAIMED", AUTHORIZING),
        Map.entry("RUNNING", EXECUTING),
        Map.entry("WAIT_MODEL", PLANNING),
        Map.entry("WAIT_TOOL", EXECUTING),
        Map.entry("WAIT_CONFIRMATION", WAITING_APPROVAL),
        Map.entry("WAITING_CONFIRM", WAITING_APPROVAL),
        Map.entry("SUCCESS", SUCCEEDED),
        Map.entry("COMPLETED", SUCCEEDED),
        Map.entry("PARTIAL_SUCCESS", PARTIAL),
        Map.entry("EMPTY", PARTIAL),
        Map.entry("NO_PRESENTABLE_RESULT", PARTIAL),
        Map.entry("COMPLETED_WITH_PARTIAL_EVIDENCE", PARTIAL),
        Map.entry("TIME_BUDGET_EXHAUSTED", FAILED),
        Map.entry("MODEL_BUDGET_EXHAUSTED", FAILED),
        Map.entry("TIMEOUT_CANCELLED", CANCELLED),
        Map.entry("KILLED", CANCELLED),
        Map.entry("DLQ", FAILED)
    );

    /** Resolves canonical and legacy wire values without silently accepting unknown states. */
    public static AgentExecutionState fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Agent execution state is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        AgentExecutionState legacy = LEGACY.get(normalized);
        if (legacy != null) {
            return legacy;
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown Agent execution state: " + value, ex);
        }
    }

    public boolean terminal() {
        return TERMINAL.contains(this);
    }
}
