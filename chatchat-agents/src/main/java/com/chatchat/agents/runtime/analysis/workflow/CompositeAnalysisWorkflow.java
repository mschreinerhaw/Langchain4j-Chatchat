package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.evidence.AnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.execution.VerificationResult;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.model.AnalysisSkillSelection;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType;
import com.chatchat.common.runtime.analysis.plan.EvidenceRequirement;
import com.chatchat.common.runtime.analysis.plan.PlanStep;
import com.chatchat.common.runtime.analysis.plan.StandardWorkflowPlan;
import com.chatchat.common.runtime.analysis.plan.WorkflowPlan;
import com.chatchat.common.runtime.analysis.spi.AnalysisWorkflow;
import com.chatchat.common.runtime.analysis.workflow.AbstractAnalysisWorkflow;

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
        for (AnalysisCapability capability : orderedCapabilities(context.intent())) {
            steps.add(new PlanStep(String.valueOf(index++), capability.name() + "_ANALYSIS", capability, true, Map.of()));
        }
        steps.add(new PlanStep(String.valueOf(index++), "EVIDENCE_MERGE", null, true, Map.of()));
        steps.add(new PlanStep(String.valueOf(index), "CROSS_VALIDATE", null, true, Map.of()));
        return new StandardWorkflowPlan(UUID.randomUUID().toString(), type(), steps,
            orderedCapabilities(context.intent()).stream()
                .map(capability -> new EvidenceRequirement(capability.name(), true, 1, "child workflow verified"))
                .toList());
    }

    @Override
    protected WorkflowExecutionResult executePlan(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan) {
        List<AnalysisEvidence> evidence = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        Map<String, Object> childResults = new LinkedHashMap<>();
        boolean upstreamRejected = false;
        for (AnalysisCapability capability : orderedCapabilities(context.intent())) {
            if (capability == AnalysisCapability.DOMAIN_INTELLIGENCE && upstreamRejected) {
                observations.add("Domain Agent skipped because upstream evidence was not verified");
                continue;
            }
            AnalysisIntent childIntent = new AnalysisIntent(context.intent().intent(), context.intent().entities(),
                Set.of(capability), context.intent().freshness(), context.intent().evidenceRequired());
            EvidenceBundle accumulated = new EvidenceBundle(EvidenceBundle.SCHEMA_VERSION, evidence,
                observations, Map.of("source", "composite-analysis"));
            AnalysisContext childContext = context.withIntent(childIntent)
                .withAttribute(AnalysisContext.EVIDENCE_BUNDLE_ATTRIBUTE, accumulated);
            if (capability == AnalysisCapability.DOCUMENT_SEARCH
                && context.attributes().get(AnalysisContext.SKILL_SELECTIONS_ATTRIBUTE) instanceof List<?> rawSelections) {
                int acceptedSelections = 0;
                for (Object raw : rawSelections) {
                    if (!(raw instanceof AnalysisSkillSelection selection) || selection.documentIds().isEmpty()) continue;
                    AnalysisContext selectedContext = childContext.withSkillSelection(selection);
                    AnalysisWorkflow selected = findChild(selectedContext, childIntent);
                    if (selected == null) {
                        observations.add("No document workflow for Skill " + selection.skillId());
                        upstreamRejected = true;
                        continue;
                    }
                    AnalysisExecutionOutcome result = selected.execute(selectedContext, context.kernelScope());
                    if (result.verification() != null && result.verification().accepted()) {
                        result.evidenceBundle().evidence().stream()
                            .filter(item -> item.capability() == capability)
                            .forEach(item -> evidence.add(skillEvidence(item, selection.skillId())));
                        acceptedSelections++;
                    } else {
                        observations.add("Document workflow rejected Skill " + selection.skillId());
                        upstreamRejected = true;
                    }
                    if (result.verification() != null) observations.addAll(result.verification().findings());
                }
                if (acceptedSelections == 0) upstreamRejected = true;
                continue;
            }
            AnalysisWorkflow child = findChild(childContext, childIntent);
            if (child == null) {
                observations.add("No child workflow for " + capability);
                upstreamRejected = true;
                continue;
            }
            AnalysisExecutionOutcome result = child.execute(childContext, context.kernelScope());
            childResults.put(capability.name(), result);
            if (result.verification() != null && result.verification().accepted()) {
                result.evidenceBundle().evidence().stream()
                    .filter(item -> item.capability() == capability)
                    .forEach(evidence::add);
            } else {
                observations.add("Child workflow rejected " + capability);
                upstreamRejected = true;
            }
            if (result.verification() != null) observations.addAll(result.verification().findings());
        }
        return new WorkflowExecutionResult(evidence, Map.of("childResults", Map.copyOf(childResults)), observations);
    }

    private AnalysisWorkflow findChild(AnalysisContext context, AnalysisIntent intent) {
        return workflows.orderedStream()
            .filter(workflow -> workflow != this && workflow.type() != AnalysisWorkflowType.COMPOSITE)
            .filter(workflow -> workflow.supports(context, intent)).findFirst().orElse(null);
    }

    private AnalysisEvidence skillEvidence(AnalysisEvidence evidence, String skillId) {
        if (!(evidence instanceof DocumentAnalysisEvidence document)) return evidence;
        Map<String, Object> attributes = new LinkedHashMap<>(document.attributes());
        attributes.put("skillId", skillId);
        return new DocumentAnalysisEvidence(skillId + ":" + document.evidenceId(),
            document.documentId(), document.chunkId(), document.documentName(), document.section(),
            document.citation(), document.content(), document.score(), attributes);
    }

    @Override
    protected VerificationResult verify(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan,
                                        WorkflowExecutionResult execution) {
        Set<AnalysisCapability> present = execution.evidence().stream()
            .map(AnalysisEvidence::capability).collect(java.util.stream.Collectors.toSet());
        List<String> missing = orderedCapabilities(context.intent()).stream()
            .filter(capability -> !present.contains(capability))
            .map(capability -> "Missing verified evidence for " + capability).toList();
        List<String> findings = new ArrayList<>(execution.observations());
        findings.addAll(missing);
        return new VerificationResult(missing.isEmpty(), missing.isEmpty() ? execution.evidence() : List.of(), findings);
    }

    @Override
    protected AnalysisExecutionOutcome synthesize(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan,
                                                  WorkflowExecutionResult execution, VerificationResult verification,
                                                  EvidenceBundle bundle) {
        String synthesis = "";
        if (verification.accepted()) {
            @SuppressWarnings("unchecked") Map<String, AnalysisExecutionOutcome> children =
                (Map<String, AnalysisExecutionOutcome>) execution.outputs().get("childResults");
            if (children != null) synthesis = orderedCapabilities(context.intent()).stream()
                .map(capability -> children.get(capability.name()))
                .filter(result -> result != null && result.verification() != null
                    && result.verification().accepted() && !result.synthesis().isBlank())
                .map(AnalysisExecutionOutcome::synthesis)
                .reduce((left, right) -> left + "\n" + right).orElse("");
        }
        return new AnalysisExecutionOutcome(null, type(), plan, verification, bundle, synthesis,
            Map.of("workflowId", workflowId(), "childCount", context.intent().requiredCapabilities().size()));
    }

    private List<AnalysisCapability> orderedCapabilities(AnalysisIntent intent) {
        List<AnalysisCapability> order = List.of(AnalysisCapability.STRUCTURED_DATA,
            AnalysisCapability.DOCUMENT_SEARCH, AnalysisCapability.TOOL_CALL,
            AnalysisCapability.EXTERNAL_RESEARCH, AnalysisCapability.COMPUTATION,
            AnalysisCapability.DOMAIN_INTELLIGENCE);
        return order.stream().filter(intent.requiredCapabilities()::contains).toList();
    }
}
