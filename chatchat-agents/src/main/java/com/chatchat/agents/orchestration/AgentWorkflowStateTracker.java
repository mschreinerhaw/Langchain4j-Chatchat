package com.chatchat.agents.orchestration;

import com.chatchat.common.interaction.InteractionToolTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maintains workflow completion state derived from successful tool traces.
 */
class AgentWorkflowStateTracker {

    Map<String, Object> attributesWithCompletedTools(Map<String, Object> runtimeAttributes,
                                                     Set<String> completedTools) {
        Map<String, Object> attributes = new LinkedHashMap<>(runtimeAttributes == null ? Map.of() : runtimeAttributes);
        Set<String> merged = new LinkedHashSet<>();
        Object existing = attributes.get("workflowCompletedTools");
        if (existing instanceof List<?> list) {
            list.stream().map(this::stringValue).filter(value -> value != null && !value.isBlank()).forEach(merged::add);
        } else if (existing instanceof String text && !text.isBlank()) {
            for (String item : text.split("[,;]")) {
                if (!item.isBlank()) {
                    merged.add(item.trim());
                }
            }
        }
        if (completedTools != null) {
            completedTools.stream().filter(value -> value != null && !value.isBlank()).forEach(merged::add);
        }
        if (!merged.isEmpty()) {
            attributes.put("workflowCompletedTools", new ArrayList<>(merged));
        }
        return attributes;
    }

    void rememberCompletedWorkflowTool(Set<String> completedTools, AgentOrchestrator.ToolCallExecution execution) {
        if (completedTools == null || execution == null || execution.trace() == null) {
            return;
        }
        if (execution.trace().isSuccess() && execution.trace().getToolName() != null && !execution.trace().getToolName().isBlank()) {
            completedTools.add(execution.trace().getToolName());
        }
    }

    Set<String> completedToolsFromTraces(List<InteractionToolTrace> traces) {
        Set<String> completed = new LinkedHashSet<>();
        if (traces == null || traces.isEmpty()) {
            return completed;
        }
        traces.stream()
            .filter(trace -> trace != null && trace.isSuccess())
            .map(InteractionToolTrace::getToolName)
            .filter(tool -> tool != null && !tool.isBlank())
            .forEach(completed::add);
        return completed;
    }

    boolean isConfirmationRequired(AgentOrchestrator.ToolCallExecution execution) {
        if (execution == null || execution.trace() == null || execution.trace().getRuntimeMetadata() == null) {
            return false;
        }
        Object outcome = execution.trace().getRuntimeMetadata().get("outcome");
        return "confirmation_required".equalsIgnoreCase(String.valueOf(outcome));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
