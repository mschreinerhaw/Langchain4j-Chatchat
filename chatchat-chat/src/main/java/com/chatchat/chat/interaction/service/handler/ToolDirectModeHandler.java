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
import java.util.LinkedHashMap;
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

    /**
     * Performs the mode operation.
     *
     * @return the operation result
     */
    @Override
    public InteractionMode mode() {
        return InteractionMode.TOOL_DIRECT;
    }

    /**
     * Handles the handle.
     *
     * @param request the request value
     * @param context the context value
     * @return the operation result
     */
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
            Object executionContext = parameters.remove("mcpExecutionContext");
            if (!(executionContext instanceof Map<?, ?>)) {
                executionContext = parameters.remove("executionContext");
            } else {
                parameters.remove("executionContext");
            }
            if (executionContext instanceof Map<?, ?> executionContextMap) {
                attributes.put("mcpExecutionContext", sanitizeExecutionContext(executionContextMap));
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

    /**
     * Performs the bind query as default parameter operation.
     *
     * @param toolName the tool name value
     * @param query the query value
     * @param params the params value
     */
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

    private Map<String, Object> sanitizeExecutionContext(Map<?, ?> rawContext) {
        if (rawContext == null || rawContext.isEmpty()) {
            return Map.of();
        }
        List<String> allowedKeys = List.of(
            "env",
            "environment",
            "cluster",
            "namespace",
            "target",
            "targetType",
            "target_type",
            "hostSelector",
            "host_selector",
            "tenant",
            "businessUnit",
            "business_unit",
            "database",
            "databaseRole",
            "database_role",
            "service",
            "labels"
        );
        Map<String, Object> sanitized = new LinkedHashMap<>();
        rawContext.forEach((key, value) -> {
            if (key == null || value == null || !allowedKeys.contains(String.valueOf(key))) {
                return;
            }
            sanitized.put(String.valueOf(key), value);
        });
        return sanitized.isEmpty() ? Map.of() : sanitized;
    }

}
