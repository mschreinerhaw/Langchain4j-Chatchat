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
            && !TemplateWorkflowMetadata.assetStages(registry.getToolMetadata(n))
            && fixed.stream().anyMatch(f->Objects.equals(TemplateWorkflowMetadata.assetType(registry.getToolMetadata(f)),TemplateWorkflowMetadata.assetType(registry.getToolMetadata(n)))));
        List<String> assets=selected.stream().filter(n->TemplateWorkflowMetadata.assetStages(registry.getToolMetadata(n)) && !fixed.contains(n))
            .filter(n->fixed.stream().anyMatch(f->Objects.equals(TemplateWorkflowMetadata.assetType(registry.getToolMetadata(f)),TemplateWorkflowMetadata.assetType(registry.getToolMetadata(n))))).toList();
        List<String> discoveries=selected.stream().filter(n->role(n)==ToolWorkflowRole.TEMPLATE_DISCOVERY && !assets.contains(n)).toList();
        List<String> executions=selected.stream().filter(n->role(n)==ToolWorkflowRole.TEMPLATE_EXECUTION).toList();
        // Multiple authorities or required operations need semantic planning. Optional unrelated
        // capabilities do not disable a configured, unambiguous data provider.
        if(discoveries.size()!=1 || executions.size()!=1 || assets.size()>1 || selected.size()!=2+assets.size()) return null;
        String discovery=discoveries.get(0),execution=executions.get(0);
        ToolMetadata metadata=registry.getToolMetadata(discovery);
        if((configured || authorized.size()>2) && TemplateWorkflowMetadata.fixedScope(metadata).isEmpty()) return null;
        if(metadata!=null && metadata.getParameters()!=null && metadata.getParameters().stream().anyMatch(p->p!=null && "stage".equals(p.getName()) && p.getEnumValues()!=null && Arrays.asList(p.getEnumValues()).contains("asset"))) return null;
        TemplateWorkflowTool provider=descriptor(discovery),executor=descriptor(execution);
        var plugin=TemplateWorkflowPluginRegistry.load().resolve(executor).orElse(null);
        if(plugin==null || !plugin.accepts(provider,executor)) return null;
        int templateStep=assets.isEmpty()?1:2, executionStep=templateStep+1, answerStep=executionStep+1;
        List<InterpretationPlan.Step> steps=new ArrayList<>();
        List<InterpretationPlan.DependencyContract> dependencies=new ArrayList<>();
        List<String> allowed=new ArrayList<>();
        if(!assets.isEmpty()) {
            steps.add(new InterpretationPlan.Step(1,"mcp_tool",assets.get(0),
                Map.of("stage","asset","query",query,"limit",10),List.of(),null,null));
            dependencies.add(new InterpretationPlan.DependencyContract(1,templateStep,true,null,
                "Canonical asset selection precedes fixed template selection.","stop"));
            allowed.add(assets.get(0));
        }
        steps.add(new InterpretationPlan.Step(templateStep,"mcp_tool",discovery,Map.of("query",query,"limit",10),
            assets.isEmpty()?List.of():List.of(1),null,null));
        steps.add(new InterpretationPlan.Step(executionStep,"mcp_tool",execution,Map.of(),List.of(templateStep),null,null));
        steps.add(new InterpretationPlan.Step(answerStep,"final_answer","",Map.of("answer","Use the admitted tool evidence to answer the current question."),List.of(executionStep),null,null));
        dependencies.add(new InterpretationPlan.DependencyContract(templateStep,executionStep,true,null,
            "Published template provider must precede execution.","stop"));
        dependencies.add(new InterpretationPlan.DependencyContract(executionStep,answerStep,true,null,
            "Data execution must precede the answer.","stop"));
        allowed.add(discovery);allowed.add(execution);
        return new InterpretationPlan("1.0",new InterpretationPlan.Intent("mixed",query,"low"),
            new InterpretationPlan.Context(List.of("The published workflow separates asset selection, fixed template selection and execution."),
                List.of(),List.of(),List.of("Use only canonical asset context and returned template identities.")),
            new InterpretationPlan.Plan(steps,
                List.of(new InterpretationPlan.EdgeContract(templateStep,executionStep,plugin.templateIdOutputPath(),"string",true)),
                dependencies,List.of(new InterpretationPlan.Binding(templateStep,plugin.templateIdOutputPath(),executionStep,
                    plugin.templateIdInputPath(),"jsonpath",true)),null),
            new InterpretationPlan.ExecutionPolicy(steps.size(),true,allowed,List.of(),null,1,"partial_result"),
            new InterpretationPlan.Review(new InterpretationPlan.SelfCheck(1.0,0.0,true,List.of()),List.of()));
    }
    private ToolWorkflowRole role(String name) { return ToolWorkflowContract.resolveRole(name,registry.getToolMetadata(name)); }
    private TemplateWorkflowTool descriptor(String name) {
        ToolMetadata metadata=registry.getToolMetadata(name);
        return new TemplateWorkflowTool(name,role(name),ToolWorkflowContract.declaredProtocolFamily(metadata).orElse(null),TemplateWorkflowMetadata.assetType(metadata));
    }
}
