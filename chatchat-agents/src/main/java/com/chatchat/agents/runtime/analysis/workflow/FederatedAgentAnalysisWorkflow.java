package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.agent.AgentExecutionMode;
import com.chatchat.common.runtime.agent.AgentCollaborationPlan;
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
import java.util.LinkedHashSet;
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
            && !context.attributes().containsKey(AnalysisContext.DOMAIN_PROVIDER_ATTRIBUTE)
            && !context.attributes().containsKey(AnalysisContext.GENERAL_MODEL_ATTRIBUTE)
            && (context.attributes().containsKey(AnalysisContext.AGENT_CAPABILITY_ATTRIBUTE)
                || context.attributes().containsKey(AgentCollaborationPlan.CONTEXT_ATTRIBUTE));
    }

    @Override protected AnalysisScope resolveScope(AnalysisContext context) {
        return new AnalysisScope(context.kernelScope().tenantId(), context.kernelScope().userId(), context.roles(),
            context.documentIds(), Map.of("skillId", context.skillId()));
    }

    @Override protected WorkflowPlan plan(AnalysisContext context, AnalysisScope scope) {
        Object collaboration = context.attributes().get(AgentCollaborationPlan.CONTEXT_ATTRIBUTE);
        if (collaboration != null) {
            AgentCollaborationPlan tasks = AgentCollaborationPlan.from(collaboration);
            List<PlanStep> steps = new ArrayList<>();
            steps.add(new PlanStep("evidence", "COMPILE_MINIMUM_EVIDENCE",
                AnalysisCapability.DOMAIN_INTELLIGENCE, true, Map.of()));
            tasks.tasks().forEach(task -> steps.add(new PlanStep(task.taskId(), "AGENT_COLLABORATION_TASK",
                AnalysisCapability.DOMAIN_INTELLIGENCE, true, Map.of("agentId", task.agentId(),
                    "capability", task.capability().toString(), "mode", task.mode().name(),
                    "dependsOn", task.dependsOn()))));
            steps.add(new PlanStep("merge", "MERGE_AGENT_EVIDENCE",
                AnalysisCapability.DOMAIN_INTELLIGENCE, true, Map.of()));
            return new StandardWorkflowPlan(UUID.randomUUID().toString(), type(), steps,
                List.of(new EvidenceRequirement("AGENT_GROUNDED_RESULT", true, 1,
                    "preserve each agent's evidence and limitations")));
        }
        return new StandardWorkflowPlan(UUID.randomUUID().toString(), type(), List.of(
            new PlanStep("1", "COMPILE_MINIMUM_EVIDENCE", AnalysisCapability.DOMAIN_INTELLIGENCE, true, Map.of()),
            new PlanStep("2", "SELECT_POLICY_ADMITTED_AGENT", AnalysisCapability.DOMAIN_INTELLIGENCE, true, Map.of()),
            new PlanStep("3", "EXECUTE_AND_VERIFY", AnalysisCapability.DOMAIN_INTELLIGENCE, true, Map.of())
        ), List.of(new EvidenceRequirement("AGENT_GROUNDED_RESULT", true, 1, "claims cite Runtime evidence")));
    }

    @Override protected WorkflowExecutionResult executePlan(AnalysisContext context, AnalysisScope scope,
                                                             WorkflowPlan plan) {
        Object collaboration = context.attributes().get(AgentCollaborationPlan.CONTEXT_ATTRIBUTE);
        if (collaboration != null) return executeCollaboration(context, plan,
            AgentCollaborationPlan.from(collaboration));
        CapabilityId capability = CapabilityId.parse(String.valueOf(
            context.attributes().get(AnalysisContext.AGENT_CAPABILITY_ATTRIBUTE)));
        EvidenceBundle input = inputEvidence(context);
        Map<String, Object> localMetadata = baseMetadata(context, plan);
        localMetadata.put(AgentExecutionRequest.MODE_METADATA_KEY,
            AgentExecutionMode.parse(context.attributes().get(AnalysisContext.AGENT_EXECUTION_MODE_ATTRIBUTE)).name());
        AgentExecutionRequest request = new AgentExecutionRequest(AgentExecutionRequest.SCHEMA_VERSION,
            UUID.randomUUID().toString(), capability,
            new AgentExecutionRequest.TaskContract(context.intent().intent(), context.query(),
                Map.of("entities", context.intent().entities(), "freshness", context.intent().freshness())),
            input, Set.of(), constraints(context), AgentExecutionRequest.OutputContract.defaults(),
            context.kernelScope(), localMetadata);
        AgentExecutionOutcome outcome = computeNodes.execute(ComputeNodeType.AGENT, request,
            AgentExecutionOutcome.class, context.kernelScope());
        List<AnalysisEvidence> evidence = outcomeEvidence(input, outcome, "");
        return new WorkflowExecutionResult(evidence, Map.of("agentOutcome", outcome), outcome.limitations());
    }

    private WorkflowExecutionResult executeCollaboration(AnalysisContext context, WorkflowPlan plan,
                                                         AgentCollaborationPlan collaboration) {
        EvidenceBundle original = inputEvidence(context);
        Map<String, List<AnalysisEvidence>> completed = new LinkedHashMap<>();
        Map<String, AgentExecutionOutcome> outcomes = new LinkedHashMap<>();
        Map<String, String> taskStates = new LinkedHashMap<>();
        List<String> findings = new ArrayList<>();
        List<AnalysisEvidence> merged = new ArrayList<>(original.evidence());
        Set<String> allIds = new LinkedHashSet<>();
        merged.forEach(item -> allIds.add(item.evidenceId()));
        for (AgentCollaborationPlan.Task task : collaboration.tasks()) {
            if (!completed.keySet().containsAll(task.dependsOn())) {
                taskStates.put(task.taskId(), "SKIPPED_DEPENDENCY");
                findings.add(task.taskId() + ": dependency did not produce verified claims");
                continue;
            }
            List<AnalysisEvidence> taskInput = new ArrayList<>(original.evidence());
            Set<String> ids = new LinkedHashSet<>();
            taskInput.forEach(item -> ids.add(item.evidenceId()));
            for (String dependency : task.dependsOn())
                completed.get(dependency).stream().filter(item -> ids.add(item.evidenceId())).forEach(taskInput::add);
            EvidenceBundle bundle = new EvidenceBundle(null, taskInput, original.limitations(), original.metadata());
            Map<String, Object> metadata = baseMetadata(context, plan);
            metadata.put(AgentExecutionRequest.MODE_METADATA_KEY, task.mode().name());
            metadata.put(AgentExecutionRequest.COLLABORATION_TASK_METADATA_KEY, task.taskId());
            if (!task.agentId().isBlank()) metadata.put(AgentExecutionRequest.TARGET_AGENT_METADATA_KEY, task.agentId());
            AgentExecutionRequest request = new AgentExecutionRequest(null, UUID.randomUUID().toString(),
                task.capability(), new AgentExecutionRequest.TaskContract(context.intent().intent(),
                    task.instruction(), Map.of("collaborationTaskId", task.taskId(),
                        "dependsOn", task.dependsOn())), bundle, Set.of(), constraints(context),
                AgentExecutionRequest.OutputContract.defaults(), context.kernelScope(), metadata);
            AgentExecutionOutcome outcome;
            try {
                outcome = computeNodes.execute(ComputeNodeType.AGENT, request,
                    AgentExecutionOutcome.class, context.kernelScope());
            } catch (RuntimeException failure) {
                taskStates.put(task.taskId(), "FAILED");
                findings.add(task.taskId() + ": agent execution failed (" + failure.getClass().getSimpleName() + ")");
                continue;
            }
            outcomes.put(task.taskId(), outcome);
            taskStates.put(task.taskId(), outcome.status().name());
            findings.addAll(outcome.limitations());
            if (outcome.status() != AgentExecutionOutcome.Status.COMPLETED || outcome.claims().isEmpty()) {
                findings.add(task.taskId() + ": " + outcome.errorCode() + " " + outcome.errorMessage());
                continue;
            }
            List<AnalysisEvidence> produced = outcomeEvidence(bundle, outcome, task.taskId() + ":");
            completed.put(task.taskId(), produced);
            produced.stream().filter(item -> allIds.add(item.evidenceId())).forEach(merged::add);
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("agentOutcomes", Map.copyOf(outcomes));
        outputs.put("taskStates", Map.copyOf(taskStates));
        outputs.put("completedTaskCount", completed.size());
        return new WorkflowExecutionResult(merged, outputs, findings);
    }

    @Override protected VerificationResult verify(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan,
                                                  WorkflowExecutionResult execution) {
        if (execution.outputs().containsKey("agentOutcomes")) {
            @SuppressWarnings("unchecked") Map<String, AgentExecutionOutcome> outcomes =
                (Map<String, AgentExecutionOutcome>) execution.outputs().get("agentOutcomes");
            boolean accepted = outcomes.values().stream().anyMatch(outcome ->
                outcome.status() == AgentExecutionOutcome.Status.COMPLETED && !outcome.claims().isEmpty());
            return new VerificationResult(accepted, accepted ? execution.evidence() : List.of(),
                execution.observations());
        }
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
        if (execution.outputs().containsKey("agentOutcomes")) {
            @SuppressWarnings("unchecked") Map<String, AgentExecutionOutcome> outcomes =
                (Map<String, AgentExecutionOutcome>) execution.outputs().get("agentOutcomes");
            String synthesis = verification.accepted() ? outcomes.values().stream()
                .filter(outcome -> outcome.status() == AgentExecutionOutcome.Status.COMPLETED)
                .flatMap(outcome -> outcome.claims().stream())
                .map(AgentExecutionOutcome.GroundedClaim::text).filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "\n" + right).orElse("") : "";
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("workflowId", workflowId());
            metadata.put("collaboration", true);
            metadata.put("taskStates", execution.outputs().get("taskStates"));
            metadata.put("completedTaskCount", execution.outputs().get("completedTaskCount"));
            metadata.put("judgeDecision", verification.accepted() ? "ACCEPT" : "SUPPLEMENT");
            return new AnalysisExecutionOutcome(null, type(), plan, verification, bundle, synthesis, metadata);
        }
        AgentExecutionOutcome outcome = (AgentExecutionOutcome) execution.outputs().get("agentOutcome");
        String synthesis = outcome == null || !verification.accepted() ? "" : outcome.claims().stream()
            .map(AgentExecutionOutcome.GroundedClaim::text).filter(value -> !value.isBlank())
            .reduce((left, right) -> left + "\n" + right).orElse("");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowId", workflowId());
        if (outcome != null) {
            metadata.put("agentId", outcome.providerAgentId());
            metadata.put("agentExecutionId", outcome.executionId());
            metadata.put("agentStatus", outcome.status().name());
            metadata.put("judgeDecision", verification.accepted() ? "ACCEPT"
                : switch (outcome.status()) {
                    case REPLAN_REQUIRED -> "REPLAN";
                    case INPUT_REQUIRED, SUPPLEMENT_EVIDENCE -> "SUPPLEMENT";
                    default -> "REJECT";
                });
        }
        return new AnalysisExecutionOutcome(AnalysisExecutionOutcome.SCHEMA_VERSION, type(), plan, verification,
            bundle, synthesis, metadata);
    }

    private EvidenceBundle inputEvidence(AnalysisContext context) {
        Object value = context.attributes().get(AnalysisContext.EVIDENCE_BUNDLE_ATTRIBUTE);
        return value instanceof EvidenceBundle bundle ? bundle : EvidenceBundle.empty("no upstream evidence");
    }

    private Map<String, Object> baseMetadata(AnalysisContext context, WorkflowPlan plan) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowId", workflowId());
        metadata.put("planId", plan.planId());
        metadata.put("documentIds", context.documentIds());
        metadata.put("roles", context.roles());
        metadata.put("documentTags", stringSet(context.attributes().get("documentTags")));
        metadata.put("knowledgeDomains", stringSet(context.attributes().get("knowledgeDomains")));
        if (!context.skillId().isBlank()) metadata.put("localSkillId", context.skillId());
        Object domainProvider = context.attributes().get(AnalysisContext.DOMAIN_PROVIDER_ATTRIBUTE);
        if (domainProvider instanceof String id && !id.isBlank())
            metadata.put(AgentExecutionRequest.TARGET_AGENT_METADATA_KEY, id);
        if (domainProvider instanceof String id && !id.isBlank())
            metadata.put(AgentExecutionRequest.DOMAIN_PACKAGE_METADATA_KEY, "analysis_package.v1");
        for (String key : List.of("runtime.analysis.dataTemplateId", "runtime.analysis.dataAssetName",
            "runtime.analysis.dataEnvironment", "runtime.analysis.dataParameters")) {
            Object value = context.attributes().get(key);
            if (value != null) metadata.put(key, value);
        }
        return metadata;
    }

    private List<AnalysisEvidence> outcomeEvidence(EvidenceBundle input, AgentExecutionOutcome outcome,
                                                    String prefix) {
        List<AnalysisEvidence> evidence = new ArrayList<>(input.evidence());
        Set<String> ids = new LinkedHashSet<>();
        evidence.forEach(item -> ids.add(item.evidenceId()));
        Object runtimeBundle = outcome.metadata().get("runtimeEvidenceBundle");
        if (runtimeBundle instanceof EvidenceBundle supplied)
            supplied.evidence().stream().filter(item -> ids.add(item.evidenceId())).forEach(evidence::add);
        outcome.claims().forEach(claim -> {
            String id = prefix + (claim.claimId().isBlank() ? UUID.randomUUID() : claim.claimId());
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("confidence", claim.confidence());
            attributes.put("kind", "claim");
            attributes.put("remoteProjection", Map.of("text", claim.text(), "sourceAgentId", outcome.providerAgentId()));
            if (ids.add(id)) evidence.add(new AgentAnalysisEvidence(id, outcome.providerAgentId(),
                outcome.executionId(), claim.evidenceIds(), claim.text(), attributes));
        });
        outcome.artifacts().forEach(artifact -> {
            String id = prefix + (artifact.artifactId().isBlank() ? UUID.randomUUID() : artifact.artifactId());
            if (ids.add(id)) evidence.add(new AgentAnalysisEvidence(id, outcome.providerAgentId(),
                outcome.executionId(), List.of(), artifact.content(),
                Map.of("mediaType", artifact.mediaType(), "kind", "artifact")));
        });
        return evidence;
    }

    private AgentExecutionRequest.Constraints constraints(AnalysisContext context) {
        Set<String> domains = stringSet(context.attributes().get("allowedDataDomains"));
        long timeout = longValue(context.attributes().get("agentTimeoutMs"), 60_000);
        int attempts = (int) Math.max(1, Math.min(3, longValue(context.attributes().get("agentMaxAttempts"), 2)));
        return new AgentExecutionRequest.Constraints(timeout, attempts, true, true, domains);
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
