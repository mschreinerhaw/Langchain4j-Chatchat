package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.SqlTemplateConfig;
import com.chatchat.mcpserver.sql.SqlTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CommandTemplateDiscoveryService {

    public static final String QUERY_SCHEMA_VERSION = "template_query.v1";
    public static final String RESULT_SCHEMA_VERSION = "template_query_result.v1";
    public static final String TEMPLATE_SCHEMA_VERSION = "command_template.v1";
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 20;

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
        "uri",
        "command",
        "rawCommand",
        "shell",
        "sql",
        "rawSql",
        "body",
        "bodyTemplate"
    );

    private final CommandTemplateService templateService;
    private final SshHostConfigService hostConfigService;
    private final SqlTemplateService sqlTemplateService;
    private final SqlDatasourceConfigService datasourceConfigService;
    private final HttpEndpointConfigService httpEndpointConfigService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> query(Map<String, Object> arguments) {
        Map<String, Object> filters = filters(arguments);
        rejectConcreteTargetFields(filters);
        String assetType = normalize(text(firstValue(arguments, "assetType", "type")));
        if (assetType == null) {
            assetType = "ssh_host";
        }
        int limit = limit(arguments);
        if ("sql_datasource".equals(assetType)) {
            return querySqlTemplates(assetType, filters, limit);
        }
        if ("http_endpoint".equals(assetType)) {
            return queryHttpTemplates(assetType, filters, limit);
        }
        if (!"ssh_host".equals(assetType)) {
            return result(assetType, filters, limit, List.of(), false);
        }
        return querySshTemplates(assetType, filters, limit);
    }

    private Map<String, Object> querySshTemplates(String assetType, Map<String, Object> filters, int limit) {
        Set<String> allowedByAsset = allowedTemplatesForAssets(filters);
        boolean assetScoped = hasAssetScope(filters);
        List<CommandTemplateConfig> matched = templateService.listEnabled().stream()
            .filter(template -> !assetScoped || allowedByAsset.contains(normalize(template.getCode())))
            .filter(template -> matchesIntent(template, filters))
            .limit(limit)
            .toList();
        long total = templateService.listEnabled().stream()
            .filter(template -> !assetScoped || allowedByAsset.contains(normalize(template.getCode())))
            .filter(template -> matchesIntent(template, filters))
            .count();
        return result(assetType, filters, limit, matched.stream()
            .map(template -> templateMetadata(template, assetType))
            .toList(), total > limit);
    }

    private Map<String, Object> querySqlTemplates(String assetType, Map<String, Object> filters, int limit) {
        boolean assetScoped = hasAssetScope(filters);
        List<SqlDatasourceConfig> datasources = matchingDatasources(filters);
        if (assetScoped && datasources.isEmpty()) {
            return result(assetType, filters, limit, List.of(), false);
        }
        List<SqlTemplateConfig> matched = sqlTemplateService.listEnabled().stream()
            .filter(template -> matchesSqlTemplateBinding(template, filters, datasources, assetScoped))
            .filter(template -> matchesIntent(template, filters))
            .limit(limit)
            .toList();
        long total = sqlTemplateService.listEnabled().stream()
            .filter(template -> matchesSqlTemplateBinding(template, filters, datasources, assetScoped))
            .filter(template -> matchesIntent(template, filters))
            .count();
        return result(assetType, filters, limit, matched.stream()
            .map(template -> templateMetadata(template, assetType))
            .toList(), total > limit);
    }

    private Map<String, Object> queryHttpTemplates(String assetType, Map<String, Object> filters, int limit) {
        List<HttpEndpointConfig> matched = httpEndpointConfigService.listEnabled().stream()
            .filter(endpoint -> matchesHttpEndpoint(endpoint, filters))
            .filter(endpoint -> matchesIntent(endpoint, filters))
            .limit(limit)
            .toList();
        long total = httpEndpointConfigService.listEnabled().stream()
            .filter(endpoint -> matchesHttpEndpoint(endpoint, filters))
            .filter(endpoint -> matchesIntent(endpoint, filters))
            .count();
        return result(assetType, filters, limit, matched.stream()
            .map(endpoint -> templateMetadata(endpoint, assetType))
            .toList(), total > limit);
    }

    private Map<String, Object> result(String assetType,
                                       Map<String, Object> filters,
                                       int limit,
                                       List<Map<String, Object>> templateMetadata,
                                       boolean possiblyTruncated) {
        return mapOf(
            "schemaVersion", RESULT_SCHEMA_VERSION,
            "querySchemaVersion", QUERY_SCHEMA_VERSION,
            "success", true,
            "view", view(filters),
            "assetType", assetType,
            "filters", compactFilters(filters),
            "limit", limit,
            "returnedCount", templateMetadata.size(),
            "possiblyTruncated", possiblyTruncated,
            "templates", templateMetadata
        );
    }

    private Map<String, Object> templateMetadata(CommandTemplateConfig template, String assetType) {
        List<String> signals = intentSignals(template);
        return mapOf(
            "schemaVersion", TEMPLATE_SCHEMA_VERSION,
            "templateId", template.getCode(),
            "name", firstText(template.getTitle(), template.getCode()),
            "description", firstText(template.getDescription(), ""),
            "category", category(template),
            "riskLevel", riskLevel(template),
            "supportedAssetTypes", List.of(assetType),
            "intentSignals", signals,
            "routingHints", mapOf(
                "strongSignals", signals.stream().limit(5).toList(),
                "contextKeys", List.of("assetName", "env", "environment", "cluster", "service", "target", "labels")
            ),
            "parameterSchema", parameterSchema(template),
            "enabled", template.isEnabled()
        );
    }

    private Map<String, Object> templateMetadata(SqlTemplateConfig template, String assetType) {
        List<String> signals = intentSignals(template);
        return mapOf(
            "schemaVersion", TEMPLATE_SCHEMA_VERSION,
            "templateId", template.getCode(),
            "name", firstText(template.getTitle(), template.getCode()),
            "description", firstText(template.getDescription(), ""),
            "category", category(template),
            "riskLevel", riskLevel(template),
            "databaseType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(template.getDatabaseType()),
            "binding", sqlTemplateBindingMetadata(template),
            "supportedAssetTypes", List.of(assetType),
            "intentSignals", signals,
            "routingHints", mapOf(
                "strongSignals", signals.stream().limit(5).toList(),
                "contextKeys", List.of("assetName", "env", "environment", "cluster", "database", "databaseType", "dbType", "dialect", "databaseRole", "service", "target", "labels")
            ),
            "parameterSchema", parameterSchema(template.getParameterSchemaJson()),
            "enabled", template.isEnabled()
        );
    }

    private Map<String, Object> templateMetadata(HttpEndpointConfig endpoint, String assetType) {
        List<String> signals = intentSignals(endpoint);
        String templateId = firstText(endpoint.getToolName(), firstText(endpoint.getName(), endpoint.getId()));
        return mapOf(
            "schemaVersion", TEMPLATE_SCHEMA_VERSION,
            "templateId", templateId,
            "name", firstText(endpoint.getTitle(), templateId),
            "description", firstText(endpoint.getDescription(), ""),
            "category", firstText(endpoint.getCategory(), "http_request"),
            "riskLevel", httpRiskLevel(endpoint),
            "supportedAssetTypes", List.of(assetType),
            "intentSignals", signals,
            "routingHints", mapOf(
                "strongSignals", signals.stream().limit(5).toList(),
                "contextKeys", List.of("assetName", "env", "environment", "cluster", "service", "target", "labels")
            ),
            "parameterSchema", parameterSchema(endpoint.getInputSchemaJson()),
            "enabled", endpoint.isEnabled()
        );
    }

    private boolean matchesIntent(CommandTemplateConfig template, Map<String, Object> filters) {
        return matchesIntentText(
            template.getCode(),
            template.getTitle(),
            template.getDescription(),
            category(template),
            intentSignals(template),
            filters
        );
    }

    private boolean matchesIntent(SqlTemplateConfig template, Map<String, Object> filters) {
        return matchesIntentText(
            template.getCode(),
            template.getTitle(),
            template.getDescription(),
            category(template),
            intentSignals(template),
            filters
        );
    }

    private boolean matchesIntent(HttpEndpointConfig endpoint, Map<String, Object> filters) {
        return matchesIntentText(
            firstText(endpoint.getToolName(), endpoint.getName()),
            endpoint.getTitle(),
            endpoint.getDescription(),
            firstText(endpoint.getCategory(), "http_request"),
            intentSignals(endpoint),
            filters
        );
    }

    private boolean matchesIntentText(String code,
                                      String title,
                                      String description,
                                      String category,
                                      List<String> signals,
                                      Map<String, Object> filters) {
        List<String> tokens = intentTokens(filters);
        if (tokens.isEmpty()) {
            return true;
        }
        Set<String> haystack = new LinkedHashSet<>();
        addWords(haystack, code);
        addWords(haystack, title);
        addWords(haystack, description);
        addWords(haystack, category);
        signals.forEach(signal -> addWords(haystack, signal));
        return tokens.stream().anyMatch(haystack::contains);
    }

    private Set<String> allowedTemplatesForAssets(Map<String, Object> filters) {
        if (!hasAssetScope(filters)) {
            return Set.of();
        }
        Set<String> allowed = new LinkedHashSet<>();
        matchingHosts(filters).forEach(host -> allowedCommands(host).forEach(code -> {
            String normalized = normalize(code);
            if (normalized != null) {
                allowed.add(normalized);
            }
        }));
        return allowed;
    }

    private List<SshHostConfig> matchingHosts(Map<String, Object> filters) {
        String assetName = text(firstValue(filters, "assetName", "asset_name", "name"));
        String env = text(firstValue(filters, "env", "environment"));
        List<String> tokens = contextTokens(filters);
        return hostConfigService.listEnabled().stream()
            .filter(host -> assetName == null || assetNameMatches(host, assetName))
            .filter(host -> env == null || equalsNormalized(env, host.getEnvironment()))
            .filter(host -> tokens.isEmpty() || hostLabels(host).containsAll(tokens))
            .toList();
    }

    private boolean assetNameMatches(SshHostConfig host, String assetName) {
        return equalsNormalized(assetName, host.getName())
            || equalsNormalized(assetName, host.getTitle())
            || equalsNormalized(assetName, host.getToolName());
    }

    private List<SqlDatasourceConfig> matchingDatasources(Map<String, Object> filters) {
        String assetName = text(firstValue(filters, "assetName", "asset_name", "name"));
        String env = text(firstValue(filters, "env", "environment"));
        List<String> tokens = contextTokens(filters);
        return datasourceConfigService.listEnabled().stream()
            .filter(datasource -> assetName == null || datasourceNameMatches(datasource, assetName))
            .filter(datasource -> env == null || equalsNormalized(env, datasource.getEnvironment()))
            .filter(datasource -> matchesRequestedDatabaseType(datasource, filters))
            .filter(datasource -> tokens.isEmpty() || datasourceLabels(datasource).containsAll(tokens))
            .toList();
    }

    private boolean datasourceNameMatches(SqlDatasourceConfig datasource, String assetName) {
        return equalsNormalized(assetName, datasource.getName())
            || equalsNormalized(assetName, datasource.getTitle())
            || equalsNormalized(assetName, datasource.getToolName());
    }

    private boolean matchesHttpEndpoint(HttpEndpointConfig endpoint, Map<String, Object> filters) {
        String assetName = text(firstValue(filters, "assetName", "asset_name", "name", "template", "templateId", "template_id"));
        String env = text(firstValue(filters, "env", "environment"));
        List<String> tokens = contextTokens(filters);
        return (assetName == null || endpointNameMatches(endpoint, assetName))
            && (env == null || equalsNormalized(env, endpoint.getEnvironment()))
            && (tokens.isEmpty() || endpointLabels(endpoint).containsAll(tokens));
    }

    private boolean endpointNameMatches(HttpEndpointConfig endpoint, String assetName) {
        return equalsNormalized(assetName, endpoint.getName())
            || equalsNormalized(assetName, endpoint.getTitle())
            || equalsNormalized(assetName, endpoint.getToolName());
    }

    private Set<String> hostLabels(SshHostConfig host) {
        Set<String> labels = new LinkedHashSet<>();
        addLabel(labels, host.getName());
        addLabel(labels, host.getTitle());
        addLabel(labels, host.getToolName());
        addLabel(labels, host.getEnvironment());
        addDelimited(labels, host.getTags());
        addJsonLabels(labels, host.getRoutingLabelsJson());
        addJsonLabels(labels, host.getCapabilitiesJson());
        return labels;
    }

    private Set<String> datasourceLabels(SqlDatasourceConfig datasource) {
        Set<String> labels = new LinkedHashSet<>();
        addLabel(labels, datasource.getName());
        addLabel(labels, datasource.getTitle());
        addLabel(labels, datasource.getToolName());
        addLabel(labels, datasource.getEnvironment());
        addLabel(labels, datasource.getDatabaseType());
        addJsonLabels(labels, datasource.getRoutingLabelsJson());
        addJsonLabels(labels, datasource.getCapabilitiesJson());
        return labels;
    }

    private Set<String> endpointLabels(HttpEndpointConfig endpoint) {
        Set<String> labels = new LinkedHashSet<>();
        addLabel(labels, endpoint.getName());
        addLabel(labels, endpoint.getTitle());
        addLabel(labels, endpoint.getToolName());
        addLabel(labels, endpoint.getEnvironment());
        addLabel(labels, endpoint.getCategory());
        addDelimited(labels, endpoint.getTags());
        addJsonLabels(labels, endpoint.getRoutingLabelsJson());
        addJsonLabels(labels, endpoint.getCapabilitiesJson());
        return labels;
    }

    private boolean hasAssetScope(Map<String, Object> filters) {
        return firstValue(filters, "assetName", "asset_name", "name", "env", "environment", "cluster", "service", "target",
            "database", "databaseType", "dbType", "dialect", "databaseRole", "database_role", "labels") != null;
    }

    private List<String> contextTokens(Map<String, Object> filters) {
        List<String> tokens = new ArrayList<>();
        for (String key : List.of("cluster", "service", "target", "targetType", "target_type", "database", "databaseRole",
            "database_role", "labels")) {
            Object value = filters.get(key);
            if (value instanceof List<?> list) {
                list.forEach(item -> addToken(tokens, item));
            } else {
                addToken(tokens, value);
            }
        }
        return tokens.stream().distinct().toList();
    }

    private List<String> intentTokens(Map<String, Object> filters) {
        List<String> tokens = new ArrayList<>();
        for (String key : List.of("intent", "goal", "category", "template", "templateId", "template_id", "service")) {
            Object value = filters.get(key);
            if (value instanceof List<?> list) {
                list.forEach(item -> addWords(tokens, item));
            } else {
                addWords(tokens, value);
            }
        }
        return tokens.stream().distinct().toList();
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
            "assetName",
            "asset_name",
            "name",
            "env",
            "environment",
            "cluster",
            "service",
            "target",
            "targetType",
            "target_type",
            "labels",
            "intent",
            "goal",
            "category",
            "database",
            "databaseType",
            "dbType",
            "dialect",
            "databaseRole",
            "database_role",
            "template",
            "templateId",
            "template_id",
            "view"
        )) {
            Object value = arguments.get(key);
            if (value != null) {
                filters.putIfAbsent(key, value);
            }
        }
        return filters;
    }

    private void rejectConcreteTargetFields(Map<String, Object> filters) {
        for (String field : CONCRETE_TARGET_FIELDS) {
            Object value = filters.get(field);
            if (value != null && !String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("Concrete target or raw execution field is not allowed in template_query: " + field);
            }
        }
    }

    private List<String> allowedCommands(SshHostConfig host) {
        if (host.getAllowedCommandsJson() == null || host.getAllowedCommandsJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(host.getAllowedCommandsJson(), new TypeReference<>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> parameterSchema(CommandTemplateConfig template) {
        return parameterSchema(template.getParameterSchemaJson());
    }

    private Map<String, Object> parameterSchema(String parameterSchemaJson) {
        if (parameterSchemaJson == null || parameterSchemaJson.isBlank()) {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }
        try {
            return objectMapper.readValue(parameterSchemaJson, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }
    }

    private List<String> intentSignals(CommandTemplateConfig template) {
        Set<String> signals = new LinkedHashSet<>();
        readStringArray(template.getIntentSignalsJson()).forEach(signal -> addWords(signals, signal));
        addWords(signals, template.getCode());
        addWords(signals, template.getTitle());
        addWords(signals, template.getDescription());
        addWords(signals, category(template));
        return signals.stream().limit(12).toList();
    }

    private List<String> intentSignals(SqlTemplateConfig template) {
        Set<String> signals = new LinkedHashSet<>();
        readStringArray(template.getIntentSignalsJson()).forEach(signal -> addWords(signals, signal));
        addWords(signals, template.getCode());
        addWords(signals, template.getTitle());
        addWords(signals, template.getDescription());
        addWords(signals, category(template));
        return signals.stream().limit(12).toList();
    }

    private List<String> intentSignals(HttpEndpointConfig endpoint) {
        Set<String> signals = new LinkedHashSet<>();
        addWords(signals, endpoint.getToolName());
        addWords(signals, endpoint.getName());
        addWords(signals, endpoint.getTitle());
        addWords(signals, endpoint.getDescription());
        addWords(signals, endpoint.getCategory());
        addDelimited(signals, endpoint.getTags());
        return signals.stream().limit(12).toList();
    }

    private String category(CommandTemplateConfig template) {
        String configured = normalize(template.getCategory());
        if (configured != null) {
            return configured;
        }
        String code = normalize(template.getCode());
        if (code == null) {
            return "system_diagnostic";
        }
        if (code.contains("log")) {
            return "log_diagnostic";
        }
        if (code.contains("service")) {
            return "service_diagnostic";
        }
        if (code.contains("disk") || code.contains("memory") || code.contains("cpu") || code.contains("system")) {
            return "system_diagnostic";
        }
        return "host_diagnostic";
    }

    private String category(SqlTemplateConfig template) {
        String configured = normalize(template.getCategory());
        if (configured != null) {
            return configured;
        }
        String code = normalize(template.getCode());
        if (code != null && code.contains("count")) {
            return "sql_count";
        }
        if (code != null && (code.contains("recent") || code.contains("data"))) {
            return "sql_sample";
        }
        return "sql_diagnostic";
    }

    private String riskLevel(CommandTemplateConfig template) {
        String configured = normalize(template.getRiskLevel());
        if (configured != null) {
            return configured.toUpperCase(Locale.ROOT);
        }
        String code = normalize(template.getCode());
        if (code != null && (code.contains("tail") || code.contains("log") || code.contains("service"))) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String riskLevel(SqlTemplateConfig template) {
        String configured = normalize(template.getRiskLevel());
        if (configured != null) {
            return configured.toUpperCase(Locale.ROOT);
        }
        return "MEDIUM";
    }

    private String httpRiskLevel(HttpEndpointConfig endpoint) {
        String method = normalize(endpoint.getMethod());
        if ("get".equals(method)) {
            return "LOW";
        }
        if ("post".equals(method)) {
            return "MEDIUM";
        }
        return "HIGH";
    }

    private List<String> readStringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<?> values = objectMapper.readValue(json, new TypeReference<>() {});
            return values.stream()
                .map(value -> value == null ? null : String.valueOf(value).trim())
                .filter(value -> value != null && !value.isBlank())
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private boolean matchesSqlTemplateBinding(SqlTemplateConfig template,
                                              Map<String, Object> filters,
                                              List<SqlDatasourceConfig> datasources,
                                              boolean assetScoped) {
        String requestedType = requestedDatabaseType(filters);
        String templateType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(template.getDatabaseType());
        if (requestedType != null && !"generic".equals(templateType) && !templateType.equals(requestedType)) {
            return false;
        }
        String datasourceId = text(template.getDatasourceId());
        List<String> templateLabels = readStringArray(template.getRoutingLabelsJson()).stream()
            .map(this::normalize)
            .filter(value -> value != null)
            .toList();
        if (!assetScoped) {
            return true;
        }
        return datasources.stream().anyMatch(datasource -> {
            Set<String> allowedTemplates = allowedSqlTemplates(datasource);
            if (!allowedTemplates.isEmpty() && !allowedTemplates.contains(normalize(template.getCode()))) {
                return false;
            }
            if (datasourceId != null && !datasourceId.equals(datasource.getId())) {
                return false;
            }
            String datasourceType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType());
            if (!"generic".equals(templateType) && !templateType.equals(datasourceType)) {
                return false;
            }
            if (!templateLabels.isEmpty() && !datasourceLabels(datasource).containsAll(templateLabels)) {
                return false;
            }
            return true;
        });
    }

    private Set<String> allowedSqlTemplates(SqlDatasourceConfig datasource) {
        if (datasource == null || datasource.getAllowedTemplatesJson() == null || datasource.getAllowedTemplatesJson().isBlank()) {
            return Set.of();
        }
        try {
            List<String> values = objectMapper.readValue(datasource.getAllowedTemplatesJson(), new TypeReference<>() {});
            Set<String> allowed = new LinkedHashSet<>();
            values.forEach(value -> {
                String normalized = normalize(value);
                if (normalized != null) {
                    allowed.add(normalized);
                }
            });
            return allowed;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private boolean matchesRequestedDatabaseType(SqlDatasourceConfig datasource, Map<String, Object> filters) {
        String requested = requestedDatabaseType(filters);
        return requested == null || equalsNormalized(requested, datasource.getDatabaseType());
    }

    private String requestedDatabaseType(Map<String, Object> filters) {
        String value = text(firstValue(filters, "databaseType", "dbType", "dialect"));
        return value == null ? null : SqlDatasourceConfigService.normalizeDatabaseTypeToken(value);
    }

    private Map<String, Object> sqlTemplateBindingMetadata(SqlTemplateConfig template) {
        String datasourceId = text(template.getDatasourceId());
        List<String> labels = readStringArray(template.getRoutingLabelsJson());
        String scope = datasourceId != null ? "datasource_asset" : (!labels.isEmpty() ? "routing_labels" : "database_type");
        String assetName = null;
        if (datasourceId != null) {
            assetName = datasourceConfigService.listAll().stream()
                .filter(datasource -> datasourceId.equals(datasource.getId()))
                .map(datasource -> firstText(datasource.getName(), datasource.getToolName()))
                .findFirst()
                .orElse(null);
        }
        return mapOf(
            "scope", scope,
            "assetName", assetName,
            "routingLabels", labels
        );
    }

    private Map<String, Object> compactFilters(Map<String, Object> filters) {
        Map<String, Object> compact = new LinkedHashMap<>();
        filters.forEach((key, value) -> {
            if (value != null && !String.valueOf(value).isBlank()) {
                compact.put(key, value);
            }
        });
        return compact;
    }

    private String view(Map<String, Object> filters) {
        String view = text(firstValue(filters, "view"));
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

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private boolean equalsNormalized(Object first, Object second) {
        String left = normalize(first == null ? null : String.valueOf(first));
        String right = normalize(second == null ? null : String.valueOf(second));
        return left != null && left.equals(right);
    }

    private void addDelimited(Set<String> labels, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String item : value.split("[,;\\s]+")) {
            addLabel(labels, item);
        }
    }

    private void addJsonLabels(Set<String> labels, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() {});
            values.forEach(value -> addLabel(labels, value));
        } catch (Exception ignored) {
            // Stale invalid labels are ignored for discovery.
        }
    }

    private void addLabel(Set<String> labels, Object value) {
        String normalized = normalize(value == null ? null : String.valueOf(value));
        if (normalized != null) {
            labels.add(normalized);
        }
    }

    private void addToken(List<String> tokens, Object value) {
        String normalized = normalize(value == null ? null : String.valueOf(value));
        if (normalized != null) {
            tokens.add(normalized);
        }
    }

    private void addWords(Set<String> words, Object value) {
        addWordsToCollection(words, value);
    }

    private void addWords(List<String> words, Object value) {
        addWordsToCollection(words, value);
    }

    private void addWordsToCollection(java.util.Collection<String> words, Object value) {
        String text = normalize(value == null ? null : String.valueOf(value));
        if (text == null) {
            return;
        }
        words.add(text);
        for (String token : text.split("[^a-z0-9]+")) {
            if (!token.isBlank()) {
                words.add(token);
            }
        }
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
