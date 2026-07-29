package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.protocol.AgentProtocolCatalog;

import java.util.Map;
import java.util.Set;

/**
 * Stable contract for LLM-controlled InterpretationPlan execution.
 */
public final class InterpretationExecutionProtocol {

    public static final String VERSION = AgentProtocolCatalog.INTERPRETATION_EXECUTION;
    public static final String DECISION_OBSERVATION_SOURCE = "interpretation_plan_controller";
    public static final String GUARD_OBSERVATION_SOURCE = "interpretation_plan_guard";
    public static final String TEMPLATE_PARAMETER_PROTOCOL_VERSION = AgentProtocolCatalog.TEMPLATE_PARAMETER;
    public static final Set<String> ACTIONS = Set.of(
        "execute_step",
        "execute_parallel_steps",
        "final_answer",
        "rewrite_plan",
        "abort"
    );

    public static final String DECISION_SCHEMA = """
        {
          "protocol_version": "%s",
          "action": "execute_step | execute_parallel_steps | final_answer | rewrite_plan | abort",
          "step_ids": [1],
          "reason": "Why this decision is correct for the current DAG state.",
          "parameter_protocols": [
            {
              "protocol_version": "%s",
              "step_id": 3,
              "template_id": "exact template id from template discovery",
              "arguments": {
                "declaredParameterName": {
                  "value": "semantic value extracted from the current user query",
                  "source": "user_query",
                  "evidence": "short supporting text from the current user query"
                }
              },
              "unresolved_parameters": []
            }
          ],
          "review_answer": "Optional controller diagnostic. Do not write final_answer here; final_answer is produced only by the final_answer plan step.",
          "confidence": 0.0
        }
        """.formatted(VERSION, TEMPLATE_PARAMETER_PROTOCOL_VERSION);

    public static final String GUARD_RESULT_SCHEMA = """
        {
          "protocol_version": "%s",
          "allowed": true,
          "status": "accepted | rejected",
          "reason": "Runtime guard decision.",
          "validated_action": "execute_step",
          "validated_step_ids": [1]
        }
        """.formatted(VERSION);

    public static final String OBSERVATION_SCHEMA = """
        {
          "protocol_version": "%s",
          "execution_trace_id": "stable trace id",
          "decision_count": 1,
          "lifecycle_phase": "controller_decision | guard_result | observation | state_update",
          "decision": {},
          "guard_result": {},
          "remaining_step_ids": [1, 2],
          "completed_step_ids": [0]
        }
        """.formatted(VERSION);

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
