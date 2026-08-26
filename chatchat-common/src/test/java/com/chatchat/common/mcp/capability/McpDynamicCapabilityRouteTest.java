package com.chatchat.common.mcp.capability;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpDynamicCapabilityRouteTest {
    @Test
    void roundTripsParentDelegationContract() {
        McpDynamicCapabilityRoute route = McpDynamicCapabilityRoute.parentDelegation(
            "api_service_query", "_publishedImplementation");

        McpDynamicCapabilityRoute parsed = McpDynamicCapabilityRoute.fromToolMetadata(
            Map.of(McpDynamicCapabilityRoute.METADATA_KEY, route.toMetadata())).orElseThrow();

        assertThat(parsed).isEqualTo(route);
    }

    @Test
    void rejectsUnknownContractVersion() {
        assertThatThrownBy(() -> McpDynamicCapabilityRoute.fromToolMetadata(Map.of(
            McpDynamicCapabilityRoute.METADATA_KEY, Map.of(
                "contractVersion", "mcp.dynamic-capability-route.v2",
                "parentToolName", "parent",
                "implementationIdentityArgument", "_implementation",
                "routingMode", "parent_delegation"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported");
    }

    @Test
    void requiresReservedIdentityArgumentNamespace() {
        assertThatThrownBy(() -> McpDynamicCapabilityRoute.parentDelegation(
            "parent", "tenantId"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reserved MCP '_' namespace");
    }
}
