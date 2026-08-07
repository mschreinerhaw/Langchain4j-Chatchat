package com.chatchat.integration.mcp.service;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.integration.mcp.entity.McpServiceConfig;
import com.chatchat.integration.mcp.model.McpToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

class McpToolRegistryBridgeLifecycleTest {

    @Test
    void initialDiscoveryRunsOnlyAfterApplicationIsReady() throws Exception {
        McpServiceConfigService configService = mock(McpServiceConfigService.class);

        new McpToolRegistryBridge(
            mock(ToolRegistry.class),
            configService,
            mock(McpGatewayClient.class),
            new ObjectMapper()
        );

        verifyNoInteractions(configService);

        Method initialize = McpToolRegistryBridge.class.getMethod("initialize");
        EventListener eventListener = initialize.getAnnotation(EventListener.class);
        Order order = initialize.getAnnotation(Order.class);

        assertThat(eventListener).isNotNull();
        assertThat(eventListener.value()).containsExactly(ApplicationReadyEvent.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void readyCallbackRefreshesEnabledServices() {
        McpServiceConfigService configService = mock(McpServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of());
        McpToolRegistryBridge bridge = new McpToolRegistryBridge(
            mock(ToolRegistry.class),
            configService,
            mock(McpGatewayClient.class),
            new ObjectMapper()
        );

        bridge.initialize();

        org.mockito.Mockito.verify(configService).listEnabled();
    }

    @Test
    void discoveredRemoteTimeoutIsPropagatedToRuntimeMetadata() {
        ToolRegistry registry = mock(ToolRegistry.class);
        McpServiceConfigService configService = mock(McpServiceConfigService.class);
        McpGatewayClient gateway = mock(McpGatewayClient.class);
        McpServiceConfig service = new McpServiceConfig();
        service.setId("service-1");
        service.setName("remote");
        when(configService.listEnabled()).thenReturn(List.of(service));
        when(gateway.discoverTools(service, 0)).thenReturn(List.of(new McpToolDefinition(
            "web_search", "search", java.util.Map.of(), null, null, null, null, null,
            java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), 30_000L,
            java.util.Map.of())));
        McpToolRegistryBridge bridge = new McpToolRegistryBridge(registry, configService, gateway, new ObjectMapper());

        bridge.refreshRegistry(0);

        ArgumentCaptor<ToolMetadata> metadata = ArgumentCaptor.forClass(ToolMetadata.class);
        org.mockito.Mockito.verify(registry).registerTool(anyString(), metadata.capture(), any());
        assertThat(metadata.getValue().getTimeoutMillis()).isEqualTo(30_000L);
    }

    @Test
    void discoveredInputSchemaRequiredFieldsArePropagatedToToolParameters() {
        ToolRegistry registry = mock(ToolRegistry.class);
        McpServiceConfigService configService = mock(McpServiceConfigService.class);
        McpGatewayClient gateway = mock(McpGatewayClient.class);
        McpServiceConfig service = new McpServiceConfig();
        service.setId("service-1");
        service.setName("remote");
        Map<String, Object> inputSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                "templateId", Map.of("type", "string", "description", "Selected template"),
                "parameters", Map.of("type", "object")
            ),
            "required", List.of("templateId")
        );
        when(configService.listEnabled()).thenReturn(List.of(service));
        when(gateway.discoverTools(service, 0)).thenReturn(List.of(
            new McpToolDefinition("template_execute", "execute", inputSchema)
        ));
        McpToolRegistryBridge bridge = new McpToolRegistryBridge(
            registry, configService, gateway, new ObjectMapper());

        bridge.refreshRegistry(0);

        ArgumentCaptor<ToolMetadata> metadata = ArgumentCaptor.forClass(ToolMetadata.class);
        org.mockito.Mockito.verify(registry).registerTool(anyString(), metadata.capture(), any());
        assertThat(metadata.getValue().getParameters())
            .extracting(com.chatchat.common.tool.ToolParameter::getName,
                com.chatchat.common.tool.ToolParameter::isRequired)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("templateId", true),
                org.assertj.core.groups.Tuple.tuple("parameters", false)
            );
    }

    @Test
    @SuppressWarnings("unchecked")
    void finalSummaryPurposeIsPropagatedInsideMcpContext() throws Exception {
        McpToolRegistryBridge bridge = new McpToolRegistryBridge(
            mock(ToolRegistry.class), mock(McpServiceConfigService.class), mock(McpGatewayClient.class),
            new ObjectMapper());
        Method enrich = McpToolRegistryBridge.class.getDeclaredMethod(
            "enrichInvocationContext", Map.class, com.chatchat.common.tool.ToolInput.class);
        enrich.setAccessible(true);
        Map<String, Object> arguments = new LinkedHashMap<>();
        var input = com.chatchat.common.tool.ToolInput.builder()
            .requestId("request-1")
            .context(Map.of("internalPurpose", "final_summary_web_enhancement", "tenantId", "tenant-1"))
            .build();

        enrich.invoke(bridge, arguments, input);

        assertThat((Map<String, Object>) arguments.get("mcpContext"))
            .containsEntry("internalPurpose", "final_summary_web_enhancement");
    }

    @Test
    void dynamicTemplateQueryInvokesParentAndInjectsChildIdentity() {
        ToolRegistry registry = mock(ToolRegistry.class);
        McpServiceConfigService configService = mock(McpServiceConfigService.class);
        McpGatewayClient gateway = mock(McpGatewayClient.class);
        McpServiceConfig service = new McpServiceConfig();
        service.setId("chatchat-mcp-server");
        service.setName("ChatChat MCP Server");
        McpToolDefinition child = new McpToolDefinition(
            "customer_service_template_query", "authorized templates", Map.of(),
            "template_discovery", "low", "read", null, true,
            Map.of(), Map.of(), Map.of(), Map.of(), null,
            Map.of("kind", "dynamic_authorized_template_discovery",
                "parentToolName", "api_template_query"));
        when(configService.listEnabled()).thenReturn(List.of(service));
        when(configService.getById("chatchat-mcp-server")).thenReturn(service);
        when(gateway.discoverTools(service, 0)).thenReturn(List.of(child));
        when(gateway.invokeTool(eq(service), eq("api_template_query"), anyMap(), eq(null)))
            .thenReturn(new com.chatchat.integration.mcp.model.McpToolInvokeResult(
                true, Map.of("templates", List.of()), null, null));
        McpToolRegistryBridge bridge = new McpToolRegistryBridge(
            registry, configService, gateway, new ObjectMapper());

        bridge.refreshRegistry(0);
        ArgumentCaptor<ToolRegistry.EnhancedTool> toolCaptor =
            ArgumentCaptor.forClass(ToolRegistry.EnhancedTool.class);
        verify(registry).registerTool(anyString(), any(ToolMetadata.class), toolCaptor.capture());
        toolCaptor.getValue().execute(com.chatchat.common.tool.ToolInput.builder()
            .parameters(Map.of("limit", 10, "_templateQueryChildToolName", "spoofed_template_query"))
            .requestId("request-1")
            .build());

        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(gateway).invokeTool(eq(service), eq("api_template_query"), arguments.capture(), eq(null));
        assertThat(arguments.getValue())
            .containsEntry("_templateQueryChildToolName", "customer_service_template_query")
            .containsEntry("limit", 10);
        assertThat(bridge.listRegisteredTools().get(0).remoteToolName())
            .isEqualTo("customer_service_template_query");

        bridge.invoke("chatchat-mcp-server", "customer_service_template_query",
            Map.of("_templateQueryChildToolName", "spoofed_template_query", "limit", 5));
        ArgumentCaptor<Map<String, Object>> adminArguments = ArgumentCaptor.forClass(Map.class);
        verify(gateway).invokeTool(eq(service), eq("api_template_query"), adminArguments.capture());
        assertThat(adminArguments.getValue())
            .containsEntry("_templateQueryChildToolName", "customer_service_template_query")
            .containsEntry("limit", 5);
    }
}
