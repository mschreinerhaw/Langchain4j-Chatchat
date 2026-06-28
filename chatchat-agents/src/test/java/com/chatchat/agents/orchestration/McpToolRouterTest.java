package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolRouterTest {

    private final McpToolRouter router = new McpToolRouter();

    @Test
    void routesAssetDiscoveryToTypedApiAssetTool() {
        McpToolRouter.RoutingDecision decision = router.route(
            "asset_discovery",
            Map.of("routerCapability", "asset_discovery", "assetType", "api_service", "domain", "order"),
            List.of("api_asset_query", "ssh_asset_query"),
            "tenant-a",
            List.of("BUSINESS_ADMIN")
        );

        assertThat(decision.routed()).isTrue();
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.capability()).isEqualTo("asset_discovery");
        assertThat(decision.resolvedToolName()).isEqualTo("api_asset_query");
        assertThat(decision.scope().value())
            .isEqualTo("mcp:api_service:asset:query@tenant=tenant-a;domain=order;level=read");
    }

    @Test
    void routesTemplateDiscoveryByFinalDecisionTargetKind() {
        McpToolRouter.RoutingDecision decision = router.route(
            "template_discovery",
            Map.of("routerCapability", "template_discovery", "finalDecision", "business_database_query"),
            List.of("database_query_template_query", "sql_datasource_template_query"),
            "tenant-a",
            List.of()
        );

        assertThat(decision.resolvedToolName()).isEqualTo("database_query_template_query");
        assertThat(decision.scope().assetType()).isEqualTo("database_query");
    }

    @Test
    void deniesWhenTypedToolForAssetTypeIsUnavailable() {
        McpToolRouter.RoutingDecision decision = router.route(
            "asset_discovery",
            Map.of("assetType", "api_service"),
            List.of("ssh_asset_query"),
            "tenant-a",
            List.of()
        );

        assertThat(decision.routed()).isTrue();
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo("TOOL_ROUTING_DENIED");
    }

    @Test
    void acceptsLegacyQueryCapabilityAsCompatibilityInputOnly() {
        McpToolRouter.RoutingDecision decision = router.route(
            "asset_query",
            Map.of("assetType", "api_service"),
            List.of("api_asset_query"),
            "tenant-a",
            List.of()
        );

        assertThat(decision.resolvedToolName()).isEqualTo("api_asset_query");
        assertThat(decision.capability()).isEqualTo("asset_discovery");
    }
}
