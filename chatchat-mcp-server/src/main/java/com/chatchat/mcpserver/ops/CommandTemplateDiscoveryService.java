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
import java.util.Comparator;
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
    private final TemplateDiscoveryProperties properties;

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
        List<ScoredTemplate<CommandTemplateConfig>> matched = templateService.listEnabled().stream()
            .filter(template -> !assetScoped || allowedByAsset.contains(normalize(template.getCode())))
            .map(template -> new ScoredTemplate<>(template, relevance(template, filters)))
            .filter(this::matchesIntent)
            .sorted(scoredComparator(scored -> scored.template().getCode()))
            .toList();
        return result(assetType, filters, limit, sshTemplateMetadata(matched, assetType, limit), matched.size() > limit);
    }

    private Map<String, Object> querySqlTemplates(String assetType, Map<String, Object> filters, int limit) {
        boolean assetScoped = hasAssetScope(filters);
        List<SqlDatasourceConfig> datasources = matchingDatasources(filters);
        if (assetScoped && datasources.isEmpty()) {
            return result(assetType, filters, limit, List.of(), false);
        }
        List<ScoredTemplate<SqlTemplateConfig>> matched = sqlTemplateService.listEnabled().stream()
            .filter(template -> matchesSqlTemplateBinding(template, filters, datasources, assetScoped))
            .map(template -> new ScoredTemplate<>(template, relevance(template, filters)))
            .filter(this::matchesIntent)
            .sorted(scoredComparator(scored -> scored.template().getCode()))
            .toList();
        return result(assetType, filters, limit, sqlTemplateMetadata(matched, assetType, limit), matched.size() > limit);
    }

    private Map<String, Object> queryHttpTemplates(String assetType, Map<String, Object> filters, int limit) {
        List<ScoredTemplate<HttpEndpointConfig>> matched = httpEndpointConfigService.listEnabled().stream()
            .filter(endpoint -> matchesHttpEndpoint(endpoint, filters))
            .map(endpoint -> new ScoredTemplate<>(endpoint, relevance(endpoint, filters)))
            .filter(this::matchesIntent)
            .sorted(scoredComparator(scored -> firstText(scored.template().getToolName(), scored.template().getName())))
            .toList();
        return result(assetType, filters, limit, httpTemplateMetadata(matched, assetType, limit), matched.size() > limit);
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
            "templateSelectionPolicy", mapOf(
                "templateIdSource", "templates[].templateId",
                "mustUseReturnedTemplateId", true,
                "doNotInventTemplateNames", true,
                "orderedBy", "templates[] is ranked by relevanceScore desc, then lower risk, then templateId",
                "intentSynonymSource", "chatchat.mcp.template-discovery.intent-synonyms plus template intentSignals",
                "selectionHint", "Choose the returned template whose name, description, intentSignals, relevanceScore, and matchReasons best match the user intent; do not use asset allowed template order as semantic ranking.",
                "onEmptyResult", "No existing authorized template matched the request. Do not suggest a new template name unless the user asks to administer templates."
            ),
            "templates", templateMetadata
        );
    }

    private List<Map<String, Object>> sshTemplateMetadata(List<ScoredTemplate<CommandTemplateConfig>> scored,
                                                          String assetType,
                                                          int limit) {
        List<Map<String, Object>> metadata = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, scored.size()); index++) {
            ScoredTemplate<CommandTemplateConfig> item = scored.get(index);
            metadata.add(templateMetadata(item.template(), assetType, item.relevance(), index + 1));
        }
        return metadata;
    }

    private List<Map<String, Object>> sqlTemplateMetadata(List<ScoredTemplate<SqlTemplateConfig>> scored,
                                                          String assetType,
                                                          int limit) {
        List<Map<String, Object>> metadata = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, scored.size()); index++) {
            ScoredTemplate<SqlTemplateConfig> item = scored.get(index);
            metadata.add(templateMetadata(item.template(), assetType, item.relevance(), index + 1));
        }
        return metadata;
    }

    private List<Map<String, Object>> httpTemplateMetadata(List<ScoredTemplate<HttpEndpointConfig>> scored,
                                                           String assetType,
                                                           int limit) {
        List<Map<String, Object>> metadata = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, scored.size()); index++) {
            ScoredTemplate<HttpEndpointConfig> item = scored.get(index);
            metadata.add(templateMetadata(item.template(), assetType, item.relevance(), index + 1));
        }
        return metadata;
    }

    private Map<String, Object> templateMetadata(CommandTemplateConfig template,
                                                 String assetType,
                                                 Relevance relevance,
                                                 int rank) {
        List<String> signals = intentSignals(template);
        return mapOf(
            "schemaVersion", TEMPLATE_SCHEMA_VERSION,
            "templateId", template.getCode(),
            "name", firstText(template.getTitle(), template.getCode()),
            "description", firstText(template.getDescription(), ""),
            "rank", rank,
            "relevanceScore", relevance.score(),
            "matchReasons", relevance.reasons(),
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

    private Map<String, Object> templateMetadata(SqlTemplateConfig template,
                                                 String assetType,
                                                 Relevance relevance,
                                                 int rank) {
        List<String> signals = intentSignals(template);
        return mapOf(
            "schemaVersion", TEMPLATE_SCHEMA_VERSION,
            "templateId", template.getCode(),
            "name", firstText(template.getTitle(), template.getCode()),
            "description", firstText(template.getDescription(), ""),
            "rank", rank,
            "relevanceScore", relevance.score(),
            "matchReasons", relevance.reasons(),
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

    private Map<String, Object> templateMetadata(HttpEndpointConfig endpoint,
                                                 String assetType,
                                                 Relevance relevance,
                                                 int rank) {
        List<String> signals = intentSignals(endpoint);
        String templateId = firstText(endpoint.getToolName(), firstText(endpoint.getName(), endpoint.getId()));
        return mapOf(
            "schemaVersion", TEMPLATE_SCHEMA_VERSION,
            "templateId", templateId,
            "name", firstText(endpoint.getTitle(), templateId),
            "description", firstText(endpoint.getDescription(), ""),
            "rank", rank,
            "relevanceScore", relevance.score(),
            "matchReasons", relevance.reasons(),
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

    private Relevance relevance(CommandTemplateConfig template, Map<String, Object> filters) {
        return relevanceText(
            template.getCode(),
            template.getTitle(),
            template.getDescription(),
            category(template),
            intentSignals(template),
            filters
        );
    }

    private Relevance relevance(SqlTemplateConfig template, Map<String, Object> filters) {
        return relevanceText(
            template.getCode(),
            template.getTitle(),
            template.getDescription(),
            category(template),
            intentSignals(template),
            filters
        );
    }

    private Relevance relevance(HttpEndpointConfig endpoint, Map<String, Object> filters) {
        return relevanceText(
            firstText(endpoint.getToolName(), endpoint.getName()),
            endpoint.getTitle(),
            endpoint.getDescription(),
            firstText(endpoint.getCategory(), "http_request"),
            intentSignals(endpoint),
            filters
        );
    }

    private Relevance relevanceText(String code,
                                    String title,
                                    String description,
                                    String category,
                                    List<String> signals,
                                    Map<String, Object> filters) {
        List<String> tokens = intentTokens(filters);
        if (tokens.isEmpty()) {
            return new Relevance(0, List.of("no_intent_filter"));
        }
        Map<String, Integer> weightedFields = new LinkedHashMap<>();
        weightedFields.put(firstText(code, ""), 35);
        weightedFields.put(firstText(title, ""), 30);
        weightedFields.put(firstText(description, ""), 24);
        weightedFields.put(firstText(category, ""), 14);
        signals.forEach(signal -> weightedFields.put(signal, 28));
        int score = 0;
        Set<String> reasons = new LinkedHashSet<>();
        for (String token : tokens) {
            int bestWeight = 0;
            String bestField = null;
            for (Map.Entry<String, Integer> entry : weightedFields.entrySet()) {
                MatchStrength strength = matchStrength(entry.getKey(), token);
                if (strength == MatchStrength.NONE) {
                    continue;
                }
                int weight = strength == MatchStrength.WORD ? entry.getValue() : Math.max(1, entry.getValue() / 2);
                if (weight > bestWeight) {
                    bestWeight = weight;
                    bestField = entry.getKey();
                }
            }
            if (bestWeight > 0) {
                score += bestWeight;
                reasons.add("matched intent token '" + token + "' in '" + truncateReason(bestField) + "'");
            }
        }
        return new Relevance(score, reasons.stream().limit(8).toList());
    }

    private boolean matchesIntent(ScoredTemplate<?> scored) {
        return scored.relevance().score() > 0 || scored.relevance().reasons().contains("no_intent_filter");
    }

    private <T> Comparator<ScoredTemplate<T>> scoredComparator(java.util.function.Function<ScoredTemplate<T>, String> idExtractor) {
        return Comparator
            .<ScoredTemplate<T>>comparingInt(scored -> scored.relevance().score()).reversed()
            .thenComparingInt(scored -> riskPriority(riskLevelOf(scored.template())))
            .thenComparing(scored -> firstText(idExtractor.apply(scored), ""));
    }

    private MatchStrength matchStrength(String fieldText, String token) {
        String normalizedToken = normalize(token);
        String normalizedField = normalize(fieldText);
        if (normalizedToken == null || normalizedField == null) {
            return MatchStrength.NONE;
        }
        Set<String> words = new LinkedHashSet<>();
        addWords(words, normalizedField);
        if (words.contains(normalizedToken)) {
            return MatchStrength.WORD;
        }
        if (normalizedToken.length() >= 2 && normalizedField.contains(normalizedToken)) {
            return MatchStrength.SUBSTRING;
        }
        return MatchStrength.NONE;
    }

    private String truncateReason(String value) {
        String text = firstText(value, "");
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }

    private String riskLevelOf(Object template) {
        if (template instanceof CommandTemplateConfig commandTemplate) {
            return riskLevel(commandTemplate);
        }
        if (template instanceof SqlTemplateConfig sqlTemplate) {
            return riskLevel(sqlTemplate);
        }
        if (template instanceof HttpEndpointConfig endpoint) {
            return httpRiskLevel(endpoint);
        }
        return "LOW";
    }

    private int riskPriority(String riskLevel) {
        return switch (firstText(riskLevel, "LOW").toUpperCase(Locale.ROOT)) {
            case "LOW" -> 0;
            case "MEDIUM" -> 1;
            case "HIGH" -> 2;
            case "CRITICAL" -> 3;
            default -> 4;
        };
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
        addConfiguredIntentSynonyms(words, text);
    }

    private void addConfiguredIntentSynonyms(java.util.Collection<String> words, String text) {
        if (properties == null || properties.getIntentSynonyms() == null || properties.getIntentSynonyms().isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : properties.getIntentSynonyms().entrySet()) {
            List<String> values = entry.getValue();
            if (!containsAny(text, entry.getKey()) && !containsAny(text, values == null ? List.of() : values)) {
                continue;
            }
            addNormalizedWord(words, entry.getKey());
            if (values != null) {
                values.forEach(value -> addNormalizedWord(words, value));
            }
        }
    }

    private boolean containsAny(String text, List<String> probes) {
        if (probes == null || probes.isEmpty()) {
            return false;
        }
        return containsAny(text, probes.toArray(String[]::new));
    }

    private boolean containsAny(String text, String... probes) {
        if (text == null || probes == null) {
            return false;
        }
        for (String probe : probes) {
            String normalized = normalize(probe);
            if (normalized != null && text.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private void addNormalizedWord(java.util.Collection<String> words, Object value) {
        String normalized = normalize(value == null ? null : String.valueOf(value));
        if (normalized != null) {
            words.add(normalized);
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

    private record ScoredTemplate<T>(T template, Relevance relevance) {
    }

    private record Relevance(int score, List<String> reasons) {
    }

    private enum MatchStrength {
        NONE,
        SUBSTRING,
        WORD
    }
}
