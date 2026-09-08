package com.chatchat.mcpserver.tool;

import com.chatchat.common.tool.ToolMetadata;

import java.util.List;
import java.util.Map;

/** Serializable publication policy paired with a capability contract. */
public record McpToolPublicationDescriptor(
    ToolMetadata metadata,
    McpToolPublicationStatus status,
    String schemaVersion,
    String deprecationMessage,
    List<String> visibleTenantIds,
    List<String> rolloutLabels,
    int rolloutPercentage,
    boolean breakingChangeApproved,
    Map<String, Object> attributes
) {
    public McpToolPublicationDescriptor {
        if (metadata == null) throw new IllegalArgumentException("Tool metadata is required");
        status = status == null ? McpToolPublicationStatus.ACTIVE : status;
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
            ? "1.0.0" : schemaVersion.trim();
        visibleTenantIds = visibleTenantIds == null ? List.of() : visibleTenantIds.stream()
            .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        rolloutLabels = rolloutLabels == null ? List.of() : rolloutLabels.stream()
            .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        rolloutPercentage = Math.max(0, Math.min(100, rolloutPercentage));
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
