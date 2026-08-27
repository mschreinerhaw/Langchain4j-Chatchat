package com.chatchat.common.mcp.capability;

import com.chatchat.common.tool.McpToolNamePolicy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Collection;

/**
 * Runtime OS port for resolving MCP parent/child capability identity.
 *
 * <p>Workflow role equality is intentionally not node equality. A governed child
 * keeps its own business identity and authorization scope. When it implements an
 * abstract parent, the parent is a transport/capability boundary rather than a
 * second mandatory business-evidence node.</p>
 */
public interface McpCapabilityHierarchy {
    String METADATA_KEY = "mcpCapabilityNode";

    Optional<McpCapabilityNode> node(String toolName);

    default Collection<McpCapabilityNode> nodes() {
        return List.of();
    }

    default boolean sameNode(String left, String right) {
        if (left == null || right == null) return false;
        Optional<McpCapabilityNode> leftNode = node(left);
        Optional<McpCapabilityNode> rightNode = node(right);
        if (leftNode.isPresent() || rightNode.isPresent()) {
            return leftNode.isPresent() && rightNode.isPresent()
                && leftNode.get().identity().equals(rightNode.get().identity());
        }
        return McpToolNamePolicy.workflowSemanticKey(left)
            .equals(McpToolNamePolicy.workflowSemanticKey(right));
    }

    default List<McpCapabilityNode> lineage(String toolName) {
        List<McpCapabilityNode> lineage = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Optional<McpCapabilityNode> current = node(toolName);
        while (current.isPresent() && visited.add(current.get().identity())) {
            McpCapabilityNode value = current.get();
            lineage.add(value);
            current = value.parentToolName() == null ? Optional.empty() : node(value.parentToolName());
        }
        return List.copyOf(lineage);
    }

    default boolean isImplementationOf(String candidate, String abstractTool) {
        if (candidate == null || abstractTool == null || sameNode(candidate, abstractTool)) return false;
        return lineage(candidate).stream().skip(1)
            .anyMatch(ancestor -> sameNode(ancestor.toolName(), abstractTool));
    }

    default List<McpCapabilityNode> implementations(String abstractTool) {
        return nodes().stream()
            .filter(McpCapabilityNode::businessImplementation)
            .filter(candidate -> isImplementationOf(candidate.toolName(), abstractTool))
            .toList();
    }

    default boolean directlyInvocable(String toolName) {
        return node(toolName)
            .map(value -> !value.abstractCapability()
                || value.fallbackPolicy() == McpCapabilityFallbackPolicy.ALLOW_STANDALONE)
            .orElse(true);
    }

    default List<String> mostSpecific(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) return List.of();
        List<String> ordered = toolNames.stream()
            .filter(value -> value != null && !value.isBlank()).distinct().toList();
        return ordered.stream()
            .filter(candidate -> ordered.stream()
                .noneMatch(other -> isImplementationOf(other, candidate)))
            .toList();
    }

    static McpCapabilityHierarchy empty() {
        return ignored -> Optional.empty();
    }
}
