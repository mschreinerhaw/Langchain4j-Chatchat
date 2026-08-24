package com.chatchat.mcpserver.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Restores protocol invocation context into the mutable arguments passed to governed tools.
 * Request metadata is the primary source because MCP handlers may execute outside the servlet thread.
 */
public final class McpInvocationArguments {

    private McpInvocationArguments() {
    }

    public static void enrich(String toolName, Map<String, Object> arguments, Map<String, Object> requestMeta) {
        if (arguments == null) return;

        McpInvocationContext.Context context = McpInvocationContext.current();
        Map<String, Object> meta = requestMeta == null ? Map.of() : requestMeta;
        Map<String, Object> tenant = mapValue(meta.get("tenant"));
        Map<String, Object> user = mapValue(meta.get("user"));
        Map<String, Object> scope = mapValue(meta.get("scope"));

        putIfAbsent(arguments, "tenantId", firstText(
            textValue(tenant.get("tenantId")), textValue(meta.get("tenantId")),
            context == null ? null : context.tenantId()));
        putIfAbsent(arguments, "userId", firstText(
            textValue(user.get("userId")), textValue(meta.get("userId")),
            context == null ? null : context.userId()));
        putIfAbsent(arguments, "roles", firstText(
            textValue(user.get("roles")), textValue(meta.get("roles")),
            context == null ? null : context.roles()));
        putIfAbsent(arguments, "traceId", firstText(
            textValue(meta.get("traceId")), context == null ? null : context.traceId()));

        if (!isDocumentSearch(toolName)) {
            putIfAbsent(arguments, "workspaceId", firstText(
                textValue(tenant.get("workspaceId")), context == null ? null : context.workspaceId()));
            putIfAbsent(arguments, "env", firstText(
                textValue(tenant.get("env")), context == null ? null : context.environment()));
            putIfAbsent(arguments, "username", firstText(
                textValue(user.get("username")), context == null ? null : context.username()));
            putIfAbsent(arguments, "assetType", firstText(
                textValue(scope.get("assetType")), context == null ? null : context.assetType()));
            putIfAbsent(arguments, "domain", firstText(
                textValue(scope.get("domain")), context == null ? null : context.domain()));
            putIfAbsent(arguments, "permissionLevel", firstText(
                textValue(scope.get("permissionLevel")), context == null ? null : context.permissionLevel()));
            putIfAbsent(arguments, "scopeExpression", firstText(
                textValue(meta.get("scopeExpression")), textValue(scope.get("scopeExpression")),
                context == null ? null : context.scopeExpression()));
        }

        Map<String, Object> mcpContext = new LinkedHashMap<>();
        putIfPresent(mcpContext, "traceId", textArgument(arguments, "traceId"));
        mcpContext.put("user", Map.of(
            "userId", valueOrEmpty(textArgument(arguments, "userId")),
            "username", valueOrEmpty(textArgument(arguments, "username")),
            "roles", valueOrEmpty(textArgument(arguments, "roles"))
        ));
        mcpContext.put("tenant", Map.of(
            "tenantId", valueOrEmpty(textArgument(arguments, "tenantId")),
            "workspaceId", valueOrEmpty(textArgument(arguments, "workspaceId")),
            "env", valueOrEmpty(textArgument(arguments, "env"))
        ));
        mcpContext.put("scope", Map.of(
            "assetType", valueOrEmpty(textArgument(arguments, "assetType")),
            "domain", valueOrEmpty(textArgument(arguments, "domain")),
            "permissionLevel", valueOrEmpty(textArgument(arguments, "permissionLevel")),
            "scopeExpression", valueOrEmpty(textArgument(arguments, "scopeExpression"))
        ));
        arguments.putIfAbsent("mcpContext", mcpContext);
    }

    private static boolean isDocumentSearch(String toolName) {
        if (toolName == null || toolName.isBlank()) return false;
        String normalized = toolName.trim().toLowerCase();
        return "document_search".equals(normalized) || normalized.endsWith("_document_search");
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> mapped = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) mapped.put(String.valueOf(key), item);
        });
        return mapped;
    }

    private static String textArgument(Map<String, Object> arguments, String key) {
        return textValue(arguments.get(key));
    }

    private static String textValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        return String.valueOf(value).trim();
    }

    private static String firstText(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void putIfAbsent(Map<String, Object> arguments, String key, String value) {
        if (value != null && !value.isBlank() && !arguments.containsKey(key)) arguments.put(key, value);
    }

    private static void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value != null) values.put(key, value);
    }
}
