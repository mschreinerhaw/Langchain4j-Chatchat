package com.chatchat.integration.mcp.service;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpToolQuery;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.integration.mcp.model.McpToolInvokeResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfiguredRemoteMcpServiceProviderTest {
    @Test
    void projectsNormalizedAndRawGatewayDataIntoCommonResult() {
        McpToolRegistryBridge registry = mock(McpToolRegistryBridge.class);
        when(registry.listRegisteredTools()).thenReturn(List.of(
            new McpToolRegistryBridge.RegisteredMcpTool("docker_ps", "docker", "Docker", "ps", "list")));
        Map<String, Object> normalized = Map.of("execution", Map.of("stdoutLength", 25));
        Map<String, Object> raw = Map.of("structuredContent", normalized,
            "content", List.of(Map.of("type", "text", "text", "container-a\ncontainer-b")));
        when(registry.invoke("docker", "docker_ps", Map.of())).thenReturn(
            new McpToolInvokeResult(true, normalized, raw, "ok", null, null, false, null, Map.of()));
        ConfiguredRemoteMcpServiceProvider provider = new ConfiguredRemoteMcpServiceProvider(
            mock(McpServiceConfigService.class), registry, mock(ToolRegistry.class));

        McpServiceResult result = provider.invoke(
            new McpServiceCall(null, "r1", "docker", "docker_ps", Map.of(), Map.of(), 0));

        assertThat(result.data()).isSameAs(normalized);
        assertThat(result.rawData()).isSameAs(raw);
    }

    @Test
    void exposesContractEvidenceButNotConnectionSecrets() {
        McpToolRegistryBridge registry = mock(McpToolRegistryBridge.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(registry.listRegisteredTools()).thenReturn(List.of(
            new McpToolRegistryBridge.RegisteredMcpTool("linux_command", "ops", "Ops", "linux_command_execute", "run")));
        when(toolRegistry.getToolMetadata("linux_command")).thenReturn(ToolMetadata.builder()
            .riskLevel("low").operationType("read").runtimeLevel("readonly")
            .metadata(Map.of(
                "contractVersion", "mcp_tool_contract.v1",
                "inputSchema", Map.of("type", "object"),
                "outputSchema", Map.of("type", "object"),
                "mcpToolMeta", Map.of(
                    "templates", List.of(Map.of("templateId", "CHECK_DOCKER_IMAGES")),
                    "authToken", "must-not-leak",
                    "password", "must-not-leak")))
            .build());
        ConfiguredRemoteMcpServiceProvider provider = new ConfiguredRemoteMcpServiceProvider(
            mock(McpServiceConfigService.class), registry, toolRegistry);

        Map<String, Object> metadata = provider.tools(McpToolQuery.all()).iterator().next().metadata();

        assertThat(metadata).containsEntry("contractVersion", "mcp_tool_contract.v1");
        assertThat(String.valueOf(metadata.get("contractMeta"))).contains("CHECK_DOCKER_IMAGES")
            .doesNotContain("must-not-leak", "authToken", "password");
    }
}
