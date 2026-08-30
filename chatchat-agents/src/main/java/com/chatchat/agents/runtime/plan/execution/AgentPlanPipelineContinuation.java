package com.chatchat.agents.runtime.plan.execution;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.common.interaction.InteractionToolTrace;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

/**
 * Durable business continuation captured exactly where Agent orchestration yields plan execution
 * to a workflow engine. It contains the state needed to resume evidence analysis and plan rewrite
 * without invoking the planner again.
 */
public record AgentPlanPipelineContinuation(
    String schemaVersion,
    String continuationId,
    AgentRunRequest request,
    InterpretationPlan initialPlan,
    InterpretationPlan currentPlan,
    PlanExecutionContinuation execution,
    int planAttempt,
    int rewriteCount,
    int maxRewriteTimes,
    List<InterpretationPlanRuntime.ExecutionResult> attemptResults,
    List<Map<String, Object>> evidenceHistory,
    List<InteractionToolTrace> traces,
    List<String> observations,
    Map<String, Object> runtimeAttributes,
    Map<String, Object> metadata
) {
    public static final String SCHEMA_VERSION = "agent_plan_pipeline_continuation.v1";

    public AgentPlanPipelineContinuation {
        schemaVersion = text(schemaVersion, SCHEMA_VERSION);
        continuationId = text(continuationId, null);
        if (continuationId == null || request == null || initialPlan == null
            || currentPlan == null || execution == null) {
            throw new IllegalArgumentException(
                "Agent plan continuation identity, request, plans and execution state are required");
        }
        planAttempt = Math.max(1, planAttempt);
        rewriteCount = Math.max(0, rewriteCount);
        maxRewriteTimes = Math.max(rewriteCount, maxRewriteTimes);
        attemptResults = List.copyOf(attemptResults == null ? List.of() : attemptResults);
        evidenceHistory = evidenceHistory == null ? List.of() : evidenceHistory.stream()
            .map(AgentPlanPipelineContinuation::immutableMap).toList();
        traces = List.copyOf(traces == null ? List.of() : traces);
        observations = List.copyOf(observations == null ? List.of() : observations);
        runtimeAttributes = immutableMap(runtimeAttributes);
        metadata = immutableMap(metadata);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values == null ? Map.of() : values));
    }
}
