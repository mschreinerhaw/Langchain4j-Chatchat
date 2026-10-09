package com.chatchat.agents.orchestration.planning.generation;

import com.chatchat.agents.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPlannerPromptBudgetTest {

    @Test void compactWorkflowPreservesAuthorizedPublisherContracts() {
        ToolRegistry registry = mock(ToolRegistry.class);
        var metadata = com.chatchat.common.tool.ToolMetadata.builder().description("short label").metadata(Map.of(
            "inputSchema", Map.of("type", "object", "properties", Map.of("operation", Map.of("enum", List.of("read_dataset")))),
            "mcpToolMeta", Map.of("capabilityManifest", Map.of("datasets", List.of(Map.of("dataset", "registered_dataset"))))))
            .build();
        when(registry.getToolMetadata("authorized_tool")).thenReturn(metadata);
        when(registry.getToolMetadata("unauthorized_tool")).thenThrow(new AssertionError("Must not inspect unauthorized contracts"));
        var builder = new AgentPlannerPromptBuilder(registry, new ObjectMapper(), Clock.systemUTC());
        var prompt = builder.build("analyze", null, List.of("authorized_tool"), List.of(), List.of(), List.of(),
            List.of("authorized_tool"), true, false, null, null,
            Map.of("authoritativeWorkflowDag", List.of(Map.of("id", "read", "tool", "authorized_tool"))));
        assertThat(prompt).contains("registered_dataset", "read_dataset", "inputSchema").doesNotContain("unauthorized_tool");
    }

    @Test
    void authoritativeWorkflowUsesCompactModelFacingContract() {
        AgentPlannerPromptBuilder builder = new AgentPlannerPromptBuilder(
            mock(ToolRegistry.class), new ObjectMapper(), Clock.systemUTC());
        List<String> tools = List.of("data_query", "sql_execute", "web_search");
        Map<String, Object> attributes = Map.of(
            "authoritativeWorkflowDag", List.of(
                Map.of("id", "discover", "tool", "data_query"),
                Map.of("id", "execute", "tool", "sql_execute", "dependsOnTools", List.of("data_query")),
                Map.of("id", "search", "tool", "web_search")
            ),
            "agentRuntimeEnvironment", "DEV"
        );

        String prompt = builder.build(
            "分析最新数据", null, tools, List.of("x".repeat(40_000)),
            List.of(), List.of(), tools, true, false, null, null, attributes);

        assertThat(prompt)
            .contains("Compact InterpretationPlan shape", "complete value retained by Runtime")
            .contains("step discover: tool=data_query", "step execute: tool=sql_execute")
            .doesNotContain("InterpretationPlan JSON Schema:")
            .hasSizeLessThan(24_000);
    }
}
