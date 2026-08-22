package com.chatchat.agents.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import com.chatchat.common.tool.ToolWorkflowRole;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolRouterTest {

    private static final List<String> PUBLIC_TEMPLATE_DISCOVERY_BRIDGES = List.of(
        "api_service_query",
        "server_capability_query",
        "http_capability_query",
        "jmx_capability_query",
        "database_capability_query",
        "data_query_query",
        "python_analysis_query"
    );

    private final McpToolRouter router = new McpToolRouter();

    @Test
    void preservesUserBoundTemplateToolDespiteBusinessTargetHint() {
        String requested = "mcp_chatchat_mcp_server_database_ops_template_search";

        McpToolRouter.RoutingDecision decision = router.route(
            requested,
            Map.of(
                "capability", "template_discovery",
                "finalDecision", "business_database_query",
                "filters", Map.of("intent", "分析市场异常")
            ),
            List.of(
                requested,
                "mcp_chatchat_mcp_server_database_query_template_query"
            ),
            "tenant-a",
            List.of()
        );

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.resolvedToolName()).isEqualTo(requested);
        assertThat(decision.scope().assetType()).isEqualTo("database_query");
    }

    @Test
    void doesNotSelectAnotherBoundToolFromCapabilityOrListOrder() {
        String requested = "mcp_vendor_sql_datasource_template_query";
        String other = "mcp_vendor_database_query_template_query";

        McpToolRouter.RoutingDecision decision = router.route(
            requested,
            Map.of("routerCapability", "template_discovery", "assetType", "database_query"),
            List.of(other, requested),
            "tenant-a",
            List.of()
        );

        assertThat(decision.resolvedToolName()).isEqualTo(requested);
    }

    @Test
    void deniesTypedToolThatIsNotBoundToWorkflow() {
        String requested = "mcp_chatchat_mcp_server_database_query_template_query";

        McpToolRouter.RoutingDecision decision = router.route(
            requested,
            Map.of("capability", "template_discovery"),
            List.of("mcp_chatchat_mcp_server_database_ops_template_search"),
            "tenant-a",
            List.of()
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo("TOOL_ROUTING_DENIED");
        assertThat(decision.reason()).contains("not bound");
    }

    @Test
    void leavesNonDiscoveryToolUnrouted() {
        McpToolRouter.RoutingDecision decision = router.route(
            "sql_query_execute",
            Map.of(),
            List.of("sql_query_execute"),
            "tenant-a",
            List.of()
        );

        assertThat(decision.routed()).isFalse();
        assertThat(decision.resolvedToolName()).isEqualTo("sql_query_execute");
    }

    @Test
    void routesEveryPublicCapabilityBridgeAsTemplateDiscovery() {
        for (String bridge : PUBLIC_TEMPLATE_DISCOVERY_BRIDGES) {
            String requested = "mcp_chatchat_mcp_server_" + bridge;
            McpToolRouter.RoutingDecision decision = router.route(
                requested,
                Map.of("query", "inspect target"),
                List.of(requested),
                "tenant-a",
                List.of()
            );

            assertThat(router.isTypedTemplateQuery(requested)).as(requested).isTrue();
            assertThat(decision.allowed()).as(requested).isTrue();
            assertThat(decision.routed()).as(requested).isTrue();
            assertThat(decision.capability()).as(requested).isEqualTo("template_discovery");
        }
    }

    @Test
    void routesArbitraryPublishedToolNameFromWorkflowRole() {
        String requested = "mcp_vendor_x9f37";

        McpToolRouter.RoutingDecision decision = router.route(
            requested, Map.of("query", "inspect"), List.of(requested),
            "tenant-a", List.of(), ToolWorkflowRole.TEMPLATE_DISCOVERY);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.routed()).isTrue();
        assertThat(decision.capability()).isEqualTo("template_discovery");
    }
}
