package com.chatchat.mcpserver.routing;

import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.SshHostConfigService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
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

    public Map<String, Object> query(Map<String, Object> arguments) {
        Map<String, Object> filters = filters(arguments);
        rejectConcreteTargetFields(filters);
        if (!hasContextFilter(filters)) {
            throw new IllegalArgumentException("asset_query requires at least one logical context filter: "
                + "assetName, env, cluster, service, target, database, databaseRole, or labels");
        }

        String assetType = text(firstValue(arguments, "assetType", "type"));
        int limit = limit(arguments);
        List<Map<String, Object>> matched = allAssets(assetType).stream()
            .filter(asset -> matches(asset, filters))
            .limit(limit)
            .toList();

        return mapOf(
            "schemaVersion", RESULT_SCHEMA_VERSION,
            "querySchemaVersion", QUERY_SCHEMA_VERSION,
            "success", true,
            "view", view(arguments),
            "routingPolicyVersion", AssetMetadataFactory.ROUTING_POLICY_VERSION,
            "discoveryPolicy", mapOf(
                "readOnly", true,
                "requiresContextFilter", true,
                "maxResults", MAX_LIMIT,
                "redaction", "concrete target fields are never returned"
            ),
            "filters", compactFilters(filters),
            "assetType", assetType,
            "limit", limit,
            "returnedCount", matched.size(),
            "possiblyTruncated", allAssets(assetType).stream().filter(asset -> matches(asset, filters)).count() > limit,
            "assets", matched
        );
    }

    private List<Map<String, Object>> allAssets(String assetType) {
        List<Map<String, Object>> assets = new ArrayList<>();
        if (assetType == null || equalsNormalized(assetType, "ssh_host")) {
            assets.addAll(hostConfigService.listEnabled().stream().map(assetMetadataFactory::sshAsset).toList());
        }
        if (assetType == null || equalsNormalized(assetType, "sql_datasource")) {
            assets.addAll(datasourceConfigService.listEnabled().stream().map(assetMetadataFactory::sqlDatasource).toList());
        }
        if (assetType == null || equalsNormalized(assetType, "http_endpoint")) {
            assets.addAll(httpEndpointConfigService.listEnabled().stream().map(assetMetadataFactory::httpEndpoint).toList());
        }
        return assets;
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
            || equalsNormalized(assetName, asset.get("toolName"));
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

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }
}
