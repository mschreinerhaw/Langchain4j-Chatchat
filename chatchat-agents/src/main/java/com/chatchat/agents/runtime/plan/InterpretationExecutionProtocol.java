package com.chatchat.agents.runtime.plan;

import java.util.Map;
import java.util.Set;

/**
 * Stable contract for LLM-controlled InterpretationPlan execution.
 */
public final class InterpretationExecutionProtocol {

    public static final String VERSION = "interpretation_execution_protocol_v1";
    public static final String DECISION_OBSERVATION_SOURCE = "interpretation_plan_controller";
    public static final String GUARD_OBSERVATION_SOURCE = "interpretation_plan_guard";
    public static final Set<String> ACTIONS = Set.of(
        "execute_step",
        "execute_parallel_steps",
        "final_answer",
        "rewrite_plan",
        "abort"
    );

    public static final String DECISION_SCHEMA = """
        {
          "protocol_version": "interpretation_execution_protocol_v1",
          "action": "execute_step | execute_parallel_steps | final_answer | rewrite_plan | abort",
          "step_ids": [1],
          "reason": "Why this decision is correct for the current DAG state.",
          "review_answer": "Optional controller diagnostic. Do not write final_answer here; final_answer is produced only by the final_answer plan step.",
          "confidence": 0.0
        }
        """;

    public static final String GUARD_RESULT_SCHEMA = """
        {
          "protocol_version": "interpretation_execution_protocol_v1",
          "allowed": true,
          "status": "accepted | rejected",
          "reason": "Runtime guard decision.",
          "validated_action": "execute_step",
          "validated_step_ids": [1]
        }
        """;

    public static final String OBSERVATION_SCHEMA = """
        {
          "protocol_version": "interpretation_execution_protocol_v1",
          "execution_trace_id": "stable trace id",
          "decision_count": 1,
          "lifecycle_phase": "controller_decision | guard_result | observation | state_update",
          "decision": {},
          "guard_result": {},
          "remaining_step_ids": [1, 2],
          "completed_step_ids": [0]
        }
        """;

    private InterpretationExecutionProtocol() {
    }

    public static Map<String, Object> protocolMetadata(String executionTraceId,
                                                       int decisionCount,
                                                       String lifecyclePhase) {
        return Map.of(
            "protocolVersion", VERSION,
            "executionTraceId", executionTraceId == null ? "" : executionTraceId,
            "decisionCount", decisionCount,
            "lifecyclePhase", lifecyclePhase == null ? "" : lifecyclePhase
        );
    }
}
