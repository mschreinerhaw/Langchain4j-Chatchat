package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.analysis.evidence.AgentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.AnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.execution.VerificationResult;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType;
import com.chatchat.common.runtime.analysis.plan.EvidenceRequirement;
import com.chatchat.common.runtime.analysis.plan.PlanStep;
import com.chatchat.common.runtime.analysis.plan.StandardWorkflowPlan;
import com.chatchat.common.runtime.analysis.plan.WorkflowPlan;
import com.chatchat.common.runtime.analysis.workflow.AbstractAnalysisWorkflow;
import com.chatchat.common.runtime.capability.CapabilityId;
import com.chatchat.common.runtime.capability.ComputeNodeType;
import com.chatchat.agents.runtime.federation.ComputeNodeRouter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Delegates evidence-bound domain reasoning while Runtime retains data, policy, and verification ownership. */
@Component
public class FederatedAgentAnalysisWorkflow extends AbstractAnalysisWorkflow {
    private final ComputeNodeRouter computeNodes;

    public FederatedAgentAnalysisWorkflow(ComputeNodeRouter computeNodes) {
        this.computeNodes = computeNodes;
    }

    @Override public AnalysisWorkflowType type() { return AnalysisWorkflowType.FEDERATED_AGENT; }
    @Override public String workflowId() { return "problem-analysis.federated-agent"; }
    @Override public boolean supports(AnalysisContext context, AnalysisIntent intent) {
        return intent.requiredCapabilities().equals(Set.of(AnalysisCapability.DOMAIN_INTELLIGENCE))
            && context.attributes().containsKey(AnalysisContext.AGENT_CAPABILITY_ATTRIBUTE);
    }

    @Override protected AnalysisScope resolveScope(AnalysisContext context) {
        return new AnalysisScope(context.kernelScope().tenantId(), context.kernelScope().userId(), context.roles(),
            context.documentIds(), Map.of("skillId", context.skillId()));
    }

    @Override protected WorkflowPlan plan(AnalysisContext context, AnalysisScope scope) {
        return new StandardWorkflowPlan(UUID.randomUUID().toString(), type(), List.of(
            new PlanStep("1", "COMPILE_MINIMUM_EVIDENCE", AnalysisCapability.DOMAIN_INTELLIGENCE, true, Map.of()),
            new PlanStep("2", "SELECT_POLICY_ADMITTED_AGENT", AnalysisCapability.DOMAIN_INTELLIGENCE, true, Map.of()),
            new PlanStep("3", "EXECUTE_AND_VERIFY", AnalysisCapability.DOMAIN_INTELLIGENCE, true, Map.of())
        ), List.of(new EvidenceRequirement("AGENT_GROUNDED_RESULT", true, 1, "claims cite Runtime evidence")));
    }

    @Override protected WorkflowExecutionResult executePlan(AnalysisContext context, AnalysisScope scope,
                                                             WorkflowPlan plan) {
        CapabilityId capability = CapabilityId.parse(String.valueOf(
            context.attributes().get(AnalysisContext.AGENT_CAPABILITY_ATTRIBUTE)));
        EvidenceBundle input = inputEvidence(context);
        AgentExecutionRequest request = new AgentExecutionRequest(AgentExecutionRequest.SCHEMA_VERSION,
            UUID.randomUUID().toString(), capability,
            new AgentExecutionRequest.TaskContract(context.intent().intent(), context.query(),
                Map.of("entities", context.intent().entities(), "freshness", context.intent().freshness())),
            input, Set.of(), constraints(context), AgentExecutionRequest.OutputContract.defaults(),
            context.kernelScope(), Map.of("workflowId", workflowId(), "planId", plan.planId()));
        AgentExecutionOutcome outcome = computeNodes.execute(ComputeNodeType.AGENT, request,
            AgentExecutionOutcome.class, context.kernelScope());
        List<AnalysisEvidence> evidence = new ArrayList<>(input.evidence());
        outcome.claims().forEach(claim -> evidence.add(new AgentAnalysisEvidence(
            claim.claimId().isBlank() ? UUID.randomUUID().toString() : claim.claimId(), outcome.providerAgentId(),
            outcome.executionId(), claim.evidenceIds(), claim.text(), Map.of("confidence", claim.confidence(),
                "kind", "claim"))));
        outcome.artifacts().forEach(artifact -> evidence.add(new AgentAnalysisEvidence(
            artifact.artifactId().isBlank() ? UUID.randomUUID().toString() : artifact.artifactId(),
            outcome.providerAgentId(), outcome.executionId(), List.of(), artifact.content(),
            Map.of("mediaType", artifact.mediaType(), "kind", "artifact"))));
        return new WorkflowExecutionResult(evidence, Map.of("agentOutcome", outcome), outcome.limitations());
    }

    @Override protected VerificationResult verify(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan,
                                                  WorkflowExecutionResult execution) {
        AgentExecutionOutcome outcome = (AgentExecutionOutcome) execution.outputs().get("agentOutcome");
        // An upstream evidence bundle or an unstructured artifact is not a verified agent conclusion.
        boolean accepted = outcome != null && outcome.status() == AgentExecutionOutcome.Status.COMPLETED
            && !outcome.claims().isEmpty();
        List<String> findings = new ArrayList<>(execution.observations());
        if (!accepted && outcome != null) findings.add(outcome.errorCode() + ": " + outcome.errorMessage());
        return new VerificationResult(accepted, accepted ? execution.evidence() : List.of(), findings);
    }

    @Override protected AnalysisExecutionOutcome synthesize(AnalysisContext context, AnalysisScope scope,
                                                             WorkflowPlan plan, WorkflowExecutionResult execution,
                                                             VerificationResult verification, EvidenceBundle bundle) {
        AgentExecutionOutcome outcome = (AgentExecutionOutcome) execution.outputs().get("agentOutcome");
        String synthesis = outcome == null ? "" : outcome.claims().stream()
            .map(AgentExecutionOutcome.GroundedClaim::text).filter(value -> !value.isBlank())
            .reduce((left, right) -> left + "\n" + right).orElse("");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowId", workflowId());
        if (outcome != null) {
            metadata.put("agentId", outcome.providerAgentId());
            metadata.put("agentExecutionId", outcome.executionId());
            metadata.put("agentStatus", outcome.status().name());
        }
        return new AnalysisExecutionOutcome(AnalysisExecutionOutcome.SCHEMA_VERSION, type(), plan, verification,
            bundle, synthesis, metadata);
    }

    private EvidenceBundle inputEvidence(AnalysisContext context) {
        Object value = context.attributes().get(AnalysisContext.EVIDENCE_BUNDLE_ATTRIBUTE);
        return value instanceof EvidenceBundle bundle ? bundle : EvidenceBundle.empty("no upstream evidence");
    }

    private AgentExecutionRequest.Constraints constraints(AnalysisContext context) {
        Set<String> domains = stringSet(context.attributes().get("allowedDataDomains"));
        long timeout = longValue(context.attributes().get("agentTimeoutMs"), 60_000);
        return new AgentExecutionRequest.Constraints(timeout, 1, true, true, domains);
    }

    private Set<String> stringSet(Object value) {
        if (!(value instanceof Iterable<?> values)) return Set.of();
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        values.forEach(item -> { if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item)); });
        return Set.copyOf(result);
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
