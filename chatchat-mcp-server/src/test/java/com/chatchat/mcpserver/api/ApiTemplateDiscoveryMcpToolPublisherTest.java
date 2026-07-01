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
        config.setBusinessGroup("order_services");
        config.setBusinessGroupName("Order services");
        config.setBusinessGroupDescription("APIs for order status and fulfillment workflows");
        config.setMethod("GET");
        config.setUrlTemplate("https://internal.example/orders/{{orderId}}");
        config.setHeadersJson("{\"Authorization\":\"secret\"}");
        config.setBodyTemplate("{\"raw\":\"body\"}");
        config.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}");
        config.setEnabled(true);

        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(config));
        ApiTemplateDiscoveryMcpToolPublisher publisher = new ApiTemplateDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            configService,
            new ObjectMapper()
        );

        Map<String, Object> result = publisher.query(Map.of(
            "filters", Map.of("businessGroup", "Order services")
        ));

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("assetType")).isEqualTo("api_service");
        assertThat(result.toString()).contains("order_status_api", "订单状态查询", "parameterSchema");
        assertThat(result.toString()).doesNotContain("internal.example", "Authorization", "{\"raw\":\"body\"}");
        Map<?, ?> first = (Map<?, ?>) ((List<?>) result.get("templates")).get(0);
        assertThat(first.get("businessGroup").toString()).contains("order_services", "Order services", "fulfillment");
        assertThat(first.get("requiredParameters")).isEqualTo(List.of("orderId"));
        assertThat(first.get("parameterContract").toString()).contains("order_status_api.arguments", "orderId");
        assertThat(first.get("invocationExample").toString()).contains("order_status_api", "orderId");
    }

    @Test
    void queryMatchesApiTemplateByBusinessGroupDescription() {
        ApiServiceConfig orderApi = new ApiServiceConfig();
        orderApi.setId("api-order");
        orderApi.setToolName("order_status_api");
        orderApi.setTitle("Order status API");
        orderApi.setDescription("Query order status by order id");
        orderApi.setBusinessGroup("order_services");
        orderApi.setBusinessGroupName("Order services");
        orderApi.setBusinessGroupDescription("fulfillment lifecycle APIs");
        orderApi.setMethod("GET");
        orderApi.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}");
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
        ApiTemplateDiscoveryMcpToolPublisher publisher = new ApiTemplateDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            configService,
            new ObjectMapper()
        );

        Map<String, Object> result = publisher.query(Map.of(
            "filters", Map.of("groupDescription", "fulfillment lifecycle")
        ));

        assertThat(result.get("returnedCount")).isEqualTo(1);
        assertThat(result.toString()).contains("order_status_api", "order_services", "fulfillment lifecycle APIs");
        assertThat(result.toString()).doesNotContain("invoice_status_api", "billing_services");
        Map<?, ?> first = (Map<?, ?>) ((List<?>) result.get("templates")).get(0);
        assertThat(first.get("templateId")).isEqualTo("order_status_api");
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
