package com.chatchat.agents.orchestration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adds routing scope metadata without changing the tool selected by the user or plan.
 */
public class McpToolRouter {

    public static final String ASSET_DISCOVERY = "asset_discovery";
    public static final String TEMPLATE_DISCOVERY = "template_discovery";
    private static final String LEGACY_ASSET_QUERY = "asset_query";
    private static final String LEGACY_TEMPLATE_QUERY = "template_query";
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
        if (availableTools != null && !availableTools.isEmpty() && !availableTools.contains(requestedToolName)) {
            return RoutingDecision.denied(
                requestedToolName,
                assetType,
                capability,
                "TOOL_ROUTING_DENIED",
                "Requested MCP tool is not bound to this agent workflow: " + requestedToolName
            );
        }
        McpScopeExpression scope = McpScopeExpression.of(
            assetType,
            ASSET_DISCOVERY.equals(capability) ? "asset" : "template",
            "query",
            tenantId,
            domain(arguments),
            level(arguments)
        );
        return RoutingDecision.routed(requestedToolName, requestedToolName, assetType, capability, scope, roles);
    }

    public String resolveToolName(String requestedToolName, Map<String, Object> arguments, List<String> availableTools) {
        RoutingDecision decision = route(requestedToolName, arguments, availableTools, null, List.of());
        return decision.routed() && decision.allowed() ? decision.resolvedToolName() : requestedToolName;
    }

    public boolean isTypedAssetQuery(String toolName) {
        String semantic = semantic(toolName);
        return semantic.equals(ASSET_DISCOVERY)
            || semantic.equals(LEGACY_ASSET_QUERY)
            || semantic.endsWith("_asset_query")
            || semantic.equals("database_asset_search");
    }

    public boolean isTypedTemplateQuery(String toolName) {
        String semantic = semantic(toolName);
        return semantic.equals(TEMPLATE_DISCOVERY)
            || semantic.equals(LEGACY_TEMPLATE_QUERY)
            || semantic.endsWith("_template_query")
            || semantic.endsWith("_template_search");
    }

    private String requestedCapability(String requestedToolName, Map<String, Object> arguments) {
        String explicit = normalizeCapability(firstText(arguments, "routerCapability", "capability"));
        if (explicit != null) {
            return explicit;
        }
        if (isTypedAssetQuery(requestedToolName)) {
            return ASSET_DISCOVERY;
        }
        if (isTypedTemplateQuery(requestedToolName)) {
            return TEMPLATE_DISCOVERY;
        }
        return null;
    }

    private String assetType(Map<String, Object> arguments) {
        String assetType = normalize(firstText(arguments, "assetType", "asset_type"));
        if (assetType != null) {
            return assetType;
        }
        for (String nestedKey : List.of("scope", "router")) {
            Object nested = firstValue(arguments, nestedKey);
            if (nested instanceof Map<?, ?> map) {
                assetType = normalize(value(map, "assetType", "asset_type"));
                if (assetType != null) {
                    return assetType;
                }
            }
        }
        String targetKind = normalize(firstText(arguments, "finalDecision", "targetKind", "target_kind"));
        return targetKind == null ? null : TARGET_KIND_TO_ASSET_TYPE.get(targetKind);
    }

    private String domain(Map<String, Object> arguments) {
        String domain = firstText(arguments, "domain", "service", "category");
        Object scope = firstValue(arguments, "scope");
        return domain != null ? domain : scope instanceof Map<?, ?> map ? value(map, "domain", "service", "category") : null;
    }

    private String level(Map<String, Object> arguments) {
        String level = firstText(arguments, "permissionLevel", "level");
        Object scope = firstValue(arguments, "scope");
        if (level == null && scope instanceof Map<?, ?> map) {
            level = value(map, "permissionLevel", "level");
        }
        return level == null ? "read" : level;
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
