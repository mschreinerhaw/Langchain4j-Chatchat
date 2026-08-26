package com.chatchat.common.mcp.capability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Transport-neutral contract for a dynamically published business tool whose
 * invocation is delegated to a stable parent MCP tool.
 *
 * <p>The implementation identity is supplied by trusted discovery state. A
 * caller-provided value must always be replaced by the bridge.</p>
 */
public record McpDynamicCapabilityRoute(
    String contractVersion,
    String parentToolName,
    String implementationIdentityArgument,
    String routingMode,
    Map<String, Object> attributes
) implements McpCapabilityRouteContract {
    public static final String METADATA_KEY = "mcpDynamicCapabilityRoute";
    public static final String CURRENT_VERSION = "mcp.dynamic-capability-route.v1";
    public static final String ROUTING_MODE_PARENT_DELEGATION = "parent_delegation";

    public McpDynamicCapabilityRoute {
        contractVersion = required(contractVersion, "contractVersion");
        parentToolName = required(parentToolName, "parentToolName");
        implementationIdentityArgument = required(
            implementationIdentityArgument, "implementationIdentityArgument");
        if (!implementationIdentityArgument.matches("_[A-Za-z][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException(
                "implementationIdentityArgument must use the reserved MCP '_' namespace");
        }
        routingMode = required(routingMode, "routingMode");
        attributes = attributes == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static McpDynamicCapabilityRoute parentDelegation(
        String parentToolName, String implementationIdentityArgument) {
        return new McpDynamicCapabilityRoute(
            CURRENT_VERSION,
            parentToolName,
            implementationIdentityArgument,
            ROUTING_MODE_PARENT_DELEGATION,
            Map.of()
        );
    }

    public Map<String, Object> toMetadata() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", contractVersion);
        result.put("parentToolName", parentToolName);
        result.put("implementationIdentityArgument", implementationIdentityArgument);
        result.put("routingMode", routingMode);
        if (!attributes.isEmpty()) result.put("attributes", attributes);
        return Collections.unmodifiableMap(result);
    }

    public static Optional<McpDynamicCapabilityRoute> fromToolMetadata(Map<String, Object> toolMetadata) {
        if (toolMetadata == null) return Optional.empty();
        Object raw = toolMetadata.get(METADATA_KEY);
        if (!(raw instanceof Map<?, ?> values)) return Optional.empty();
        String version = text(values.get("contractVersion"));
        String parent = text(values.get("parentToolName"));
        String identityArgument = text(values.get("implementationIdentityArgument"));
        String mode = text(values.get("routingMode"));
        if (version == null || parent == null || identityArgument == null || mode == null) {
            throw new IllegalArgumentException("Incomplete dynamic MCP capability route contract");
        }
        if (!CURRENT_VERSION.equals(version)) {
            throw new IllegalArgumentException("Unsupported dynamic MCP capability route contract: " + version);
        }
        if (!ROUTING_MODE_PARENT_DELEGATION.equals(mode)) {
            throw new IllegalArgumentException("Unsupported dynamic MCP capability routing mode: " + mode);
        }
        return Optional.of(new McpDynamicCapabilityRoute(
            version, parent, identityArgument, mode,
            values.get("attributes") instanceof Map<?, ?> attributes ? cast(attributes) : Map.of()
        ));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> values) {
        return new LinkedHashMap<>((Map<String, Object>) values);
    }

    private static String required(String value, String field) {
        String normalized = text(value);
        if (normalized == null) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
