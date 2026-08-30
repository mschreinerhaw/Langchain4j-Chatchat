package com.chatchat.agents.orchestration.evidence;

import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Restores successful batch evidence produced after the primary plan into its evidence chain. */
public final class RecoveredBatchEvidenceBridge {

    private RecoveredBatchEvidenceBridge() { }

    public static List<InterpretationPlanRuntime.ExecutionResult> project(
        List<InteractionToolTrace> traces,
        ObjectMapper objectMapper
    ) {
        if (traces == null || traces.isEmpty() || objectMapper == null) return List.of();
        List<InterpretationPlanRuntime.ExecutionResult> results = new ArrayList<>();
        for (InteractionToolTrace trace : traces) {
            ToolCallBatchResult batch = parseBatch(trace, objectMapper);
            if (batch == null || batch.results().isEmpty()) continue;
            InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
                -1, "mcp_tool", trace.getToolName(), true, batch, null, null, null,
                trace.getDurationMs() == null ? 0L : trace.getDurationMs(),
                Map.of("batchExecution", true, "recoveredExecutionEvidence", true));
            results.add(new InterpretationPlanRuntime.ExecutionResult(
                "RECOVERED_BATCH_EVIDENCE", true, false, null, null, List.of(step),
                Map.of("source", "MANDATORY_WORKFLOW_TRACE"), step.durationMs()));
        }
        return List.copyOf(results);
    }

    @SuppressWarnings("unchecked")
    private static ToolCallBatchResult parseBatch(InteractionToolTrace trace, ObjectMapper objectMapper) {
        if (trace == null || !trace.isSuccess() || trace.getOutput() == null
            || trace.getOutput().isBlank()) return null;
        try {
            Map<String, Object> root = objectMapper.readValue(trace.getOutput(), Map.class);
            if (!(root.get("results") instanceof List<?>) || !(root.get("summary") instanceof Map<?, ?>)) {
                return null;
            }
            Map<String, Object> contract = new LinkedHashMap<>();
            for (String key : List.of("batchId", "executionMode", "startedAt", "completedAt",
                "status", "cardinality", "summary", "results")) {
                if (root.containsKey(key)) contract.put(key, root.get(key));
            }
            return objectMapper.convertValue(contract, ToolCallBatchResult.class);
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            return null;
        }
    }
}
