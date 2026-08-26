package com.chatchat.integration.mcp.service;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.integration.mcp.config.McpCenterProperties;
import com.chatchat.integration.mcp.entity.McpServiceConfig;
import com.chatchat.integration.mcp.model.McpToolInvokeResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class McpGatewayClientTest {

    @Test
    void dispatchesStandardToolListChangeToRuntimeListeners() {
        McpGatewayClient client = new McpGatewayClient(
            new ObjectMapper(), new McpCenterProperties(), new InternalCredentialProperties(),
            mock(McpStdioProxyService.class));
        client.setToolsChangeExecutor(Runnable::run);
        AtomicReference<String> changedService = new AtomicReference<>();
        client.addToolsChangeListener(changedService::set);
        McpServiceConfig service = new McpServiceConfig();
        service.setId("dynamic-service");
        service.setName("Dynamic service");

        client.notifyToolsChanged(service);

        assertThat(changedService).hasValue("dynamic-service");
    }

    @Test
    void preservesExpiredLicenseErrorReturnedByMcpServer() {
        McpGatewayClient client = new McpGatewayClient(
            new ObjectMapper(),
            new McpCenterProperties(),
            new InternalCredentialProperties(),
            mock(McpStdioProxyService.class)
        );

        McpToolInvokeResult result = client.failureResult("""
            MCP HTTP status 403: {"success":false,"error":"MCP_LICENSE_EXPIRED",
            "message":"License 已过期，新的 MCP 工具调用已停止，请联系供应商续期",
            "licenseStatus":"EXPIRED","retryable":false,"action":"STOP"}
            """);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("MCP_LICENSE_EXPIRED");
        assertThat(result.errorMessage()).isEqualTo("License 已过期，新的 MCP 工具调用已停止，请联系供应商续期");
        assertThat(result.retryable()).isFalse();
        assertThat(result.action()).isEqualTo("STOP");
    }

    @Test
    void removesRuntimeContextFromStandaloneToolArgumentsBeforeSdkValidation() {
        McpCenterProperties properties = new McpCenterProperties();
        properties.setStandaloneServiceId("chatchat-mcp-server");
        McpGatewayClient client = new McpGatewayClient(
            new ObjectMapper(), properties, new InternalCredentialProperties(),
            mock(McpStdioProxyService.class));
        McpServiceConfig service = new McpServiceConfig();
        service.setId("chatchat-mcp-server");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", "分析持仓数据");
        arguments.put("limit", 5);
        arguments.put("tenantId", "tenant-1");
        arguments.put("userId", "user-1");
        arguments.put("username", "analyst");
        arguments.put("roles", "role-a");
        arguments.put("requestId", "request-1");
        arguments.put("conversationId", "conversation-1");
        arguments.put("mcpExecutionContext", Map.of("source", "agent"));
        arguments.put("mcpContext", Map.of("tenantId", "tenant-1"));

        assertThat(client.toolArgumentsForTransport(service, arguments))
            .containsOnlyKeys("query", "limit")
            .containsEntry("query", "分析持仓数据")
            .containsEntry("limit", 5);
        assertThat(arguments).containsKeys("tenantId", "mcpContext");
        assertThat(client.invocationMeta(arguments))
            .containsEntry("traceId", "request-1")
            .containsEntry("tenant", Map.of("tenantId", "tenant-1"))
            .containsEntry("user", Map.of(
                "userId", "user-1", "username", "analyst", "roles", "role-a"));
    }

    @Test
    void preservesArgumentsForExternalMcpServices() {
        McpGatewayClient client = new McpGatewayClient(
            new ObjectMapper(), new McpCenterProperties(), new InternalCredentialProperties(),
            mock(McpStdioProxyService.class));
        McpServiceConfig service = new McpServiceConfig();
        service.setId("external-mcp");
        Map<String, Object> arguments = Map.of("query", "status", "tenantId", "remote-contract-tenant");

        assertThat(client.toolArgumentsForTransport(service, arguments)).isEqualTo(arguments);
    }

    @Test
    void normalizationKeepsCompleteRawMcpPayload() {
        McpGatewayClient client = new McpGatewayClient(
            new ObjectMapper(), new McpCenterProperties(), new InternalCredentialProperties(),
            mock(McpStdioProxyService.class));
        Map<String, Object> raw = Map.of(
            "structuredContent", Map.of("execution", Map.of("stdoutLength", 2208)),
            "content", java.util.List.of(Map.of("type", "text", "text", "mysql:8\npostgres:16")),
            "isError", false);

        McpToolInvokeResult result = client.normalizeInvokeResult(raw);

        assertThat(result.data()).isEqualTo(raw.get("structuredContent"));
        assertThat(result.rawData()).isEqualTo(raw);
        assertThat(result.rawData()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsKey("content");
    }

    @Test
    void recognizesRenamedStandaloneServiceByConfiguredEndpoint() {
        McpCenterProperties properties = new McpCenterProperties();
        properties.setBaseUrl("http://127.0.0.1:18080");
        McpGatewayClient client = new McpGatewayClient(
            new ObjectMapper(), properties, new InternalCredentialProperties(),
            mock(McpStdioProxyService.class));
        McpServiceConfig service = new McpServiceConfig();
        service.setId("database-generated-id");
        service.setName("renamed-service");
        service.setBaseUrl("http://127.0.0.1:18080/mcp");

        assertThat(client.toolArgumentsForTransport(service, Map.of(
            "query", "status", "tenantId", "tenant-1", "mcpContext", Map.of())))
            .containsOnlyKeys("query");
    }
}
