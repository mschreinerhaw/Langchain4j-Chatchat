package com.chatchat.mcpserver.ops;

import com.chatchat.common.tool.ToolProtocolDriverContract;
import com.chatchat.mcpserver.routing.AssetDiscoveryService;
import com.chatchat.mcpserver.routing.AssetDiscoveryMcpToolPublisher;
import com.chatchat.mcpserver.routing.TargetKindRegistry;
import com.chatchat.mcpserver.tool.McpToolPublicationReviewer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes one read-only discovery bridge for all governed operations domains.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsCapabilityBridgePublisher {
    public static final String TOOL_NAME = "ops_capability_query";
    private static final List<String> INTERNAL_TOOLS = List.of(
            AssetDiscoveryMcpToolPublisher.SSH_ASSET_TOOL_NAME,
            AssetDiscoveryMcpToolPublisher.SQL_DATASOURCE_ASSET_TOOL_NAME,
            AssetDiscoveryMcpToolPublisher.LEGACY_SQL_DATASOURCE_ASSET_TOOL_NAME,
            AssetDiscoveryMcpToolPublisher.HTTP_ENDPOINT_ASSET_TOOL_NAME,
            AssetDiscoveryMcpToolPublisher.MICROSERVICE_ASSET_TOOL_NAME,
            TemplateDiscoveryMcpToolPublisher.SSH_TEMPLATE_TOOL_NAME,
            TemplateDiscoveryMcpToolPublisher.SQL_DATASOURCE_TEMPLATE_TOOL_NAME,
            TemplateDiscoveryMcpToolPublisher.LEGACY_SQL_DATASOURCE_TEMPLATE_TOOL_NAME,
            TemplateDiscoveryMcpToolPublisher.HTTP_ENDPOINT_TEMPLATE_TOOL_NAME,
            TemplateDiscoveryMcpToolPublisher.JMX_TEMPLATE_TOOL_NAME,
            TemplateDiscoveryMcpToolPublisher.DATABASE_QUERY_TEMPLATE_TOOL_NAME,
            HttpRequirementAnalysisMcpToolPublisher.TOOL_NAME);

    private final McpSyncServer server;
    private final AssetDiscoveryService assetDiscovery;
    private final CommandTemplateDiscoveryService templateDiscovery;
    private final TargetKindRegistry targetKinds;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        refresh();
    }

    public synchronized void refresh() {
        INTERNAL_TOOLS.forEach(this::remove);
        remove(TOOL_NAME);
        McpToolPublicationReviewer.addReviewedTool(server, specification());
        server.notifyToolsListChanged();
        log.info("Unified operations capability discovery bridge published: {}; internal discovery tools removed", TOOL_NAME);
    }

    private McpServerFeatures.SyncToolSpecification specification() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "Complete operations or data capability request"));
        properties.put("targetKind", Map.of("type", "string",
                "description", "Logical domain: host, database, http, java, or business_database_query"));
        properties.put("stage", Map.of("type", "string", "enum", List.of("template", "asset"),
                "description", "template by default; use asset only when logical target disambiguation is needed"));
        properties.put("filters", Map.of("type", "object", "additionalProperties", true,
                "description", "Logical filters only; hostnames, IPs, URLs and connection strings are forbidden"));
        properties.put("templateIds", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 20));
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .title("运维能力发现")
                .description("One read-only bridge for discovering governed operations assets and templates across host, database, HTTP and JMX domains. It returns logical routing metadata only and never executes a command or exposes concrete endpoints.")
                .inputSchema(new McpSchema.JsonSchema("object", properties, List.of(), false, null, null))
                .meta(meta()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            try {
                Map<String, Object> result = query(request.arguments());
                return McpSchema.CallToolResult.builder().addTextContent("Operations capability query completed")
                        .structuredContent(result).isError(false).build();
            } catch (Exception ex) {
                return McpSchema.CallToolResult.builder().addTextContent(ex.getMessage())
                        .structuredContent(Map.of("success", false, "status", "INVALID_REQUEST", "error", ex.getMessage()))
                        .isError(true).build();
            }
        }).build();
    }

    Map<String, Object> query(Map<String, Object> rawArguments) {
        Map<String, Object> arguments = rawArguments == null ? Map.of() : rawArguments;
        String targetKind = targetKinds.normalizeTargetKind(text(arguments.get("targetKind")));
        if (targetKind == null || targetKinds.assetTypeForTargetKind(targetKind) == null || "document".equals(targetKind)) {
            Map<String, Object> clarification = new LinkedHashMap<>();
            clarification.put("schemaVersion", "ops_capability_bridge_result.v1");
            clarification.put("success", true);
            clarification.put("status", "NEEDS_CLARIFICATION");
            clarification.put("requiresClarification", true);
            clarification.put("message", "Select the logical operations target kind");
            clarification.put("choices", targetKinds.allowedTargetKindsForTool("template_query"));
            return Map.copyOf(clarification);
        }
        Map<String, Object> normalized = new LinkedHashMap<>(arguments);
        Map<String, Object> filters = map(arguments.get("filters"));
        String query = text(arguments.get("query"));
        if (query != null) filters.putIfAbsent("intent", query);
        normalized.put("filters", filters);
        normalized.put("assetType", targetKinds.assetTypeForTargetKind(targetKind));
        normalized.put("finalDecision", targetKind);
        normalized.put("confidence", 1.0D);
        normalized.put("candidates", List.of(Map.of("targetKind", targetKind, "confidence", 1.0D)));
        normalized.put("trace", Map.of("source", TOOL_NAME, "bridgeManaged", true));
        boolean assetStage = "asset".equalsIgnoreCase(text(arguments.get("stage")));
        Map<String, Object> result = assetStage ? assetDiscovery.query(normalized) : templateDiscovery.query(normalized);
        result.put("bridgeManaged", true);
        result.put("bridgeTool", TOOL_NAME);
        result.put("stage", assetStage ? "asset" : "template");
        return result;
    }

    private Map<String, Object> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", "ops_capability_query.v1");
        meta.put("assetType", "operations");
        meta.put("runtime_action", "read_only");
        meta.put("runtimeAction", "read_only");
        meta.put("readOnly", true);
        meta.put("bridgeManaged", true);
        meta.put("executionTools", Map.of(
                "host", "linux_command_execute",
                "http", "http_request_execute",
                "java", "jmx_monitor_execute",
                "database", "data_query_run",
                "business_database_query", "data_query_run"));
        meta.put(ToolProtocolDriverContract.METADATA_KEY, ToolProtocolDriverContract.of(
                "mcp.ops-capability-bridge.v1",
                List.of(
                        "Call ops_capability_query once with the complete request and logical targetKind.",
                        "Use stage=template by default; use stage=asset only to disambiguate a logical target.",
                        "Execute only through the returned governed execution tool after selecting a returned templateId."),
                List.of(
                        "Never invent a target, asset id, template id or parameter value.",
                        "Never pass hostnames, IP addresses, URLs, credentials or connection strings.",
                        "Keep command, HTTP, JMX and database execution under their separate authorization and confirmation policies.")));
        return Map.copyOf(meta);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private void remove(String name) {
        try {
            server.removeTool(name);
        } catch (Exception ignored) {
        }
    }
}
