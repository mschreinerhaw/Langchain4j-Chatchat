package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.*;
import com.chatchat.common.mcp.capability.McpTemplateSelectionScope;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PublishedTemplateSupplyTest {
    static final String CHILD="published_inspection", BRIDGE="capability_bridge", EXECUTE="template_executor";
    static ToolRegistry registry(boolean nested) {
        var registry=mock(ToolRegistry.class);
        Map<String,Object> child=new LinkedHashMap<>();
        child.put("assetType","sql_datasource");
        child.put("templateSelectionScope",McpTemplateSelectionScope.fixedBinding("sql_datasource").toMetadata());
        child.put("workflowContract",ToolWorkflowContract.declaration(ToolWorkflowRole.TEMPLATE_DISCOVERY,"mcp.authorized-template-query.v1","filters","template"));
        Map<String,Object> bridge=Map.of("assetType","sql_datasource","executionTool",EXECUTE,"workflowContract",ToolWorkflowContract.declaration(ToolWorkflowRole.TEMPLATE_DISCOVERY,"mcp.sql-template.v1","filters"));
        Map<String,Object> policy=new LinkedHashMap<>();
        policy.put("logicalContextKeys",List.of("env"));
        policy.put("concreteTargetFields",List.of("datasourceId"));
        policy.put("rawExecutionFields",List.of("sql"));
        policy.put("targetKindFields",List.of("targetKind"));
        policy.put("filterProtocolFields",List.of("trace"));
        policy.put("logicalFilterFields",List.of("env"));
        policy.put("assetIdentityForbiddenParameterFields",List.of("parameters.datasourceId"));
        for(String key:List.of("filterFieldAliases","executionValidationFields","requiredParametersByTemplateSuffix","requiredExecutionContextFields")) policy.put(key,Map.of());
        policy.put("identityField","assetName");policy.put("semanticField","intent");
        policy.put("executionProtocolBindings",Map.of("mcp.sql-template.v1","SQL_EXECUTION"));
        policy.put("executionValidationFields",Map.of("SQL_EXECUTION",List.of("sql")));
        Map<String,Object> executor=Map.of("argumentBindingPolicy",policy,"workflowContract",ToolWorkflowContract.declaration(ToolWorkflowRole.TEMPLATE_EXECUTION,"mcp.sql-template.v1","executionContext"));
        var entries=Map.of(CHILD,child,BRIDGE,bridge,EXECUTE,executor);
        when(registry.getAllToolNames()).thenReturn(entries.keySet());
        entries.forEach((name,meta)->when(registry.getToolMetadata(name)).thenReturn(ToolMetadata.builder().id(name).metadata(nested?Map.of("mcpToolMeta",meta):meta).build()));
        entries.keySet().forEach(name->when(registry.hasTool(name)).thenReturn(true));
        when(registry.getWorkflowRole(anyString())).thenAnswer(i->ToolWorkflowContract.resolveRole(i.getArgument(0),registry.getToolMetadata(i.getArgument(0))));
        return registry;
    }
    static InterpretationPlan plan() {
        return new InterpretationPlan("1.0",new InterpretationPlan.Intent("mixed","Inspect authorized data","low"),new InterpretationPlan.Context(List.of(),List.of(),List.of(),List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1,"mcp_tool",CHILD,Map.of("query","health"),List.of(),null,null),
                new InterpretationPlan.Step(2,"mcp_tool",BRIDGE,Map.of("query","health"),List.of(),null,null),
                new InterpretationPlan.Step(3,"mcp_tool",EXECUTE,Map.of(),List.of(2),null,null),
                new InterpretationPlan.Step(4,"final_answer","",Map.of(),List.of(1,3),null,null)),
                List.of(new InterpretationPlan.EdgeContract(2,3,"$.templates[0].templateId","string",true)),
                List.of(new InterpretationPlan.DependencyContract(2,3,true,null,"template source","stop")),
                List.of(new InterpretationPlan.Binding(2,"$.templates[0].templateId",3,"$.templateId","jsonpath",true)),null),
            new InterpretationPlan.ExecutionPolicy(6,true,List.of(CHILD,BRIDGE,EXECUTE),List.of(),30000),new InterpretationPlan.Review(new InterpretationPlan.SelfCheck(1.0,0.0,true,List.of()),List.of()));
    }
    @Test void fixedAuthorityOwnsBindingsInBothMetadataEnvelopes() {
        for(boolean nested:List.of(false,true)) {
            var registry=registry(nested);
            var result=new InterpretationPlanOptimizer(registry).optimize(plan()).plan();
            assertThat(result.steps()).extracting(InterpretationPlan.Step::toolName).doesNotContain(BRIDGE);
            assertThat(result.plan().bindings()).isNotEmpty().allSatisfy(b->assertThat(b.from()).isEqualTo(1));
            assertThat(result.steps().stream().filter(s->EXECUTE.equals(s.toolName())).findFirst().orElseThrow().dependsOn()).contains(1).doesNotContain(2);
            var validation=new InterpretationPlanValidator().validate(result,registry,Set.of(CHILD,EXECUTE));
            assertThat(validation.errors()).isEmpty();
        }
    }
    @Test void configuredProviderDoesNotDependOnUnrelatedOptionalTools() {
        var registry=registry(true);
        var planner=new com.chatchat.agents.runtime.plan.template.PublishedDataAcquisitionPlanner(registry);
        var plan=planner.compile("Inspect authorized data",List.of(CHILD,BRIDGE,EXECUTE,"unrelated_tool"),List.of(CHILD,BRIDGE,EXECUTE));
        assertThat(plan).isNotNull();
        assertThat(plan.steps()).extracting(InterpretationPlan.Step::toolName).containsExactly(CHILD,EXECUTE,"");
        assertThat(plan.plan().bindings()).allSatisfy(b->assertThat(b.from()).isEqualTo(1));
        assertThat(planner.compile("Inspect",List.of(CHILD),List.of(CHILD,EXECUTE))).isNull();
        assertThat(planner.compile("Inspect",List.of(CHILD,BRIDGE,EXECUTE,"unrelated_tool"),List.of(CHILD,EXECUTE,"unrelated_tool"))).isNull();
    }
    @Test void sqlProviderCannotFeedAnotherExecutionProtocol() {
        var registry=registry(true);
        when(registry.getToolMetadata(EXECUTE)).thenReturn(ToolMetadata.builder().metadata(Map.of("workflowContract",ToolWorkflowContract.declaration(ToolWorkflowRole.TEMPLATE_EXECUTION,"mcp.ssh-template.v1","executionContext"))).build());
        assertThat(new com.chatchat.agents.runtime.plan.template.PublishedDataAcquisitionPlanner(registry).compile("Inspect",List.of(CHILD,EXECUTE),List.of(CHILD,EXECUTE))).isNull();
    }
    @Test void assetStageCannotSupplyTemplateIdsOrDisappearFromTheCompiledChain() {
        var registry=registry(true);
        var bridge=registry.getToolMetadata(BRIDGE);
        bridge.setParameters(List.of(ToolParameter.builder().name("stage").type("string").enumValues(new String[]{"asset","template"}).build()));
        var plan=new com.chatchat.agents.runtime.plan.template.PublishedDataAcquisitionPlanner(registry)
            .compile("Inspect authorized target",List.of(BRIDGE,CHILD,EXECUTE),List.of(BRIDGE,CHILD,EXECUTE));
        assertThat(plan).isNotNull();
        assertThat(plan.steps()).extracting(InterpretationPlan.Step::toolName).containsExactly(BRIDGE,CHILD,EXECUTE,"");
        assertThat(plan.steps().get(0).input()).containsEntry("stage","asset");
        assertThat(plan.steps().get(1).dependsOn()).containsExactly(1);
        assertThat(plan.steps().get(2).dependsOn()).containsExactly(2);
        assertThat(plan.plan().bindings()).allSatisfy(b->assertThat(b.from()).isEqualTo(2));
        var optimized=new InterpretationPlanOptimizer(registry).optimize(plan,List.of(
            Map.of("tool",BRIDGE,"dependsOnTools",List.of()),
            Map.of("tool",CHILD,"dependsOnTools",List.of(BRIDGE)),
            Map.of("tool",EXECUTE,"dependsOnTools",List.of(CHILD)))).plan();
        assertThat(optimized.steps()).extracting(InterpretationPlan.Step::toolName).containsExactly(BRIDGE,CHILD,EXECUTE,"");
        assertThat(optimized.plan().bindings()).allSatisfy(b->assertThat(b.from()).isEqualTo(2));
    }

    @Test void omittedStageInNestedPublisherIsExpandedBeforeFixedScopePruning() {
        var registry=registry(true);
        var bridge=registry.getToolMetadata(BRIDGE);
        Map<String,Object> attributes=new LinkedHashMap<>(bridge.getMetadata());
        Map<String,Object> publisher=new LinkedHashMap<>((Map<String,Object>)attributes.get("mcpToolMeta"));
        publisher.put("inputSchema",Map.of("type","object","properties",Map.of(
            "stage",Map.of("type","string","enum",List.of("asset","template")))));
        attributes.put("mcpToolMeta",publisher);bridge.setMetadata(attributes);
        var optimizer=new InterpretationPlanOptimizer(registry);
        var optimized=optimizer.optimize(plan()).plan();
        var asset=optimized.steps().stream().filter(s->BRIDGE.equals(s.toolName())).findFirst().orElseThrow();
        assertThat(asset.input()).containsEntry("stage","asset");
        var provider=optimized.steps().stream().filter(s->CHILD.equals(s.toolName())).findFirst().orElseThrow();
        assertThat(provider.dependsOn()).contains(asset.id());
        var execution=optimized.steps().stream().filter(s->EXECUTE.equals(s.toolName())).findFirst().orElseThrow();
        assertThat(execution.dependsOn()).contains(provider.id());
        assertThat(optimized.plan().bindings()).allSatisfy(b->assertThat(b.from()).isEqualTo(provider.id()));
        assertThat(new InterpretationPlanValidator().validate(optimized,registry,Set.of(BRIDGE,CHILD,EXECUTE)).errors()).isEmpty();
        assertThat(optimizer.optimize(optimized).plan().steps()).hasSameSizeAs(optimized.steps());
    }
}
