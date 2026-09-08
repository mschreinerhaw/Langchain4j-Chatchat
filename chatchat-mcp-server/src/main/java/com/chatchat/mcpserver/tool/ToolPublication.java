package com.chatchat.mcpserver.tool;

import com.chatchat.common.tool.ToolMetadata;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/** Materialized descriptor and executable handler contributed for publication. */
public record ToolPublication(
    McpToolPublicationDescriptor descriptor,
    McpServerFeatures.SyncToolSpecification specification
) {
    public ToolPublication {
        if (descriptor == null) throw new IllegalArgumentException("Publication descriptor is required");
        if (specification == null || specification.tool() == null) {
            throw new IllegalArgumentException("Tool specification is required");
        }
    }

    public String toolName() {
        return specification.tool().name();
    }

    public static ToolPublication from(McpServerFeatures.SyncToolSpecification specification) {
        if (specification == null || specification.tool() == null) {
            throw new IllegalArgumentException("Tool specification is required");
        }
        McpSchema.Tool tool = specification.tool();
        Map<String, Object> meta = tool.meta() == null ? Map.of() : tool.meta();
        String schemaVersion = text(first(meta, "schemaVersion", "contractVersion", "contract_version"), "1.0.0");
        McpToolPublicationStatus status = McpToolPublicationStatus.parse(
            first(meta, "publicationStatus", "publication_status"));
        String deprecation = text(first(meta, "deprecationMessage", "deprecation_message"), null);
        List<String> tenants = strings(first(meta, "visibleTenantIds", "visible_tenant_ids"));
        List<String> labels = strings(first(meta, "rolloutLabels", "rollout_labels"));
        int percentage = integer(first(meta, "rolloutPercentage", "rollout_percentage"), 100);
        boolean approved = bool(first(meta, "breakingChangeApproved", "breaking_change_approved"));
        ToolMetadata metadata = ToolMetadata.builder()
            .id(tool.name())
            .title(tool.title())
            .description(tool.description())
            .version(text(first(meta, "toolVersion", "tool_version", "version"), "1.0.0"))
            .schemaVersion(schemaVersion)
            .publicationStatus(status.name().toLowerCase(java.util.Locale.ROOT))
            .deprecationMessage(deprecation)
            .visibleTenantIds(tenants)
            .rolloutLabels(labels)
            .rolloutPercentage(percentage)
            .category(text(first(meta, "category", "capability", "capabilityCode"), "mcp_tool"))
            .riskLevel(text(first(meta, "riskLevel", "risk_level"), "low"))
            .operationType(text(first(meta, "operationType", "operation_type"), "read"))
            .runtimeLevel(text(first(meta, "runtimeLevel", "runtime_level", "runtimeAction", "runtime_action"), "readonly"))
            .agentCompatible(!Boolean.FALSE.equals(first(meta, "agentCompatible", "agent_compatible")))
            .outputType("json")
            .metadata(meta)
            .build();
        return new ToolPublication(new McpToolPublicationDescriptor(metadata, status, schemaVersion,
            deprecation, tenants, labels, percentage, approved, meta), specification);
    }

    private static Object first(Map<String, Object> values, String... keys) {
        for (String key : keys) if (values.containsKey(key)) return values.get(key);
        return null;
    }

    private static String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static List<String> strings(Object value) {
        if (value instanceof Iterable<?> iterable) {
            java.util.ArrayList<String> result = new java.util.ArrayList<>();
            iterable.forEach(item -> { if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item).trim()); });
            return List.copyOf(result);
        }
        return value == null || String.valueOf(value).isBlank() ? List.of() : List.of(String.valueOf(value).trim());
    }
}
