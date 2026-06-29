package com.chatchat.agents.orchestration;

import com.chatchat.common.tool.ToolMetadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Normalizes loose planner arguments into the logical MCP gateway contracts.
 */
class McpParamBindingResolver {

    static final String STATUS_KEY = "__runtimeParamBindingStatus";
    static final String ERROR_KEY = "__runtimeParamBindingError";
    static final String CODE_KEY = "__runtimeParamBindingCode";

    private static final Set<String> MCP_CATEGORIES = Set.of("mcp");
    private static final List<String> LOGICAL_CONTEXT_KEYS = List.of(
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
        "hostSelector",
        "host_selector",
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
    private static final List<String> RAW_EXECUTION_FIELDS = List.of(
        "command",
        "rawCommand",
        "shell",
        "sql",
        "rawSql",
        "body",
        "bodyTemplate"
    );
    private static final List<String> TARGET_KIND_FIELDS = List.of(
        "targetKind",
        "target_kind",
        "queryDomain",
        "query_domain",
        "domain",
        "resourceType",
        "resource_type",
        "resourceKind",
        "resource_kind"
    );
    private static final String FILTERS_SCHEMA_VERSION = "target_filters.v1";
    private static final double TARGET_KIND_CONFIDENCE_THRESHOLD = 0.60;

    Map<String, Object> resolve(String toolName,
                                ToolMetadata metadata,
                                Map<String, Object> arguments,
                                String userQuery) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        if (!isMcpTool(toolName, metadata)) {
            return values;
        }
        String remoteToolName = remoteToolName(toolName, metadata);
        if (sameTool(remoteToolName, "linux_command_execute")) {
            return bindLinuxCommand(values, userQuery);
        }
        if (sameTool(remoteToolName, "http_request_execute")) {
            return bindHttpRequest(values, userQuery);
        }
        if (sameTool(remoteToolName, "sql_query_execute")) {
            return bindSqlQuery(values, userQuery);
        }
        if (sameTool(remoteToolName, "asset_query")) {
            return bindDiscoveryQuery(toolName, values, userQuery, false);
        }
        if (sameTool(remoteToolName, "template_query")) {
            return bindDiscoveryQuery(toolName, values, userQuery, true);
        }
        return values;
    }

    private Map<String, Object> bindSqlQuery(Map<String, Object> values, String userQuery) {
        String forbidden = firstPresentField(values, List.of(
            "hostId", "host", "hostname", "ip", "ipAddress", "address", "datasourceId", "jdbcUrl", "connectionString"
        ));
        if (forbidden != null) {
            return denied(values, "Concrete datasource target is not allowed for sql_query_execute: " + forbidden);
        }
        renameFirst(values, "template", "templateId", "template_id", "sqlTemplate", "sql_template");
        Map<String, Object> context = logicalExecutionContext(values, userQuery);
        if (!context.isEmpty()) {
            values.put("executionContext", context);
        }
        if (!hasText(values.get("purpose")) && hasText(userQuery)) {
            values.put("purpose", trim(userQuery));
        }
        values.remove("query");
        return values;
    }

    private Map<String, Object> bindLinuxCommand(Map<String, Object> values, String userQuery) {
        String forbidden = firstPresentField(values, CONCRETE_TARGET_FIELDS);
        if (forbidden != null) {
            return denied(values, "Concrete execution target is not allowed for linux_command_execute: " + forbidden);
        }
        String rawExecution = firstPresentField(values, RAW_EXECUTION_FIELDS);
        if (rawExecution != null) {
            return denied(values, "Raw execution field is not allowed for linux_command_execute: " + rawExecution
                + ". Use a registered template plus parameters.");
        }

        renameFirst(values, "template", "templateId", "template_id", "commandTemplate", "command_template");
        Map<String, Object> context = logicalExecutionContext(values, userQuery);
        if (!context.isEmpty()) {
            values.put("executionContext", context);
        }
        normalizeParameters(values, userQuery);
        if (!hasText(values.get("reason")) && hasText(userQuery)) {
            values.put("reason", trim(userQuery));
        }
        values.remove("query");
        return values;
    }

    private Map<String, Object> bindHttpRequest(Map<String, Object> values, String userQuery) {
        String forbidden = firstPresentField(values, CONCRETE_TARGET_FIELDS);
        if (forbidden != null) {
            return denied(values, "Concrete endpoint target is not allowed for http_request_execute: " + forbidden);
        }
        renameFirst(values, "template", "templateId", "template_id", "endpoint", "endpointName");
        Map<String, Object> context = logicalExecutionContext(values, userQuery);
        if (!context.isEmpty()) {
            values.put("executionContext", context);
        }
        normalizeParameters(values, userQuery);
        if (!hasText(values.get("reason")) && hasText(userQuery)) {
            values.put("reason", trim(userQuery));
        }
        values.remove("query");
        return values;
    }

    private Map<String, Object> bindDiscoveryQuery(String toolName,
                                                   Map<String, Object> values,
                                                   String userQuery,
                                                   boolean templateQuery) {
        String forbidden = firstPresentField(values, CONCRETE_TARGET_FIELDS);
        if (forbidden != null) {
            return denied(values, "Concrete target field is not allowed for discovery: " + forbidden);
        }
        if (templateQuery) {
            String rawExecution = firstPresentField(values, RAW_EXECUTION_FIELDS);
            if (rawExecution != null) {
                return denied(values, "Raw execution field is not allowed for template_query: " + rawExecution);
            }
        }

        String targetKind = removeTargetKind(values);
        Object rawCandidates = firstPresent(values, "candidates", "routingCandidates", "routing_candidates");
        String finalDecision = firstText(firstPresent(values, "finalDecision", "final_decision", "selectedTargetKind", "selected_target_kind"));
        if (targetKind == null) {
            targetKind = finalDecision;
        }
        Object explicitFilterEnvelope = firstPresent(values, "filters", "executionContext", "mcpExecutionContext");
        if (!(explicitFilterEnvelope instanceof Map<?, ?>)) {
            String toolTargetKind = targetKindFromDiscoveryToolName(toolName, templateQuery);
            if (hasText(values.get("query")) && toolTargetKind != null) {
                targetKind = firstNonBlank(targetKind, toolTargetKind);
                Map<String, Object> filters = new LinkedHashMap<>();
                inferLogicalContext(userQuery).forEach(filters::putIfAbsent);
                if (templateQuery && hasText(userQuery)) {
                    filters.putIfAbsent("intent", trim(userQuery));
                } else if (hasText(userQuery)) {
                    filters.putIfAbsent("intent", trim(userQuery));
                }
                values.put("filters", filters);
                values.putIfAbsent("candidates", List.of(Map.of("targetKind", targetKind, "confidence", 0.9)));
                values.putIfAbsent("finalDecision", targetKind);
                values.putIfAbsent("confidence", 0.9);
                values.putIfAbsent("trace", Map.of(
                    "schemaVersion", "routing_trace.v1",
                    "source", "agent_tool_argument_resolver",
                    "toolName", toolName == null ? "" : toolName
                ));
                explicitFilterEnvelope = filters;
                rawCandidates = firstPresent(values, "candidates", "routingCandidates", "routing_candidates");
                finalDecision = firstText(firstPresent(values, "finalDecision", "final_decision", "selectedTargetKind", "selected_target_kind"));
            }
        }
        if (!(explicitFilterEnvelope instanceof Map<?, ?>)) {
            return denied(values, (templateQuery ? "template_query" : "asset_query")
                + " requires explicit filters object, even when it is empty.");
        }
        if (targetKind == null && !hasText(values.get("assetType"))) {
            return denied(values, (templateQuery ? "template_query" : "asset_query")
                + " requires explicit finalDecision/targetKind/assetType. Use finalDecision="
                + (templateQuery ? "host, database, http, or business_database_query" : "host, database, or http")
                + "; use document_search for targetKind=document.");
        }
        Double confidence = confidence(values.get("confidence"));
        if (confidence == null) {
            confidence = candidateConfidence(rawCandidates, targetKind);
            if (confidence != null) {
                values.put("confidence", confidence);
            }
        }
        if (confidence == null) {
            return denied(values, (templateQuery ? "template_query" : "asset_query")
                + " requires confidence between 0.0 and 1.0.");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            return denied(values, "confidence must be between 0.0 and 1.0: " + confidence);
        }
        if (!(firstPresent(values, "trace", "routingTrace", "routing_trace") instanceof Map<?, ?> trace) || trace.isEmpty()) {
            return denied(values, (templateQuery ? "template_query" : "asset_query")
                + " requires trace object for replayable routing.");
        }
        Map<String, Object> filters = filters(values);
        inferLogicalContext(userQuery).forEach(filters::putIfAbsent);
        if (targetKind == null) {
            targetKind = removeTargetKind(filters);
        }
        if (templateQuery && !hasText(firstPresent(filters, "intent", "goal", "category"))) {
            String intent = hasText(userQuery) ? trim(userQuery) : inferIntent(userQuery);
            if (intent != null) {
                filters.put("intent", intent);
            }
        }
        if (!filters.isEmpty()) {
            values.put("filters", filters);
        } else if (values.containsKey("query")) {
            values.put("filters", Map.of());
        }
        values.putIfAbsent("filtersSchemaVersion", FILTERS_SCHEMA_VERSION);
        if (!hasText(values.get("assetType"))) {
            String assetType = assetTypeFromTargetKind(targetKind);
            if (assetType != null) {
                values.put("assetType", assetType);
                values.put("targetKind", normalizeTargetKind(targetKind));
                values.putIfAbsent("finalDecision", normalizeTargetKind(targetKind));
            } else if (hasText(targetKind)) {
                return denied(values, "Unsupported targetKind for " + (templateQuery ? "template_query" : "asset_query")
                    + ": " + targetKind + ". Allowed targetKind values are "
                    + (templateQuery ? "host, database, http, business_database_query" : "host, database, http")
                    + "; use document_search for targetKind=document.");
            } else {
                return denied(values, (templateQuery ? "template_query" : "asset_query")
                    + " requires explicit finalDecision/targetKind/assetType. Use finalDecision="
                    + (templateQuery ? "host, database, http, or business_database_query" : "host, database, or http")
                    + "; use document_search for targetKind=document.");
            }
        } else if (targetKind != null) {
            String normalizedTargetKind = normalizeTargetKind(targetKind);
            String expectedAssetType = assetTypeFromTargetKind(normalizedTargetKind);
            if (expectedAssetType == null) {
                return denied(values, "Unsupported targetKind for " + (templateQuery ? "template_query" : "asset_query")
                    + ": " + targetKind + ". Allowed targetKind values are "
                    + (templateQuery ? "host, database, http, business_database_query" : "host, database, http")
                    + "; use document_search for targetKind=document.");
            }
            String providedAssetType = normalizeAssetType(values.get("assetType") == null ? null : String.valueOf(values.get("assetType")));
            if (providedAssetType != null && !providedAssetType.equals(expectedAssetType)) {
                return denied(values, "targetKind=" + normalizedTargetKind + " maps to assetType="
                    + expectedAssetType + ", but request provided assetType=" + providedAssetType + ".");
            }
            values.put("assetType", providedAssetType);
            values.put("targetKind", normalizedTargetKind);
            values.putIfAbsent("finalDecision", normalizedTargetKind);
        }
        if (confidence < TARGET_KIND_CONFIDENCE_THRESHOLD) {
            return reviewRequired(values, "confidence below routing threshold: " + confidence);
        }
        values.putIfAbsent("limit", 10);
        values.remove("query");
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> logicalExecutionContext(Map<String, Object> values, String userQuery) {
        Map<String, Object> context = new LinkedHashMap<>();
        Object existing = firstPresent(values, "executionContext", "mcpExecutionContext");
        if (existing instanceof Map<?, ?> map) {
            context.putAll((Map<String, Object>) map);
        }
        for (String key : LOGICAL_CONTEXT_KEYS) {
            Object value = values.remove(key);
            if (value != null && hasText(value)) {
                context.putIfAbsent(key, value);
            }
        }
        inferLogicalContext(userQuery).forEach(context::putIfAbsent);
        removeForbidden(context);
        return context;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filters(Map<String, Object> values) {
        Map<String, Object> filters = new LinkedHashMap<>();
        Object existing = firstPresent(values, "filters", "executionContext", "mcpExecutionContext");
        if (existing instanceof Map<?, ?> map) {
            filters.putAll((Map<String, Object>) map);
        }
        for (String key : LOGICAL_CONTEXT_KEYS) {
            Object value = values.remove(key);
            if (value != null && hasText(value)) {
                filters.putIfAbsent(key, value);
            }
        }
        removeForbidden(filters);
        return filters;
    }

    @SuppressWarnings("unchecked")
    private void normalizeParameters(Map<String, Object> values, String userQuery) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        Object existing = values.get("parameters");
        if (existing instanceof Map<?, ?> map) {
            parameters.putAll((Map<String, Object>) map);
        }
        moveIfPresent(values, parameters, "serviceName", "service_name");
        moveIfPresent(values, parameters, "path", "filePath", "file_path", "logPath", "log_path");
        moveIfPresent(values, parameters, "lines", "tailLines", "tail_lines", "limit");
        moveIfPresent(values, parameters, "keyword", "keywords", "pattern");
        String serviceName = firstText(parameters.get("serviceName"));
        if (serviceName == null) {
            String service = firstText(firstPresent(asMap(values.get("executionContext")), "service", "target"));
            serviceName = canonicalServiceName(service);
            if (serviceName == null) {
                serviceName = canonicalServiceName(inferService(userQuery));
            }
            if (serviceName != null && looksServiceTemplate(values.get("template"))) {
                parameters.put("serviceName", serviceName);
            }
        }
        if (!parameters.isEmpty()) {
            values.put("parameters", parameters);
        }
    }

    private Map<String, Object> inferLogicalContext(String query) {
        Map<String, Object> context = new LinkedHashMap<>();
        String env = inferEnvironment(query);
        if (env != null) {
            context.put("env", env);
        }
        String service = inferService(query);
        if (service != null) {
            context.put("service", service);
        }
        return context;
    }

    private String inferEnvironment(String query) {
        String text = normalize(query);
        if (text == null) {
            return null;
        }
        if (containsAny(text, "生产", "prod", "production", "线上")) {
            return "prod";
        }
        if (containsAny(text, "测试", "test", "testing", "qa")) {
            return "test";
        }
        if (containsAny(text, "开发", "dev", "develop", "development")) {
            return "dev";
        }
        if (containsAny(text, "预发", "staging", "stage", "uat")) {
            return "staging";
        }
        return null;
    }

    private String inferService(String query) {
        String text = normalize(query);
        if (text == null) {
            return null;
        }
        for (String service : List.of(
            "hive",
            "nginx",
            "mysql",
            "redis",
            "kafka",
            "spark",
            "flink",
            "hdfs",
            "yarn",
            "zookeeper",
            "elasticsearch",
            "postgresql",
            "postgres",
            "oracle"
        )) {
            if (text.matches(".*(^|[^a-z0-9_-])" + service + "([^a-z0-9_-]|$).*")) {
                return service;
            }
        }
        return null;
    }

    private String inferIntent(String query) {
        String text = normalize(query);
        if (text == null) {
            return null;
        }
        if (containsAny(text, "状态", "status", "健康", "health")) {
            return "service status";
        }
        if (containsAny(text, "日志", "log", "tail")) {
            return "log";
        }
        if (containsAny(text, "磁盘", "disk")) {
            return "disk";
        }
        if (containsAny(text, "内存", "memory", "mem")) {
            return "memory";
        }
        if (containsAny(text, "cpu")) {
            return "cpu";
        }
        return null;
    }

    private String inferAssetType(String query) {
        String text = normalize(query);
        if (text != null && containsAny(text, "数据库", "数据源", "库", "sql", "mysql", "postgres", "postgresql", "oracle")) {
            return "sql_datasource";
        }
        if (text != null && containsAny(text, "hive")) {
            return containsAny(text, "表", "元数据", "schema", "sql", "数据库", "数据源")
                ? "sql_datasource"
                : "ssh_host";
        }
        if (text != null && containsAny(text, "数据库", "sql", "mysql", "postgres", "postgresql", "oracle", "hive")) {
            return containsAny(text, "状态", "日志", "系统", "主机", "服务", "status", "log") ? "ssh_host" : "sql_datasource";
        }
        return "ssh_host";
    }

    private String targetKindFromDiscoveryToolName(String toolName, boolean templateQuery) {
        String normalized = normalizeToolName(toolName);
        if (normalized.contains("sql_datasource") || normalized.contains("database_query")) {
            return "database";
        }
        if (normalized.contains("http_endpoint") || normalized.contains("api_")) {
            return "http";
        }
        if (normalized.contains("ssh_") || normalized.contains("linux_")) {
            return "host";
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first : second;
    }

    private String canonicalServiceName(String service) {
        String value = normalize(service);
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "hive" -> "hive-server2";
            case "postgres" -> "postgresql";
            case "elasticsearch" -> "elasticsearch";
            default -> value;
        };
    }

    private boolean looksServiceTemplate(Object template) {
        String value = normalize(template == null ? null : String.valueOf(template));
        return value != null && (value.contains("service") || value.contains("status") || value.contains("log"));
    }

    private Map<String, Object> denied(Map<String, Object> values, String message) {
        Map<String, Object> result = new LinkedHashMap<>(values == null ? Map.of() : values);
        result.put(STATUS_KEY, "DENIED");
        result.put(ERROR_KEY, message);
        result.put(CODE_KEY, "MCP_PARAM_BINDING_DENIED");
        return result;
    }

    private Map<String, Object> reviewRequired(Map<String, Object> values, String message) {
        Map<String, Object> result = new LinkedHashMap<>(values == null ? Map.of() : values);
        result.put(STATUS_KEY, "REVIEW_REQUIRED");
        result.put(ERROR_KEY, message);
        result.put(CODE_KEY, "MCP_ROUTING_REVIEW_REQUIRED");
        result.put("routingDecision", Map.of(
            "decision", "REVIEW_REQUIRED",
            "threshold", TARGET_KIND_CONFIDENCE_THRESHOLD,
            "reason", message
        ));
        return result;
    }

    private boolean isMcpTool(String toolName, ToolMetadata metadata) {
        if (metadata != null) {
            if (metadata.getCategories() != null && metadata.getCategories().stream()
                .map(value -> value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT))
                .anyMatch(MCP_CATEGORIES::contains)) {
                return true;
            }
            if (metadata.getMetadata() != null && metadata.getMetadata().containsKey("remoteToolName")) {
                return true;
            }
        }
        return toolName != null && toolName.startsWith("mcp_");
    }

    private String remoteToolName(String toolName, ToolMetadata metadata) {
        if (metadata != null && metadata.getMetadata() != null) {
            Object remote = metadata.getMetadata().get("remoteToolName");
            if (hasText(remote)) {
                return String.valueOf(remote).trim();
            }
        }
        if (toolName == null) {
            return "";
        }
        for (String known : List.of("linux_command_execute", "http_request_execute", "sql_query_execute",
            "database_query_execute", "asset_query", "template_query")) {
            if (sameTool(toolName, known) || normalizeToolName(toolName).endsWith("_" + known)) {
                return known;
            }
        }
        return toolName;
    }

    private String removeTargetKind(Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        for (String field : TARGET_KIND_FIELDS) {
            Object value = values.remove(field);
            if (hasText(value)) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private String assetTypeFromTargetKind(String targetKind) {
        String normalized = normalizeTargetKind(targetKind);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "host" -> "ssh_host";
            case "database" -> "sql_datasource";
            case "http" -> "http_endpoint";
            case "business_database_query" -> "database_query";
            default -> null;
        };
    }

    private String normalizeAssetType(String assetType) {
        String normalized = normalize(assetType);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "host", "ssh", "sshhost" -> "ssh_host";
            case "database", "db", "sql", "sqldatasource", "datasource" -> "sql_datasource";
            case "http", "api", "endpoint", "httpendpoint" -> "http_endpoint";
            case "businessdatabasequery", "business_database_query", "business_db_query", "sqltemplateregistry",
                "sql_template_registry" -> "database_query";
            default -> normalized;
        };
    }

    private String normalizeTargetKind(String targetKind) {
        String normalized = normalize(targetKind);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "host", "ssh", "ssh_host", "server", "machine", "linux" -> "host";
            case "database", "db", "sql", "sql_datasource", "datasource" -> "database";
            case "http", "api", "endpoint", "http_endpoint" -> "http";
            case "business_database_query", "database_query", "business_db_query" -> "business_database_query";
            case "document", "doc", "knowledge", "file" -> "document";
            default -> normalized;
        };
    }

    private Double confidence(Object value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return -1.0;
        }
    }

    private Double candidateConfidence(Object rawCandidates, String targetKind) {
        String normalizedTargetKind = normalizeTargetKind(targetKind);
        if (normalizedTargetKind == null || !(rawCandidates instanceof List<?> candidates)) {
            return null;
        }
        for (Object item : candidates) {
            Map<String, Object> candidate = asMap(item);
            if (normalizedTargetKind.equals(normalizeTargetKind(firstText(candidate.get("targetKind"))))) {
                return confidence(candidate.get("confidence"));
            }
        }
        return null;
    }

    private void renameFirst(Map<String, Object> values, String target, String... aliases) {
        if (hasText(values.get(target))) {
            return;
        }
        for (String alias : aliases) {
            Object value = values.remove(alias);
            if (hasText(value)) {
                values.put(target, value);
                return;
            }
        }
    }

    private void moveIfPresent(Map<String, Object> source, Map<String, Object> target, String targetKey, String... aliases) {
        Object direct = source.remove(targetKey);
        if (hasText(direct)) {
            target.put(targetKey, direct);
            return;
        }
        for (String alias : aliases) {
            Object value = source.remove(alias);
            if (hasText(value)) {
                target.put(targetKey, value);
                return;
            }
        }
    }

    private String firstPresentField(Map<String, Object> values, List<String> fields) {
        if (values == null) {
            return null;
        }
        for (String field : fields) {
            if (hasText(values.get(field))) {
                return field;
            }
        }
        Object context = firstPresent(values, "executionContext", "mcpExecutionContext", "filters");
        if (context instanceof Map<?, ?> map) {
            for (String field : fields) {
                if (hasText(map.get(field))) {
                    return field;
                }
            }
        }
        return null;
    }

    private void removeForbidden(Map<String, Object> values) {
        if (values == null) {
            return;
        }
        CONCRETE_TARGET_FIELDS.forEach(values::remove);
        RAW_EXECUTION_FIELDS.forEach(values::remove);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        if (values == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String firstText(Object value) {
        return hasText(value) ? String.valueOf(value).trim() : null;
    }

    private boolean containsAny(String text, String... probes) {
        if (text == null || probes == null) {
            return false;
        }
        for (String probe : probes) {
            if (probe != null && text.contains(probe.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean sameTool(String first, String second) {
        return normalizeToolName(first).equals(normalizeToolName(second));
    }

    private String normalizeToolName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private String normalize(Object value) {
        if (!hasText(value)) {
            return null;
        }
        return String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).trim().isBlank();
    }
}
