package com.chatchat.agents.orchestration.tool;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolOutput;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime boundary for an authorized tool call and its durable structured observation.
 * Argument resolution and execution stay in {@link AgentToolExecutor}; this class guarantees
 * that every outcome, including failures, enters the run event stream exactly once.
 */
public final class AgentToolCallCoordinator {

    private final AgentToolExecutor executor;
    private final AgentRunResultAdapter resultAdapter;
    private final String runIdAttribute;

    public AgentToolCallCoordinator(AgentToolExecutor executor,
                                    AgentRunResultAdapter resultAdapter,
                                    String runIdAttribute) {
        this.executor = executor;
        this.resultAdapter = resultAdapter;
        this.runIdAttribute = runIdAttribute;
    }

    public AgentToolExecutor.Execution execute(String toolName,
                                               Map<String, Object> arguments,
                                               String conversationId,
                                               String requestId,
                                               String userId,
                                               String tenantId,
                                               List<String> allowedTools,
                                               Map<String, Object> plannerExecutionPlan,
                                               List<InteractionToolTrace> priorTraces,
                                               Map<String, Object> runtimeAttributes) {
        AgentToolExecutor.Execution execution = executor.execute(
            toolName, arguments, conversationId, requestId, userId, tenantId,
            allowedTools, plannerExecutionPlan, priorTraces, runtimeAttributes);
        record(runtimeAttributes, toolName, execution.output(),
            execution.runtimeExecution(), execution.observation());
        return execution;
    }

    private void record(Map<String, Object> runtimeAttributes,
                        String toolName,
                        ToolOutput output,
                        ToolRuntimeExecution execution,
                        String observation) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", output != null && output.isSuccess() ? "tool" : "tool_failure");
        metadata.put("toolName", toolName);
        metadata.put("success", output != null && output.isSuccess());
        metadata.put("outcome", execution == null ? null : execution.outcome());
        if (output != null && output.getMetadata() != null
            && output.getMetadata().get("mcpEvidenceResult") != null) {
            metadata.put("mcpEvidenceResult", output.getMetadata().get("mcpEvidenceResult"));
            metadata.put("mcpEvidenceResultSchemaVersion",
                output.getMetadata().get("mcpEvidenceResultSchemaVersion"));
        }
        copy(runtimeAttributes, metadata, "workflowStepId");
        copy(runtimeAttributes, metadata, "workflowToolName");
        copy(runtimeAttributes, metadata, "interpretationPlanStepId");
        copy(runtimeAttributes, metadata, "interpretationPlanActionType");
        if (output != null && output.getErrorMessage() != null
            && !output.getErrorMessage().isBlank()) {
            metadata.put("errorMessage", output.getErrorMessage());
        }
        resultAdapter.recordRuntimeObservation(
            runtimeAttributes, runIdAttribute, observation, toolName, metadata);
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null) return;
        Object value = source.get(key);
        if (value != null) target.put(key, value);
    }
}
