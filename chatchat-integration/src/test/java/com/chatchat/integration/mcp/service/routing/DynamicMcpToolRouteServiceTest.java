package com.chatchat.integration.mcp.service.routing;

import com.chatchat.integration.mcp.service.routing.DynamicMcpToolRouteService;

import com.chatchat.common.mcp.capability.McpDynamicCapabilityRoute;
import com.chatchat.integration.mcp.model.McpToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicMcpToolRouteServiceTest {

    private final DynamicMcpToolRouteService routeService = new DynamicMcpToolRouteService();

    @Test
    void dynamicChildRoutesToParentAndOverwritesCallerIdentity() {
        DynamicMcpToolRouteService.RouteDefinition route = routeService
            .register("service-1", dynamicTool("sales_template_query", "api_template_query"))
            .orElseThrow();

        DynamicMcpToolRouteService.InvocationPlan plan = routeService.plan(
            "service-1",
            "sales_template_query",
            Map.of("limit", 10, DynamicMcpToolRouteService.CHILD_TOOL_ARGUMENT, "spoofed_template_query")
        );

        assertThat(plan.routed()).isTrue();
        assertThat(plan.remoteToolName()).isEqualTo("api_template_query");
        assertThat(plan.childToolName()).isEqualTo("sales_template_query");
        assertThat(plan.arguments())
            .containsEntry("limit", 10)
            .containsEntry(DynamicMcpToolRouteService.CHILD_TOOL_ARGUMENT, "sales_template_query");
        assertThatThrownBy(() -> plan.arguments().put("limit", 20))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(route.routingMode()).isEqualTo("api_parent_mcp_policy_filter");
    }

    @Test
    void genericContractRoutesAnyDynamicBusinessImplementation() {
        McpDynamicCapabilityRoute contract = McpDynamicCapabilityRoute.parentDelegation(
            "stable_business_gateway", "_implementationId");
        McpToolDefinition definition = new McpToolDefinition(
            "regional_customer_query", "regional implementation", Map.of(),
            null, null, null, null, true,
            Map.of(), Map.of(), Map.of(), Map.of(), null,
            Map.of(McpDynamicCapabilityRoute.METADATA_KEY, contract.toMetadata()));

        routeService.register("service-1", definition).orElseThrow();
        DynamicMcpToolRouteService.InvocationPlan plan = routeService.plan(
            "service-1", "regional_customer_query",
            Map.of("_implementationId", "spoofed", "customerId", "42"));

        assertThat(plan.remoteToolName()).isEqualTo("stable_business_gateway");
        assertThat(plan.arguments())
            .containsEntry("_implementationId", "regional_customer_query")
            .containsEntry("customerId", "42");
        assertThat(plan.routingMode()).isEqualTo("parent_delegation");
    }

    @Test
    void ordinaryToolUsesDirectImmutablePlan() {
        assertThat(routeService.register("service-1", new McpToolDefinition(
            "health", "health", Map.of()))).isEmpty();

        DynamicMcpToolRouteService.InvocationPlan plan =
            routeService.plan("service-1", "health", Map.of(
                "verbose", true,
                DynamicMcpToolRouteService.CHILD_TOOL_ARGUMENT, "spoofed_template_query"));

        assertThat(plan.routed()).isFalse();
        assertThat(plan.remoteToolName()).isEqualTo("health");
        assertThat(plan.arguments()).containsEntry("verbose", true);
        assertThat(plan.arguments()).doesNotContainKey(DynamicMcpToolRouteService.CHILD_TOOL_ARGUMENT);
    }

    @Test
    void routesAreIsolatedByMcpServiceAndRemovedOnRefresh() {
        routeService.register("service-1", dynamicTool("sales_template_query", "api_template_query"));

        assertThat(routeService.plan("service-2", "sales_template_query", Map.of()).routed()).isFalse();

        routeService.clear();

        assertThat(routeService.plan("service-1", "sales_template_query", Map.of()).routed()).isFalse();
    }

    @Test
    void invalidDynamicRoutesAreRejectedInsteadOfFallingBackToDirectInvocation() {
        assertThatThrownBy(() -> routeService.register(
            "service-1", dynamicTool("sales_template_query", "sales_template_query")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot route to itself");

        McpToolDefinition unsupported = dynamicTool(
            "sales_template_query", "api_template_query", "client_side_filter");
        assertThatThrownBy(() -> routeService.register("service-1", unsupported))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported dynamic MCP routing mode");
    }

    private McpToolDefinition dynamicTool(String child, String parent) {
        return dynamicTool(child, parent, null);
    }

    private McpToolDefinition dynamicTool(String child, String parent, String routingMode) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("kind", "dynamic_authorized_template_discovery");
        meta.put("parentToolName", parent);
        if (routingMode != null) {
            meta.put("routingMode", routingMode);
        }
        return new McpToolDefinition(
            child, "authorized templates", Map.of(),
            "template_discovery", "low", "read", null, true,
            Map.of(), Map.of(), Map.of(), Map.of(), null, meta);
    }
}
