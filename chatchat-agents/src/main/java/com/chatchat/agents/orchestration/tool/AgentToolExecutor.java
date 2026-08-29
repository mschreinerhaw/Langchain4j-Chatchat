package com.chatchat.agents.orchestration.tool;

import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.firstNonBlank;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.stringify;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.stringValue;

/**
 * Executes one Agent tool call after applying runtime-owned argument contracts.
 *
 * <p>The orchestrator owns sequencing; this component owns the complete tool-call boundary:
 * argument compilation, execution-plan construction, runtime invocation and observation text.</p>
 */
public final class AgentToolExecutor {

    private final ToolRegistry toolRegistry;
    private final ToolRuntimeService toolRuntimeService;
    private final AgentToolArgumentResolver toolArguments;
    private ToolObservationBuilder observationBuilder;

    public AgentToolExecutor(ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService,
                             AgentToolArgumentResolver toolArguments,
                             ToolObservationBuilder observationBuilder) {
        this.toolRegistry = toolRegistry;
        this.toolRuntimeService = toolRuntimeService;
        this.toolArguments = toolArguments;
        this.observationBuilder = observationBuilder;
    }

    public void setObservationBuilder(ToolObservationBuilder observationBuilder) {
        if (observationBuilder != null) {
            this.observationBuilder = observationBuilder;
        }
    }

    public Execution execute(String toolName,
                             Map<String, Object> arguments,
                             String conversationId,
                             String requestId,
                             String userId,
                             String tenantId,
                             List<String> allowedTools,
                             Map<String, Object> plannerExecutionPlan,
                             List<InteractionToolTrace> priorTraces,
                             Map<String, Object> runtimeAttributes) {
        String originalUserQuery = stringValue(runtimeAttributes == null
            ? null
            : runtimeAttributes.get("originalUserQuery"));
        Map<String, Object> compiledArguments = originalUserQuery == null || originalUserQuery.isBlank()
            ? toolArguments.applyObservedTemplateContract(toolName, arguments, priorTraces)
            : toolArguments.applyObservedTemplateContract(
                toolName, arguments, priorTraces, originalUserQuery);
        Map<String, Object> safeArguments = new LinkedHashMap<>(compiledArguments);
        safeArguments = new LinkedHashMap<>(
            toolArguments.enforceObservedAssetContinuity(toolName, safeArguments, priorTraces));
        boolean runtimeOwnedTemplateBatch = Boolean.TRUE.equals(
            safeArguments.remove(AgentToolArgumentResolver.RUNTIME_OWNED_TEMPLATE_BATCH_MARKER));
        Map<String, Object> attributes = new LinkedHashMap<>(
            runtimeAttributes == null ? Map.of() : runtimeAttributes);
        if (runtimeOwnedTemplateBatch) {
            attributes.put("runtimeOwnedTemplateBatch", true);
        }
        attributes.put("executionPlan", buildRuntimeExecutionPlan(
            toolName, safeArguments, plannerExecutionPlan));

        ToolInput toolInput = ToolInput.builder()
            .conversationId(conversationId)
            .requestId(requestId)
            .userId(userId)
            .parameters(safeArguments)
            .build();
        ToolRuntimeExecution runtimeExecution = toolRuntimeService.execute(ToolRuntimeRequest.builder()
            .toolName(toolName)
            .runtimeMode("agent_chat")
            .requestId(requestId)
            .conversationId(conversationId)
            .tenantId(tenantId)
            .userId(userId)
            .allowedTools(allowedTools == null ? List.of() : allowedTools)
            .toolInput(toolInput)
            .attributes(attributes)
            .build());
        ToolOutput output = runtimeExecution.output();
        String outputText = stringify(output.getData());
        String observation = output.isSuccess()
            ? observationBuilder.buildSuccessObservation(toolName, output, outputText)
            : observationBuilder.buildFailureObservation(toolName, output);
        return new Execution(runtimeExecution.trace(), observation, output, runtimeExecution);
    }

    private Map<String, Object> buildRuntimeExecutionPlan(String toolName,
                                                           Map<String, Object> arguments,
                                                           Map<String, Object> plannerExecutionPlan) {
        Map<String, Object> plan = new LinkedHashMap<>(
            plannerExecutionPlan == null ? Map.of() : plannerExecutionPlan);
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        plan.putIfAbsent("intent", firstNonBlank(
            stringValue(plan.get("intent")), "Use tool to satisfy the user request"));
        plan.put("tool", firstNonBlank(stringValue(plan.get("tool")), toolName));
        plan.put("operation_type", firstNonBlank(
            firstNonBlank(stringValue(plan.get("operation_type")), stringValue(plan.get("operationType"))),
            metadata == null ? "read" : firstNonBlank(metadata.getOperationType(), "read")
        ));
        plan.put("risk_level", firstNonBlank(
            firstNonBlank(stringValue(plan.get("risk_level")), stringValue(plan.get("riskLevel"))),
            metadata == null ? "low" : firstNonBlank(metadata.getRiskLevel(), "low")
        ));
        plan.put("parameters", arguments == null ? Map.of() : new LinkedHashMap<>(arguments));
        plan.putIfAbsent("reason", firstNonBlank(
            stringValue(plan.get("reason")), "Planner selected " + toolName));
        return plan;
    }

    public record Execution(
        InteractionToolTrace trace,
        String observation,
        ToolOutput output,
        ToolRuntimeExecution runtimeExecution
    ) {
    }
}
