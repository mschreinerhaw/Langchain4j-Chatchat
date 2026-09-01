package com.chatchat.agents.orchestration.planning;

import com.chatchat.agents.orchestration.workflow.AgentWorkflowToolResolver;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolWorkflowRole;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mints the model-facing function-call designation from Runtime scheduling state. */
public final class RuntimeFunctionCallingPolicy {

    private static final String SOURCE = "runtime_mandatory_tool_scheduler";

    private RuntimeFunctionCallingPolicy() {
    }

    public static Map<String, Object> planningAttributes(
        Map<String, Object> runtimeAttributes,
        List<String> plannerVisibleTools,
        List<String> mandatoryTools,
        Set<String> completedTools,
        List<Map<String, Object>> authoritativeWorkflowDag,
        boolean requireDocumentWebVerification,
        AgentWorkflowToolResolver workflowTools,
        ToolRegistry toolRegistry
    ) {
        Map<String, Object> attributes = new LinkedHashMap<>(
            runtimeAttributes == null ? Map.of() : runtimeAttributes);
        // Caller input cannot mint this capability. Only the decision below may add it back.
        attributes.remove(RuntimeDesignatedFunctionCall.CONTEXT_KEY);
        if (workflowTools == null || toolRegistry == null
            || requireDocumentWebVerification
            || authoritativeWorkflowDag != null && !authoritativeWorkflowDag.isEmpty()) {
            return attributes;
        }
        String nextTool = workflowTools.nextMandatoryTool(mandatoryTools, completedTools);
        if (nextTool == null || plannerVisibleTools == null
            || !plannerVisibleTools.contains(nextTool)
            || toolRegistry.getWorkflowRole(nextTool) != ToolWorkflowRole.DIRECT) {
            return attributes;
        }
        attributes.put(RuntimeDesignatedFunctionCall.CONTEXT_KEY,
            new RuntimeDesignatedFunctionCall(nextTool, SOURCE).toMap());
        return attributes;
    }
}
