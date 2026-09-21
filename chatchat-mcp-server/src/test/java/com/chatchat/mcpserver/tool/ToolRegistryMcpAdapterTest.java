package com.chatchat.mcpserver.tool;

import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.config.ChatChatMcpServerProperties;
import com.chatchat.agents.tool.DefaultToolRegistry;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class ToolRegistryMcpAdapterTest {

    @Test
    void writesChineseAliasIntoStartupToolList() {
        McpToolAliasRepository aliases = mock(McpToolAliasRepository.class);
        when(aliases.findById("name:calculator"))
            .thenReturn(Optional.of(new McpToolAlias("name:calculator", "计算器")));
        McpToolChineseAliasResolver resolver = new McpToolChineseAliasResolver(aliases, new ObjectMapper());
        try {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        ToolMetadata metadata = ToolMetadata.builder().id("calculator").title("Calculator")
            .description("Calculate a result").metadata(Map.of()).build();
        registry.registerTool("calculator", metadata, new ToolRegistry.EnhancedTool() {
            @Override public ToolMetadata getMetadata() { return metadata; }
            @Override public ToolOutput execute(ToolInput input) { return ToolOutput.success(Map.of()); }
        });
        AgentRuntimeGovernanceFactory governance = mock(AgentRuntimeGovernanceFactory.class);
        McpToolConcurrencyManager concurrency = mock(McpToolConcurrencyManager.class);
        when(governance.metaForToolMetadata(eq("builtin_tool"), eq("calculator"), eq(metadata)))
            .thenReturn(Map.of());
        when(concurrency.limitMeta(eq("calculator"), any())).thenReturn(Map.of());
        ToolRegistryMcpAdapter adapter = new ToolRegistryMcpAdapter(new ObjectMapper(),
            new ChatChatMcpServerProperties(), governance, concurrency,
            mock(McpAuthorizationService.class));

        assertThat(adapter.toToolSpecifications(registry)).singleElement()
            .satisfies(spec -> assertThat(spec.tool().meta()).containsEntry("chineseAlias", "计算器"));
        } finally {
            resolver.close();
        }
    }

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
