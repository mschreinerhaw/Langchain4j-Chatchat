package com.chatchat.common.mcp.capability;

import com.chatchat.common.tool.McpToolNamePolicy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

/**
 * One immutable node in the MCP capability tree.
 *
 * <p>A published child keeps its own governance identity even when transport
 * invocation is delegated to a parent tool.</p>
 */
public record McpCapabilityNode(
    String serviceId,
    String toolName,
    String parentToolName,
    McpCapabilityNodeKind nodeKind,
    McpCapabilityFallbackPolicy fallbackPolicy,
    String relationType,
    String routingMode,
    Map<String, Object> attributes
) {
    public static final String RELATION_ROOT = "root";
    public static final String RELATION_IMPLEMENTS_ABSTRACT_CAPABILITY =
        "implements_abstract_capability";
    /** Legacy transport-oriented relationship value accepted during rolling upgrades. */
    @Deprecated(forRemoval = false)
    public static final String RELATION_DELEGATES_TO_PARENT = "delegates_to_parent";

    public McpCapabilityNode {
        serviceId = normalized(serviceId);
        toolName = required(toolName, "toolName");
        parentToolName = normalized(parentToolName);
        nodeKind = nodeKind == null
            ? (parentToolName == null ? McpCapabilityNodeKind.STANDALONE
            : McpCapabilityNodeKind.BUSINESS_IMPLEMENTATION)
            : nodeKind;
        fallbackPolicy = fallbackPolicy == null
            ? (nodeKind == McpCapabilityNodeKind.ABSTRACT_CAPABILITY
            ? McpCapabilityFallbackPolicy.DENY_WHEN_NO_IMPLEMENTATION
            : McpCapabilityFallbackPolicy.ALLOW_STANDALONE)
            : fallbackPolicy;
        relationType = normalized(relationType);
        if (relationType == null) {
            relationType = parentToolName == null
                ? RELATION_ROOT : RELATION_IMPLEMENTS_ABSTRACT_CAPABILITY;
        }
        routingMode = normalized(routingMode);
        attributes = attributes == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public String identity() {
        String semanticName = McpToolNamePolicy.workflowSemanticKey(toolName);
        return (serviceId == null ? "" : serviceId.toLowerCase(Locale.ROOT)) + ":" + semanticName;
    }

    public boolean child() {
        return parentToolName != null;
    }

    public boolean abstractCapability() {
        return nodeKind == McpCapabilityNodeKind.ABSTRACT_CAPABILITY;
    }

    public boolean businessImplementation() {
        return nodeKind == McpCapabilityNodeKind.BUSINESS_IMPLEMENTATION;
    }

    public Map<String, Object> toMetadata() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (serviceId != null) result.put("serviceId", serviceId);
        result.put("toolName", toolName);
        if (parentToolName != null) result.put("parentToolName", parentToolName);
        result.put("nodeKind", nodeKind.name());
        result.put("fallbackPolicy", fallbackPolicy.name());
        result.put("relationType", relationType);
        if (routingMode != null) result.put("routingMode", routingMode);
        if (!attributes.isEmpty()) result.put("attributes", attributes);
        return Collections.unmodifiableMap(result);
    }

    public static Optional<McpCapabilityNode> fromMetadata(Map<String, Object> metadata,
                                                            String fallbackServiceId,
                                                            String fallbackToolName) {
        if (metadata == null || metadata.isEmpty()) return Optional.empty();
        String toolName = text(metadata.get("toolName"));
        if (toolName == null) toolName = normalized(fallbackToolName);
        if (toolName == null) return Optional.empty();
        String serviceId = text(metadata.get("serviceId"));
        if (serviceId == null) serviceId = normalized(fallbackServiceId);
        return Optional.of(new McpCapabilityNode(
            serviceId,
            toolName,
            text(metadata.get("parentToolName")),
            McpCapabilityNodeKind.parse(metadata.get("nodeKind"), null),
            McpCapabilityFallbackPolicy.parse(metadata.get("fallbackPolicy"), null),
            text(metadata.get("relationType")),
            text(metadata.get("routingMode")),
            metadata.get("attributes") instanceof Map<?, ?> values
                ? cast(values) : Map.of()
        ));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> values) {
        return new LinkedHashMap<>((Map<String, Object>) values);
    }

    private static String text(Object value) {
        return value == null ? null : normalized(String.valueOf(value));
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized == null) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
