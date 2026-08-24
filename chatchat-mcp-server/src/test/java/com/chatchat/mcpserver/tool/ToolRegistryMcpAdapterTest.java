package com.chatchat.mcpserver.tool;

import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.config.ChatChatMcpServerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ToolRegistryMcpAdapterTest {

    @Test
    void injectsInvocationContextFromCallToolRequestMetaWithoutThreadLocalContext() {
        ToolRegistryMcpAdapter adapter = new ToolRegistryMcpAdapter(
            new ObjectMapper(),
            new ChatChatMcpServerProperties(),
            mock(AgentRuntimeGovernanceFactory.class),
            mock(McpToolConcurrencyManager.class),
            mock(McpAuthorizationService.class)
        );
        Map<String, Object> arguments = new LinkedHashMap<>(Map.of("query", "analyze error.log"));
        Map<String, Object> meta = Map.of(
            "traceId", "request-1",
            "tenant", Map.of("tenantId", "tenant-1", "workspaceId", "workspace-1", "env", "PROD"),
            "user", Map.of("userId", "user-1", "username", "analyst", "roles", "role-a"),
            "scope", Map.of("assetType", "python", "domain", "analysis", "permissionLevel", "execute"),
            "scopeExpression", "python:execute"
        );

        adapter.injectProtocolContext("python_analysis_query", arguments, meta);

        assertThat(arguments)
            .containsEntry("tenantId", "tenant-1")
            .containsEntry("userId", "user-1")
            .containsEntry("username", "analyst")
            .containsEntry("roles", "role-a")
            .containsEntry("traceId", "request-1")
            .containsEntry("workspaceId", "workspace-1")
            .containsEntry("env", "PROD")
            .containsEntry("assetType", "python")
            .containsEntry("domain", "analysis")
            .containsEntry("permissionLevel", "execute")
            .containsEntry("scopeExpression", "python:execute");
        assertThat(arguments.get("mcpContext"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("traceId", "request-1")
            .containsEntry("tenant", Map.of(
                "tenantId", "tenant-1", "workspaceId", "workspace-1", "env", "PROD"));
    }

    @Test
    void keepsExplicitBusinessArgumentsAheadOfRequestMeta() {
        ToolRegistryMcpAdapter adapter = new ToolRegistryMcpAdapter(
            new ObjectMapper(),
            new ChatChatMcpServerProperties(),
            mock(AgentRuntimeGovernanceFactory.class),
            mock(McpToolConcurrencyManager.class),
            mock(McpAuthorizationService.class)
        );
        Map<String, Object> arguments = new LinkedHashMap<>(Map.of("tenantId", "explicit-tenant"));

        adapter.injectProtocolContext(
            "python_analysis_query",
            arguments,
            Map.of("tenant", Map.of("tenantId", "meta-tenant"))
        );

        assertThat(arguments).containsEntry("tenantId", "explicit-tenant");
    }
}
