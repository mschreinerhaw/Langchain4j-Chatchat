package com.chatchat.common.mcp.capability;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class McpCapabilityHierarchyTest {
    @Test
    void preservesChildIdentityAndBuildsLineage() {
        McpCapabilityNode parent = new McpCapabilityNode(
            "service", "mcp_service_api_service_query", null,
            McpCapabilityNodeKind.ABSTRACT_CAPABILITY, null, null, Map.of());
        McpCapabilityNode child = new McpCapabilityNode(
            "service", "mcp_service_customer_service_template_query", parent.toolName(),
            McpCapabilityNodeKind.BUSINESS_IMPLEMENTATION,
            McpCapabilityNode.RELATION_IMPLEMENTS_ABSTRACT_CAPABILITY,
            "api_parent_mcp_policy_filter", Map.of());
        McpCapabilityHierarchy hierarchy = tool -> {
            if (parent.toolName().equals(tool)) return Optional.of(parent);
            if (child.toolName().equals(tool)) return Optional.of(child);
            return Optional.empty();
        };

        assertThat(hierarchy.sameNode(parent.toolName(), child.toolName())).isFalse();
        assertThat(hierarchy.lineage(child.toolName())).containsExactly(child, parent);
        assertThat(hierarchy.isImplementationOf(child.toolName(), parent.toolName())).isTrue();
        assertThat(hierarchy.mostSpecific(java.util.List.of(parent.toolName(), child.toolName())))
            .containsExactly(child.toolName());
    }
}
