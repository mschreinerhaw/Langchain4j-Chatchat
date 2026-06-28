package com.chatchat.mcpserver.authorization;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record McpScopeExpression(
    String assetType,
    String capability,
    String action,
    String tenantId,
    String domain,
    String level
) {

    public static McpScopeExpression of(String assetType,
                                        String capability,
                                        String action,
                                        String tenantId,
                                        String domain,
                                        String level) {
        return new McpScopeExpression(
            normalize(assetType),
            normalize(capability),
            normalize(action),
            text(tenantId),
            text(domain),
            normalize(level)
        );
    }

    public static McpScopeExpression parse(String value) {
        String text = text(value);
        if (text == null || !text.startsWith("mcp:")) {
            throw new IllegalArgumentException("Invalid MCP scope expression: " + value);
        }
        String[] parts = text.split("@", 2);
        String[] head = parts[0].split(":");
        if (head.length < 4) {
            throw new IllegalArgumentException("Invalid MCP scope head: " + value);
        }
        Map<String, String> attributes = attributes(parts.length > 1 ? parts[1] : "");
        return of(
            head[1],
            head[2],
            head[3],
            attributes.get("tenant"),
            attributes.get("domain"),
            attributes.get("level")
        );
    }

    public boolean matches(String requestedAssetType, String requestedCapability, String requestedAction, String requestedTenantId) {
        return matchesPart(assetType, normalize(requestedAssetType))
            && matchesPart(capability, normalize(requestedCapability))
            && matchesPart(action, normalize(requestedAction))
            && matchesPart(tenantId, text(requestedTenantId));
    }

    public String value() {
        StringBuilder builder = new StringBuilder("mcp:")
            .append(assetType)
            .append(':')
            .append(capability)
            .append(':')
            .append(action);
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "tenant", tenantId);
        put(attributes, "domain", domain);
        put(attributes, "level", level);
        if (!attributes.isEmpty()) {
            builder.append('@');
            boolean first = true;
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                if (!first) {
                    builder.append(';');
                }
                first = false;
                builder.append(entry.getKey()).append('=').append(entry.getValue());
            }
        }
        return builder.toString();
    }

    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("assetType", assetType);
        values.put("capability", capability);
        values.put("action", action);
        values.put("tenantId", tenantId);
        values.put("domain", domain);
        values.put("level", level);
        values.put("scopeExpression", value());
        return values;
    }

    private boolean matchesPart(String configured, String requested) {
        return configured == null || configured.isBlank() || "*".equals(configured)
            || configured.equals(requested);
    }

    private static Map<String, String> attributes(String value) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return attributes;
        }
        for (String item : value.split(";")) {
            if (item == null || item.isBlank() || !item.contains("=")) {
                continue;
            }
            String[] pair = item.split("=", 2);
            attributes.put(normalize(pair[0]), text(pair[1]));
        }
        return attributes;
    }

    private static void put(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private static String normalize(String value) {
        String text = text(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
