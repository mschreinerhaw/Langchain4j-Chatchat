package com.chatchat.mcpserver.tool;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mirrors contributed MCP publications into the Agent ToolRegistry descriptor plane. */
@Component
public class McpDynamicToolRegistryMirror {

    private static volatile McpDynamicToolRegistryMirror instance;
    private final ToolRegistry registry;

    public McpDynamicToolRegistryMirror(ToolRegistry registry) {
        this.registry = registry;
        instance = this;
    }

    @PreDestroy
    void close() {
        if (instance == this) instance = null;
    }

    static void publishIfAvailable(ToolPublication publication) {
        McpDynamicToolRegistryMirror current = instance;
        if (current != null) current.publish(publication);
    }

    static void unpublishIfAvailable(String toolName) {
        McpDynamicToolRegistryMirror current = instance;
        if (current != null) current.registry.unregisterTool(toolName);
    }

    private void publish(ToolPublication publication) {
        io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification governed =
            McpToolPublicationReviewer.governedSpecification(publication);
        registry.registerTool(publication.toolName(), publication.descriptor().metadata(),
            new ToolRegistry.EnhancedTool() {
                @Override public com.chatchat.common.tool.ToolMetadata getMetadata() {
                    return publication.descriptor().metadata();
                }

                @Override public ToolOutput execute(ToolInput input) {
                    try {
                        Map<String, Object> arguments = new LinkedHashMap<>(
                            input == null || input.getParameters() == null ? Map.of() : input.getParameters());
                        putIfPresent(arguments, "requestId", input == null ? null : input.getRequestId());
                        putIfPresent(arguments, "conversationId", input == null ? null : input.getConversationId());
                        putIfPresent(arguments, "userId", input == null ? null : input.getUserId());
                        Map<String, Object> meta = input == null || input.getContext() == null
                            ? Map.of() : input.getContext();
                        McpSchema.CallToolResult result = governed.callHandler().apply(null,
                            new McpSchema.CallToolRequest(publication.toolName(), arguments, meta));
                        Object data = result.structuredContent() == null ? result.content() : result.structuredContent();
                        return Boolean.TRUE.equals(result.isError())
                            ? ToolOutput.failure(String.valueOf(data)) : ToolOutput.success(data);
                    } catch (Exception failure) {
                        return ToolOutput.failure(failure);
                    }
                }
            });
    }

    private void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) values.putIfAbsent(key, value);
    }
}
