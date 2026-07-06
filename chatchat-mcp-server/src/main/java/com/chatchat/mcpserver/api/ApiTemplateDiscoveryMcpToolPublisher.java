package com.chatchat.mcpserver.api;

import com.chatchat.mcpserver.ops.CommandTemplateDiscoveryService;
import com.chatchat.mcpserver.routing.TargetKindRegistry;
import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiTemplateDiscoveryMcpToolPublisher {

    public static final String TOOL_NAME = "api_template_query";
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;

    private final McpSyncServer mcpSyncServer;
    private final ApiServiceConfigService configService;
    private final LuceneMcpSearchService luceneSearchService;
    private final ObjectMapper objectMapper;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        remove(TOOL_NAME);
        mcpSyncServer.addTool(apiTemplateQueryTool());
        mcpSyncServer.notifyToolsListChanged();
        log.info("API template discovery MCP tool refreshed: {}", TOOL_NAME);
    }

    private McpServerFeatures.SyncToolSpecification apiTemplateQueryTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(TOOL_NAME)
            .title("API template discovery")
            .description("Read-only MCP tool for retrieving API service templates. "
                + "Use it to discover authorized API business capabilities by toolName, title, description, intent, category, or bilingual Chinese and English retrieval terms. "
                + "It returns API template metadata and parameter schema, but never returns raw URL templates, headers, or body templates.")
            .inputSchema(inputSchema())
            .meta(meta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                try {
                    Map<String, Object> result = query(request.arguments());
                    return McpSchema.CallToolResult.builder()
                        .addTextContent("API template query completed")
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
        List<ApiServiceConfig> enabledConfigs = configService.listEnabled();
        List<ApiServiceConfig> scopedConfigs = scopedApiServices(enabledConfigs, filters);
        List<String> assetSignals = apiServiceSignals(scopedConfigs);
        Map<String, Object> retrievalFilters = assetSignals.isEmpty()
            ? filters
            : filtersWithApiAssetSignals(filters, assetSignals);
        List<String> terms = terms(retrievalFilters);
        List<LuceneMcpSearchService.SearchHit> hits = luceneSearchService == null || !luceneSearchService.enabled()
            ? List.of()
            : luceneSearchService.searchApiServiceTemplates(new LuceneMcpSearchService.TemplateSearchRequest(
                "api_service",
                null,
                terms.isEmpty() ? null : String.join(" ", terms),
                Math.max(limit, DEFAULT_LIMIT)
        ));
        Map<String, ApiServiceConfig> configsByToolName = enabledConfigs.stream()
            .filter(config -> !text(config.getToolName()).isBlank())
            .collect(Collectors.toMap(
                config -> text(config.getToolName()),
                Function.identity(),
                (first, ignored) -> first,
                LinkedHashMap::new
            ));
        List<ScoredApiTemplate> matched = hits.stream()
            .map(hit -> apiTemplateHit(configsByToolName, hit))
            .filter(item -> item != null)
            .toList();
        List<Map<String, Object>> templates = matched.stream()
            .limit(limit)
            .map(item -> templateMetadata(item.config(), item.score()))
            .toList();
        return mapOf(
            "schemaVersion", CommandTemplateDiscoveryService.RESULT_SCHEMA_VERSION,
            "querySchemaVersion", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION,
            "success", true,
            "targetKind", "api_service",
            "assetType", "api_service",
            "filtersSchemaVersion", TargetKindRegistry.FILTERS_SCHEMA_VERSION,
            "filters", filters,
            "limit", limit,
            "returnedCount", templates.size(),
            "possiblyTruncated", matched.size() > limit,
            "templateSelectionPolicy", mapOf(
                "templateIdSource", "templates[].templateId",
                "mustUseReturnedTemplateId", true,
                "doNotInventTemplateNames", true,
                "rawExecutionSpecReturned", false,
                "selectionFields", List.of("templateId", "toolName", "title", "description", "businessGroup", "parameterSchema", "requiredParameters", "parameterContract", "invocationExample"),
                "onEmptyResult", "No existing API template matched the request. Do not invent an API tool name."
            ),
            "queryIr", mapOf(
                "schemaVersion", "api_template_query_ir.v1",
                "assetType", "api_service",
                "targetKind", "api_service",
                "indexType", "api_service_template",
                "terms", terms
            ),
            "diagnostics", mapOf(
                "source", "lucene_api_service_template_index",
                "hitCount", hits.size(),
                "hitIds", hits.stream().map(LuceneMcpSearchService.SearchHit::id).limit(limit).toList(),
                "assetScoped", !scopedConfigs.isEmpty(),
                "scopedAssetCount", scopedConfigs.size(),
                "retrievalSignals", assetSignals,
                "fallbackUsed", false
            ),
            "templates", templates
        );
    }

    private McpSchema.JsonSchema inputSchema() {
        return new McpSchema.JsonSchema("object", mapOf(
            "schemaVersion", Map.of("type", "string", "description", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION),
            "filtersSchemaVersion", Map.of("type", "string", "description", TargetKindRegistry.FILTERS_SCHEMA_VERSION),
            "filters", Map.of(
                "type", "object",
                "description", "Logical filters for API templates, such as businessGroup, groupName, intent, bilingualIntent, intentZh, intentEn, toolName, service, category, labels, language, or queryLanguage. Raw URL, headers, and body templates are not accepted.",
                "additionalProperties", true
            ),
            "bilingualIntent", Map.of(
                "type", "array",
                "description", "Model-generated bilingual retrieval terms. Include both Chinese and English phrases.",
                "items", Map.of("type", "string")
            ),
            "bilingualQuery", Map.of("type", "string", "description", "Alias for model-generated bilingual retrieval text."),
            "intentZh", Map.of("type", "string", "description", "Chinese retrieval phrase generated by the model for API template matching."),
            "intentEn", Map.of("type", "string", "description", "English retrieval phrase generated by the model for API template matching."),
            "executionContext", Map.of(
                "type", "object",
                "description", "Alias for logical filters. Raw URL, headers, and body templates are not accepted.",
                "additionalProperties", true
            ),
            "trace", Map.of(
                "type", "object",
                "description", "Replay trace such as plannerVersion, model, promptVersion, or taskId.",
                "additionalProperties", true
            ),
            "limit", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", MAX_LIMIT,
                "description", "Maximum number of templates returned; capped at 20."
            )
        ), List.of("filters"), false, null, null);
    }

    private Map<String, Object> meta() {
        return mapOf(
            "schemaVersion", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION,
            "kind", "api_template_discovery_tool",
            "runtime_action", "read_only",
            "runtimeAction", "read_only",
            "controlPlane", "discovery",
            "readOnly", true,
            "risk_level", "low",
            "riskLevel", "low",
            "targetKind", "api_service",
            "assetType", "api_service",
            "confirmation", mapOf("default", "auto_execute", "allow_user_override", false),
            "resultShape", mapOf(
                "canonical", "templates[]",
                "templateIdPath", "templates[].templateId",
                "queryIrPath", "queryIr"
            ),
            "languageSupport", mapOf(
                "mode", "bilingual",
                "languages", List.of("zh", "en"),
                "modelMustGenerateBilingualRetrieval", true,
                "bilingualQueryFields", List.of("bilingualIntent", "bilingualQuery", "intentZh", "intentEn", "filters.bilingualIntent", "filters.intentZh", "filters.intentEn")
            ),
            "routingProtocol", mapOf(
                "forcedTargetKind", "api_service",
                "forcedAssetType", "api_service",
                "filtersSchemaVersion", TargetKindRegistry.FILTERS_SCHEMA_VERSION
            ),
            "indexPolicy", mapOf(
                "logicalIndex", "template:api_service",
                "filterField", "assetType",
                "isolatedByTool", true
            ),
            "forbiddenConcreteTargetFields", List.of("url", "urlTemplate", "headers", "headersJson", "body", "bodyTemplate"),
            "rawExecutionSpecReturned", false
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
        for (String key : List.of(
            "toolName",
            "name",
            "businessGroup",
            "business_group",
            "group",
            "groupName",
            "group_name",
            "groupDescription",
            "group_description",
            "service",
            "target",
            "labels",
            "intent",
            "bilingualIntent",
            "bilingualQuery",
            "intentZh",
            "intentEn",
            "goal",
            "category",
            "language",
            "queryLanguage"
        )) {
            Object value = arguments.get(key);
            if (value != null) {
                filters.putIfAbsent(key, value);
            }
        }
        rejectRawApiFields(filters);
        return filters;
    }

    private void rejectRawApiFields(Map<String, Object> filters) {
        for (String field : List.of("url", "urlTemplate", "headers", "headersJson", "body", "bodyTemplate")) {
            Object value = filters.get(field);
            if (value != null && !String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("Raw API execution field is not allowed in api_template_query: " + field);
            }
        }
    }

    private List<String> terms(Map<String, Object> filters) {
        List<String> terms = new ArrayList<>();
        for (String key : List.of("toolName", "name", "businessGroup", "business_group", "group", "groupName",
            "group_name", "groupDescription", "group_description", "service", "target", "intent",
            "bilingualQuery", "intentZh", "intentEn", "goal", "category")) {
            addTerm(terms, filters.get(key));
        }
        addTerm(terms, filters.get("bilingualIntent"));
        addTerm(terms, filters.get("labels"));
        addTerm(terms, filters.get("retrievalSignals"));
        addTerm(terms, filters.get("queryTerms"));
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

    private ScoredApiTemplate apiTemplateHit(Map<String, ApiServiceConfig> configsByToolName,
                                             LuceneMcpSearchService.SearchHit hit) {
        if (hit == null) {
            return null;
        }
        ApiServiceConfig config = configsByToolName.get(text(hit.id()));
        if (config == null) {
            config = configsByToolName.get(text(hit.documentId()));
        }
        return config == null ? null : new ScoredApiTemplate(config, hit.score());
    }

    private Map<String, Object> filtersWithApiAssetSignals(Map<String, Object> filters, List<String> signals) {
        Map<String, Object> merged = new LinkedHashMap<>(filters);
        appendSignals(merged, "intent", signals);
        appendSignals(merged, "retrievalSignals", signals);
        appendSignals(merged, "queryTerms", signals);
        return merged;
    }

    private void appendSignals(Map<String, Object> filters, String key, List<String> signals) {
        if (filters == null || key == null || signals == null || signals.isEmpty()) {
            return;
        }
        List<Object> values = new ArrayList<>();
        Object existing = filters.get(key);
        if (existing instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                values.add(item);
            }
        } else if (existing != null) {
            values.add(existing);
        }
        values.addAll(signals);
        filters.put(key, values.stream()
            .filter(value -> value != null && !String.valueOf(value).isBlank())
            .map(value -> String.valueOf(value).trim())
            .distinct()
            .toList());
    }

    private List<ApiServiceConfig> scopedApiServices(List<ApiServiceConfig> configs, Map<String, Object> filters) {
        if (!hasAssetScope(filters)) {
            return List.of();
        }
        return (configs == null ? List.<ApiServiceConfig>of() : configs).stream()
            .filter(config -> matchesApiServiceScope(config, filters))
            .toList();
    }

    private boolean hasAssetScope(Map<String, Object> filters) {
        return firstValue(filters, "toolName", "name", "businessGroup", "business_group", "group", "groupName",
            "group_name", "service", "target", "labels", "category") != null;
    }

    private boolean matchesApiServiceScope(ApiServiceConfig config, Map<String, Object> filters) {
        String requestedName = text(firstValue(filters, "toolName", "name", "service", "target"));
        if (!requestedName.isBlank()
            && !logicalNameMatches(requestedName, config.getToolName())
            && !logicalNameMatches(requestedName, config.getTitle())
            && !logicalNameMatches(requestedName, config.getBusinessGroup())
            && !logicalNameMatches(requestedName, config.getBusinessGroupName())) {
            return false;
        }
        String group = text(firstValue(filters, "businessGroup", "business_group", "group", "groupName", "group_name"));
        if (!group.isBlank()
            && !logicalNameMatches(group, config.getBusinessGroup())
            && !logicalNameMatches(group, config.getBusinessGroupName())) {
            return false;
        }
        Object labels = filters.get("labels");
        if (labels != null) {
            List<String> requestedLabels = new ArrayList<>();
            addTerm(requestedLabels, labels);
            List<String> configLabels = apiServiceSignals(List.of(config)).stream()
                .map(this::normalize)
                .toList();
            if (!configLabels.containsAll(requestedLabels.stream().map(this::normalize).toList())) {
                return false;
            }
        }
        return true;
    }

    private List<String> apiServiceSignals(List<ApiServiceConfig> configs) {
        List<String> signals = new ArrayList<>();
        for (ApiServiceConfig config : configs == null ? List.<ApiServiceConfig>of() : configs) {
            addTerm(signals, config.getToolName());
            addTerm(signals, config.getTitle());
            addTerm(signals, config.getDescription());
            addTerm(signals, config.getBusinessGroup());
            addTerm(signals, config.getBusinessGroupName());
            addTerm(signals, config.getBusinessGroupDescription());
            addTerm(signals, governanceSignals(config.getGovernanceJson()));
        }
        return signals.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .limit(24)
            .toList();
    }

    private List<String> governanceSignals(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object value = objectMapper.readValue(json, Object.class);
            List<String> signals = new ArrayList<>();
            collectScalarSignals(value, signals);
            return signals;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void collectScalarSignals(Object value, List<String> signals) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectScalarSignals(item, signals));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectScalarSignals(item, signals);
            }
            return;
        }
        addTerm(signals, value);
    }

    private boolean logicalNameMatches(String requested, String candidate) {
        String left = normalize(requested);
        String right = normalize(candidate);
        return !left.isBlank() && !right.isBlank() && (left.equals(right) || left.contains(right) || right.contains(left));
    }

    private Map<String, Object> templateMetadata(ApiServiceConfig config, double score) {
        Map<String, Object> parameterSchema = parameterSchema(config.getInputSchemaJson());
        List<String> requiredParameters = requiredParameters(parameterSchema);
        return mapOf(
            "schemaVersion", CommandTemplateDiscoveryService.TEMPLATE_SCHEMA_VERSION,
            "templateId", config.getToolName(),
            "id", config.getToolName(),
            "toolName", config.getToolName(),
            "title", firstText(config.getTitle(), config.getToolName()),
            "description", text(config.getDescription()),
            "businessGroup", businessGroupMetadata(config),
            "assetType", "api_service",
            "targetKind", "api_service",
            "method", config.getMethod(),
            "parameterSchema", parameterSchema,
            "requiredParameters", requiredParameters,
            "parameterContract", directParameterContract(config.getToolName(), parameterSchema),
            "invocationExample", directInvocationExample(config.getToolName(), parameterSchema),
            "riskLevel", "LOW",
            "enabled", config.isEnabled(),
            "relevanceScore", score,
            "routing", mapOf(
                "callTool", config.getToolName(),
                "templateId", config.getToolName(),
                "source", TOOL_NAME + ".templates[].templateId"
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> directParameterContract(String toolName, Map<String, Object> parameterSchema) {
        Map<String, Object> properties = parameterSchema == null || !(parameterSchema.get("properties") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        List<String> required = requiredParameters(parameterSchema);
        return mapOf(
            "schemaVersion", "template_parameter_contract.v1",
            "templateId", toolName,
            "executionTool", toolName,
            "argumentContainer", toolName + ".arguments",
            "required", required,
            "optional", properties.keySet().stream()
                .filter(key -> !required.contains(key))
                .toList(),
            "mustPassUnderParameters", false,
            "topLevelTemplateParametersAllowed", true,
            "missingRequiredBehavior", "Do not call the API MCP tool until every required argument is present."
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> directInvocationExample(String toolName, Map<String, Object> parameterSchema) {
        Map<String, Object> properties = parameterSchema == null || !(parameterSchema.get("properties") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (String required : requiredParameters(parameterSchema)) {
            arguments.put(required, exampleValue(required, properties.get(required)));
        }
        return mapOf(
            "tool", toolName,
            "arguments", arguments
        );
    }

    private List<String> requiredParameters(Map<String, Object> parameterSchema) {
        Object required = parameterSchema == null ? null : parameterSchema.get("required");
        if (!(required instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : iterable) {
            if (item != null && !String.valueOf(item).isBlank()) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }

    private String exampleValue(String name, Object schema) {
        if (schema instanceof Map<?, ?> map && map.get("example") != null) {
            return String.valueOf(map.get("example"));
        }
        return "<" + name + ">";
    }

    private Map<String, Object> parameterSchema(String json) {
        if (json == null || json.isBlank()) {
            return Map.of("type", "object", "additionalProperties", true);
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of("type", "object", "additionalProperties", true);
        }
    }

    private Map<String, Object> errorResult(String message) {
        return mapOf(
            "schemaVersion", CommandTemplateDiscoveryService.RESULT_SCHEMA_VERSION,
            "querySchemaVersion", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION,
            "success", false,
            "error", message,
            "errorDetail", mapOf(
                "code", "API_TEMPLATE_QUERY_REJECTED",
                "message", message
            )
        );
    }

    private int limit(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("limit");
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
            log.debug("API template discovery MCP tool {} was not registered: {}", toolName, ex.getMessage());
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    private record ScoredApiTemplate(ApiServiceConfig config, double score) {
    }
}
