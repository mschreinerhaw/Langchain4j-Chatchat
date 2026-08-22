package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunEventType;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.agents.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maintains workflow completion state derived from terminal tool observations.
 */
class AgentWorkflowStateTracker {

    private final ToolRegistry toolRegistry;

    AgentWorkflowStateTracker() {
        this(null);
    }

    AgentWorkflowStateTracker(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

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

    Map<String, Object> attributesWithCompletedWorkflowState(Map<String, Object> runtimeAttributes,
                                                             Set<String> completedTools,
                                                             List<InteractionToolTrace> traces) {
        Map<String, Object> attributes = attributesWithCompletedTools(runtimeAttributes, completedTools);
        Map<String, Object> existingContext = asMap(attributes.get("workflowContext"));
        if (workflowTargetRef(existingContext) != null) {
            return attributes;
        }

        Map<String, String> targetsByCanonicalValue = new LinkedHashMap<>();
        if (traces != null) {
            traces.stream()
                .filter(trace -> trace != null && trace.isSuccess() && isAssetDiscoveryTool(trace.getToolName()))
                .map(InteractionToolTrace::getInput)
                .map(this::workflowTargetRef)
                .filter(value -> value != null && !value.isBlank())
                .forEach(value -> targetsByCanonicalValue.putIfAbsent(value.trim().toLowerCase(), value.trim()));
        }
        if (targetsByCanonicalValue.size() != 1) {
            return attributes;
        }

        Map<String, Object> workflowContext = new LinkedHashMap<>(existingContext);
        workflowContext.put("workflowTargetRef", targetsByCanonicalValue.values().iterator().next());
        attributes.put("workflowContext", workflowContext);
        return attributes;
    }

    void rememberCompletedWorkflowTool(Set<String> completedTools, AgentOrchestrator.ToolCallExecution execution) {
        if (completedTools == null || execution == null || execution.trace() == null) {
            return;
        }
        if (execution.trace().isSuccess()
            && !isConfirmationRequired(execution)
            && execution.trace().getToolName() != null
            && !execution.trace().getToolName().isBlank()) {
            completedTools.add(execution.trace().getToolName());
        }
    }

    Set<String> completedToolsFromTraces(List<InteractionToolTrace> traces) {
        Set<String> completed = new LinkedHashSet<>();
        if (traces == null || traces.isEmpty()) {
            return completed;
        }
        traces.stream()
            .filter(trace -> trace != null && trace.isSuccess() && !confirmationRequired(trace))
            .map(InteractionToolTrace::getToolName)
            .filter(tool -> tool != null && !tool.isBlank())
            .forEach(completed::add);
        return completed;
    }

    Set<String> completedToolsFromEvents(List<AgentRunEvent> events) {
        WorkflowEventSnapshot snapshot = WorkflowEventSnapshot.from(events);
        return snapshot.completedTools();
    }

    WorkflowEventSnapshot eventSnapshot(List<AgentRunEvent> events) {
        return WorkflowEventSnapshot.from(events);
    }

    boolean isConfirmationRequired(AgentOrchestrator.ToolCallExecution execution) {
        if (execution == null || execution.trace() == null || execution.trace().getRuntimeMetadata() == null) {
            return false;
        }
        Object outcome = execution.trace().getRuntimeMetadata().get("outcome");
        return "confirmation_required".equalsIgnoreCase(String.valueOf(outcome));
    }

    private boolean confirmationRequired(InteractionToolTrace trace) {
        if (trace == null || trace.getRuntimeMetadata() == null) {
            return false;
        }
        Object outcome = trace.getRuntimeMetadata().get("outcome");
        return "confirmation_required".equalsIgnoreCase(String.valueOf(outcome));
    }

    private boolean isAssetDiscoveryTool(String toolName) {
        ToolWorkflowRole role = toolRegistry == null ? null : toolRegistry.getWorkflowRole(toolName);
        // Null is possible for compatibility registries and test doubles that predate the
        // metadata contract. Published registries always return the database-declared role.
        if (role == null) {
            role = ToolWorkflowContract.resolveRole(toolName, null);
        }
        return role == ToolWorkflowRole.ASSET_DISCOVERY;
    }

    private String workflowTargetRef(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (String key : List.of(
            "workflowTargetRef", "targetAssetId", "targetAssetName",
            "assetId", "assetName", "databaseAssetId", "databaseAssetName"
        )) {
            String value = stringValue(values.get(key));
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        for (String nestedKey : List.of(
            "executionContext", "execution_context", "filters", "target",
            "defaultDataAsset", "mcpExecutionContext", "workflowContext"
        )) {
            String nested = workflowTargetRef(asMap(values.get(nestedKey)));
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) {
                values.put(String.valueOf(key), item);
            }
        });
        return values;
    }

    record WorkflowEventSnapshot(
        Set<String> completedTools,
        Set<Integer> completedStepIds,
        Set<Integer> failedStepIds,
        boolean confirmationRequired,
        boolean failed,
        boolean cancelled
    ) {
        static WorkflowEventSnapshot from(List<AgentRunEvent> events) {
            Set<String> completedTools = new LinkedHashSet<>();
            Set<Integer> completedStepIds = new LinkedHashSet<>();
            Set<Integer> failedStepIds = new LinkedHashSet<>();
            boolean confirmationRequired = false;
            boolean failed = false;
            boolean cancelled = false;
            if (events != null) {
                for (AgentRunEvent event : events) {
                    if (event == null || event.type() == null) {
                        continue;
                    }
                    if (event.type() == AgentRunEventType.CONFIRMATION_REQUIRED) {
                        confirmationRequired = true;
                    } else if (event.type() == AgentRunEventType.RUN_FAILED) {
                        failed = true;
                    } else if (event.type() == AgentRunEventType.RUN_CANCELLED) {
                        cancelled = true;
                    }
                    if (event.type() != AgentRunEventType.OBSERVATION_RECORDED) {
                        continue;
                    }
                    Map<String, Object> payload = event.payload();
                    Map<String, Object> metadata = asMap(payload == null ? null : payload.get("metadata"));
                    Boolean success = booleanObject(firstObject(metadata, "success", "toolSuccess"));
                    String toolName = stringObject(firstObject(metadata, "toolName", "resolvedToolName"));
                    if (toolName == null || toolName.isBlank()) {
                        toolName = stringObject(payload == null ? null : payload.get("source"));
                    }
                    if (Boolean.FALSE.equals(success)) {
                        Integer stepId = firstInteger(firstObject(metadata, "interpretationPlanStepId", "workflowStepId", "stepId"));
                        if (stepId != null) {
                            failedStepIds.add(stepId);
                        }
                        continue;
                    }
                    if (!Boolean.TRUE.equals(success)) {
                        continue;
                    }
                    Integer stepId = firstInteger(firstObject(metadata, "interpretationPlanStepId", "workflowStepId", "stepId"));
                    if (stepId != null) {
                        completedStepIds.add(stepId);
                    }
                    if (toolName != null && !toolName.isBlank()) {
                        completedTools.add(toolName);
                    }
                }
            }
            return new WorkflowEventSnapshot(completedTools, completedStepIds, failedStepIds, confirmationRequired, failed, cancelled);
        }

        boolean isStepCompleted(Integer stepId) {
            return stepId != null && completedStepIds.contains(stepId);
        }

        boolean isStepFailed(Integer stepId) {
            return stepId != null && failedStepIds.contains(stepId);
        }

        private static Object firstObject(Map<String, Object> values, String... keys) {
            if (values == null || values.isEmpty()) {
                return null;
            }
            for (String key : keys) {
                Object value = values.get(key);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        private static Map<String, Object> asMap(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> values = new LinkedHashMap<>();
                map.forEach((key, item) -> {
                    if (key != null) {
                        values.put(String.valueOf(key), item);
                    }
                });
                return values;
            }
            return Map.of();
        }

        private static Boolean booleanObject(Object value) {
            if (value instanceof Boolean bool) {
                return bool;
            }
            if (value == null) {
                return null;
            }
            return Boolean.parseBoolean(String.valueOf(value));
        }

        private static Integer firstInteger(Object value) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value == null) {
                return null;
            }
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private static String stringObject(Object value) {
            return value == null ? null : String.valueOf(value);
        }
    }
}
