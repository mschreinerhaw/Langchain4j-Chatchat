package com.chatchat.mcpserver.ops;

import com.chatchat.common.tool.ToolProtocolDriverContract;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.mcpserver.routing.AssetDiscoveryMcpToolPublisher;
import com.chatchat.mcpserver.routing.AssetDiscoveryService;
import com.chatchat.mcpserver.templatepublication.TemplateQueryMcpToolPublisher;
import com.chatchat.mcpserver.tool.McpToolPublicationReviewer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes domain-specific read-only discovery bridges. The Java implementation is shared, but
 * each MCP contract retains its own business meaning, authorization scope and execution tool.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsCapabilityBridgePublisher {
    public static final String LEGACY_TOOL_NAME = "ops_capability_query";
    public static final String SERVER_QUERY_TOOL = "server_capability_query";
    public static final String HTTP_QUERY_TOOL = "http_capability_query";
    public static final String JMX_QUERY_TOOL = "jmx_capability_query";
    public static final String DATABASE_QUERY_TOOL = "database_capability_query";

    private static final Domain SERVER = new Domain(SERVER_QUERY_TOOL, "Server operations capability query",
        "host", "ssh_host", "linux_command_execute", true, "mcp.server-capability-query.v1");
    private static final Domain HTTP = new Domain(HTTP_QUERY_TOOL, "HTTP capability query",
        "http", "http_endpoint", "http_request_execute", true, "mcp.http-capability-query.v1");
    private static final Domain JMX = new Domain(JMX_QUERY_TOOL, "Java/JMX monitoring capability query",
        "java", "jmx_endpoint", "jmx_monitor_execute", false, "mcp.jmx-capability-query.v1");
    private static final Domain DATABASE = new Domain(DATABASE_QUERY_TOOL, "Database operations capability query",
        "database", "sql_datasource", "sql_query_execute", true, "mcp.database-capability-query.v1");
    private static final List<Domain> DOMAINS = List.of(SERVER, HTTP, JMX, DATABASE);

    private static final List<String> INTERNAL_DISCOVERY_TOOLS = List.of(
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
        TemplateDiscoveryMcpToolPublisher.DATABASE_QUERY_TEMPLATE_TOOL_NAME);

    private final McpSyncServer server;
    private final AssetDiscoveryService assetDiscovery;
    private final CommandTemplateDiscoveryService templateDiscovery;
    private TemplateQueryMcpToolPublisher dynamicTemplateQueries;

    @Autowired
    void configureDynamicTemplateQueries(TemplateQueryMcpToolPublisher dynamicTemplateQueries) {
        this.dynamicTemplateQueries = dynamicTemplateQueries;
    }
    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        refresh();
    }

    public synchronized void refresh() {
        INTERNAL_DISCOVERY_TOOLS.forEach(this::remove);
        remove(LEGACY_TOOL_NAME);
        DOMAINS.forEach(domain -> {
            remove(domain.toolName());
            McpToolPublicationReviewer.addReviewedTool(server, specification(domain));
        });
        server.notifyToolsListChanged();
        log.info("Domain capability queries published: {}; generic operations bridge removed",
            DOMAINS.stream().map(Domain::toolName).toList());
    }

    private McpServerFeatures.SyncToolSpecification specification(Domain domain) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "Complete " + domain.title() + " request"));
        properties.put("stage", Map.of("type", "string",
            "enum", domain.assetDiscoverySupported() ? List.of("template", "asset") : List.of("template"),
            "description", "template by default" + (domain.assetDiscoverySupported()
                ? "; use asset only to disambiguate a logical target" : "")));
        properties.put("filters", Map.of("type", "object", "additionalProperties", true,
            "description", "Logical domain filters only; concrete endpoints and credentials are forbidden"));
        properties.put("templateIds", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 20));
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(domain.toolName())
            .title(domain.title())
            .description(description(domain))
            .inputSchema(new McpSchema.JsonSchema("object", properties, List.of(), false, null, null))
            .meta(meta(domain)).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            try {
                Map<String, Object> result = query(domain.toolName(), request.arguments());
                return McpSchema.CallToolResult.builder().addTextContent(domain.title() + " completed")
                    .structuredContent(result).isError(false).build();
            } catch (Exception ex) {
                return McpSchema.CallToolResult.builder().addTextContent(ex.getMessage())
                    .structuredContent(Map.of("success", false, "status", "INVALID_REQUEST", "error", ex.getMessage()))
                    .isError(true).build();
            }
        }).build();
    }

    Map<String, Object> query(String toolName, Map<String, Object> rawArguments) {
        Domain domain = domain(toolName);
        Map<String, Object> arguments = rawArguments == null ? Map.of() : rawArguments;
        boolean assetStage = "asset".equalsIgnoreCase(text(arguments.get("stage")));
        if (assetStage && !domain.assetDiscoverySupported()) {
            throw new IllegalArgumentException(domain.toolName() + " supports template discovery only");
        }
        String childToolName = TemplateQueryMcpToolPublisher.childToolName(arguments);
        if (!childToolName.isBlank() && assetStage) {
            throw new IllegalArgumentException("Custom template queries support template discovery only");
        }
        Map<String, Object> normalized = new LinkedHashMap<>(arguments);
        normalized.remove("targetKind");
        normalized.remove("target_kind");
        normalized.remove("assetType");
        Map<String, Object> filters = map(arguments.get("filters"));
        String query = text(arguments.get("query"));
        if (query != null) filters.putIfAbsent("intent", query);
        normalized.put("filters", filters);
        normalized.put("assetType", domain.assetType());
        normalized.put("finalDecision", domain.targetKind());
        normalized.put("confidence", 1.0D);
        normalized.put("candidates", List.of(Map.of("targetKind", domain.targetKind(), "confidence", 1.0D)));
        normalized.put("trace", Map.of("source", domain.toolName(), "bridgeManaged", true));
        Map<String, Object> discovered;
        if (!childToolName.isBlank()) {
            discovered = requireDynamicTemplateQueries().queryFromParent(
                childToolName, persistedParent(domain), normalized);
        } else {
            discovered = assetStage ? assetDiscovery.query(normalized) : templateDiscovery.query(normalized);
        }
        Map<String, Object> result = new LinkedHashMap<>(discovered == null ? Map.of() : discovered);
        result.put("bridgeManaged", true);
        result.put("bridgeTool", domain.toolName());
        result.put("businessDomain", domain.targetKind());
        result.put("assetType", domain.assetType());
        result.put("stage", assetStage ? "asset" : "template");
        result.put("executionTool", domain.executionTool());
        return result;
    }

    private TemplateQueryMcpToolPublisher requireDynamicTemplateQueries() {
        if (dynamicTemplateQueries == null) {
            throw new IllegalStateException("Dynamic template query routing is unavailable");
        }
        return dynamicTemplateQueries;
    }

    private String persistedParent(Domain domain) {
        return switch (domain.toolName()) {
            case SERVER_QUERY_TOOL -> TemplateDiscoveryMcpToolPublisher.SSH_TEMPLATE_TOOL_NAME;
            case HTTP_QUERY_TOOL -> TemplateDiscoveryMcpToolPublisher.HTTP_ENDPOINT_TEMPLATE_TOOL_NAME;
            case DATABASE_QUERY_TOOL -> TemplateDiscoveryMcpToolPublisher.SQL_DATASOURCE_TEMPLATE_TOOL_NAME;
            default -> throw new IllegalArgumentException(
                domain.toolName() + " does not support custom template-query publication");
        };
    }

    private Domain domain(String toolName) {
        return DOMAINS.stream().filter(item -> item.toolName().equals(toolName)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported capability query tool: " + toolName));
    }

    private String description(Domain domain) {
        return "Read-only discovery for the " + domain.targetKind() + " domain only. It returns governed assets or "
            + "template candidates and never executes them. Accepted templates execute only through "
            + domain.executionTool() + ". Other business domains require their own capability query tool.";
    }

    private Map<String, Object> meta(Domain domain) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", domain.toolName() + ".v1");
        meta.put("assetType", domain.assetType());
        meta.put("businessDomain", domain.targetKind());
        meta.put("runtime_action", "read_only");
        meta.put("runtimeAction", "read_only");
        meta.put("readOnly", true);
        meta.put("bridgeManaged", true);
        meta.put("executionTool", domain.executionTool());
        meta.put(ToolWorkflowContract.METADATA_KEY, ToolWorkflowContract.declaration(
            ToolWorkflowRole.TEMPLATE_DISCOVERY, domain.protocolId(), "intent+filters"));
        meta.put(ToolProtocolDriverContract.METADATA_KEY, ToolProtocolDriverContract.of(
            domain.protocolId(),
            List.of(
                "Call " + domain.toolName() + " only for " + domain.targetKind() + " requests.",
                "Review all returned candidates; retrieval rank is not semantic acceptance.",
                "Execute accepted templates only through " + domain.executionTool() + " under Agent Runtime governance."),
            List.of(
                "Never route a different business domain through this tool.",
                "Never invent an asset id, template id or parameter value.",
                "Never pass concrete endpoints, credentials or connection strings.")));
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

    private record Domain(String toolName, String title, String targetKind, String assetType,
                          String executionTool, boolean assetDiscoverySupported, String protocolId) {
    }
}
