package com.chatchat.agents.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
            return denied(values, "template discovery requires explicit finalDecision/targetKind/assetType");
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
            return denied(values, "template discovery requires explicit finalDecision/targetKind/assetType");
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
        RetrievalSignals signals = retrievalSignals(result, query);
        mergeListField(result, "intentAliases", signals.aliases());
        mergeListField(result, "keywords", signals.keywords());
        result.putIfAbsent("intentZh", signals.intentZh());
        result.putIfAbsent("intentEn", signals.intentEn());
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
        List<String> values = new ArrayList<>(retrievalSignals(filters, query).bilingualIntent());
        if (!values.isEmpty()) {
            return values.stream().distinct().toList();
        }
        String text = retrievalText(filters, query);
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

    private RetrievalSignals retrievalSignals(Map<String, Object> filters, String query) {
        String text = retrievalText(filters, query);
        String normalized = normalize(text);
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        LinkedHashSet<String> bilingual = new LinkedHashSet<>();
        if (text != null && !text.isBlank()) {
            bilingual.add(text.trim());
        }
        if (normalized == null) {
            return new RetrievalSignals(List.of(), List.of(), List.of(), null, null);
        }
        if (containsAny(normalized, "metadata", "schema", "column", "field", "describe", "show create", "information_schema",
            "\u5143\u6570\u636e", "\u8868\u7ed3\u6784", "\u5b57\u6bb5", "\u5217", "\u7d22\u5f15", "\u7ea6\u675f")) {
            addAll(aliases, "\u8868\u5143\u6570\u636e", "\u8868\u7ed3\u6784", "\u5b57\u6bb5\u4fe1\u606f", "\u7d22\u5f15\u4fe1\u606f",
                "table metadata", "table schema", "column metadata", "DESCRIBE TABLE", "SHOW CREATE TABLE");
            addAll(keywords, "metadata", "schema", "column", "field", "index", "constraint", "DESCRIBE",
                "SHOW COLUMNS", "SHOW CREATE TABLE", "INFORMATION_SCHEMA", "COLUMNS", "\u5143\u6570\u636e", "\u8868\u7ed3\u6784", "\u5b57\u6bb5", "\u7d22\u5f15");
            addAll(bilingual, "\u8868\u5143\u6570\u636e", "\u8868\u7ed3\u6784", "table metadata", "table schema", "column metadata");
        }
        if (normalized.contains("innodb")) {
            addAll(aliases, "\u67e5\u8be2InnoDB\u72b6\u6001", "\u5206\u6790InnoDB\u72b6\u6001", "SHOW ENGINE INNODB STATUS",
                "InnoDB status", "InnoDB engine status", "MySQL InnoDB status");
            addAll(keywords, "InnoDB", "SHOW ENGINE INNODB STATUS", "engine status", "transaction", "lock wait",
                "deadlock", "buffer pool", "\u4e8b\u52a1", "\u9501\u7b49\u5f85", "\u6b7b\u9501", "\u7f13\u51b2\u6c60");
            addAll(bilingual, "\u67e5\u8be2InnoDB\u72b6\u6001", "SHOW ENGINE INNODB STATUS", "InnoDB engine status");
        }
        if (containsAny(normalized, "lock", "blocking", "deadlock", "wait", "\u9501", "\u963b\u585e", "\u7b49\u5f85", "\u6b7b\u9501")) {
            addAll(aliases, "\u9501\u7b49\u5f85", "\u6b7b\u9501\u68c0\u67e5", "lock wait", "deadlock", "blocking sessions");
            addAll(keywords, "lock", "lock wait", "deadlock", "blocking", "transaction", "\u9501", "\u963b\u585e", "\u6b7b\u9501");
            addAll(bilingual, "\u9501\u7b49\u5f85", "lock wait", "deadlock");
        }
        if (containsAny(normalized, "connection", "connections", "session", "processlist", "\u8fde\u63a5", "\u4f1a\u8bdd", "\u8fde\u63a5\u6570")) {
            addAll(aliases, "\u8fde\u63a5\u6570", "\u4f1a\u8bdd\u5217\u8868", "current connections", "session status", "SHOW PROCESSLIST");
            addAll(keywords, "connection", "connections", "session", "processlist", "SHOW PROCESSLIST", "\u8fde\u63a5", "\u4f1a\u8bdd");
            addAll(bilingual, "\u8fde\u63a5\u6570", "current connections", "SHOW PROCESSLIST");
        }
        if (containsAny(normalized, "storage", "size", "space", "capacity", "\u7a7a\u95f4", "\u5bb9\u91cf", "\u5927\u5c0f", "\u5b58\u50a8")) {
            addAll(aliases, "\u6570\u636e\u5e93\u7a7a\u95f4", "\u8868\u5927\u5c0f", "database size", "table size", "storage usage");
            addAll(keywords, "storage", "size", "space", "capacity", "DATA_LENGTH", "INDEX_LENGTH", "\u7a7a\u95f4", "\u5bb9\u91cf");
            addAll(bilingual, "\u6570\u636e\u5e93\u7a7a\u95f4", "database size", "storage usage");
        }
        if (containsAny(normalized, "slow", "latency", "performance", "cpu", "\u6162", "\u5361", "\u6027\u80fd", "\u6162\u67e5\u8be2")) {
            addAll(aliases, "\u6162SQL", "\u6027\u80fd\u5206\u6790", "slow query", "performance diagnostics", "latency");
            addAll(keywords, "slow query", "performance", "latency", "cpu", "query time", "\u6162SQL", "\u6027\u80fd");
            addAll(bilingual, "\u6162SQL", "slow query", "performance diagnostics");
        }
        if (containsAny(normalized, "database", "mysql", "status", "health", "instance", "\u6570\u636e\u5e93", "\u72b6\u6001", "\u5065\u5eb7", "\u5b9e\u4f8b")) {
            addAll(aliases, "\u6570\u636e\u5e93\u72b6\u6001", "\u5b9e\u4f8b\u5065\u5eb7", "database health status", "database status", "instance status");
            addAll(keywords, "database", "mysql", "status", "health", "instance", "SHOW STATUS", "\u6570\u636e\u5e93", "\u72b6\u6001");
            addAll(bilingual, "\u6570\u636e\u5e93\u72b6\u6001", "database health status");
        }
        extractTechnicalTerms(text).forEach(term -> {
            keywords.add(term);
            bilingual.add(term);
        });
        String intentZh = aliases.stream().filter(this::containsChinese).findFirst().orElse(text);
        String intentEn = aliases.stream().filter(value -> !containsChinese(value)).findFirst().orElse(null);
        return new RetrievalSignals(
            bilingual.stream().distinct().toList(),
            aliases.stream().distinct().toList(),
            keywords.stream().distinct().toList(),
            intentZh,
            intentEn
        );
    }

    private String retrievalText(Map<String, Object> filters, String query) {
        Object intent = firstValue(filters, "intent", "goal", "category");
        return intent == null ? query : String.valueOf(intent);
    }

    private void mergeListField(Map<String, Object> values, String field, List<String> additions) {
        if (values == null || additions == null || additions.isEmpty()) {
            return;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        Object existing = values.get(field);
        if (existing instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    merged.add(String.valueOf(item));
                }
            }
        } else if (existing != null && !String.valueOf(existing).isBlank()) {
            merged.add(String.valueOf(existing));
        }
        merged.addAll(additions);
        if (!merged.isEmpty()) {
            values.put(field, new ArrayList<>(merged));
        }
    }

    private List<String> extractTechnicalTerms(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String token : text.split("[^A-Za-z0-9_]+")) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalized = token.toLowerCase(Locale.ROOT);
            if (token.contains("_") || token.equals(token.toUpperCase(Locale.ROOT))
                || List.of("mysql", "innodb", "sql", "ddl", "dml").contains(normalized)) {
                terms.add(token);
            }
        }
        return terms.stream().toList();
    }

    private boolean containsAny(String text, String... probes) {
        if (text == null || probes == null) {
            return false;
        }
        for (String probe : probes) {
            if (probe != null && !probe.isBlank() && text.contains(probe.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void addAll(LinkedHashSet<String> target, String... values) {
        if (target == null || values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                target.add(value);
            }
        }
    }

    private boolean containsChinese(String value) {
        return value != null && value.codePoints().anyMatch(codePoint ->
            Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
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
            "api", "api_service",
            "api_service", "api_service",
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

    private record RetrievalSignals(
        List<String> bilingualIntent,
        List<String> aliases,
        List<String> keywords,
        String intentZh,
        String intentEn
    ) {
    }
}
