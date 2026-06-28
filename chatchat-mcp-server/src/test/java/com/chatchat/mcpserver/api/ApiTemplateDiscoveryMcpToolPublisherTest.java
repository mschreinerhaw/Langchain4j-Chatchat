package com.chatchat.mcpserver.api;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class ApiTemplateDiscoveryMcpToolPublisherTest {

    @Test
    void apiTemplateToolIsBusinessNamedReadOnlyDiscoveryTool() throws Exception {
        ApiTemplateDiscoveryMcpToolPublisher publisher = new ApiTemplateDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            mock(ApiServiceConfigService.class),
            new ObjectMapper()
        );
        Method apiTemplateQueryTool = ApiTemplateDiscoveryMcpToolPublisher.class.getDeclaredMethod("apiTemplateQueryTool");
        apiTemplateQueryTool.setAccessible(true);

        McpServerFeatures.SyncToolSpecification spec =
            (McpServerFeatures.SyncToolSpecification) apiTemplateQueryTool.invoke(publisher);
        McpSchema.Tool tool = spec.tool();
        Map<?, ?> meta = tool.meta();

        assertThat(tool.name()).isEqualTo(ApiTemplateDiscoveryMcpToolPublisher.TOOL_NAME);
        assertThat(tool.description()).contains("API service templates");
        assertThat(meta.get("runtimeAction")).isEqualTo("read_only");
        assertThat(meta.get("readOnly")).isEqualTo(true);
        assertThat(meta.get("targetKind")).isEqualTo("api_service");
        assertThat(meta.get("assetType")).isEqualTo("api_service");
        assertThat(meta.get("rawExecutionSpecReturned")).isEqualTo(false);
        assertThat(tool.inputSchema().toString()).contains("bilingualIntent", "intentZh", "intentEn");
    }

    @Test
    void queryReturnsApiTemplateMetadataWithoutRawExecutionSpec() {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setId("api-1");
        config.setToolName("order_status_api");
        config.setTitle("订单状态查询");
        config.setDescription("Query order status by order id");
        config.setMethod("GET");
        config.setUrlTemplate("https://internal.example/orders/{{orderId}}");
        config.setHeadersJson("{\"Authorization\":\"secret\"}");
        config.setBodyTemplate("{\"raw\":\"body\"}");
        config.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}");
        config.setEnabled(true);

        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(config));
        ApiTemplateDiscoveryMcpToolPublisher publisher = new ApiTemplateDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            configService,
            new ObjectMapper()
        );

        Map<String, Object> result = publisher.query(Map.of(
            "filters", Map.of("intentZh", "订单状态", "intentEn", "order status")
        ));

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("assetType")).isEqualTo("api_service");
        assertThat(result.toString()).contains("order_status_api", "订单状态查询", "parameterSchema");
        assertThat(result.toString()).doesNotContain("internal.example", "Authorization", "{\"raw\":\"body\"}");
    }

    @Test
    void queryRejectsRawApiExecutionFields() {
        ApiTemplateDiscoveryMcpToolPublisher publisher = new ApiTemplateDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            mock(ApiServiceConfigService.class),
            new ObjectMapper()
        );

        assertThatThrownBy(() -> publisher.query(Map.of("filters", Map.of("urlTemplate", "https://example.com"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("api_template_query");
    }
}
