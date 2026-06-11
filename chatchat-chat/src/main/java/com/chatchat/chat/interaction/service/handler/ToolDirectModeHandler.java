package com.chatchat.chat.interaction.service.handler;

import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.chat.interaction.service.InteractionModeHandler;
import com.chatchat.common.tool.ToolInput;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct tool invocation mode (manual tool call without agent loop).
 */
@Component
@RequiredArgsConstructor
public class ToolDirectModeHandler implements InteractionModeHandler {

    private static final int WEB_SEARCH_REFERENCE_LIMIT = 10;

    private final ToolRuntimeService toolRuntimeService;

    @Override
    public InteractionMode mode() {
        return InteractionMode.TOOL_DIRECT;
    }

    @Override
    public InteractionResponse handle(InteractionRequest request, InteractionContext context) {
        if (request.getToolName() == null || request.getToolName().isBlank()) {
            throw new IllegalArgumentException("toolName is required in tool_direct mode");
        }

        Map<String, Object> parameters = new HashMap<>();
        Map<String, Object> attributes = new HashMap<>();
        if (request.getToolInput() != null) {
            parameters.putAll(request.getToolInput());
            Object confirmation = parameters.remove("mcpConfirmation");
            if (confirmation instanceof Map<?, ?>) {
                attributes.put("mcpConfirmation", confirmation);
            }
        }
        bindQueryAsDefaultParameter(request.getToolName(), request.getQuery(), parameters);

        ToolInput toolInput = ToolInput.builder()
            .conversationId(context.conversationId())
            .requestId(context.requestId())
            .userId(request.getUserId())
            .parameters(parameters)
            .build();

        ToolRuntimeExecution execution = toolRuntimeService.execute(ToolRuntimeRequest.builder()
            .toolName(request.getToolName())
            .runtimeMode("tool_direct")
            .requestId(context.requestId())
            .conversationId(context.conversationId())
            .tenantId(request.getTenantId())
            .userId(request.getUserId())
            .allowedTools(request.getAvailableTools() == null ? List.of() : request.getAvailableTools())
            .toolInput(toolInput)
            .attributes(attributes)
            .build());
        InteractionToolTrace trace = execution.trace();
        var output = execution.output();
        String answer = output.isSuccess()
            ? output.getDataAsString()
            : "Tool execution failed: " + output.getErrorMessage();

        return InteractionResponse.builder()
            .answer(answer == null ? "" : answer)
            .toolTraces(java.util.List.of(trace))
            .metadata(java.util.Map.of(
                "handler", "ToolDirectModeHandler",
                "executionTimeMs", output.getExecutionTimeMs() == null ? -1L : output.getExecutionTimeMs(),
                "toolRuntimeOutcome", execution.outcome()
            ))
            .build();
    }

    private void bindQueryAsDefaultParameter(String toolName, String query, Map<String, Object> params) {
        if (query == null || query.isBlank() || !params.isEmpty()) {
            return;
        }
        if (toolName != null && toolName.startsWith("mcp_")) {
            params.put("query", query);
            return;
        }
        if ("calculator".equals(toolName)) {
            params.put("expression", query);
            return;
        }
        if ("web_search".equals(toolName)) {
            params.put("query", query);
            params.put("num_results", WEB_SEARCH_REFERENCE_LIMIT);
            return;
        }
        params.put("input", query);
    }

}
