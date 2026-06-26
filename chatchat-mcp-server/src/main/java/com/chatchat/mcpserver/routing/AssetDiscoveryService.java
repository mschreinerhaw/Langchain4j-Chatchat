package com.chatchat.mcpserver.routing;

import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.SshHostConfigService;
import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AssetDiscoveryService {

    public static final String QUERY_SCHEMA_VERSION = "asset_query.v1";
    public static final String RESULT_SCHEMA_VERSION = "asset_query_result.v1";
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 20;

    private static final List<String> CONTEXT_FILTER_KEYS = List.of(
        "env",
        "environment",
        "cluster",
        "namespace",
        "target",
        "targetType",
        "target_type",
        "assetName",
        "asset_name",
        "name",
        "database",
        "databaseType",
        "dbType",
        "dialect",
        "databaseRole",
        "database_role",
        "service",
        "labels"
    );

    private static final List<String> CONCRETE_TARGET_FIELDS = List.of(
        "hostId",
        "host",
        "hostname",
        "ip",
        "ipAddress",
        "address",
        "datasourceId",
        "jdbcUrl",
        "url",
        "connectionString",
        "endpointId",
        "uri"
    );

    private final SshHostConfigService hostConfigService;
    private final SqlDatasourceConfigService datasourceConfigService;
    private final HttpEndpointConfigService httpEndpointConfigService;
    private final AssetMetadataFactory assetMetadataFactory;
    private final LuceneMcpSearchService luceneSearchService;
    private final TargetKindRegistry targetKindRegistry;

    public AssetDiscoveryService(SshHostConfigService hostConfigService,
                                 SqlDatasourceConfigService datasourceConfigService,
                                 HttpEndpointConfigService httpEndpointConfigService,
                                 AssetMetadataFactory assetMetadataFactory) {
        this(hostConfigService, datasourceConfigService, httpEndpointConfigService, assetMetadataFactory, null, new TargetKindRegistry());
    }

    @Autowired
    public AssetDiscoveryService(SshHostConfigService hostConfigService,
                                 SqlDatasourceConfigService datasourceConfigService,
                                 HttpEndpointConfigService httpEndpointConfigService,
                                 AssetMetadataFactory assetMetadataFactory,
                                 LuceneMcpSearchService luceneSearchService,
                                 TargetKindRegistry targetKindRegistry) {
        this.hostConfigService = hostConfigService;
        this.datasourceConfigService = datasourceConfigService;
        this.httpEndpointConfigService = httpEndpointConfigService;
        this.assetMetadataFactory = assetMetadataFactory;
        this.luceneSearchService = luceneSearchService;
        this.targetKindRegistry = targetKindRegistry == null ? new TargetKindRegistry() : targetKindRegistry;
    }

    public Map<String, Object> query(Map<String, Object> arguments) {
        long startedAt = System.nanoTime();
        Map<String, Object> filters = filters(arguments);
        rejectConcreteTargetFields(filters);
        boolean broadDiscovery = !hasContextFilter(filters);

        TargetKindRegistry.Resolution target = targetKindRegistry.resolveForTool(
            "asset_query",
            firstValue(arguments, "assetType", "type"),
            arguments,
            filters
        );
        String assetType = target.definition().assetType();
        filters.putIfAbsent("filtersSchemaVersion", target.filtersSchemaVersion());
        int limit = limit(arguments);
        if (target.reviewRequired()) {
            return reviewResult(target, filters, limit, startedAt);
        }
        List<Map<String, Object>> allAssets = allAssets(assetType);
        List<Map<String, Object>> matchedAll = matchingAssetsFromLucene(allAssets, assetType, filters, Math.max(limit + 1, MAX_LIMIT));
        List<Map<String, Object>> matched = matchedAll.stream().limit(limit).toList();
        List<Map<String, Object>> unavailableMatched = unavailableAssets(assetType, filters, limit);
        Map<String, Object> compactFilters = compactFilters(filters);

        return mapOf(
            "schemaVersion", RESULT_SCHEMA_VERSION,
            "querySchemaVersion", QUERY_SCHEMA_VERSION,
            "success", true,
            "view", view(arguments),
            "routingPolicyVersion", AssetMetadataFactory.ROUTING_POLICY_VERSION,
            "targetKind", target.definition().targetKind(),
            "filtersSchemaVersion", target.filtersSchemaVersion(),
            "discoveryPolicy", mapOf(
                "readOnly", true,
                "requiresContextFilter", false,
                "broadDiscovery", "allowed_redacted_candidates_only",
                "maxResults", MAX_LIMIT,
                "redaction", "concrete target fields are never returned"
            ),
            "filters", compactFilters,
            "assetType", assetType,
            "limit", limit,
            "returnedCount", matched.size(),
            "unavailableCount", unavailableMatched.size(),
            "possiblyTruncated", matchedAll.size() > limit,
            "broadDiscovery", broadDiscovery,
            "broadDiscoveryAdvice", broadDiscovery ? broadDiscoveryAdvice(matched.size()) : null,
            "emptyResultAdvice", matched.isEmpty() ? emptyResultAdvice(compactFilters, unavailableMatched) : null,
            "routingDecision", routingDecision(target, startedAt),
            "assets", matched,
            "unavailableAssets", unavailableMatched
        );
    }

    private Map<String, Object> reviewResult(TargetKindRegistry.Resolution target,
                                             Map<String, Object> filters,
                                             int limit,
                                             long startedAt) {
        return mapOf(
            "schemaVersion", RESULT_SCHEMA_VERSION,
            "querySchemaVersion", QUERY_SCHEMA_VERSION,
            "success", false,
            "status", TargetKindRegistry.DECISION_REVIEW_REQUIRED,
            "reason", "LOW_CONFIDENCE_TARGET_KIND",
            "targetKind", target.definition().targetKind(),
            "assetType", target.definition().assetType(),
            "filtersSchemaVersion", target.filtersSchemaVersion(),
            "filters", compactFilters(filters),
            "limit", limit,
            "returnedCount", 0,
            "assets", List.of(),
            "unavailableAssets", List.of(),
            "routingDecision", routingDecision(target, startedAt),
            "review", mapOf(
                "required", true,
                "reason", "confidence below routing threshold",
                "threshold", TargetKindRegistry.MIN_CONFIDENCE,
                "confidence", target.confidence()
            )
        );
    }

    private Map<String, Object> routingDecision(TargetKindRegistry.Resolution target, long startedAt) {
        return mapOf(
            "routingDecision", target.definition().targetKind() + " -> " + target.definition().assetType(),
            "targetKind", target.definition().targetKind(),
            "finalDecision", target.finalDecision(),
            "assetType", target.definition().assetType(),
            "confidence", target.confidence(),
            "threshold", TargetKindRegistry.MIN_CONFIDENCE,
            "latencyMs", elapsedMs(startedAt),
            "decision", target.decision(),
            "candidateSet", mapOf(
                "finalDecision", target.finalDecision(),
                "candidates", routingCandidates(target)
            ),
            "trace", target.trace()
        );
    }

    private List<Map<String, Object>> routingCandidates(TargetKindRegistry.Resolution target) {
        return target.candidates().stream()
            .map(candidate -> mapOf(
                "targetKind", candidate.targetKind(),
                "assetType", candidate.assetType(),
                "confidence", candidate.confidence(),
                "feasible", candidate.feasible(),
                "feasibilityReasons", candidate.feasibilityReasons(),
                "latencyEstimateMs", candidate.latencyEstimateMs(),
                "historicalSuccessRate", candidate.historicalSuccessRate(),
                "score", candidate.score()
            ))
            .toList();
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private List<Map<String, Object>> matchingAssetsFromLucene(List<Map<String, Object>> assets,
                                                               String assetType,
                                                               Map<String, Object> filters,
                                                               int limit) {
        if (luceneSearchService == null || !luceneSearchService.enabled()) {
            return assets.stream()
                .filter(asset -> matches(asset, filters))
                .limit(limit)
                .toList();
        }
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        assets.forEach(asset -> {
            String id = assetId(asset);
            if (id != null) {
                byId.put(id, asset);
            }
        });
        List<LuceneMcpSearchService.SearchHit> hits = luceneSearchService.searchAssets(
            assets.stream().map(this::assetDoc).toList(),
            assetSearchRequest(assetType, filters, limit)
        );
        return hits.stream()
            .map(hit -> byId.get(hit.id()))
            .filter(asset -> asset != null)
            .limit(limit)
            .toList();
    }

    private LuceneMcpSearchService.AssetSearchRequest assetSearchRequest(String assetType,
                                                                         Map<String, Object> filters,
                                                                         int limit) {
        return new LuceneMcpSearchService.AssetSearchRequest(
            normalize(assetType),
            text(firstValue(filters, "assetName", "asset_name", "name")),
            text(firstValue(filters, "env", "environment")),
            text(firstValue(filters, "databaseType", "dbType", "dialect")),
            contextTokens(filters),
            limit
        );
    }

    @SuppressWarnings("unchecked")
    private LuceneMcpSearchService.AssetDoc assetDoc(Map<String, Object> metadata) {
        Map<String, Object> asset = metadata == null || !(metadata.get("asset") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        Map<String, Object> routingHints = metadata == null || !(metadata.get("routingHints") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        Map<String, Object> capabilities = metadata == null || !(metadata.get("capabilities") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        return new LuceneMcpSearchService.AssetDoc(
            text(asset.get("id")),
            text(metadata == null ? null : metadata.get("assetType")),
            text(asset.get("name")),
            text(asset.get("displayName")),
            text(asset.get("toolName")),
            text(asset.get("environment")),
            text(capabilities.get("databaseType")),
            labels(routingHints.get("labels")),
            "asset_query"
        );
    }

    @SuppressWarnings("unchecked")
    private String assetId(Map<String, Object> metadata) {
        if (metadata == null || !(metadata.get("asset") instanceof Map<?, ?> map)) {
            return null;
        }
        return text(((Map<String, Object>) map).get("id"));
    }

    private List<Map<String, Object>> allAssets(String assetType) {
        List<Map<String, Object>> assets = new ArrayList<>();
        if (assetType == null || equalsNormalized(assetType, "ssh_host")) {
            assets.addAll(safeList(hostConfigService.listEnabled()).stream().map(assetMetadataFactory::sshAsset).toList());
        }
        if (assetType == null || equalsNormalized(assetType, "sql_datasource")) {
            assets.addAll(safeList(datasourceConfigService.listEnabled()).stream().map(assetMetadataFactory::sqlDatasource).toList());
        }
        if (assetType == null || equalsNormalized(assetType, "http_endpoint")) {
            assets.addAll(safeList(httpEndpointConfigService.listEnabled()).stream().map(assetMetadataFactory::httpEndpoint).toList());
        }
        return assets;
    }

    private List<Map<String, Object>> allRegisteredAssets(String assetType) {
        List<Map<String, Object>> assets = new ArrayList<>();
        if (assetType == null || equalsNormalized(assetType, "ssh_host")) {
            assets.addAll(safeList(hostConfigService.listAll()).stream().map(assetMetadataFactory::sshAsset).toList());
        }
        if (assetType == null || equalsNormalized(assetType, "sql_datasource")) {
            assets.addAll(safeList(datasourceConfigService.listAll()).stream().map(assetMetadataFactory::sqlDatasource).toList());
        }
        if (assetType == null || equalsNormalized(assetType, "http_endpoint")) {
            assets.addAll(safeList(httpEndpointConfigService.listAll()).stream().map(assetMetadataFactory::httpEndpoint).toList());
        }
        return assets;
    }

    private List<Map<String, Object>> unavailableAssets(String assetType, Map<String, Object> filters, int limit) {
        if (!hasContextFilter(filters)) {
            return List.of();
        }
        return allRegisteredAssets(assetType).stream()
            .filter(asset -> matches(asset, filters))
            .filter(asset -> {
                Object assetNode = asset.get("asset");
                return assetNode instanceof Map<?, ?> map && !Boolean.TRUE.equals(map.get("enabled"));
            })
            .limit(limit)
            .map(this::unavailableAssetSummary)
            .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unavailableAssetSummary(Map<String, Object> metadata) {
        Map<String, Object> asset = metadata == null || !(metadata.get("asset") instanceof Map<?, ?> map)
            ? Map.of()
            : new LinkedHashMap<>((Map<String, Object>) map);
        return mapOf(
            "schemaVersion", AssetMetadataFactory.SCHEMA_VERSION,
            "kind", "asset_unavailable",
            "assetType", metadata == null ? null : metadata.get("assetType"),
            "asset", asset,
            "availability", mapOf(
                "available", false,
                "reason", "registered_but_disabled_or_not_published",
                "nextAction", "Enable and publish this asset before executing tools against it."
            )
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
        Object looseContext = arguments.get("context");
        if (looseContext instanceof Map<?, ?> map) {
            filters.putAll((Map<String, Object>) map);
        } else if (looseContext != null && !String.valueOf(looseContext).isBlank()) {
            filters.putIfAbsent("service", String.valueOf(looseContext).trim());
        }
        for (String key : CONTEXT_FILTER_KEYS) {
            if (arguments.get(key) != null) {
                filters.putIfAbsent(key, arguments.get(key));
            }
        }
        return filters;
    }

    private boolean matches(Map<String, Object> assetMetadata, Map<String, Object> filters) {
        Map<?, ?> asset = (Map<?, ?>) assetMetadata.get("asset");
        Map<?, ?> routingHints = (Map<?, ?>) assetMetadata.get("routingHints");
        List<String> labels = labels(routingHints == null ? null : routingHints.get("labels"));
        String env = text(firstValue(filters, "env", "environment"));
        if (env != null && !equalsNormalized(env, asset == null ? null : asset.get("environment"))) {
            return false;
        }
        String assetName = text(firstValue(filters, "assetName", "asset_name", "name"));
        if (assetName != null && !assetNameMatches(asset, assetName)) {
            return false;
        }
        return contextTokens(filters).stream().allMatch(token -> labelMatches(labels, token));
    }

    private boolean hasContextFilter(Map<String, Object> filters) {
        return CONTEXT_FILTER_KEYS.stream()
            .map(filters::get)
            .anyMatch(value -> value != null && !String.valueOf(value).isBlank());
    }

    private List<String> contextTokens(Map<String, Object> filters) {
        List<String> tokens = new ArrayList<>();
        for (String key : List.of(
            "cluster",
            "namespace",
            "target",
            "targetType",
            "target_type",
            "database",
            "databaseType",
            "dbType",
            "dialect",
            "databaseRole",
            "database_role",
            "service",
            "labels"
        )) {
            Object value = filters.get(key);
            if (value instanceof List<?> list) {
                list.forEach(item -> addToken(tokens, item));
            } else {
                addToken(tokens, value);
            }
        }
        return tokens.stream().distinct().toList();
    }

    private List<String> labels(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(String::valueOf)
                .map(this::normalize)
                .filter(item -> item != null && !item.isBlank())
                .toList();
        }
        return List.of();
    }

    private boolean labelMatches(List<String> labels, String token) {
        String normalized = normalize(token);
        if (normalized == null) {
            return false;
        }
        return labels.stream().anyMatch(label -> label.equals(normalized) || label.endsWith(":" + normalized));
    }

    private boolean assetNameMatches(Map<?, ?> asset, String assetName) {
        if (asset == null || assetName == null || assetName.isBlank()) {
            return false;
        }
        return equalsNormalized(assetName, asset.get("name"))
            || equalsNormalized(assetName, asset.get("displayName"))
            || equalsNormalized(assetName, asset.get("toolName"))
            || assetNameAppearsInQuery(assetName, asset.get("name"))
            || assetNameAppearsInQuery(assetName, asset.get("displayName"))
            || assetNameAppearsInQuery(assetName, asset.get("toolName"));
    }

    private boolean assetNameAppearsInQuery(String queryValue, Object assetValue) {
        String query = normalize(queryValue);
        String assetName = normalize(assetValue == null ? null : String.valueOf(assetValue));
        if (query == null || assetName == null || assetName.length() < 3 || query.equals(assetName)) {
            return false;
        }
        Pattern pattern = Pattern.compile("(^|[^a-z0-9_-])" + Pattern.quote(assetName) + "($|[^a-z0-9_-])");
        return pattern.matcher(query).find();
    }

    private String view(Map<String, Object> arguments) {
        String view = text(firstValue(arguments, "view"));
        return equalsNormalized(view, "system") ? "system" : "model";
    }

    private int limit(Map<String, Object> arguments) {
        Object value = firstValue(arguments, "limit", "maxResults");
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            return Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(String.valueOf(value))));
        } catch (NumberFormatException ignored) {
            return DEFAULT_LIMIT;
        }
    }

    private void rejectConcreteTargetFields(Map<String, Object> filters) {
        for (String field : CONCRETE_TARGET_FIELDS) {
            Object value = filters.get(field);
            if (value != null && !String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("Concrete target field is not allowed in asset_query: " + field);
            }
        }
    }

    private Map<String, Object> compactFilters(Map<String, Object> filters) {
        Map<String, Object> compact = new LinkedHashMap<>();
        CONTEXT_FILTER_KEYS.forEach(key -> {
            Object value = filters.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                compact.put(key, value);
            }
        });
        return compact;
    }

    private Map<String, Object> emptyResultAdvice(Map<String, Object> filters, List<Map<String, Object>> unavailableAssets) {
        boolean hasUnavailable = unavailableAssets != null && !unavailableAssets.isEmpty();
        return mapOf(
            "reason", hasUnavailable
                ? "A registered asset matched the filters, but it is disabled or not published."
                : "No enabled and published asset matched the exact logical filters.",
            "doNotConclude", "Do not conclude the requested system/service does not exist solely from this empty result; it may be registered but disabled or not published.",
            "doNotInvent", "Do not invent or transform service labels such as service:<topic> from the user's natural-language intent.",
            "nextAction", hasUnavailable
                ? "Tell the user the matching asset exists but must be enabled/published before query or command execution."
                : "Ask the user to confirm the exact assetName/env/cluster/service label and whether the asset is enabled/published, or retry only with values explicitly provided by the user or returned by prior tool observations.",
            "receivedFilters", filters == null ? Map.of() : filters
        );
    }

    private Map<String, Object> broadDiscoveryAdvice(int returnedCount) {
        return mapOf(
            "reason", "No exact logical context filter was supplied, so asset_query returned redacted candidate assets.",
            "selectionPolicy", "Select an asset only when its asset.name/environment/capabilities clearly match the user request; otherwise ask for assetName/env/cluster/service.",
            "templateHint", "For execution-template questions, prefer assets whose capabilities.allowedCommandTemplates[].templateId, allowedCommandTemplateIds[], authorizedSqlTemplates[], or authorizedHttpTemplates[] contains a matching registered template id.",
            "returnedCount", returnedCount
        );
    }

    private Object firstValue(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
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

    private void addToken(List<String> tokens, Object value) {
        String token = normalize(value == null ? null : String.valueOf(value));
        if (token != null) {
            tokens.add(token);
        }
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean equalsNormalized(Object first, Object second) {
        String left = normalize(first == null ? null : String.valueOf(first));
        String right = normalize(second == null ? null : String.valueOf(second));
        return left != null && left.equals(right);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }
}
