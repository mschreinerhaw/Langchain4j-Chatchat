package com.chatchat.mcpserver.api;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiAssetDiscoveryMcpToolPublisherTest {

    @Test
    void apiAssetToolIsTypedReadOnlyDiscoveryTool() throws Exception {
        ApiAssetDiscoveryMcpToolPublisher publisher = new ApiAssetDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            mock(ApiServiceConfigService.class)
        );
        Method apiAssetQueryTool = ApiAssetDiscoveryMcpToolPublisher.class.getDeclaredMethod("apiAssetQueryTool");
        apiAssetQueryTool.setAccessible(true);

        McpServerFeatures.SyncToolSpecification spec =
            (McpServerFeatures.SyncToolSpecification) apiAssetQueryTool.invoke(publisher);
        McpSchema.Tool tool = spec.tool();
        Map<?, ?> meta = tool.meta();
        Map<?, ?> boundary = (Map<?, ?>) meta.get("toolBoundary");
        Map<?, ?> indexPolicy = (Map<?, ?>) meta.get("indexPolicy");

        assertThat(tool.name()).isEqualTo(ApiAssetDiscoveryMcpToolPublisher.TOOL_NAME);
        assertThat(meta.get("runtimeAction")).isEqualTo("read_only");
        assertThat(meta.get("readOnly")).isEqualTo(true);
        assertThat(meta.get("targetKind")).isEqualTo("api_service");
        assertThat(meta.get("assetType")).isEqualTo("api_service");
        assertThat(boundary.get("rejectCrossTypeRouting")).isEqualTo(true);
        assertThat(indexPolicy.get("logicalIndex")).isEqualTo("asset:api_service");
    }

    @Test
    void queryReturnsApiAssetMetadataWithoutRawExecutionSpec() {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setId("api-1");
        config.setToolName("order_status_api");
        config.setTitle("订单状态查询");
        config.setDescription("Query order status by order id");
        config.setBusinessGroup("order_services");
        config.setBusinessGroupName("Order services");
        config.setBusinessGroupDescription("APIs for order status and fulfillment workflows");
        config.setMethod("GET");
        config.setUrlTemplate("https://internal.example/orders/{{orderId}}");
        config.setHeadersJson("{\"Authorization\":\"secret\"}");
        config.setBodyTemplate("{\"raw\":\"body\"}");
        config.setEnabled(true);

        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(config));
        ApiAssetDiscoveryMcpToolPublisher publisher = new ApiAssetDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            configService
        );

        Map<String, Object> result = publisher.query(Map.of(
            "filters", Map.of("groupName", "Order services")
        ));

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("assetType")).isEqualTo("api_service");
        assertThat(result.toString()).contains("order_status_api", "订单状态查询", "api_template_query");
        assertThat(result.toString()).contains("order_services", "Order services", "fulfillment");
        assertThat(result.toString()).doesNotContain("internal.example", "Authorization", "{\"raw\":\"body\"}");
    }

    @Test
    void queryMatchesApiAssetByBusinessGroupDescription() {
        ApiServiceConfig orderApi = new ApiServiceConfig();
        orderApi.setId("api-order");
        orderApi.setToolName("order_status_api");
        orderApi.setTitle("Order status API");
        orderApi.setDescription("Query order status by order id");
        orderApi.setBusinessGroup("order_services");
        orderApi.setBusinessGroupName("Order services");
        orderApi.setBusinessGroupDescription("fulfillment lifecycle APIs");
        orderApi.setMethod("GET");
        orderApi.setEnabled(true);
        ApiServiceConfig billingApi = new ApiServiceConfig();
        billingApi.setId("api-billing");
        billingApi.setToolName("invoice_status_api");
        billingApi.setTitle("Invoice status API");
        billingApi.setDescription("Query invoice status by invoice id");
        billingApi.setBusinessGroup("billing_services");
        billingApi.setBusinessGroupName("Billing services");
        billingApi.setBusinessGroupDescription("invoice settlement APIs");
        billingApi.setMethod("GET");
        billingApi.setEnabled(true);

        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(billingApi, orderApi));
        ApiAssetDiscoveryMcpToolPublisher publisher = new ApiAssetDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            configService
        );

        Map<String, Object> result = publisher.query(Map.of(
            "filters", Map.of("groupDescription", "fulfillment lifecycle")
        ));

        assertThat(result.get("returnedCount")).isEqualTo(1);
        assertThat(result.toString()).contains("order_status_api", "order_services", "fulfillment lifecycle APIs");
        assertThat(result.toString()).doesNotContain("invoice_status_api", "billing_services");
        Map<?, ?> first = (Map<?, ?>) ((List<?>) result.get("assets")).get(0);
        Map<?, ?> asset = (Map<?, ?>) first.get("asset");
        assertThat(asset.get("name")).isEqualTo("order_status_api");
    }

    @Test
    void queryRejectsRawApiExecutionFields() {
        ApiAssetDiscoveryMcpToolPublisher publisher = new ApiAssetDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            mock(ApiServiceConfigService.class)
        );

        assertThatThrownBy(() -> publisher.query(Map.of("filters", Map.of("urlTemplate", "https://example.com"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("api_asset_query");
    }
}
