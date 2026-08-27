package com.chatchat.agents.tool;

import com.chatchat.common.mcp.capability.McpCapabilityFallbackPolicy;
import com.chatchat.common.mcp.capability.McpCapabilityHierarchy;
import com.chatchat.common.mcp.capability.McpCapabilityNode;
import com.chatchat.common.mcp.capability.McpCapabilityNodeKind;
import com.chatchat.common.tool.McpToolNamePolicy;
import com.chatchat.common.tool.ToolMetadata;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Resolves the common MCP capability tree from the live tool registry. */
public final class RegistryMcpCapabilityHierarchy implements McpCapabilityHierarchy {
    private final ToolRegistry registry;

    public RegistryMcpCapabilityHierarchy(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Optional<McpCapabilityNode> node(String toolName) {
        try {
            String registeredName = registeredName(toolName);
            if (registeredName == null) return Optional.empty();
            ToolMetadata metadata = registry.getToolMetadata(registeredName);
            if (metadata == null || metadata.getMetadata() == null) return Optional.empty();
            Map<String, Object> extra = metadata.getMetadata();
            Map<String, Object> declared = map(extra.get(METADATA_KEY));
            String serviceId = text(first(declared.get("serviceId"), extra.get("serviceId")));
            String parent = text(first(declared.get("parentToolName"), extra.get("parentRemoteToolName")));
            String routingMode = text(first(declared.get("routingMode"), extra.get("routingMode")));
            String relationType = text(declared.get("relationType"));
            String resolvedParent = parent == null ? null : registeredName(serviceId, parent);
            boolean inferredAbstractCapability = parent == null
                && hasBusinessImplementations(serviceId, registeredName);
            McpCapabilityNodeKind nodeKind = McpCapabilityNodeKind.parse(
                declared.get("nodeKind"), parent == null
                    ? (inferredAbstractCapability
                        ? McpCapabilityNodeKind.ABSTRACT_CAPABILITY
                        : McpCapabilityNodeKind.STANDALONE)
                    : McpCapabilityNodeKind.BUSINESS_IMPLEMENTATION);
            McpCapabilityFallbackPolicy fallbackPolicy = McpCapabilityFallbackPolicy.parse(
                declared.get("fallbackPolicy"), nodeKind == McpCapabilityNodeKind.ABSTRACT_CAPABILITY
                    ? McpCapabilityFallbackPolicy.DENY_WHEN_NO_IMPLEMENTATION
                    : McpCapabilityFallbackPolicy.ALLOW_STANDALONE);
            return Optional.of(new McpCapabilityNode(
                serviceId,
                registeredName,
                resolvedParent == null ? parent : resolvedParent,
                nodeKind,
                fallbackPolicy,
                relationType,
                routingMode,
                declared
            ));
        } catch (RuntimeException registryRefreshRace) {
            return Optional.empty();
        }
    }

    @Override
    public Collection<McpCapabilityNode> nodes() {
        try {
            var names = registry == null ? null : registry.getAllToolNames();
            if (names == null) return List.of();
            return names.stream().map(this::node).flatMap(Optional::stream).toList();
        } catch (RuntimeException registryRefreshRace) {
            return List.of();
        }
    }

    private boolean hasBusinessImplementations(String serviceId, String parentToolName) {
        var names = registry.getAllToolNames();
        if (names == null) return false;
        String parentSemantic = McpToolNamePolicy.workflowSemanticKey(parentToolName);
        for (String candidate : names) {
            ToolMetadata metadata = registry.getToolMetadata(candidate);
            Map<String, Object> extra = metadata == null || metadata.getMetadata() == null
                ? Map.of() : metadata.getMetadata();
            if (serviceId != null && !serviceId.equals(text(extra.get("serviceId")))) continue;
            Map<String, Object> declared = map(extra.get(METADATA_KEY));
            String declaredParent = text(first(declared.get("parentToolName"), extra.get("parentRemoteToolName")));
            if (declaredParent != null
                && McpToolNamePolicy.workflowSemanticKey(declaredParent).equals(parentSemantic)) {
                return true;
            }
        }
        return false;
    }

    private String registeredName(String requested) {
        return registeredName(null, requested);
    }

    private String registeredName(String serviceId, String requested) {
        if (registry == null || requested == null || requested.isBlank()) return null;
        String semantic = McpToolNamePolicy.workflowSemanticKey(requested);
        var names = registry.getAllToolNames();
        if (names == null || names.isEmpty()) return null;
        for (String candidate : names) {
            ToolMetadata metadata = registry.getToolMetadata(candidate);
            Map<String, Object> extra = metadata == null || metadata.getMetadata() == null
                ? Map.of() : metadata.getMetadata();
            if (serviceId != null && !serviceId.equals(text(extra.get("serviceId")))) continue;
            String remoteName = text(extra.get("remoteToolName"));
            if (candidate.equals(requested)
                || McpToolNamePolicy.workflowSemanticKey(candidate).equals(semantic)
                || (remoteName != null && McpToolNamePolicy.workflowSemanticKey(remoteName).equals(semantic))) {
                return candidate;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    private Object first(Object first, Object second) {
        return first == null ? second : first;
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }
}
