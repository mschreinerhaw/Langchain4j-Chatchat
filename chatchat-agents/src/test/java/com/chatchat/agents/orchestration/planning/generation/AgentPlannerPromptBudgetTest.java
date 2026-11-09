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
    @Test void acquisitionContractPreservesTheMaintainedSystemPolicyInsteadOfClippingItBehindCodingGuidance() {
        var builder=new AgentPlannerPromptBuilder(mock(ToolRegistry.class),new ObjectMapper(),Clock.systemUTC());
        String system="Maintained original acquisition policy. ".repeat(40);
        var attributes=Map.<String,Object>of("analysisCodingAvailable",true,"authoritativeWorkflowDag",
            List.of(Map.of("id","read","tool","authorized_read")));
        String prompt=builder.build("Exact original customer request",system,List.of("authorized_read"),List.of(),List.of(),List.of(),
            List.of("authorized_read"),true,false,null,null,attributes);
        assertThat(prompt).contains(system,"User query:\nExact original customer request","step read: tool=authorized_read",
            "Runtime verifies delivery","without indentation");
        assertThat(prompt).doesNotContain("\"optional_tool_decisions\":[{\"tool_name\":\"exact-name\"");
    }
    @Test void compactPlannerSharesRepeatedSchemasLosslesslyAcrossAuthorizedTools() throws Exception {
        var registry=mock(ToolRegistry.class);var mapper=new ObjectMapper();
        String declaration="original-published-semantics-"+"x".repeat(4000);
        var schema=Map.of("type","object","properties",Map.of("customerNo",Map.of("type","string","description",declaration)),
            "required",List.of("customerNo"),"additionalProperties",false);
        var metadata=com.chatchat.common.tool.ToolMetadata.builder().description("Authorized lookup")
            .metadata(Map.of("inputSchema",schema)).build();
        for(String name:List.of("first_read","second_read"))when(registry.getToolMetadata(name)).thenReturn(metadata);
        var prompt=new AgentPlannerPromptBuilder(registry,mapper,Clock.systemUTC()).build("070200046604",null,
            List.of("first_read","second_read"),List.of(),List.of(),List.of(),List.of("first_read","second_read"),
            true,false,null,null,Map.of("authoritativeWorkflowDag",List.of(Map.of("id","first","tool","first_read"),Map.of("id","second","tool","second_read"))));
        assertThat(prompt).containsOnlyOnce(declaration).contains("User query:\n070200046604");
        String json=prompt.substring(prompt.indexOf("Shared values preserve complete original schemas and capability declarations.\n")
            +"Shared values preserve complete original schemas and capability declarations.\n".length()).split("\\R",2)[0];
        var projected=mapper.readTree(json);var definitions=projected.path("sharedValues");
        assertThat(definitions.size()).isPositive();
        for(String name:List.of("first_read","second_read")) {
            var encoded=projected.path("context").path(name).path("inputSchema");
            var restored=definitions.path(encoded.path("$runtimeReviewRef").asText());
            assertThat(restored).isEqualTo(mapper.valueToTree(schema));
        }
    }
    @Test void batchPublisherSchemaIsRenderedOnceWithoutLosingItsInvocationContract() {
        var registry=mock(ToolRegistry.class);
        String declaration="publisher-business-field-"+"x".repeat(4000);
        var metadata=com.chatchat.common.tool.ToolMetadata.builder().description("Execute authorized templates")
            .metadata(Map.of("batch_execution",true,"template_execution",true,
                "capabilityManifest",Map.of("operations",List.of("execute_template")),
                "inputSchema",Map.of("type","object","properties",Map.of("customerNo",Map.of("type","string","description",declaration)),"required",List.of("customerNo"))))
            .build();
        when(registry.getToolMetadata("authorized_execute")).thenReturn(metadata);
        String prompt=new AgentPlannerPromptBuilder(registry,new ObjectMapper(),Clock.systemUTC())
            .describeTools(List.of("authorized_execute"),Map.of());
        assertThat(prompt).containsOnlyOnce(declaration).contains("execute_template","Formal runtime inputSchema","customerNo","required");
    }

    @Test void codingIsAdvertisedAsLaterAnalysisWithoutAddingAcquisitionAuthority() {
        var builder=new AgentPlannerPromptBuilder(mock(ToolRegistry.class),new ObjectMapper(),Clock.systemUTC());
        var attributes=Map.<String,Object>of("analysisCodingAvailable",true,"authoritativeWorkflowDag",
            List.of(Map.of("id","read","tool","authorized_read")));
        String prompt=builder.build("Compute after acquisition","Role context ".repeat(1000),List.of("authorized_read"),List.of(),List.of(),List.of(),
            List.of("authorized_read"),true,false,null,null,attributes);
        assertThat(prompt).contains("Cline owns data exploration", "unavailable as an MCP acquisition tool",
            "source data/capability", "step read: tool=authorized_read", "filters.assetName",
            "An intent string alone does not constrain asset identity")
            .doesNotContain("later analysis loop decides");
        String disabled=builder.build("Compute",null,List.of("authorized_read"),List.of(),List.of(),List.of(),
            List.of(),false,false,null,null,Map.of());
        assertThat(disabled).doesNotContain("Runtime analysis capability:");
    }

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
