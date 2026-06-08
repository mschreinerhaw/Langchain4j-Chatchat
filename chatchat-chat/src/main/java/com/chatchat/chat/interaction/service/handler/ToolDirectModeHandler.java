package com.chatchat.chat.interaction.service.handler;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.chat.interaction.service.InteractionModeHandler;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Direct tool invocation mode (manual tool call without agent loop).
 */
@Component
@RequiredArgsConstructor
public class ToolDirectModeHandler implements InteractionModeHandler {

    private final ToolRegistry toolRegistry;

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
        if (request.getToolInput() != null) {
            parameters.putAll(request.getToolInput());
        }
        bindQueryAsDefaultParameter(request.getToolName(), request.getQuery(), parameters);

        ToolInput toolInput = ToolInput.builder()
            .conversationId(context.conversationId())
            .requestId(context.requestId())
            .userId(request.getUserId())
            .parameters(parameters)
            .build();

        ToolMetadata toolMetadata = toolRegistry.getToolMetadata(request.getToolName());
        long startedAt = System.currentTimeMillis();
        ToolOutput output = toolRegistry.executeEnhancedTool(request.getToolName(), toolInput);
        long finishedAt = System.currentTimeMillis();
        String answer = output.isSuccess()
            ? output.getDataAsString()
            : "Tool execution failed: " + output.getErrorMessage();
        Long durationMs = output.getExecutionTimeMs() == null ? Math.max(0L, finishedAt - startedAt) : output.getExecutionTimeMs();

        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName(request.getToolName())
            .displayName(resolveDisplayName(request.getToolName(), toolMetadata))
            .serviceId(resolveServiceId(toolMetadata))
            .serviceName(resolveServiceName(toolMetadata))
            .success(output.isSuccess())
            .input(parameters)
            .output(output.getDataAsString())
            .errorMessage(output.getErrorMessage())
            .durationMs(durationMs)
            .startedAt(startedAt)
            .finishedAt(finishedAt)
            .build();

        return InteractionResponse.builder()
            .answer(answer == null ? "" : answer)
            .toolTraces(java.util.List.of(trace))
            .metadata(java.util.Map.of(
                "handler", "ToolDirectModeHandler",
                "executionTimeMs", output.getExecutionTimeMs() == null ? -1L : output.getExecutionTimeMs()
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
            params.put("num_results", 5);
            return;
        }
        params.put("input", query);
    }

    private String resolveDisplayName(String toolName, ToolMetadata metadata) {
        if (metadata != null && metadata.getTitle() != null && !metadata.getTitle().isBlank()) {
            return metadata.getTitle().trim();
        }
        return toolName;
    }

    private String resolveServiceId(ToolMetadata metadata) {
        if (metadata == null || metadata.getMetadata() == null) {
            return null;
        }
        Object value = metadata.getMetadata().get("serviceId");
        return value == null ? null : String.valueOf(value);
    }

    private String resolveServiceName(ToolMetadata metadata) {
        if (metadata == null || metadata.getAuthor() == null || metadata.getAuthor().isBlank()) {
            return null;
        }
        String author = metadata.getAuthor().trim();
        if (author.startsWith("MCP:")) {
            return author.substring(4).trim();
        }
        return author;
    }
}
