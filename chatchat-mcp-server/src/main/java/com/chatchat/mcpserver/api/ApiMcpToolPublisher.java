package com.chatchat.mcpserver.api;

import com.chatchat.common.tool.ToolProtocolDriverContract;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.mcpserver.tool.McpToolPublicationReviewer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiMcpToolPublisher {

    public static final String BRIDGE_TOOL_NAME = "api_service_query";
    /** @deprecated internal protocol name retained only for compatibility metadata and cleanup. */
    @Deprecated
    public static final String EXECUTE_TOOL_NAME = "api_template_execute";
    static final List<String> LEGACY_PROTOCOL_TOOLS = List.of(
        "api_asset_query", "api_template_query", "api_requirement_analyze", EXECUTE_TOOL_NAME);

    private final McpSyncServer mcpSyncServer;
    private final ApiServiceBridge bridge;
    private final ApiToolSpecFactory toolSpecFactory;
    private final McpToolConcurrencyManager concurrencyManager;
    private final ObjectMapper objectMapper;

    /**
     * Performs the on application ready operation.
     */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    /**
     * Performs the refresh operation.
     */
    public synchronized void refresh() {
        LEGACY_PROTOCOL_TOOLS.forEach(toolName -> {
            try {
                mcpSyncServer.removeTool(toolName);
            } catch (Exception ex) {
                log.debug("API internal tool {} was not registered: {}", toolName, ex.getMessage());
            }
        });
        try { mcpSyncServer.removeTool(BRIDGE_TOOL_NAME); } catch (Exception ignored) { }
        McpToolPublicationReviewer.addReviewedTool(mcpSyncServer, bridgeTool());
        McpToolPublicationReviewer.addReviewedTool(mcpSyncServer, toolSpecFactory.toGatewayToolSpecification());
        mcpSyncServer.notifyToolsListChanged();
        log.info("Unified API discovery bridge published: {}; Runtime executor retained: {}",
            BRIDGE_TOOL_NAME, EXECUTE_TOOL_NAME);
    }

    private McpServerFeatures.SyncToolSpecification bridgeTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "The user's complete API business request"));
        properties.put("templateIds", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("excludeTemplateIds", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("filters", Map.of("type", "object", "additionalProperties", true,
            "description", "Optional logical discovery filters; raw URL and HTTP definitions are forbidden"));
        properties.put("purpose", Map.of("type", "string"));
        properties.put("sourceTaskId", Map.of("type", "string"));
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(BRIDGE_TOOL_NAME)
            .title("API 服务模板查询")
            .description("Return all authorized API template candidates for model review. This facade never executes templates; execution remains owned by api_template_execute and Agent Runtime batch governance.")
            .inputSchema(new McpSchema.JsonSchema("object", properties, List.of(), false, null, null))
            .meta(meta()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            return concurrencyManager.execute(BRIDGE_TOOL_NAME, "read_only", arguments, () -> callResult(bridge.query(arguments)));
        }).build();
    }

    private Map<String, Object> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", "api_service_query.v1");
        meta.put("assetType", "api_service");
        meta.put("runtime_action", "read_only");
        meta.put("runtimeAction", "read_only");
        meta.put("templateGoverned", true);
        meta.put("bridgeManaged", true);
        meta.put("executionTool", EXECUTE_TOOL_NAME);
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta(BRIDGE_TOOL_NAME, "read_only"));
        meta.put(ToolWorkflowContract.METADATA_KEY, ToolWorkflowContract.declaration(
            ToolWorkflowRole.TEMPLATE_DISCOVERY, "mcp.api-service-bridge.v1", "intent+filters"));
        meta.put(ToolProtocolDriverContract.METADATA_KEY, ToolProtocolDriverContract.of(
            "mcp.api-service-bridge.v1",
            List.of(
                "Call api_service_query with the complete intent and review every returned candidate semantically.",
                "Execute accepted candidates through api_template_execute. For multiple candidates use Agent Runtime's standard ordered batch envelope with one child call per template.",
                "Retrieval ranking is evidence, not semantic acceptance; the query bridge never executes or silently chooses one candidate."),
            List.of(
                "Preserve the selected templateId and evidence-backed parameters during retry.",
                "Never invent or pass raw URL, HTTP method, headers or body templates.",
                "Do not bypass the governed API bridge.")));
        return Map.copyOf(meta);
    }

    private McpSchema.CallToolResult callResult(ApiServiceBridge.Result result) {
        String text;
        try { text = objectMapper.writeValueAsString(result.body()); }
        catch (Exception ex) { text = String.valueOf(result.body()); }
        return McpSchema.CallToolResult.builder().addTextContent(text).structuredContent(result.body())
            .isError(result.error()).build();
    }
}
