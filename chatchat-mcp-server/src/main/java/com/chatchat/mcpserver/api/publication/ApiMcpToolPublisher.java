package com.chatchat.mcpserver.api.publication;

import com.chatchat.common.tool.ToolProtocolDriverContract;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class ApiMcpToolPublisher implements com.chatchat.mcpserver.tool.McpToolContributor {

    public static final String BRIDGE_TOOL_NAME = "api_service_query";
    /** @deprecated internal protocol name retained only for compatibility metadata and cleanup. */
    @Deprecated
    public static final String EXECUTE_TOOL_NAME = "api_template_execute";
    static final List<String> LEGACY_PROTOCOL_TOOLS = List.of(
        "api_asset_query", "api_requirement_analyze", EXECUTE_TOOL_NAME);

    private final McpSyncServer mcpSyncServer;
    private final ApiAssetDiscoveryMcpToolPublisher assetDiscovery;
    private final ApiToolSpecFactory toolSpecFactory;
    private final McpToolConcurrencyManager concurrencyManager;
    private final ObjectMapper objectMapper;

    public ApiMcpToolPublisher(McpSyncServer mcpSyncServer,
                               ApiAssetDiscoveryMcpToolPublisher assetDiscovery,
                               ApiToolSpecFactory toolSpecFactory,
                               McpToolConcurrencyManager concurrencyManager,
                               ObjectMapper objectMapper) {
        this.mcpSyncServer = mcpSyncServer;
        this.assetDiscovery = assetDiscovery;
        this.toolSpecFactory = toolSpecFactory;
        this.concurrencyManager = concurrencyManager;
        this.objectMapper = objectMapper;
    }

    /**
     * Performs the on application ready operation.
     */
    /**
     * Performs the refresh operation.
     */
    public synchronized void refresh() {
        refreshPublication();
        log.info("Unified API discovery bridge published: {}; Runtime executor retained: {}",
            BRIDGE_TOOL_NAME, EXECUTE_TOOL_NAME);
    }

    @Override public String contributorId() { return "api"; }
    @Override public McpSyncServer publicationServer() { return mcpSyncServer; }
    @Override public List<com.chatchat.mcpserver.tool.ToolPublication> contribute() {
        return List.of(com.chatchat.mcpserver.tool.ToolPublication.from(bridgeTool()),
            com.chatchat.mcpserver.tool.ToolPublication.from(toolSpecFactory.toGatewayToolSpecification()));
    }
    @Override public Set<String> retiredToolNames() {
        java.util.LinkedHashSet<String> retired = new java.util.LinkedHashSet<>(LEGACY_PROTOCOL_TOOLS);
        return Set.copyOf(retired);
    }

    private McpServerFeatures.SyncToolSpecification bridgeTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string",
            "description", "The user's complete API service discovery request"));
        properties.put("filters", Map.of("type", "object", "additionalProperties", true,
            "description", "Optional logical filters for API service assets; raw URL and HTTP definitions are forbidden"));
        properties.put("executionContext", Map.of("type", "object", "additionalProperties", true));
        properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 20));
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(BRIDGE_TOOL_NAME)
            .title("API 服务资产查询")
            .description("Query redacted API service assets and their routing metadata. "
                + "This tool returns services, not template candidates; use a template_query capability for templates.")
            .inputSchema(new McpSchema.JsonSchema("object", properties, List.of(), false, null, null))
            .meta(meta()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            return concurrencyManager.execute(BRIDGE_TOOL_NAME, "discovery", arguments,
                () -> dynamicQueryResult(assetDiscovery.query(arguments)));
        }).build();
    }

    private McpSchema.CallToolResult dynamicQueryResult(Map<String, Object> body) {
        String text;
        try { text = objectMapper.writeValueAsString(body); }
        catch (Exception ex) { text = String.valueOf(body); }
        return McpSchema.CallToolResult.builder().addTextContent(text).structuredContent(body)
            .isError(false).build();
    }

    private Map<String, Object> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", "api_service_query.v1");
        meta.put("resultKind", "RAW_RECORDS");
        meta.put("resultSchemaRef", "asset_query_result.v1");
        meta.put("assetType", "api_service");
        meta.put("targetKind", "api_service");
        meta.put("resultEntityKind", "service_asset");
        meta.put("runtime_action", "read_only");
        meta.put("runtimeAction", "read_only");
        meta.put("runtime_level", "discovery");
        meta.put("runtimeLevel", "discovery");
        meta.put("templateGoverned", false);
        meta.put("bridgeManaged", true);
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta(BRIDGE_TOOL_NAME, "discovery"));
        meta.put(ToolWorkflowContract.METADATA_KEY, ToolWorkflowContract.declaration(
            ToolWorkflowRole.ASSET_DISCOVERY, "mcp.api-service-asset.v1", "filters", "service_asset"));
        meta.put(ToolProtocolDriverContract.METADATA_KEY, ToolProtocolDriverContract.of(
            "mcp.api-service-asset.v1",
            List.of(
                "Call api_service_query only when API service asset discovery is required.",
                "Treat assets[] as service assets, never as template candidates.",
                "Use a declared template_query capability when template selection is required."),
            List.of(
                "Never invent or pass raw URL, HTTP method, headers or body templates.",
                "Do not reinterpret service assets as executable templates.")));
        return Map.copyOf(meta);
    }
}
