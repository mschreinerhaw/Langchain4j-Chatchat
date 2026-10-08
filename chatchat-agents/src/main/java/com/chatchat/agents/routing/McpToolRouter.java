package com.chatchat.agents.routing;

import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.common.tool.ToolMetadata;

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
    public RoutingDecision route(String requestedToolName,
                                 Map<String, Object> arguments,
                                 List<String> availableTools,
                                 String tenantId,
                                 List<String> roles) {
        return route(requestedToolName, arguments, availableTools, tenantId, roles, null);
    }

    public RoutingDecision route(String requestedToolName,
                                 Map<String, Object> arguments,
                                 List<String> availableTools,
                                 String tenantId,
                                 List<String> roles,
                                 ToolWorkflowRole publishedRole) {
        return route(requestedToolName, arguments, availableTools, tenantId, roles, publishedRole, null);
    }

    public RoutingDecision route(String requestedToolName,
                                 Map<String, Object> arguments,
                                 List<String> availableTools,
                                 String tenantId,
                                 List<String> roles,
                                 ToolWorkflowRole publishedRole,
                                 ToolMetadata metadata) {
        String capability = requestedCapability(requestedToolName, arguments, publishedRole);
        if (capability == null) {
            return RoutingDecision.unrouted(requestedToolName);
        }
        String assetType = assetType(arguments, metadata);
        String publishedAssetType = mappedAssetType(arguments, metadata);
        if (assetType != null && publishedAssetType != null && !assetType.equals(publishedAssetType)) {
            return RoutingDecision.denied(requestedToolName, assetType, capability,
                "TOOL_ROUTING_DENIED", "assetType conflicts with the published target kind mapping");
        }
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

    private String requestedCapability(String requestedToolName,
                                       Map<String, Object> arguments,
                                       ToolWorkflowRole publishedRole) {
        if (publishedRole == ToolWorkflowRole.ASSET_DISCOVERY) {
            return ASSET_DISCOVERY;
        }
        if (publishedRole == ToolWorkflowRole.TEMPLATE_DISCOVERY) {
            return TEMPLATE_DISCOVERY;
        }
        return null;
    }

    private String assetType(Map<String, Object> arguments, ToolMetadata metadata) {
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
        return mappedAssetType(arguments, metadata);
    }

    private String mappedAssetType(Map<String, Object> arguments, ToolMetadata metadata) {
        String targetKind = normalize(firstText(arguments, "finalDecision", "targetKind", "target_kind"));
        if (targetKind == null || metadata == null || metadata.getMetadata() == null) return null;
        Object mcpMetaValue = metadata.getMetadata().get("mcpToolMeta");
        if (!(mcpMetaValue instanceof Map<?, ?> mcpMeta)) return null;
        Object routingValue = mcpMeta.get("routingProtocol");
        if (!(routingValue instanceof Map<?, ?> routing)) return null;
        Object allowedValue = routing.get("allowedTargetKinds");
        if (allowedValue instanceof List<?> allowed && allowed.stream()
            .map(String::valueOf).map(this::normalize).noneMatch(targetKind::equals)) return null;
        Object forcedKind = routing.get("forcedTargetKind");
        if (targetKind.equals(normalize(forcedKind == null ? null : String.valueOf(forcedKind)))) {
            Object forcedType = routing.get("forcedAssetType");
            if (forcedType != null) return normalize(String.valueOf(forcedType));
        }
        Object mapping = routing.get("targetKindToAssetType");
        if (!(mapping instanceof Map<?, ?> types)) return null;
        Object declared = types.get(targetKind);
        return declared == null ? null : normalize(String.valueOf(declared));
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
