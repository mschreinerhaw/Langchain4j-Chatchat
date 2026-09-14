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
                // The template compiler deliberately retains an admitted child whose
                // parameter contract cannot be completed as a terminal preflight result.
                // ToolRuntimeService consumes this marker without invoking the child, so
                // applying the executor's required-input schema to it would reject the whole
                // failure-isolated batch before any executable sibling can run. Only trust the
                // marker on a Runtime-owned batch; planner-authored data cannot bypass schema
                // validation this way.
                if (runtimeOwnedTemplateBatch && hasPreflightFailure(call)) {
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

    private boolean hasPreflightFailure(Map<?, ?> call) {
        Object value = call.containsKey("preflightErrorCode")
            ? call.get("preflightErrorCode") : call.get("preflight_error_code");
        return value != null && !String.valueOf(value).isBlank();
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
