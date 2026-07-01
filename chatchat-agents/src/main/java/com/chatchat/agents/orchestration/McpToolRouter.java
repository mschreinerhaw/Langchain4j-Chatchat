package com.chatchat.agents.orchestration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class McpToolRouter {

    public static final String ASSET_DISCOVERY = "asset_discovery";
    public static final String TEMPLATE_DISCOVERY = "template_discovery";
    private static final String LEGACY_ASSET_QUERY = "asset_query";
    private static final String LEGACY_TEMPLATE_QUERY = "template_query";

    private static final Map<String, String> ASSET_QUERY_TOOLS = Map.of(
        "api_service", "api_asset_query",
        "ssh_host", "ssh_asset_query",
        "sql_datasource", "database_asset_search",
        "http_endpoint", "http_endpoint_asset_query"
    );
    private static final Map<String, String> TEMPLATE_QUERY_TOOLS = Map.of(
        "api_service", "api_template_query",
        "ssh_host", "ssh_template_query",
        "sql_datasource", "database_ops_template_search",
        "http_endpoint", "http_endpoint_template_query",
        "database_query", "business_query_template_search"
    );
    private static final Map<String, String> TARGET_KIND_TO_ASSET_TYPE = Map.of(
        "api", "api_service",
        "api_service", "api_service",
        "host", "ssh_host",
        "ssh", "ssh_host",
        "database", "sql_datasource",
        "sql", "sql_datasource",
        "http", "http_endpoint",
        "business_database_query", "database_query"
    );

    public RoutingDecision route(String requestedToolName,
                                 Map<String, Object> arguments,
                                 List<String> availableTools,
                                 String tenantId,
                                 List<String> roles) {
        String capability = requestedCapability(requestedToolName, arguments);
        if (capability == null) {
            return RoutingDecision.unrouted(requestedToolName);
        }
        String assetType = assetType(arguments);
        if (TEMPLATE_DISCOVERY.equals(capability)
            && "sql_datasource".equals(assetType)
            && shouldRouteBusinessDatabaseQuery(arguments, availableTools)) {
            assetType = "database_query";
        }
        String resolvedTool = resolveTypedTool(capability, assetType, availableTools);
        if (resolvedTool == null) {
            return RoutingDecision.denied(
                requestedToolName,
                assetType,
                capability,
                "TOOL_ROUTING_DENIED",
                "No typed MCP tool is available for capability=" + capability + " assetType=" + assetType
            );
        }
        McpScopeExpression scope = McpScopeExpression.of(
            assetType,
            capabilityName(capability),
            actionName(capability),
            tenantId,
            domain(arguments),
            level(arguments)
        );
        return RoutingDecision.routed(requestedToolName, resolvedTool, assetType, capability, scope, roles);
    }

    public String resolveToolName(String requestedToolName, Map<String, Object> arguments, List<String> availableTools) {
        RoutingDecision decision = route(requestedToolName, arguments, availableTools, null, List.of());
        return decision.routed() ? decision.resolvedToolName() : requestedToolName;
    }

    public boolean isTypedAssetQuery(String toolName) {
        String semantic = semantic(toolName);
        return hasSemanticSuffix(toolName, "_asset_query")
            || "database_asset_search".equals(semantic)
            || ASSET_DISCOVERY.equals(semantic)
            || LEGACY_ASSET_QUERY.equals(semantic);
    }

    public boolean isTypedTemplateQuery(String toolName) {
        String semantic = semantic(toolName);
        return hasSemanticSuffix(toolName, "_template_query")
            || hasSemanticSuffix(toolName, "_template_search")
            || TEMPLATE_DISCOVERY.equals(semantic)
            || LEGACY_TEMPLATE_QUERY.equals(semantic);
    }

    private String requestedCapability(String requestedToolName, Map<String, Object> arguments) {
        String explicit = normalize(firstText(arguments, "routerCapability", "capability"));
        String normalizedExplicit = normalizeCapability(explicit);
        if (normalizedExplicit != null) {
            return normalizedExplicit;
        }
        String semantic = semantic(requestedToolName);
        if (ASSET_DISCOVERY.equals(semantic) || LEGACY_ASSET_QUERY.equals(semantic)
            || semantic.endsWith("_asset_query") || "database_asset_search".equals(semantic)) {
            return ASSET_DISCOVERY;
        }
        if (TEMPLATE_DISCOVERY.equals(semantic) || LEGACY_TEMPLATE_QUERY.equals(semantic)
            || semantic.endsWith("_template_query") || semantic.endsWith("_template_search")) {
            return TEMPLATE_DISCOVERY;
        }
        return null;
    }

    private String assetType(Map<String, Object> arguments) {
        String assetType = normalize(firstText(arguments, "assetType", "asset_type"));
        if (assetType != null) {
            return assetType;
        }
        Object scope = firstValue(arguments, "scope");
        if (scope instanceof Map<?, ?> map) {
            assetType = normalize(value(map, "assetType", "asset_type"));
            if (assetType != null) {
                return assetType;
            }
        }
        Object router = firstValue(arguments, "router");
        if (router instanceof Map<?, ?> map) {
            assetType = normalize(value(map, "assetType", "asset_type"));
            if (assetType != null) {
                return assetType;
            }
        }
        String targetKind = normalize(firstText(arguments, "finalDecision", "targetKind", "target_kind"));
        return TARGET_KIND_TO_ASSET_TYPE.get(targetKind);
    }

    private boolean shouldRouteBusinessDatabaseQuery(Map<String, Object> arguments, List<String> availableTools) {
        if (!hasAvailableSemanticTool(availableTools, "business_query_template_search")) {
            return false;
        }
        String text = searchableText(arguments);
        if (text.isBlank()) {
            return false;
        }
        if (containsAny(text,
            "metadata", "schema", "column", "columns", "field", "fields", "table metadata",
            "lock", "blocking", "deadlock", "wait", "session", "connection", "processlist",
            "storage", "space", "capacity", "tablespace", "status", "health", "diagnostic",
            "slow query", "performance", "索引", "元数据", "表结构", "字段", "列", "锁", "阻塞",
            "死锁", "等待", "连接", "会话", "连接数", "存储", "空间", "容量", "状态", "健康",
            "诊断", "慢查询", "性能")) {
            return false;
        }
        return containsAny(text,
            "sql_query", "business_database_query", "database_query", "business", "analysis", "analytics",
            "market", "volatility", "alert", "warning", "monitor", "event", "portfolio", "trade",
            "order", "customer", "product", "行情", "市场", "波动", "异动", "异常", "提醒",
            "预警", "告警", "监控", "事件", "业务", "分析", "交易", "订单", "客户", "产品",
            "舆情", "持仓");
    }

    private boolean hasAvailableSemanticTool(List<String> availableTools, String semanticToolName) {
        if (availableTools == null || availableTools.isEmpty() || semanticToolName == null || semanticToolName.isBlank()) {
            return false;
        }
        return availableTools.stream().anyMatch(tool -> semanticMatches(semanticToolName, semantic(tool)));
    }

    private String searchableText(Object value) {
        StringBuilder builder = new StringBuilder();
        appendSearchableText(builder, value);
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private void appendSearchableText(StringBuilder builder, Object value) {
        if (builder == null || value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> appendSearchableText(builder, nested));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                appendSearchableText(builder, item);
            }
            return;
        }
        builder.append(' ').append(value);
    }

    private boolean containsAny(String text, String... tokens) {
        if (text == null || text.isBlank() || tokens == null) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isBlank() && text.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String resolveTypedTool(String capability, String assetType, List<String> availableTools) {
        if (capability == null || assetType == null) {
            return null;
        }
        String canonical = ASSET_DISCOVERY.equals(capability)
            ? ASSET_QUERY_TOOLS.get(assetType)
            : TEMPLATE_QUERY_TOOLS.get(assetType);
        if (canonical == null) {
            return null;
        }
        if (availableTools == null || availableTools.isEmpty()) {
            return canonical;
        }
        return availableTools.stream()
            .filter(tool -> semanticMatches(canonical, semantic(tool)))
            .findFirst()
            .orElse(availableTools.contains(canonical) ? canonical : null);
    }

    private boolean semanticMatches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        if (expected.equals(actual)) {
            return true;
        }
        return switch (expected) {
            case "database_asset_search" -> "sql_datasource_asset_query".equals(actual);
            case "database_ops_template_search" -> "sql_datasource_template_query".equals(actual);
            case "business_query_template_search" -> "database_query_template_query".equals(actual);
            default -> false;
        };
    }

    private String capabilityName(String capability) {
        return ASSET_DISCOVERY.equals(capability) ? "asset" : "template";
    }

    private String actionName(String capability) {
        return "query";
    }

    private String domain(Map<String, Object> arguments) {
        String domain = firstText(arguments, "domain", "service", "category");
        if (domain != null) {
            return domain;
        }
        Object scope = firstValue(arguments, "scope");
        if (scope instanceof Map<?, ?> map) {
            return value(map, "domain", "service", "category");
        }
        return null;
    }

    private String level(Map<String, Object> arguments) {
        String level = firstText(arguments, "permissionLevel", "level");
        if (level != null) {
            return level;
        }
        Object scope = firstValue(arguments, "scope");
        if (scope instanceof Map<?, ?> map) {
            level = value(map, "permissionLevel", "level");
        }
        return level == null ? "read" : level;
    }

    private boolean hasSemanticSuffix(String toolName, String suffix) {
        return semantic(toolName).endsWith(suffix);
    }

    private String semantic(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "";
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        while (normalized.startsWith("mcp_")) {
            normalized = normalized.substring(4);
        }
        for (String prefix : List.of("chatchat_mcp_server_", "chatchat_", "xxx_")) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length());
            }
        }
        return normalized;
    }

    private Object firstValue(Map<String, Object> values, String... keys) {
        if (values == null) {
            return null;
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String firstText(Map<String, Object> values, String... keys) {
        Object value = firstValue(values, keys);
        return value == null ? null : String.valueOf(value).trim();
    }

    private String value(Map<?, ?> values, String... keys) {
        if (values == null) {
            return null;
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeCapability(String value) {
        String normalized = normalize(value);
        if (ASSET_DISCOVERY.equals(normalized) || LEGACY_ASSET_QUERY.equals(normalized)) {
            return ASSET_DISCOVERY;
        }
        if (TEMPLATE_DISCOVERY.equals(normalized) || LEGACY_TEMPLATE_QUERY.equals(normalized)) {
            return TEMPLATE_DISCOVERY;
        }
        return null;
    }

    public record RoutingDecision(
        boolean routed,
        boolean allowed,
        String requestedToolName,
        String resolvedToolName,
        String assetType,
        String capability,
        McpScopeExpression scope,
        List<String> roles,
        String errorCode,
        String reason
    ) {
        static RoutingDecision unrouted(String requestedToolName) {
            return new RoutingDecision(false, true, requestedToolName, requestedToolName, null, null, null, List.of(), null, null);
        }

        static RoutingDecision routed(String requestedToolName,
                                      String resolvedToolName,
                                      String assetType,
                                      String capability,
                                      McpScopeExpression scope,
                                      List<String> roles) {
            return new RoutingDecision(true, true, requestedToolName, resolvedToolName, assetType, capability, scope,
                roles == null ? List.of() : roles, null, null);
        }

        static RoutingDecision denied(String requestedToolName,
                                      String assetType,
                                      String capability,
                                      String errorCode,
                                      String reason) {
            return new RoutingDecision(true, false, requestedToolName, null, assetType, capability, null, List.of(), errorCode, reason);
        }

        public Map<String, Object> metadata() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("requestedToolName", requestedToolName);
            values.put("resolvedToolName", resolvedToolName);
            values.put("assetType", assetType);
            values.put("capability", capability);
            values.put("allowed", allowed);
            if (scope != null) {
                values.put("scope", scope.asMap());
            }
            if (errorCode != null) {
                values.put("errorCode", errorCode);
                values.put("reason", reason);
            }
            return values;
        }
    }
}
