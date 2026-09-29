package com.chatchat.chat.asset;

import com.chatchat.agents.runtime.tool.*;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.interaction.service.AgentToolPolicyResolver;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpTemplateGuidanceSourceTest {
    final SkillCatalogService agents = mock(SkillCatalogService.class);
    final AgentToolPolicyResolver policies = mock(AgentToolPolicyResolver.class);
    final ResourceAuthorizationPort grants = mock(ResourceAuthorizationPort.class);
    final ToolRegistry registry = mock(ToolRegistry.class);
    final ToolRuntimeService tools = mock(ToolRuntimeService.class);
    final AnalysisContext context = new AnalysisContext("这个 API 做什么用", new KernelDataScope("t", "u", "r", null, null, null, Map.of()),
        "agent", List.of(), List.of(), List.of("role"), null, Map.of());
    McpTemplateGuidanceSource source() {
        var agent = mock(SkillDefinition.class);
        when(agent.id()).thenReturn("agent"); when(agents.resolve("agent")).thenReturn(agent);
        when(policies.resolve(any(), eq(agent))).thenReturn(new AgentToolPolicyResolver.ToolPolicy(
            List.of("discover", "execute"), List.of(), List.of(), true, false, List.of(), List.of(), Map.of(), List.of()));
        when(registry.getToolMetadata("discover")).thenReturn(metadata(ToolWorkflowRole.TEMPLATE_DISCOVERY));
        when(registry.getToolMetadata("execute")).thenReturn(metadata(ToolWorkflowRole.TEMPLATE_EXECUTION));
        when(grants.allowedIdsForAgent(eq("MCP_TOOL"), eq("t"), eq("u"), eq(Set.of("role")), anySet(), eq("agent")))
            .thenReturn(Set.of("discover"));
        return new McpTemplateGuidanceSource(agents, policies, grants, registry, tools, new ObjectMapper());
    }
    ToolMetadata metadata(ToolWorkflowRole role) {
        return ToolMetadata.builder().agentCompatible(true).metadata(Map.of("workflowContract",
            ToolWorkflowContract.declaration(role, "templates", "template_query"))).build();
    }
    @Test void onlyDiscoveryIsInvokedAndExecutionSpecsAreNotReturned() {
        var source = source();
        when(tools.execute(any())).thenReturn(new ToolRuntimeExecution(ToolOutput.success(Map.of("success", true,
            "templates", List.of(Map.of("templateId", "positions", "title", "持仓", "assetType", "api_service",
                "description", "持仓查询", "parameterSchema", Map.of("type", "object"), "sql", "SECRET SQL", "headers", "SECRET TOKEN")),
            "hasMore", true)), null, null, "SUCCESS", Map.of()));
        var result = source.retrieve(context);
        assertThat(result.assets()).hasSize(1);
        assertThat(result.assets().get(0).technicalMetadata()).containsKey("parameterSchema").doesNotContainKeys("sql", "headers");
        assertThat(result.hasMore()).isTrue();
        verify(tools, times(1)).execute(argThat(call -> call.getToolName().equals("discover")
            && call.getAllowedTools().equals(List.of("discover"))
            && call.getAttributes().get("authorizationAgentId").equals("agent")
            && !call.getToolInput().getParameters().containsKey("templateId")));
    }
    @Test void deniedDiscoveryDoesNotFallBackToDataExecution() {
        var source = source();
        when(grants.allowedIdsForAgent(any(), any(), any(), anySet(), anySet(), any())).thenReturn(Set.of());
        assertThat(source.retrieve(context).assets()).isEmpty();
        verifyNoInteractions(tools);
    }
    @Test void businessRowsAreNotAcceptedAsTemplateMetadata() {
        var source = source();
        when(tools.execute(any())).thenReturn(new ToolRuntimeExecution(ToolOutput.success(Map.of("success", true,
            "rows", List.of(Map.of("balance", 100)))), null, null, "SUCCESS", Map.of()));
        var result = source.retrieve(context);
        assertThat(result.assets()).isEmpty();
        assertThat(result.limitations()).anyMatch(value -> value.contains("格式无法验证"));
    }
    @Test void retrievalFailureDoesNotRunAnotherClassOfTool() {
        var source = source();
        when(tools.execute(any())).thenThrow(new IllegalStateException("secret credentials"));
        var result = source.retrieve(context);
        assertThat(result.assets()).isEmpty();
        assertThat(result.limitations()).noneMatch(value -> value.contains("secret"));
        verify(tools, times(1)).execute(any());
    }
}
