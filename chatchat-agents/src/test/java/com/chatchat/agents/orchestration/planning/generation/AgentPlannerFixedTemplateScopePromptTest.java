package com.chatchat.agents.orchestration.planning.generation;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.mcp.capability.McpTemplateSelectionScope;
import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPlannerFixedTemplateScopePromptTest {

    @Test
    void exposesFixedBindingAsTheOnlyCurrentPlanSelectionAuthority() {
        String child = "mcp_service_business_template_query";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata(child)).thenReturn(ToolMetadata.builder()
            .description("Bound business templates")
            .metadata(Map.of("mcpToolMeta", Map.of(
                McpTemplateSelectionScope.METADATA_KEY,
                McpTemplateSelectionScope.fixedBinding("api_service").toMetadata()
            )))
            .build());
        AgentPlannerPromptBuilder builder = new AgentPlannerPromptBuilder(
            registry, new ObjectMapper(), Clock.systemUTC());

        String tools = builder.describeTools(List.of(child), Map.of());

        assertThat(tools)
            .contains("Authoritative template scope: FIXED_BINDING for assetType=api_service")
            .contains("only template-candidate source")
            .contains("Semantically review only the bounded, paged candidates")
            .contains("later Runtime-authorized evidence-recovery iteration");
    }
}
