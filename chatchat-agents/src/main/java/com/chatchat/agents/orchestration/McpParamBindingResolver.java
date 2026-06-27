package com.chatchat.agents.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class McpParamBindingResolver {

    private static final double MIN_CONFIDENCE = 0.6;
    private static final String FILTERS_SCHEMA_VERSION = "target_filters.v1";

    Map<String, Object> resolve(String toolName, Object toolMetadata, Map<String, Object> arguments, String query) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        String normalizedTool = normalize(toolName);
        if (isTemplateQuery(normalizedTool)) {
            return bindTemplateQuery(values, query);
        }
        if (isLinuxGateway(normalizedTool)) {
            return bindLinuxGateway(values, query);
        }
        if (isSqlGateway(normalizedTool)) {
            return bindSqlGateway(values, query);
        }
        return values;
    }

    private Map<String, Object> bindTemplateQuery(Map<String, Object> values, String query) {
        String targetKind = text(firstValue(values, "finalDecision", "targetKind"));
        String assetType = text(values.get("assetType"));
        if (targetKind == null && assetType == null) {
            return denied(values, "template_query requires explicit finalDecision/targetKind/assetType");
        }
        if (targetKind != null && !targetAssetTypes().containsKey(normalize(targetKind))) {
            return denied(values, "Unsupported targetKind: " + targetKind);
        }
        String expectedAssetType = targetKind == null ? normalize(assetType) : targetAssetTypes().get(normalize(targetKind));
        if (assetType != null && expectedAssetType != null && !expectedAssetType.equals(normalize(assetType))) {
            return denied(values, "targetKind=" + targetKind + " maps to assetType=" + expectedAssetType
                + ", but request assetType=" + assetType);
        }
        double confidence = confidence(values, targetKind);
        if (confidence < MIN_CONFIDENCE) {
            Map<String, Object> review = new LinkedHashMap<>(values);
            review.put("__runtimeParamBindingStatus", "REVIEW_REQUIRED");
            review.put("__runtimeParamBindingCode", "MCP_ROUTING_REVIEW_REQUIRED");
            review.put("__runtimeParamBindingError", "confidence below routing threshold");
            return review;
        }
        if (values.containsKey("candidates") && text(values.get("finalDecision")) == null && text(values.get("targetKind")) == null) {
            return denied(values, "template_query requires explicit finalDecision/targetKind/assetType");
        }
        Map<String, Object> result = new LinkedHashMap<>(values);
        if (targetKind == null) {
            targetKind = targetKindForAssetType(assetType);
        }
        result.put("targetKind", normalize(targetKind));
        if (values.containsKey("finalDecision")) {
            result.put("finalDecision", normalize(values.get("finalDecision")));
        }
        result.put("assetType", expectedAssetType);
        result.put("confidence", confidence);
        result.put("filtersSchemaVersion", FILTERS_SCHEMA_VERSION);
        result.putIfAbsent("limit", 10);
        result.put("filters", bindFilters(map(values.get("filters")), query));
        return result;
    }

    private Map<String, Object> bindLinuxGateway(Map<String, Object> values, String query) {
        String forbidden = firstPresentKey(values, "host", "hostname", "ip", "ipAddress", "command", "rawCommand", "shell");
        if (forbidden != null) {
            return denied(values, "Concrete target or raw command field is not allowed: " + forbidden);
        }
        Map<String, Object> result = new LinkedHashMap<>(values);
        result.remove("query");
        putIfAbsent(result, "reason", query);
        Map<String, Object> executionContext = map(result.get("executionContext"));
        enrichContext(executionContext, query);
        if (!executionContext.isEmpty()) {
            result.put("executionContext", executionContext);
        }
        Map<String, Object> parameters = map(result.get("parameters"));
        String service = text(executionContext.get("service"));
        if ("hive".equals(normalize(service))) {
            parameters.putIfAbsent("serviceName", "hive-server2");
        } else if (service != null) {
            parameters.putIfAbsent("serviceName", service);
        }
        if (!parameters.isEmpty()) {
            result.put("parameters", parameters);
        }
        return result;
    }

    private Map<String, Object> bindSqlGateway(Map<String, Object> values, String query) {
        Map<String, Object> result = new LinkedHashMap<>(values);
        Object templateId = result.remove("templateId");
        if (templateId != null && !result.containsKey("template")) {
            result.put("template", templateId);
        }
        putIfAbsent(result, "purpose", query);
        Map<String, Object> executionContext = map(firstValue(result, "executionContext", "filters"));
        enrichContext(executionContext, query);
        if (!executionContext.isEmpty()) {
            result.put("executionContext", executionContext);
        }
        result.putIfAbsent("parameters", Map.of());
        return result;
    }

    private Map<String, Object> bindFilters(Map<String, Object> filters, String query) {
        Map<String, Object> result = new LinkedHashMap<>(filters);
        enrichContext(result, query);
        if (!result.containsKey("intent")) {
            String intent = inferIntent(query);
            if (intent != null) {
                result.put("intent", intent);
            }
        }
        if (!result.containsKey("bilingualIntent")) {
            List<String> bilingual = bilingualIntent(result, query);
            if (!bilingual.isEmpty()) {
                result.put("bilingualIntent", bilingual);
            }
        }
        return result;
    }

    private void enrichContext(Map<String, Object> context, String query) {
        String normalized = normalize(query);
        if (normalized == null) {
            return;
        }
        if (!context.containsKey("env")) {
            if (normalized.contains("prod") || normalized.contains("production")) {
                context.put("env", "prod");
            } else if (normalized.contains("dev")) {
                context.put("env", "DEV");
            }
        }
        if (!context.containsKey("service") && normalized.contains("hive")) {
            context.put("service", "hive");
        }
    }

    private String inferIntent(String query) {
        String normalized = normalize(query);
        if (normalized == null) {
            return null;
        }
        if (normalized.contains("service") && normalized.contains("status")) {
            return "service status";
        }
        if (normalized.contains("database") && (normalized.contains("status") || normalized.contains("health"))) {
            return "database status";
        }
        return query == null || query.isBlank() ? null : query.trim();
    }

    private List<String> bilingualIntent(Map<String, Object> filters, String query) {
        List<String> values = new ArrayList<>();
        Object intent = firstValue(filters, "intent", "goal", "category");
        String text = intent == null ? query : String.valueOf(intent);
        String normalized = normalize(text);
        if (normalized == null) {
            return values;
        }
        if (normalized.contains("database") && (normalized.contains("status") || normalized.contains("health"))) {
            values.add("\u6570\u636e\u5e93\u72b6\u6001");
            values.add("database health status");
        } else if (normalized.contains("service") && normalized.contains("status")) {
            values.add("\u670d\u52a1\u72b6\u6001");
            values.add("service status");
        } else {
            values.add(text.trim());
        }
        return values.stream().distinct().toList();
    }

    private double confidence(Map<String, Object> values, String targetKind) {
        Object value = values.get("confidence");
        if (value != null) {
            return number(value, 1.0);
        }
        Object candidates = values.get("candidates");
        if (candidates instanceof List<?> list) {
            String selected = normalize(firstValue(values, "finalDecision", "targetKind"));
            return list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> map(item))
                .filter(map -> selected == null || selected.equals(normalize(map.get("targetKind"))))
                .mapToDouble(map -> number(map.get("confidence"), 0.0))
                .max()
                .orElse(1.0);
        }
        return 1.0;
    }

    private Map<String, String> targetAssetTypes() {
        return Map.of(
            "host", "ssh_host",
            "database", "sql_datasource",
            "http", "http_endpoint",
            "business_database_query", "database_query"
        );
    }

    private String targetKindForAssetType(String assetType) {
        String normalized = normalize(assetType);
        for (Map.Entry<String, String> entry : targetAssetTypes().entrySet()) {
            if (entry.getValue().equals(normalized)) {
                return entry.getKey();
            }
        }
        return normalized;
    }

    private boolean isTemplateQuery(String toolName) {
        return toolName != null && toolName.endsWith("template_query");
    }

    private boolean isLinuxGateway(String toolName) {
        return toolName != null && toolName.endsWith("linux_command_execute");
    }

    private boolean isSqlGateway(String toolName) {
        return toolName != null && toolName.endsWith("sql_query_execute");
    }

    private Map<String, Object> denied(Map<String, Object> values, String message) {
        Map<String, Object> result = new LinkedHashMap<>(values);
        result.put("__runtimeParamBindingStatus", "DENIED");
        result.put("__runtimeParamBindingCode", "MCP_PARAM_BINDING_DENIED");
        result.put("__runtimeParamBindingError", message);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private Object firstValue(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key) && values.get(key) != null && !String.valueOf(values.get(key)).isBlank()) {
                return values.get(key);
            }
        }
        return null;
    }

    private void putIfAbsent(Map<String, Object> values, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            values.putIfAbsent(key, value);
        }
    }

    private String firstPresentKey(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key) && values.get(key) != null && !String.valueOf(values.get(key)).isBlank()) {
                return key;
            }
        }
        return null;
    }

    private double number(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String normalize(Object value) {
        String text = text(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }
}
