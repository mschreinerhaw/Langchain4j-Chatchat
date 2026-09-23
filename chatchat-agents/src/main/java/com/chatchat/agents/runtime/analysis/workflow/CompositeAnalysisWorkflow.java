package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.workflow.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class CompositeAnalysisWorkflow extends AbstractAnalysisWorkflow {
    private final ObjectProvider<AnalysisWorkflow> workflows;

    public CompositeAnalysisWorkflow(ObjectProvider<AnalysisWorkflow> workflows) {
        this.workflows = workflows;
    }

    @Override public AnalysisWorkflowType type() { return AnalysisWorkflowType.COMPOSITE; }
    @Override public String workflowId() { return "problem-analysis.composite"; }
    @Override public boolean supports(AnalysisContext context, AnalysisIntent intent) {
        return intent.requiredCapabilities().size() > 1;
    }

    @Override
    protected AnalysisScope resolveScope(AnalysisContext context) {
        return new AnalysisScope(context.kernelScope().tenantId(), context.kernelScope().userId(),
            context.roles(), context.documentIds(), Map.of("skillId", context.skillId()));
    }

    @Override
    protected WorkflowPlan plan(AnalysisContext context, AnalysisScope scope) {
        List<PlanStep> steps = new ArrayList<>();
        int index = 1;
        for (AnalysisCapability capability : context.intent().requiredCapabilities()) {
            steps.add(new PlanStep(String.valueOf(index++), capability.name() + "_ANALYSIS", capability, true, Map.of()));
        }
        steps.add(new PlanStep(String.valueOf(index++), "EVIDENCE_MERGE", null, true, Map.of()));
        steps.add(new PlanStep(String.valueOf(index), "CROSS_VALIDATE", null, true, Map.of()));
        return new StandardWorkflowPlan(UUID.randomUUID().toString(), type(), steps,
            context.intent().requiredCapabilities().stream()
                .map(capability -> new EvidenceRequirement(capability.name(), true, 1, "child workflow verified"))
                .toList());
    }

    @Override
    protected WorkflowExecutionResult executePlan(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan) {
        List<AnalysisEvidence> evidence = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        Map<String, Object> childResults = new LinkedHashMap<>();
        for (AnalysisCapability capability : context.intent().requiredCapabilities()) {
            AnalysisIntent childIntent = new AnalysisIntent(context.intent().intent(), context.intent().entities(),
                Set.of(capability), context.intent().freshness(), context.intent().evidenceRequired());
            AnalysisContext childContext = context.withIntent(childIntent);
            AnalysisWorkflow child = workflows.orderedStream()
                .filter(workflow -> workflow != this && workflow.type() != AnalysisWorkflowType.COMPOSITE)
                .filter(workflow -> workflow.supports(childContext, childIntent)).findFirst().orElse(null);
            if (child == null) {
                observations.add("No child workflow for " + capability);
                continue;
            }
            AnalysisExecutionOutcome result = child.execute(childContext, context.kernelScope());
            childResults.put(capability.name(), result);
            evidence.addAll(result.evidenceBundle().evidence());
            observations.addAll(result.evidenceBundle().limitations());
        }
        return new WorkflowExecutionResult(evidence, Map.of("childResults", Map.copyOf(childResults)), observations);
    }

    @Override
    protected VerificationResult verify(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan,
                                        WorkflowExecutionResult execution) {
        Set<AnalysisCapability> present = execution.evidence().stream()
            .map(AnalysisEvidence::capability).collect(java.util.stream.Collectors.toSet());
        List<String> missing = context.intent().requiredCapabilities().stream()
            .filter(capability -> !present.contains(capability))
            .map(capability -> "Missing verified evidence for " + capability).toList();
        return new VerificationResult(missing.isEmpty(), execution.evidence(), missing);
    }
}
