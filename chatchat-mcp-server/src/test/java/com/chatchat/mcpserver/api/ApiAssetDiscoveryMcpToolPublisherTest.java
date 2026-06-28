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
            "filters", Map.of("intentZh", "订单状态", "intentEn", "order status")
        ));

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("assetType")).isEqualTo("api_service");
        assertThat(result.toString()).contains("order_status_api", "订单状态查询", "api_template_query");
        assertThat(result.toString()).doesNotContain("internal.example", "Authorization", "{\"raw\":\"body\"}");
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
