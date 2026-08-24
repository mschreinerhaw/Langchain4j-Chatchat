package com.chatchat.integration.mcp.service;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowContractCatalog;
import com.chatchat.common.tool.ToolWorkflowContractSnapshot;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.integration.mcp.entity.McpServiceConfig;
import com.chatchat.integration.mcp.model.McpToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import org.mockito.ArgumentCaptor;

class McpToolRegistryBridgeLifecycleTest {

    @Test
    void unpublishedDiscoveryIsStagedButNeverRegistered() {
        ToolRegistry registry = mock(ToolRegistry.class);
        McpServiceConfigService configService = mock(McpServiceConfigService.class);
        McpGatewayClient gateway = mock(McpGatewayClient.class);
        ToolWorkflowContractCatalog catalog = mock(ToolWorkflowContractCatalog.class);
        ObjectProvider<ToolWorkflowContractCatalog> provider = mock(ObjectProvider.class);
        McpServiceConfig service = service("service-draft", "Vendor");
        service.setContractAutoPublish(false);
        McpToolDefinition definition = new McpToolDefinition("opaque-91", "draft", Map.of());
        when(provider.getIfAvailable()).thenReturn(catalog);
        when(configService.listEnabled()).thenReturn(List.of(service));
        when(gateway.discoverTools(service, 0)).thenReturn(List.of(definition));
        when(catalog.synchronizeDiscovery(anyString(), anyString(), anyString(), anyString(),
            anyString(), anyMap(), anyMap(), anyMap(), anyBoolean())).thenReturn(Optional.empty());
        McpToolRegistryBridge bridge = new McpToolRegistryBridge(
            registry, configService, gateway, new ObjectMapper(),
            new DynamicMcpToolRouteService(), provider);

        bridge.refreshRegistry();

        verify(registry, never()).registerTool(anyString(), any(), any());
        assertThat(bridge.listRegisteredTools()).isEmpty();
    }

    @Test
    void activeDatabaseSnapshotControlsRoleAndSchemaForArbitraryToolName() {
        ToolRegistry registry = mock(ToolRegistry.class);
        McpServiceConfigService configService = mock(McpServiceConfigService.class);
        McpGatewayClient gateway = mock(McpGatewayClient.class);
        ToolWorkflowContractCatalog catalog = mock(ToolWorkflowContractCatalog.class);
        ObjectProvider<ToolWorkflowContractCatalog> provider = mock(ObjectProvider.class);
        McpServiceConfig service = service("service-active", "Vendor");
        McpToolDefinition definition = new McpToolDefinition("opaque-73", "active",
            Map.of("type", "object", "properties", Map.of("untrusted", Map.of("type", "string"))));
        ToolWorkflowContractSnapshot snapshot = new ToolWorkflowContractSnapshot(
            "tool-id", 4, ToolWorkflowContract.SCHEMA_VERSION,
            ToolWorkflowRole.TEMPLATE_EXECUTION, "generic", "json", "sha",
            Map.of("type", "object", "properties", Map.of("templateId", Map.of("type", "string")),
                "required", List.of("templateId")),
            Map.of("type", "object"), Map.of());
        when(provider.getIfAvailable()).thenReturn(catalog);
        when(configService.listEnabled()).thenReturn(List.of(service));
        when(gateway.discoverTools(service, 0)).thenReturn(List.of(definition));
        when(catalog.synchronizeDiscovery(anyString(), anyString(), anyString(), anyString(),
            anyString(), anyMap(), anyMap(), anyMap(), anyBoolean())).thenReturn(Optional.of(snapshot));
        McpToolRegistryBridge bridge = new McpToolRegistryBridge(
            registry, configService, gateway, new ObjectMapper(),
            new DynamicMcpToolRouteService(), provider);

        bridge.refreshRegistry();

        ArgumentCaptor<ToolMetadata> metadata = ArgumentCaptor.forClass(ToolMetadata.class);
        verify(registry).registerTool(anyString(), metadata.capture(), any());
        assertThat(ToolWorkflowContract.resolveRole("mcp_vendor_opaque_73", metadata.getValue()))
            .isEqualTo(ToolWorkflowRole.TEMPLATE_EXECUTION);
        assertThat(metadata.getValue().getParameters())
            .extracting(com.chatchat.common.tool.ToolParameter::getName)
            .containsExactly("templateId");
    }

    @Test
    void failedRefreshPreservesPreviouslyRegisteredSnapshot() {
        ToolRegistry registry = mock(ToolRegistry.class);
        McpServiceConfigService configService = mock(McpServiceConfigService.class);
        McpGatewayClient gateway = mock(McpGatewayClient.class);
        McpServiceConfig service = service("service-stable", "Stable");
        when(configService.listEnabled()).thenReturn(List.of(service));
        when(gateway.discoverTools(service, 0))
            .thenReturn(List.of(new McpToolDefinition("health", "health", Map.of())))
            .thenThrow(new IllegalStateException("temporary discovery timeout"));
        McpToolRegistryBridge bridge = new McpToolRegistryBridge(
            registry, configService, gateway, new ObjectMapper(), new DynamicMcpToolRouteService());

        bridge.refreshRegistry();
        bridge.refreshRegistry();

        assertThat(bridge.listRegisteredTools()).hasSize(1);
        verify(registry, never()).unregisterTool("mcp_stable_health");
    }

    @Test
    void failedRefreshRemovesRuntimeToolWhenDatabasePublishedAnotherContract() {
        ToolRegistry registry = mock(ToolRegistry.class);
        McpServiceConfigService configService = mock(McpServiceConfigService.class);
        McpGatewayClient gateway = mock(McpGatewayClient.class);
        ToolWorkflowContractCatalog catalog = mock(ToolWorkflowContractCatalog.class);
        ObjectProvider<ToolWorkflowContractCatalog> provider = mock(ObjectProvider.class);
        McpServiceConfig service = service("service-governed", "Governed");
        McpToolDefinition definition = new McpToolDefinition("opaque-41", "governed", Map.of());
        ToolWorkflowContractSnapshot v1 = snapshot("checksum-v1", 1);
        ToolWorkflowContractSnapshot v2 = snapshot("checksum-v2", 2);
        when(provider.getIfAvailable()).thenReturn(catalog);
        when(configService.listEnabled()).thenReturn(List.of(service));
        when(gateway.discoverTools(service, 0))
            .thenReturn(List.of(definition))
            .thenThrow(new IllegalStateException("temporary discovery timeout"));
        when(catalog.synchronizeDiscovery(anyString(), anyString(), anyString(), anyString(),
            anyString(), anyMap(), anyMap(), anyMap(), anyBoolean())).thenReturn(Optional.of(v1));
        when(catalog.findActive("service-governed", "mcp_governed_opaque_41", "opaque-41"))
            .thenReturn(Optional.of(v2));
        McpToolRegistryBridge bridge = new McpToolRegistryBridge(
            registry, configService, gateway, new ObjectMapper(),
            new DynamicMcpToolRouteService(), provider);

        bridge.refreshRegistry();
        bridge.refreshRegistry();

        verify(registry).unregisterTool("mcp_governed_opaque_41");
        assertThat(bridge.listRegisteredTools()).isEmpty();
    }

    private ToolWorkflowContractSnapshot snapshot(String checksum, long version) {
        return new ToolWorkflowContractSnapshot(
            "tool-id", version, ToolWorkflowContract.SCHEMA_VERSION,
            ToolWorkflowRole.DIRECT, "generic", "json", checksum,
            Map.of(), Map.of(), Map.of());
    }

    private McpServiceConfig service(String id, String name) {
        McpServiceConfig service = new McpServiceConfig();
        service.setId(id);
        service.setName(name);
        return service;
    }

    @Test
    void initialDiscoveryRunsOnlyAfterApplicationIsReady() throws Exception {
        McpServiceConfigService configService = mock(McpServiceConfigService.class);

        new McpToolRegistryBridge(
            mock(ToolRegistry.class),
            configService,
            mock(McpGatewayClient.class),
            new ObjectMapper(),
            new DynamicMcpToolRouteService()
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
            new ObjectMapper(),
            new DynamicMcpToolRouteService()
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
        McpToolRegistryBridge bridge = new McpToolRegistryBridge(
            registry, configService, gateway, new ObjectMapper(), new DynamicMcpToolRouteService());

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
            registry, configService, gateway, new ObjectMapper(), new DynamicMcpToolRouteService());

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
            new ObjectMapper(), new DynamicMcpToolRouteService());
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
    @SuppressWarnings("unchecked")
    void mcpBridgeUniformlySeparatesRuntimeEnvelopesFromStrictBusinessArguments() {
        ToolRegistry registry = mock(ToolRegistry.class);
        McpServiceConfigService configService = mock(McpServiceConfigService.class);
        McpGatewayClient gateway = mock(McpGatewayClient.class);
        McpServiceConfig service = new McpServiceConfig();
        service.setId("chatchat-mcp-server");
        service.setName("ChatChat MCP Server");
        Map<String, Object> inputSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                "templateId", Map.of("type", "string"),
                "parameters", Map.of("type", "object"),
                "purpose", Map.of("type", "string")
            ),
            "required", List.of("templateId", "parameters"),
            "additionalProperties", false
        );
        when(configService.listEnabled()).thenReturn(List.of(service));
        when(configService.getById("chatchat-mcp-server")).thenReturn(service);
        when(gateway.discoverTools(service, 0)).thenReturn(List.of(
            new McpToolDefinition("python_template_execute", "execute", inputSchema)
        ));
        when(gateway.invokeTool(eq(service), eq("python_template_execute"), anyMap(), eq(null)))
            .thenReturn(new com.chatchat.integration.mcp.model.McpToolInvokeResult(
                true, Map.of("status", "ok"), null, null));
        McpToolRegistryBridge bridge = new McpToolRegistryBridge(
            registry, configService, gateway, new ObjectMapper(), new DynamicMcpToolRouteService());

        bridge.refreshRegistry(0);

        ArgumentCaptor<ToolRegistry.EnhancedTool> toolCaptor =
            ArgumentCaptor.forClass(ToolRegistry.EnhancedTool.class);
        verify(registry).registerTool(anyString(), any(ToolMetadata.class), toolCaptor.capture());
        ToolOutput output = toolCaptor.getValue().execute(ToolInput.builder()
            .parameters(new LinkedHashMap<>(Map.of(
                "executionContext", Map.of("env", "PROD"),
                "template", "legacy-template-alias",
                "runtimeTemplateBinding", Map.of(
                    "schemaVersion", "runtime_template_binding.v1",
                    "templateId", "template-123",
                    "executorTool", "python_template_execute"
                ),
                "parameters", Map.of("source_file", "error.log", "limit", 100)
            )))
            .context(new LinkedHashMap<>(Map.of("tenantId", "tenant-1")))
            .requestId("request-1")
            .build());

        assertThat(output.isSuccess()).isTrue();
        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(gateway).invokeTool(eq(service), eq("python_template_execute"), arguments.capture(), eq(null));
        assertThat(arguments.getValue())
            .containsEntry("templateId", "template-123")
            .containsEntry("parameters", Map.of("source_file", "error.log", "limit", 100))
            .doesNotContainKeys("executionContext", "template", "runtimeTemplateBinding");
        assertThat((Map<String, Object>) arguments.getValue().get("mcpContext"))
            .containsEntry("tenantId", "tenant-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void canonicalRuntimeRolesOverrideModelSuppliedRoles() throws Exception {
        McpToolRegistryBridge bridge = new McpToolRegistryBridge(
            mock(ToolRegistry.class), mock(McpServiceConfigService.class), mock(McpGatewayClient.class),
            new ObjectMapper(), new DynamicMcpToolRouteService());
        Method enrich = McpToolRegistryBridge.class.getDeclaredMethod(
            "enrichInvocationContext", Map.class, com.chatchat.common.tool.ToolInput.class);
        enrich.setAccessible(true);
        Map<String, Object> arguments = new LinkedHashMap<>(Map.of(
            "roles", "SUPER_ADMIN",
            "roleIds", "forged-role"
        ));
        var input = com.chatchat.common.tool.ToolInput.builder()
            .context(Map.of(
                "roles", List.of("role-a", "role-b"),
                "canonicalRolesResolved", true
            ))
            .build();

        enrich.invoke(bridge, arguments, input);

        assertThat(arguments).containsEntry("roles", "role-a,role-b").doesNotContainKey("roleIds");
        assertThat((Map<String, Object>) arguments.get("mcpContext"))
            .containsEntry("roles", "role-a,role-b")
            .doesNotContainKey("roleIds");
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
                "parentToolName", "api_service_query"));
        when(configService.listEnabled()).thenReturn(List.of(service));
        when(configService.getById("chatchat-mcp-server")).thenReturn(service);
        when(gateway.discoverTools(service, 0)).thenReturn(List.of(child));
        when(gateway.invokeTool(eq(service), eq("api_service_query"), anyMap(), eq(null)))
            .thenReturn(new com.chatchat.integration.mcp.model.McpToolInvokeResult(
                true, Map.of("templates", List.of()), null, null));
        McpToolRegistryBridge bridge = new McpToolRegistryBridge(
            registry, configService, gateway, new ObjectMapper(), new DynamicMcpToolRouteService());

        bridge.refreshRegistry(0);
        ArgumentCaptor<ToolRegistry.EnhancedTool> toolCaptor =
            ArgumentCaptor.forClass(ToolRegistry.EnhancedTool.class);
        verify(registry).registerTool(anyString(), any(ToolMetadata.class), toolCaptor.capture());
        toolCaptor.getValue().execute(com.chatchat.common.tool.ToolInput.builder()
            .parameters(Map.of("limit", 10, "_templateQueryChildToolName", "spoofed_template_query"))
            .requestId("request-1")
            .build());

        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(gateway).invokeTool(eq(service), eq("api_service_query"), arguments.capture(), eq(null));
        assertThat(arguments.getValue())
            .containsEntry("_templateQueryChildToolName", "customer_service_template_query")
            .containsEntry("limit", 10);
        assertThat(bridge.listRegisteredTools().get(0).remoteToolName())
            .isEqualTo("customer_service_template_query");

        bridge.invoke("chatchat-mcp-server", "customer_service_template_query",
            Map.of("_templateQueryChildToolName", "spoofed_template_query", "limit", 5));
        ArgumentCaptor<Map<String, Object>> adminArguments = ArgumentCaptor.forClass(Map.class);
        verify(gateway).invokeTool(eq(service), eq("api_service_query"), adminArguments.capture());
        assertThat(adminArguments.getValue())
            .containsEntry("_templateQueryChildToolName", "customer_service_template_query")
            .containsEntry("limit", 5);
    }
}
