package com.chatchat.mcpserver.ops.discovery;

import com.chatchat.common.tool.ToolProtocolDriverContract;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.mcpserver.routing.asset.AssetDiscoveryMcpToolPublisher;
import com.chatchat.mcpserver.routing.asset.AssetDiscoveryService;
import com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Publishes domain-specific read-only discovery bridges. The Java implementation is shared, but
 * each MCP contract retains its own business meaning, authorization scope and execution tool.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsCapabilityBridgePublisher implements com.chatchat.mcpserver.tool.McpToolContributor {
    public static final String LEGACY_TOOL_NAME = "ops_capability_query";
    public static final String SERVER_QUERY_TOOL = "server_capability_query";
    public static final String HTTP_QUERY_TOOL = "http_capability_query";
    public static final String JMX_QUERY_TOOL = "jmx_capability_query";
    public static final String DATABASE_QUERY_TOOL = "database_capability_query";

    private static final Domain SERVER = new Domain(SERVER_QUERY_TOOL, "Server operations capability query",
        "host", "ssh_host", "linux_command_execute", true,
        "mcp.server-capability-query.v1", "mcp.ssh-template.v1");
    private static final Domain HTTP = new Domain(HTTP_QUERY_TOOL, "HTTP capability query",
        "http", "http_endpoint", "http_request_execute", true,
        "mcp.http-capability-query.v1", "mcp.http-template.v1");
    private static final Domain JMX = new Domain(JMX_QUERY_TOOL, "Java/JMX monitoring capability query",
        "java", "jmx_endpoint", "jmx_monitor_execute", true,
        "mcp.jmx-capability-query.v1", "mcp.jmx-template.v1");
    private static final Domain DATABASE = new Domain(DATABASE_QUERY_TOOL, "Database operations capability query",
        "database", "sql_datasource", "sql_query_execute", true,
        "mcp.database-capability-query.v1", "mcp.sql-template.v1");
    private static final List<Domain> DOMAINS = List.of(SERVER, HTTP, JMX, DATABASE);

    private static final List<String> INTERNAL_DISCOVERY_TOOLS = List.of(
        AssetDiscoveryMcpToolPublisher.SSH_ASSET_TOOL_NAME,
        AssetDiscoveryMcpToolPublisher.SQL_DATASOURCE_ASSET_TOOL_NAME,
        AssetDiscoveryMcpToolPublisher.LEGACY_SQL_DATASOURCE_ASSET_TOOL_NAME,
        AssetDiscoveryMcpToolPublisher.HTTP_ENDPOINT_ASSET_TOOL_NAME,
        AssetDiscoveryMcpToolPublisher.MICROSERVICE_ASSET_TOOL_NAME,
        TemplateDiscoveryMcpToolPublisher.LEGACY_SQL_DATASOURCE_TEMPLATE_TOOL_NAME,
        TemplateDiscoveryMcpToolPublisher.JMX_TEMPLATE_TOOL_NAME);

    private final McpSyncServer server;
    private final AssetDiscoveryService assetDiscovery;
    private final CommandTemplateDiscoveryService templateDiscovery;
    private TemplateQueryMcpToolPublisher dynamicTemplateQueries;
    private com.chatchat.common.retrieval.query.QueryOptimizationBudget queryBudget =
        new com.chatchat.common.retrieval.query.QueryOptimizationBudget();

    @Autowired(required = false)
    void configureQueryBudget(com.chatchat.common.retrieval.query.SearchQueryOptimizer optimizer) {
        this.queryBudget = optimizer.budget();
    }

    @Autowired
    void configureDynamicTemplateQueries(TemplateQueryMcpToolPublisher dynamicTemplateQueries) {
        this.dynamicTemplateQueries = dynamicTemplateQueries;
    }
    public synchronized void refresh() {
        refreshPublication();
        log.info("Domain capability queries published: {}; generic operations bridge removed",
            DOMAINS.stream().map(Domain::toolName).toList());
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.chatchat.mcpserver.authorization.McpAuthorizationService catalogAuthorization;

    @Override public String contributorId() { return "operations_discovery"; }
    @Override public McpSyncServer publicationServer() { return server; }
    @Override public List<com.chatchat.mcpserver.tool.ToolPublication> contribute() {
        return DOMAINS.stream().map(this::specification)
            .map(com.chatchat.mcpserver.tool.ToolPublication::from).toList();
    }
    @Override public Set<String> retiredToolNames() {
        java.util.LinkedHashSet<String> retired = new java.util.LinkedHashSet<>(INTERNAL_DISCOVERY_TOOLS);
        retired.add(LEGACY_TOOL_NAME);
        return Set.copyOf(retired);
    }

    private McpServerFeatures.SyncToolSpecification specification(Domain domain) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description",
            "Concise search keywords or a short capability phrase; never the full task or report instructions. "
                + "For multiple concepts use filters.queryTerms as independent retrieval units."));
        properties.put("stage", Map.of("type", "string",
            "enum", domain.assetDiscoverySupported() ? List.of("template", "asset") : List.of("template"),
            "description", "template by default" + (domain.assetDiscoverySupported()
                ? "; use asset only to disambiguate a logical target" : "")));
        properties.put("filters", new com.chatchat.mcpserver.routing.target.TargetKindRegistry()
            .filterInputSchema(domain.targetKind(), "template_query"));
        properties.put("templateIds", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 20));
        if (domain.assetDiscoverySupported()) {
            properties.put("catalogMode", Map.of("type", "string", "enum", List.of("AUTHORIZED_CATALOG")));
            properties.put("pageToken", Map.of("type", "string"));
        }
        properties.put("cursor", Map.of("type", "string", "description",
            "Opaque nextCursor from the immediately preceding template page of the identical query; preserve its filters and scope."));
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
            } catch (SecurityException denied) {
                return McpSchema.CallToolResult.builder().addTextContent("Asset catalog authorization denied")
                    .structuredContent(Map.of("success", false, "status", "AUTHORIZATION_DENIED",
                        "errorCode", "AUTHORIZATION_DENIED")).isError(true).build();
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
        // query is the bridge's natural-language envelope, not a target filter field. Keeping it
        // at the top level makes the typed asset service reject an otherwise valid asset-stage
        // request because concrete discovery filters are deliberately schema constrained.
        normalized.remove("query");
        normalized.remove("targetKind");
        normalized.remove("target_kind");
        normalized.remove("assetType");
        Map<String, Object> filters = map(arguments.get("filters"));
        String query = text(arguments.get("query"));
        if (query != null) {
            filters.putIfAbsent("intent", query);
        }
        normalized.put("filters", filters);
        normalized.put("assetType", domain.assetType());
        normalized.put("finalDecision", domain.targetKind());
        normalized.put("confidence", 1.0D);
        normalized.put("candidates", List.of(Map.of("targetKind", domain.targetKind(), "confidence", 1.0D)));
        normalized.put("trace", Map.of("source", domain.toolName(), "bridgeManaged", true));
        // A domain bridge feeds Runtime-owned candidate review, not a user-facing search page.
        // Always expose the complete bounded review window so a small model-authored limit cannot
        // hide a second required capability and collapse a multi-check request into templates[0].
        // Exact templateIds remain a registry-governed filter; this only widens candidates up to
        // the discovery service's published hard maximum.
        if (!assetStage && !normalized.containsKey("templateIds")) {
            normalized.put("limit", CommandTemplateDiscoveryService.MAX_LIMIT);
        }
        boolean catalog = "AUTHORIZED_CATALOG".equals(arguments.get("catalogMode"));
        boolean selectedIds = filters.get("assetIds") instanceof java.util.Collection<?> ids && !ids.isEmpty();
        boolean requiresAssetConfirmation = !assetStage && childToolName.isBlank()
            && domain.assetDiscoverySupported() && !normalized.containsKey("templateIds")
            && (catalog ? !selectedIds : !hasExplicitAssetIdentity(filters) && hasAssetDiscoveryIntent(filters, query));
        if (catalog && (assetStage || requiresAssetConfirmation)) {
            var allowed = catalogAuthorization.requireAssetCatalogAuthorization(domain.executionTool(), domain.assetType());
            var result = bridgeResult(domain,
                assetDiscovery.catalog(domain.assetType(), domain.executionTool(), normalized, allowed),
                assetStage ? "asset" : "asset_selection");
            if (requiresAssetConfirmation) {
                result.put("templates", List.of());
                result.put("nextStage", "template");
                result.put("assetSelectionRequired", true);
            }
            return result;
        }
        if (requiresAssetConfirmation) {
            normalized.put("limit", 10);
            Map<String, Object> result = bridgeResult(domain, assetDiscovery.query(normalized), "asset_selection");
            int count = result.get("assets") instanceof List<?> assets ? assets.size() : 0;
            result.put("assetReturnedCount", count);
            result.put("templates", List.of());
            result.put("returnedCount", 0);
            if (count > 0) {
                result.put("nextStage", "template");
                result.put("assetSelectionRequired", true);
            } else {
                result.put("assetNotFound", true);
            }
            return result;
        }
        if (!assetStage && childToolName.isBlank() && "AUTHORIZED_CATALOG".equals(arguments.get("catalogMode"))
                && filters.get("assetIds") instanceof java.util.Collection<?> selected && !selected.isEmpty()) {
            var allowed = catalogAuthorization.requireAssetCatalogAuthorization(domain.executionTool(), domain.assetType());
            assetDiscovery.requireCatalogSelection(domain.assetType(), normalized, selected, allowed);
        }
        if (query != null) filters.putIfAbsent("queryTerms", List.of(query));
        Map<String, Object> discovered;
        if (!childToolName.isBlank()) {
            discovered = requireDynamicTemplateQueries().queryFromParent(
                childToolName, domain.toolName(), normalized);
        } else {
            discovered = assetStage ? assetDiscovery.query(normalized) : templateDiscovery.query(normalized);
        }
        Map<String, Object> result = bridgeResult(domain, discovered, assetStage ? "asset" : "template");
        if (!assetStage && !normalized.containsKey("templateIds")) {
            result.put("candidateWindowPolicy", Map.of(
                "mode", "FULL_BOUNDED_REVIEW_WINDOW",
                "limit", CommandTemplateDiscoveryService.MAX_LIMIT,
                "runtimeOwned", true
            ));
        }
        return result;
    }

    private Map<String, Object> bridgeResult(Domain domain, Map<String, Object> discovered, String stage) {
        Map<String, Object> result = new LinkedHashMap<>(discovered == null ? Map.of() : discovered);
        result.put("bridgeManaged", true);
        result.put("bridgeTool", domain.toolName());
        result.put("businessDomain", domain.targetKind());
        result.put("assetType", domain.assetType());
        result.put("stage", stage);
        result.put("executionTool", domain.executionTool());
        return result;
    }

    private boolean hasExplicitAssetIdentity(Map<String, Object> filters) {
        if (filters == null) return false;
        if (filters.get("assetIds") instanceof java.util.Collection<?> ids && !ids.isEmpty()) return true;
        return text(first(filters, "assetName", "asset_name", "assetId", "asset_id", "name")) != null;
    }

    private boolean hasAssetDiscoveryIntent(Map<String, Object> filters, String query) {
        if (text(query) != null) return true;
        return filters != null && first(filters, "queryTerms", "retrievalSignals", "keywords", "intent") != null;
    }

    private Object first(Map<String, Object> values, String... keys) {
        if (values == null) return null;
        for (String key : keys) if (values.containsKey(key)) return values.get(key);
        return null;
    }

    private TemplateQueryMcpToolPublisher requireDynamicTemplateQueries() {
        if (dynamicTemplateQueries == null) {
            throw new IllegalStateException("Dynamic template query routing is unavailable");
        }
        return dynamicTemplateQueries;
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
        if (domain.assetDiscoverySupported()) meta.put("authorizedAssetCatalog", true);
        if (JMX.equals(domain)) meta.put("catalogBeforePublishedSupply", true);
        meta.put("executionTool", domain.executionTool());
        meta.put("modelInputBridgeContract", Map.of(
            "contractVersion", "model_assisted_retrieval.v1", "mode", "DISCOVERY_QUERY_PROFILE",
            "contextPaths", List.of("query", "filters.intent", "filters.goal", "filters.queryTerms", "filters.keywords"),
            "allowedArgumentPaths", List.of("query", "filters.queryTerms", "filters.keywords", "filters.retrievalSignals", "filters.intent", "filters.goal"),
            "mergeModes", Map.of("query", "replace", "filters.queryTerms", "replace", "filters.keywords", "replace",
                "filters.retrievalSignals", "replace", "filters.intent", "replace", "filters.goal", "replace"),
            "guidance", "The model selects concise search units for published asset/template discovery. "
                + "All distinct independent query units, including exact-target filters and every keyword/alias channel, "
                + "must fit maximumBatches=" + queryBudget.getMaxBatches() + ". Prefer one filters.queryTerms channel; "
                + "avoid repeating the same intent across additional alias channels. "
                + "Return query and filters.queryTerms; replace any task-sized intent, goal, keywords or retrievalSignals "
                + "with short retrieval phrases. Preserve exact names from evidence without inventing routing identities. "
                + "Exclude execution constraints, output formats and report/evidence-assessment instructions. "
                + "Do not answer the analysis question. No retrieval quality gate applies."));
        meta.put(ToolWorkflowContract.METADATA_KEY, ToolWorkflowContract.declaration(
            ToolWorkflowRole.TEMPLATE_DISCOVERY, domain.workflowFamily(), "intent+filters"));
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

    private record Domain(String toolName, String title, String targetKind, String assetType,
                          String executionTool, boolean assetDiscoverySupported, String protocolId,
                          String workflowFamily) {
    }
}
