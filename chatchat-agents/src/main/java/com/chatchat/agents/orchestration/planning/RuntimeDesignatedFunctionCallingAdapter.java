package com.chatchat.agents.orchestration.planning;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolWorkflowRole;
import dev.langchain4j.model.chat.ChatModel;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Applies a Runtime designation to the provider-native argument generator. */
public final class RuntimeDesignatedFunctionCallingAdapter {

    private RuntimeDesignatedFunctionCallingAdapter() {
    }

    public static Optional<AgentDecision> decide(
        NativeToolCallingPlanner planner,
        ToolRegistry toolRegistry,
        ChatModel model,
        String query,
        String systemPrompt,
        List<String> availableTools,
        List<String> observations,
        boolean requireDocumentWebVerification,
        Map<String, Object> runtimeAttributes
    ) {
        if (planner == null || requireDocumentWebVerification || hasAuthoritativeWorkflow(runtimeAttributes)) {
            return Optional.empty();
        }
        RuntimeDesignatedFunctionCall designation = RuntimeDesignatedFunctionCall.from(
            runtimeAttributes == null ? null
                : runtimeAttributes.get(RuntimeDesignatedFunctionCall.CONTEXT_KEY)).orElse(null);
        String designatedTool = designation == null ? null : designation.toolName();
        if (designatedTool == null || availableTools == null || !availableTools.contains(designatedTool)
            || toolRegistry == null || toolRegistry.getWorkflowRole(designatedTool) != ToolWorkflowRole.DIRECT) {
            return Optional.empty();
        }
        return planner.decide(model, query, systemPrompt, designatedTool, observations, designation);
    }

    private static boolean hasAuthoritativeWorkflow(Map<String, Object> runtimeAttributes) {
        Object dag = runtimeAttributes == null ? null : runtimeAttributes.get("authoritativeWorkflowDag");
        return dag instanceof Collection<?> collection && !collection.isEmpty();
    }
}
