package com.chatchat.agents.runtime.plan.diagnostic;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Authoritative lifecycle, outcome, failure and recovery vocabulary for diagnostic runs.
 *
 * <p>Lifecycle state is deliberately separate from the evidence outcome and failure reason.
 * This prevents a partial result, a time budget stop and a repair transition from competing for
 * one overloaded status field.</p>
 */
public final class DiagnosticRunStateMachine {

    private static final Map<State, Set<State>> TRANSITIONS = transitions();

    private DiagnosticRunStateMachine() {
    }

    public static Snapshot resolve(String runtimeStatus,
                                   boolean runtimeSuccess,
                                   int completedChecks,
                                   int failedChecks,
                                   int missingChecks,
                                   boolean hasRemainingSteps,
                                   int remainingRetries) {
        FailureCode failureCode = FailureCode.from(runtimeStatus);
        Outcome outcome = outcome(completedChecks, failedChecks, missingChecks);
        State state;
        RecoveryAction recoveryAction = null;
        if (runtimeSuccess) {
            state = State.COMPLETED;
        } else if (failureCode != null && failureCode.requiresPlanRepair()) {
            state = State.REPAIRING;
            recoveryAction = RecoveryAction.REWRITE_PLAN;
        } else if (failureCode != null && failureCode.budgetExhausted()) {
            state = State.FAILED;
        } else if ((outcome == Outcome.PARTIAL_SUCCESS || hasRemainingSteps) && remainingRetries > 0) {
            state = State.REPAIRING;
            recoveryAction = RecoveryAction.RETRY_MISSING_EVIDENCE;
        } else {
            state = State.FAILED;
        }
        return new Snapshot(state, outcome, failureCode, recoveryAction);
    }

    public static Snapshot resolveEvidenceOnly(int completedChecks,
                                               int failedChecks,
                                               int missingChecks,
                                               boolean hasRemainingSteps,
                                               int remainingRetries) {
        Outcome outcome = outcome(completedChecks, failedChecks, missingChecks);
        if ((hasRemainingSteps || outcome != Outcome.SUCCESS) && remainingRetries > 0) {
            return new Snapshot(State.REPAIRING, outcome, null, RecoveryAction.RETRY_MISSING_EVIDENCE);
        }
        State state = !hasRemainingSteps && completedChecks > 0 ? State.COMPLETED : State.FAILED;
        return new Snapshot(state, outcome, null, null);
    }

    public static boolean canTransition(State from, State to) {
        if (from == null || to == null) {
            return false;
        }
        return from == to || TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    private static Outcome outcome(int completedChecks, int failedChecks, int missingChecks) {
        if (completedChecks > 0 && failedChecks == 0 && missingChecks == 0) {
            return Outcome.SUCCESS;
        }
        if (completedChecks > 0) {
            return Outcome.PARTIAL_SUCCESS;
        }
        return Outcome.INSUFFICIENT_EVIDENCE;
    }

    private static Map<State, Set<State>> transitions() {
        Map<State, Set<State>> values = new EnumMap<>(State.class);
        values.put(State.INIT, EnumSet.of(State.PLANNING, State.FAILED));
        values.put(State.PLANNING, EnumSet.of(State.VALIDATING, State.REPAIRING, State.FAILED));
        values.put(State.VALIDATING, EnumSet.of(State.EXECUTING, State.REPAIRING, State.FAILED));
        values.put(State.EXECUTING, EnumSet.of(State.REPAIRING, State.COMPLETED, State.FAILED));
        values.put(State.REPAIRING, EnumSet.of(State.PLANNING, State.VALIDATING, State.EXECUTING,
            State.COMPLETED, State.FAILED));
        values.put(State.COMPLETED, Set.of());
        values.put(State.FAILED, EnumSet.of(State.REPAIRING));
        return Map.copyOf(values);
    }

    public enum State {
        INIT,
        PLANNING,
        VALIDATING,
        EXECUTING,
        REPAIRING,
        COMPLETED,
        FAILED;

        @JsonValue
        public String wireValue() {
            return name();
        }
    }

    public enum Outcome {
        SUCCESS,
        PARTIAL_SUCCESS,
        INSUFFICIENT_EVIDENCE;

        @JsonValue
        public String wireValue() {
            return name();
        }
    }

    public enum AssessmentStatus {
        NOT_AVAILABLE,
        PRELIMINARY_AVAILABLE,
        COMPLETE;

        @JsonValue
        public String wireValue() {
            return name();
        }
    }

    public enum FailureCode {
        STEP_OUTPUT_CONTRACT_FAILED,
        EDGE_CONTRACT_FAILED,
        TIME_BUDGET_EXHAUSTED,
        MODEL_BUDGET_EXHAUSTED,
        TOOL_FAILED,
        DAG_REWRITE_REQUESTED,
        DAG_ABORTED,
        VALIDATION_FAILED,
        UNKNOWN_RUNTIME_FAILURE;

        @JsonValue
        public String wireValue() {
            return name();
        }

        public String message(String detail) {
            return wireValue() + (detail == null || detail.isBlank() ? "" : ": " + detail);
        }

        public boolean requiresPlanRepair() {
            return this == STEP_OUTPUT_CONTRACT_FAILED
                || this == EDGE_CONTRACT_FAILED
                || this == DAG_REWRITE_REQUESTED
                || this == VALIDATION_FAILED;
        }

        public boolean budgetExhausted() {
            return this == TIME_BUDGET_EXHAUSTED || this == MODEL_BUDGET_EXHAUSTED;
        }

        public static FailureCode from(String status) {
            if (status == null || status.isBlank()
                || "SUCCESS".equalsIgnoreCase(status)
                || "COMPLETED".equalsIgnoreCase(status)
                || "PARTIAL_SUCCESS".equalsIgnoreCase(status)) {
                return null;
            }
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if ("STEP_FAILED".equals(normalized) || "FAILED".equals(normalized)) {
                return TOOL_FAILED;
            }
            if ("INVALID_PLAN".equals(normalized) || "DAG_DECISION_INVALID".equals(normalized)) {
                return VALIDATION_FAILED;
            }
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return UNKNOWN_RUNTIME_FAILURE;
            }
        }
    }

    public enum RecoveryAction {
        REWRITE_PLAN("rewrite_plan"),
        RETRY_MISSING_EVIDENCE("retry_missing_evidence"),
        RETRY_FAILED_CHECKS("retry_failed_checks");

        private final String wireValue;

        RecoveryAction(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }
    }

    public record Snapshot(
        State state,
        Outcome outcome,
        FailureCode failureCode,
        RecoveryAction recoveryAction
    ) {
    }
}
