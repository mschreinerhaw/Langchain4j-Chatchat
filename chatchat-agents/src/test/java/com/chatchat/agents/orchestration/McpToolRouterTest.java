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
    void routesBusinessSqlIntentAwayFromDatasourceTemplateIndex() {
        McpToolRouter.RoutingDecision decision = router.route(
            "mcp_chatchat_mcp_server_sql_datasource_template_query",
            Map.of(
                "finalDecision", "database",
                "filters", Map.of(
                    "intent", "分析行情数据发生较大波动时异常提醒数据",
                    "bilingualIntent", List.of("行情波动", "异常提醒", "market data volatility", "alert")
                )
            ),
            List.of("database_query_template_query", "sql_datasource_template_query"),
            "tenant-a",
            List.of()
        );

        assertThat(decision.resolvedToolName()).isEqualTo("database_query_template_query");
        assertThat(decision.scope().assetType()).isEqualTo("database_query");
    }

    @Test
    void routesBusinessSqlIntentWithPrefixedAvailableTools() {
        McpToolRouter.RoutingDecision decision = router.route(
            "mcp_chatchat_mcp_server_sql_datasource_template_query",
            Map.of(
                "finalDecision", "database",
                "filters", Map.of(
                    "intent", "分析行情数据发生较大波动时异常提醒数据",
                    "bilingualIntent", List.of("行情波动", "异常提醒", "market data volatility", "alert")
                )
            ),
            List.of(
                "mcp_chatchat_mcp_server_database_query_template_query",
                "mcp_chatchat_mcp_server_sql_datasource_template_query"
            ),
            "tenant-a",
            List.of()
        );

        assertThat(decision.resolvedToolName()).isEqualTo("mcp_chatchat_mcp_server_database_query_template_query");
        assertThat(decision.scope().assetType()).isEqualTo("database_query");
    }

    @Test
    void routesBusinessSqlIntentAfterRuntimeRoutingInputNormalization() {
        McpToolRouter.RoutingDecision decision = router.route(
            "mcp_chatchat_mcp_server_sql_datasource_template_query",
            Map.of(
                "limit", 10,
                "finalDecision", "database",
                "filters", Map.of(
                    "intent", "分析行情数据发生较大波动时异常提醒数据",
                    "bilingualIntent", List.of("行情波动", "异常提醒", "market data volatility", "alert")
                ),
                "filtersSchemaVersion", "target_filters.v1",
                "trace", Map.of(
                    "schemaVersion", "routing_trace.v1",
                    "source", "interpretation_plan_runtime",
                    "toolName", "mcp_chatchat_mcp_server_sql_datasource_template_query"
                )
            ),
            List.of(
                "mcp_chatchat_mcp_server_database_query_template_query",
                "mcp_chatchat_mcp_server_sql_datasource_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute"
            ),
            "tenant-a",
            List.of()
        );

        assertThat(decision.resolvedToolName()).isEqualTo("mcp_chatchat_mcp_server_database_query_template_query");
        assertThat(decision.scope().assetType()).isEqualTo("database_query");
    }

    @Test
    void keepsDatasourceTemplateIndexForDatabaseOpsIntent() {
        McpToolRouter.RoutingDecision decision = router.route(
            "mcp_chatchat_mcp_server_sql_datasource_template_query",
            Map.of(
                "finalDecision", "database",
                "filters", Map.of(
                    "intent", "查询数据库锁等待和会话连接信息",
                    "bilingualIntent", List.of("锁等待", "连接数", "lock wait", "session connection")
                )
            ),
            List.of("database_query_template_query", "sql_datasource_template_query"),
            "tenant-a",
            List.of()
        );

        assertThat(decision.resolvedToolName()).isEqualTo("sql_datasource_template_query");
        assertThat(decision.scope().assetType()).isEqualTo("sql_datasource");
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
