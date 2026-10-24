package com.chatchat.agents.runtime.plan.template;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.*;
import java.util.*;

/** Compiles an unambiguous published supply chain; semantic candidate admission stays in Runtime. */
public final class PublishedDataAcquisitionPlanner {
    private final ToolRegistry registry;
    public PublishedDataAcquisitionPlanner(ToolRegistry registry) { this.registry=registry; }

    public InterpretationPlan compile(String query,List<String> authorized,List<String> required) {
        if(registry==null || query==null || query.isBlank() || authorized==null) return null;
        boolean configured=required!=null && !required.isEmpty();
        if(configured && !authorized.containsAll(required)) return null;
        List<String> selected=new ArrayList<>(new LinkedHashSet<>(configured?required:authorized));
        List<String> fixed=selected.stream().filter(n->TemplateWorkflowMetadata.fixedScope(registry.getToolMetadata(n)).isPresent()).toList();
        // The publication is the candidate authority for its asset family. A directory is not
        // a second provider for the same configured supply chain.
        selected.removeIf(n->role(n)==ToolWorkflowRole.TEMPLATE_DISCOVERY && !fixed.contains(n)
            && fixed.stream().anyMatch(f->Objects.equals(TemplateWorkflowMetadata.assetType(registry.getToolMetadata(f)),TemplateWorkflowMetadata.assetType(registry.getToolMetadata(n)))));
        List<String> discoveries=selected.stream().filter(n->role(n)==ToolWorkflowRole.TEMPLATE_DISCOVERY).toList();
        List<String> executions=selected.stream().filter(n->role(n)==ToolWorkflowRole.TEMPLATE_EXECUTION).toList();
        // Multiple authorities or required operations need semantic planning. Optional unrelated
        // capabilities do not disable a configured, unambiguous data provider.
        if(discoveries.size()!=1 || executions.size()!=1 || selected.size()!=2) return null;
        String discovery=discoveries.get(0),execution=executions.get(0);
        ToolMetadata metadata=registry.getToolMetadata(discovery);
        if((configured || authorized.size()>2) && TemplateWorkflowMetadata.fixedScope(metadata).isEmpty()) return null;
        if(metadata!=null && metadata.getParameters()!=null && metadata.getParameters().stream().anyMatch(p->p!=null && "stage".equals(p.getName()) && p.getEnumValues()!=null && Arrays.asList(p.getEnumValues()).contains("asset"))) return null;
        TemplateWorkflowTool provider=descriptor(discovery),executor=descriptor(execution);
        var plugin=TemplateWorkflowPluginRegistry.load().resolve(executor).orElse(null);
        if(plugin==null || !plugin.accepts(provider,executor)) return null;
        return new InterpretationPlan("1.0",new InterpretationPlan.Intent("mixed",query,"low"),
            new InterpretationPlan.Context(List.of("The published workflow declares one authorized template provider and executor."),List.of(),List.of(),List.of("Use only returned template identities.")),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1,"mcp_tool",discovery,Map.of("query",query,"limit",10),List.of(),null,null),
                new InterpretationPlan.Step(2,"mcp_tool",execution,Map.of(),List.of(1),null,null),
                new InterpretationPlan.Step(3,"final_answer","",Map.of("answer","Use the admitted tool evidence to answer the current question."),List.of(2),null,null)),
                List.of(new InterpretationPlan.EdgeContract(1,2,plugin.templateIdOutputPath(),"string",true)),
                List.of(new InterpretationPlan.DependencyContract(1,2,true,null,"Published template provider must precede execution.","stop"),new InterpretationPlan.DependencyContract(2,3,true,null,"Data execution must precede the answer.","stop")),
                List.of(new InterpretationPlan.Binding(1,plugin.templateIdOutputPath(),2,plugin.templateIdInputPath(),"jsonpath",true)),null),
            new InterpretationPlan.ExecutionPolicy(3,true,List.of(discovery,execution),List.of(),null,1,"partial_result"),
            new InterpretationPlan.Review(new InterpretationPlan.SelfCheck(1.0,0.0,true,List.of()),List.of()));
    }
    private ToolWorkflowRole role(String name) { return ToolWorkflowContract.resolveRole(name,registry.getToolMetadata(name)); }
    private TemplateWorkflowTool descriptor(String name) {
        ToolMetadata metadata=registry.getToolMetadata(name);
        return new TemplateWorkflowTool(name,role(name),ToolWorkflowContract.declaredProtocolFamily(metadata).orElse(null),TemplateWorkflowMetadata.assetType(metadata));
    }
}
