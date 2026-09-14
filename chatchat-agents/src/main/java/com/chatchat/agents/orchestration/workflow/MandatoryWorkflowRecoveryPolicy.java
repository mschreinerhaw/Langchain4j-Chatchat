package com.chatchat.agents.orchestration.workflow;

import com.chatchat.agents.orchestration.tool.AgentToolArgumentResolver;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.booleanValue;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.firstNonBlank;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.firstObject;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.stringValue;

/** Stateless eligibility and input-completeness policy for mandatory workflow recovery. */
public final class MandatoryWorkflowRecoveryPolicy {

    private final ToolRegistry toolRegistry;
    private final AgentToolNameResolver toolNames;

    public MandatoryWorkflowRecoveryPolicy(ToolRegistry toolRegistry, AgentToolNameResolver toolNames) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.toolNames = Objects.requireNonNull(toolNames, "toolNames");
    }

    public boolean shouldSuppressLegacyFallback(String fallbackTool, Map<String, Object> metadata) {
        if (fallbackTool == null || fallbackTool.isBlank() || metadata == null
            || metadata.get("diagnosticRun") == null) {
            return false;
        }
        if (!(metadata.get("interpretationPlanStepExecutions") instanceof Iterable<?> executions)) {
            return false;
        }
        for (Object rawExecution : executions) {
            if (!(rawExecution instanceof Map<?, ?> execution)) {
                continue;
            }
            String attemptedTool = stringValue(execution.get("toolName"));
            boolean failed = execution.containsKey("success") && !booleanValue(execution.get("success"));
            if (failed && toolNames.sameToolName(fallbackTool, attemptedTool)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public List<String> missingRequiredInputs(String toolName, Map<String, Object> arguments) {
        Map<String, Object> input = arguments == null ? Map.of() : arguments;
        Object rawCalls = firstObject(input, "calls", "toolCalls", "tool_calls");
        if (rawCalls instanceof List<?> calls && !calls.isEmpty()) {
            boolean runtimeOwnedTemplateBatch = Boolean.TRUE.equals(
                input.get(AgentToolArgumentResolver.RUNTIME_OWNED_TEMPLATE_BATCH_MARKER));
            List<String> missing = new ArrayList<>();
            for (int index = 0; index < calls.size(); index++) {
                Object rawCall = calls.get(index);
                if (!(rawCall instanceof Map<?, ?> call)) {
                    missing.add("calls[" + index + "]");
                    continue;
                }
                String childTool = firstNonBlank(
                    firstNonBlank(stringValue(call.get("toolName")), stringValue(call.get("tool_name"))), toolName);
                Object rawChildArguments = firstObject(
                    new LinkedHashMap<>((Map<String, Object>) call), "arguments", "input");
                if (!(rawChildArguments instanceof Map<?, ?> childArguments)) {
                    missing.add("calls[" + index + "].arguments");
                    continue;
                }
                // A Runtime-owned batch has already been compiled from reviewed discovery
                // contracts. Its children are deliberately failure-isolated: the tool runtime
                // validates every actual child invocation and materializes invalid/preflight
                // children as terminal batch results. Reapplying the executor's scalar schema at
                // this envelope boundary turns one incomplete child (or a publisher field filled
                // only at the child runtime boundary) into a rejection of the entire batch. That
                // recreates the exact "compiled but never executed" evidence gap this recovery
                // path exists to prevent. The marker cannot be supplied by a planner/model: the
                // argument resolver removes it and only restores it after deterministic
                // compilation from observed contracts.
                if (runtimeOwnedTemplateBatch) {
                    continue;
                }
                for (String childMissing : missingRequiredInputs(
                    childTool, new LinkedHashMap<>((Map<String, Object>) childArguments))) {
                    missing.add("calls[" + index + "].arguments." + childMissing);
                }
            }
            return List.copyOf(missing);
        }
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        if (metadata == null || metadata.getParameters() == null) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (ToolParameter parameter : metadata.getParameters()) {
            if (parameter == null || !parameter.isRequired()
                || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            Object value = requiredValue(input, parameter.getName());
            if (value == null || value instanceof CharSequence text && text.toString().isBlank()) {
                missing.add(parameter.getName());
            }
        }
        return List.copyOf(missing);
    }

    private Object requiredValue(Map<String, Object> input, String parameterName) {
        Object direct = input.get(parameterName);
        if (direct != null) {
            return direct;
        }
        String normalized = parameterName.trim().toLowerCase(Locale.ROOT).replace("_", "");
        if ("template".equals(normalized) || "templateid".equals(normalized)) {
            return firstObject(input, "template", "templateId", "template_id");
        }
        if ("executioncontext".equals(normalized) || "mcpexecutioncontext".equals(normalized)) {
            return firstObject(input, "executionContext", "mcpExecutionContext", "execution_context");
        }
        if ("parameters".equals(normalized) || "params".equals(normalized)) {
            return firstObject(input, "parameters", "params");
        }
        return null;
    }
}
