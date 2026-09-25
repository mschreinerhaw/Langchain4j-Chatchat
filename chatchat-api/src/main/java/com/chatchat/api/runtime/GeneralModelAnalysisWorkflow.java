package com.chatchat.api.runtime;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.agents.runtime.federation.RemoteAgentEvidenceProjector;
import com.chatchat.api.model.IntelligenceProviderRegistry;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.analysis.evidence.AgentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.execution.VerificationResult;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.*;
import com.chatchat.common.runtime.analysis.plan.*;
import com.chatchat.common.runtime.analysis.workflow.AbstractAnalysisWorkflow;
import com.chatchat.common.runtime.capability.CapabilityId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** General LLM adapter for the same evidence-first analysis pipeline as domain Agents. */
@Component
public class GeneralModelAnalysisWorkflow extends AbstractAnalysisWorkflow {
    private final ConfigurableChatModelFactory models;
    private final IntelligenceProviderRegistry providers;
    private final RemoteAgentEvidenceProjector projector;

    public GeneralModelAnalysisWorkflow(ConfigurableChatModelFactory models,
                                        IntelligenceProviderRegistry providers,
                                        RemoteAgentEvidenceProjector projector) {
        this.models = models;
        this.providers = providers;
        this.projector = projector;
    }

    @Override public AnalysisWorkflowType type() { return AnalysisWorkflowType.DOMAIN_INTELLIGENCE; }
    @Override public String workflowId() { return "problem-analysis.general-model"; }
    @Override public boolean supports(AnalysisContext context, AnalysisIntent intent) {
        return intent.requiredCapabilities().equals(Set.of(AnalysisCapability.DOMAIN_INTELLIGENCE))
            && context.attributes().get(AnalysisContext.GENERAL_MODEL_ATTRIBUTE) instanceof String;
    }

    @Override protected AnalysisScope resolveScope(AnalysisContext context) {
        return new AnalysisScope(context.kernelScope().tenantId(), context.kernelScope().userId(),
            context.roles(), context.documentIds(), Map.of("skillId", context.skillId()));
    }

    @Override protected WorkflowPlan plan(AnalysisContext context, AnalysisScope scope) {
        return new StandardWorkflowPlan(UUID.randomUUID().toString(), type(), List.of(
            new PlanStep("1", "PROJECT_AUTHORIZED_EVIDENCE", AnalysisCapability.DOMAIN_INTELLIGENCE, true, Map.of()),
            new PlanStep("2", "GENERAL_MODEL_INFERENCE", AnalysisCapability.DOMAIN_INTELLIGENCE, true, Map.of())),
            List.of(new EvidenceRequirement("MODEL_RESULT", true, 1, "retain source evidence identifiers")));
    }

    @Override protected WorkflowExecutionResult executePlan(AnalysisContext context, AnalysisScope scope,
                                                             WorkflowPlan plan) {
        String modelId = (String) context.attributes().get(AnalysisContext.GENERAL_MODEL_ATTRIBUTE);
        if (!providers.admits(scope.tenantId(), "llm:" + modelId, "general.analysis.v1"))
            return new WorkflowExecutionResult(List.of(), Map.of(), List.of("Model is no longer published"));
        if (!(context.attributes().get(AnalysisContext.EVIDENCE_BUNDLE_ATTRIBUTE) instanceof EvidenceBundle source)
            || source.evidence().isEmpty())
            return new WorkflowExecutionResult(List.of(), Map.of(), List.of("No authorized evidence to analyze"));
        try {
            AgentExecutionRequest request = new AgentExecutionRequest(null, UUID.randomUUID().toString(),
                CapabilityId.parse("general.analysis.v1"),
                new AgentExecutionRequest.TaskContract("GENERAL_ANALYSIS", context.query(), Map.of()),
                source, Set.of(), AgentExecutionRequest.Constraints.defaults(),
                AgentExecutionRequest.OutputContract.defaults(), context.kernelScope(),
                Map.of(AgentExecutionRequest.DOMAIN_PACKAGE_METADATA_KEY, "analysis_package.v1"));
            EvidenceBundle projected = projector.project(request).evidence();
            if (projected.evidence().isEmpty())
                return new WorkflowExecutionResult(List.of(), Map.of(), List.of("No transferable evidence"));
            StringBuilder prompt = new StringBuilder("Analyze only the supplied evidence. State uncertainty clearly. "
                + "Cite evidence IDs for factual claims. Do not assume access to any tools or hidden data.\n"
                + "User request: ").append(context.query()).append("\nEvidence:\n");
            projected.evidence().forEach(item -> prompt.append("[").append(item.evidenceId())
                .append("] ").append(item.content()).append("\n"));
            String answer = models.create(modelId).chat(prompt.toString());
            if (answer == null || answer.isBlank())
                return new WorkflowExecutionResult(List.of(), Map.of(), List.of("Model returned an empty result"));
            List<String> ids = projected.evidence().stream().map(item -> item.evidenceId()).toList();
            AgentAnalysisEvidence result = new AgentAnalysisEvidence(UUID.randomUUID().toString(),
                "llm:" + modelId, request.executionId(), ids, answer,
                Map.of("providerKind", "GENERAL_LLM", "evidenceGrounding", "model-stated"));
            return new WorkflowExecutionResult(List.of(result), Map.of("answer", answer), List.of());
        } catch (RuntimeException failure) {
            return new WorkflowExecutionResult(List.of(), Map.of(),
                List.of("General model analysis failed: " + failure.getClass().getSimpleName()));
        }
    }

    @Override protected VerificationResult verify(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan,
                                                  WorkflowExecutionResult execution) {
        boolean present = !execution.evidence().isEmpty();
        return new VerificationResult(present, present ? execution.evidence() : List.of(),
            execution.observations());
    }

    @Override protected AnalysisExecutionOutcome synthesize(AnalysisContext context, AnalysisScope scope,
                                                             WorkflowPlan plan, WorkflowExecutionResult execution,
                                                             VerificationResult verification, EvidenceBundle bundle) {
        return new AnalysisExecutionOutcome(null, type(), plan, verification, bundle,
            verification.accepted() ? String.valueOf(execution.outputs().get("answer")) : "",
            Map.of("workflowId", workflowId(), "grounding", "source-identifiers-provided-not-claim-verified"));
    }
}
