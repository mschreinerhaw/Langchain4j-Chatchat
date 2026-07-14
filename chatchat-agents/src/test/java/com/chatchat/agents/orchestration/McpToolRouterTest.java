package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolRouterTest {

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
                "mcp_chatchat_mcp_server_business_query_template_search"
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
        String requested = "mcp_chatchat_mcp_server_business_query_template_search";

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
}
