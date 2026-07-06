package com.chatchat.mcpserver.api;

import com.chatchat.mcpserver.routing.AssetDiscoveryService;
import com.chatchat.mcpserver.routing.AssetMetadataFactory;
import com.chatchat.mcpserver.routing.TargetKindRegistry;
import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class ApiAssetDiscoveryMcpToolPublisher {

    public static final String TOOL_NAME = "api_asset_query";
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;

    private final McpSyncServer mcpSyncServer;
    private final ApiServiceConfigService configService;
    private final LuceneMcpSearchService luceneSearchService;

    @Autowired
    public ApiAssetDiscoveryMcpToolPublisher(McpSyncServer mcpSyncServer,
                                             ApiServiceConfigService configService,
                                             LuceneMcpSearchService luceneSearchService) {
        this.mcpSyncServer = mcpSyncServer;
        this.configService = configService;
        this.luceneSearchService = luceneSearchService;
    }

    public ApiAssetDiscoveryMcpToolPublisher(McpSyncServer mcpSyncServer,
                                             ApiServiceConfigService configService) {
        this(mcpSyncServer, configService, null);
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        remove(TOOL_NAME);
        mcpSyncServer.addTool(apiAssetQueryTool());
        mcpSyncServer.notifyToolsListChanged();
        log.info("API asset discovery MCP tool refreshed: {}", TOOL_NAME);
    }

    private McpServerFeatures.SyncToolSpecification apiAssetQueryTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(TOOL_NAME)
            .title("API asset metadata discovery")
            .description("Read-only discovery tool for querying redacted API service asset metadata and routing hints. "
                + "It treats API services as managed assets and never exposes URL templates, headers, or body templates. "
                + "Use api_template_query to select the returned API templateId before execution planning.")
            .inputSchema(inputSchema())
            .meta(meta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                try {
                    Map<String, Object> result = query(request.arguments());
                    return McpSchema.CallToolResult.builder()
                        .addTextContent("API asset metadata query completed")
                        .structuredContent(result)
                        .isError(false)
                        .build();
                } catch (Exception ex) {
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(ex.getMessage())
                        .structuredContent(errorResult(ex.getMessage()))
                        .isError(true)
                        .build();
                }
            })
            .build();
    }

    Map<String, Object> query(Map<String, Object> arguments) {
        Map<String, Object> filters = filters(arguments);
        int limit = limit(arguments);
        List<String> terms = terms(filters);
        List<ApiServiceConfig> enabledConfigs = configService.listEnabled();
        List<ScoredApiAsset> matched = luceneMatched(enabledConfigs, terms, filters, limit);
        boolean luceneUsed = !matched.isEmpty() || luceneSearchService != null && luceneSearchService.enabled() && terms.isEmpty();
        if (matched.isEmpty()) {
            matched = fallbackMatched(enabledConfigs, terms, filters);
            luceneUsed = false;
        }
        List<Map<String, Object>> assets = matched.stream()
            .limit(limit)
            .map(item -> assetMetadata(item.config(), item.score()))
            .toList();
        return mapOf(
            "schemaVersion", AssetDiscoveryService.RESULT_SCHEMA_VERSION,
            "querySchemaVersion", AssetDiscoveryService.QUERY_SCHEMA_VERSION,
            "success", true,
            "routingPolicyVersion", AssetMetadataFactory.ROUTING_POLICY_VERSION,
            "targetKind", "api_service",
            "assetType", "api_service",
            "filtersSchemaVersion", TargetKindRegistry.FILTERS_SCHEMA_VERSION,
            "filters", filters,
            "limit", limit,
            "returnedCount", assets.size(),
            "possiblyTruncated", matched.size() > limit,
            "discoveryPolicy", mapOf(
                "readOnly", true,
                "redaction", "URL templates, headers, and body templates are never returned",
                "logicalIndex", "asset:api_service",
                "physicalIndex", "assets-api-service",
                "indexBackend", luceneUsed ? "lucene_typed_asset_index" : "registry_fallback"
            ),
            "queryIr", mapOf(
                "schemaVersion", "api_asset_query_ir.v1",
                "assetType", "api_service",
                "targetKind", "api_service",
                "logicalIndex", "asset:api_service",
                "physicalIndex", "assets-api-service",
                "terms", terms
            ),
            "assets", assets
        );
    }

    private McpSchema.JsonSchema inputSchema() {
        return new McpSchema.JsonSchema("object", mapOf(
            "schemaVersion", Map.of("type", "string", "description", AssetDiscoveryService.QUERY_SCHEMA_VERSION),
            "filtersSchemaVersion", Map.of("type", "string", "description", TargetKindRegistry.FILTERS_SCHEMA_VERSION),
            "filters", Map.of(
                "type", "object",
                "description", "Optional logical filters for API assets, such as businessGroup, groupName, assetName, toolName, name, service, target, labels, intent, bilingualIntent, intentZh, or intentEn. Raw URL, headers, and body templates are forbidden.",
                "additionalProperties", true
            ),
            "executionContext", Map.of(
                "type", "object",
                "description", "Alias for logical filters. Raw URL, headers, and body templates are forbidden.",
                "additionalProperties", true
            ),
            "limit", Map.of("type", "integer", "minimum", 1, "maximum", MAX_LIMIT)
        ), List.of("filters"), false, null, null);
    }

    private Map<String, Object> meta() {
        return mapOf(
            "schemaVersion", AssetDiscoveryService.QUERY_SCHEMA_VERSION,
            "kind", "typed_asset_discovery_tool",
            "runtime_action", "read_only",
            "runtimeAction", "read_only",
            "controlPlane", "discovery",
            "readOnly", true,
            "risk_level", "low",
            "riskLevel", "low",
            "targetKind", "api_service",
            "assetType", "api_service",
            "toolBoundary", mapOf(
                "toolName", TOOL_NAME,
                "forcedAssetType", "api_service",
                "forcedTargetKind", "api_service",
                "rejectCrossTypeRouting", true
            ),
            "confirmation", mapOf("default", "auto_execute", "allow_user_override", false),
            "routingProtocol", mapOf(
                "forcedTargetKind", "api_service",
                "forcedAssetType", "api_service",
                "filtersSchemaVersion", TargetKindRegistry.FILTERS_SCHEMA_VERSION
            ),
            "indexPolicy", mapOf(
                "logicalIndex", "asset:api_service",
                "physicalIndex", "assets-api-service",
                "indexBackend", "lucene_typed_asset_index",
                "filterField", "assetType",
                "isolatedByTool", true
            ),
            "resultShape", mapOf(
                "canonical", "assets[]",
                "assetNamePath", "assets[].asset.name",
                "apiTemplatePath", "assets[].capabilities.apiTemplates[].templateId"
            ),
            "forbiddenConcreteTargetFields", List.of("url", "urlTemplate", "headers", "headersJson", "body", "bodyTemplate")
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filters(Map<String, Object> arguments) {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (arguments == null || arguments.isEmpty()) {
            return filters;
        }
        Object rawFilters = arguments.get("filters");
        if (rawFilters instanceof Map<?, ?> map) {
            filters.putAll((Map<String, Object>) map);
        }
        Object context = firstValue(arguments, "executionContext", "mcpExecutionContext");
        if (context instanceof Map<?, ?> map) {
            filters.putAll((Map<String, Object>) map);
        }
        for (String key : List.of("assetName", "asset_name", "toolName", "name", "businessGroup", "business_group",
            "group", "groupName", "group_name", "groupDescription", "group_description", "service", "target",
            "labels", "intent", "bilingualIntent", "bilingualQuery", "intentZh", "intentEn")) {
            Object value = arguments.get(key);
            if (value != null) {
                filters.putIfAbsent(key, value);
            }
        }
        rejectRawApiFields(filters);
        return filters;
    }

    private List<ScoredApiAsset> luceneMatched(List<ApiServiceConfig> configs,
                                               List<String> terms,
                                               Map<String, Object> filters,
                                               int limit) {
        if (luceneSearchService == null || !luceneSearchService.enabled()) {
            return List.of();
        }
        Map<String, ApiServiceConfig> byId = new LinkedHashMap<>();
        for (ApiServiceConfig config : configs) {
            if (!text(config.getId()).isBlank()) {
                byId.put(config.getId(), config);
            }
        }
        List<LuceneMcpSearchService.SearchHit> hits = luceneSearchService.searchAssets(
            configs.stream().map(this::apiAssetDoc).toList(),
            new LuceneMcpSearchService.AssetSearchRequest(
                "api_service",
                queryText(terms, filters),
                null,
                null,
                List.of(),
                Math.min(MAX_LIMIT, Math.max(limit + 1, limit))
            )
        );
        return hits.stream()
            .map(hit -> {
                ApiServiceConfig config = byId.get(hit.id());
                return config == null ? null : new ScoredApiAsset(config, hit.score());
            })
            .filter(item -> item != null)
            .toList();
    }

    private List<ScoredApiAsset> fallbackMatched(List<ApiServiceConfig> configs,
                                                 List<String> terms,
                                                 Map<String, Object> filters) {
        return configs.stream()
            .map(config -> new ScoredApiAsset(config, score(config, terms, filters)))
            .filter(item -> terms.isEmpty() || item.score() > 0)
            .sorted(Comparator
                .comparingDouble(ScoredApiAsset::score)
                .reversed()
                .thenComparing(item -> text(item.config().getToolName())))
            .toList();
    }

    private LuceneMcpSearchService.AssetDoc apiAssetDoc(ApiServiceConfig config) {
        return new LuceneMcpSearchService.AssetDoc(
            config.getId(),
            "api_service",
            config.getToolName(),
            firstText(config.getTitle(), config.getToolName()),
            config.getToolName(),
            null,
            null,
            apiLabels(config),
            "api_service_asset_registry",
            null,
            null,
            null,
            null,
            String.join(" ", List.of(
                text(config.getDescription()),
                text(config.getBusinessGroup()),
                text(config.getBusinessGroupName()),
                text(config.getBusinessGroupDescription()),
                text(config.getMethod())
            )),
            null,
            null
        );
    }

    private String queryText(List<String> terms, Map<String, Object> filters) {
        if (terms != null && !terms.isEmpty()) {
            return String.join(" ", terms);
        }
        return text(firstValue(filters, "assetName", "asset_name", "toolName", "name"));
    }

    private void rejectRawApiFields(Map<String, Object> filters) {
        for (String field : List.of("url", "urlTemplate", "headers", "headersJson", "body", "bodyTemplate")) {
            Object value = filters.get(field);
            if (value != null && !String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("Raw API execution field is not allowed in api_asset_query: " + field);
            }
        }
    }

    private List<String> terms(Map<String, Object> filters) {
        List<String> terms = new ArrayList<>();
        for (String key : List.of("assetName", "asset_name", "toolName", "name", "businessGroup", "business_group",
            "group", "groupName", "group_name", "groupDescription", "group_description", "service", "target",
            "intent", "bilingualQuery", "intentZh", "intentEn")) {
            addTerm(terms, filters.get(key));
        }
        addTerm(terms, filters.get("bilingualIntent"));
        addTerm(terms, filters.get("labels"));
        return terms.stream()
            .map(this::normalize)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    private void addTerm(List<String> terms, Object value) {
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addTerm(terms, item);
            }
            return;
        }
        String text = text(value);
        if (!text.isBlank()) {
            terms.add(text);
        }
    }

    private double score(ApiServiceConfig config, List<String> terms, Map<String, Object> filters) {
        if (terms.isEmpty()) {
            return 1.0;
        }
        String haystack = normalize(String.join(" ",
            text(config.getToolName()),
            text(config.getTitle()),
            text(config.getDescription()),
            text(config.getBusinessGroup()),
            text(config.getBusinessGroupName()),
            text(config.getBusinessGroupDescription()),
            text(config.getMethod())
        ));
        long matched = terms.stream().filter(haystack::contains).count();
        double score = matched / (double) terms.size();
        String requestedName = normalize(firstValue(filters, "assetName", "asset_name", "toolName", "name"));
        if (!requestedName.isBlank() && normalize(config.getToolName()).equals(requestedName)) {
            score += 1.0;
        }
        return score;
    }

    private Map<String, Object> assetMetadata(ApiServiceConfig config, double score) {
        return mapOf(
            "schemaVersion", AssetMetadataFactory.SCHEMA_VERSION,
            "kind", "api_service_asset",
            "assetType", "api_service",
            "asset", mapOf(
                "id", config.getId(),
                "name", config.getToolName(),
                "displayName", firstText(config.getTitle(), config.getToolName()),
                "toolName", config.getToolName(),
                "businessGroup", businessGroupMetadata(config),
                "enabled", config.isEnabled()
            ),
            "capabilities", mapOf(
                "method", config.getMethod(),
                "apiTemplates", List.of(mapOf(
                    "templateId", config.getToolName(),
                    "name", firstText(config.getTitle(), config.getToolName()),
                    "description", text(config.getDescription()),
                    "businessGroup", businessGroupMetadata(config),
                    "source", "api_template_query.templates[].templateId"
                ))
            ),
            "routingHints", mapOf(
                "labels", apiLabels(config),
                "selectionPolicy", "Use api_template_query to select templates and do not invent API tool names.",
                "relevanceScore", score
            ),
            "redaction", mapOf(
                "urlTemplateReturned", false,
                "headersReturned", false,
                "bodyTemplateReturned", false
            )
        );
    }

    private Map<String, Object> businessGroupMetadata(ApiServiceConfig config) {
        String code = firstText(config.getBusinessGroup(), "default");
        return mapOf(
            "code", code,
            "name", firstText(config.getBusinessGroupName(), code),
            "description", firstText(config.getBusinessGroupDescription(), "")
        );
    }

    private List<String> apiLabels(ApiServiceConfig config) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        if (config.getMethod() != null && !config.getMethod().isBlank()) {
            labels.add("method:" + normalize(config.getMethod()));
        }
        addLabel(labels, config.getToolName());
        addLabel(labels, config.getTitle());
        addLabel(labels, config.getBusinessGroup());
        addLabel(labels, "group:" + config.getBusinessGroup());
        addLabel(labels, config.getBusinessGroupName());
        addLabel(labels, config.getBusinessGroupDescription());
        return labels.stream().toList();
    }

    private void addLabel(LinkedHashSet<String> labels, String value) {
        String normalized = normalize(value);
        if (!normalized.isBlank()) {
            labels.add(normalized);
        }
    }

    private Map<String, Object> errorResult(String message) {
        return mapOf(
            "schemaVersion", AssetDiscoveryService.RESULT_SCHEMA_VERSION,
            "querySchemaVersion", AssetDiscoveryService.QUERY_SCHEMA_VERSION,
            "success", false,
            "error", message,
            "errorDetail", mapOf("code", "API_ASSET_QUERY_REJECTED", "message", message)
        );
    }

    private int limit(Map<String, Object> arguments) {
        Object value = arguments == null ? null : firstValue(arguments, "limit", "maxResults");
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            return Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(String.valueOf(value))));
        } catch (NumberFormatException ex) {
            return DEFAULT_LIMIT;
        }
    }

    private Object firstValue(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String normalize(Object value) {
        return text(value).toLowerCase(Locale.ROOT);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void remove(String toolName) {
        try {
            mcpSyncServer.removeTool(toolName);
        } catch (Exception ex) {
            log.debug("API asset discovery MCP tool {} was not registered: {}", toolName, ex.getMessage());
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    private record ScoredApiAsset(ApiServiceConfig config, double score) {
    }
}
