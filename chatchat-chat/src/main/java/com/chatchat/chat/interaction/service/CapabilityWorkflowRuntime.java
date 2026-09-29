package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.common.runtime.capability.*;
import java.util.*;
import java.util.function.Supplier;

/** Compatibility adapters execute existing governed pipelines once, under a common capability plan. */
public final class CapabilityWorkflowRuntime {
    public enum ProviderKind { SKILL, MCP_WORKFLOW, DOCUMENT_RETRIEVAL, NATIVE_RUNTIME, EXTERNAL_AGENT }
    public record Provider(String id, ProviderKind kind, Set<String> capabilities, Supplier<InteractionResponse> execute) {
        public Provider { capabilities = Set.copyOf(capabilities); }
    }
    public InteractionResponse execute(CapabilityWorkflowPlan plan, List<Provider> providers) {
        // A legacy pipeline is an atomic composite provider. Do not execute it once per capability.
        Provider provider = providers.stream().filter(item -> item.capabilities().containsAll(plan.requiredCapabilities()))
            .findFirst().orElse(null);
        InteractionResponse response;
        if (provider == null) {
            Set<String> offered = new HashSet<>(); providers.forEach(item -> offered.addAll(item.capabilities()));
            List<String> missing = plan.requiredCapabilities().stream().filter(id -> !offered.contains(id)).sorted().toList();
            var outcome = new WorkflowOutcome(WorkflowOutcome.Type.NO_EXECUTABLE_PLAN,
                missing.isEmpty() ? "NO_COMPATIBLE_COMPOSITE_PROVIDER" : "MISSING_REQUIRED_CAPABILITY", missing, List.of(), false);
            response = InteractionResponse.builder().answer("当前没有能满足必需能力的执行路径：" + String.join("、", missing))
                .metadata(Map.of(WorkflowOutcome.METADATA_KEY, outcome)).build();
        } else {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            response = Objects.requireNonNull(provider.execute().get(), "Capability provider returned no result");
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        }
        Map<String, Object> metadata = new LinkedHashMap<>(response.getMetadata() == null ? Map.of() : response.getMetadata());
        metadata.put(WorkflowOutcome.METADATA_KEY, resolveOutcome(plan.family(), response, metadata));
        metadata.put("workflowFamily", plan.family().name());
        metadata.put("capabilityPlan", plan);
        metadata.put("runtimeLifecycleDefinition", CapabilityWorkflowPlan.LIFECYCLE);
        metadata.put("runtimeLifecycle", provider == null ? List.of("RESOLVE_CAPABILITY", "EVALUATE", "COMPLETE")
            : List.of("RESOLVE_CAPABILITY", "EXECUTE", "EVALUATE", "COMPLETE"));
        metadata.put("runtimeWorkflowProtocolVersion", "capability_workflow.v1");
        metadata.put("capabilityProvider", provider == null ? "NONE" : provider.id());
        if (provider != null) {
            metadata.put("capabilityProviderKind", provider.kind().name());
            metadata.put("unresolvedOptionalCapabilities", plan.requirements().stream().filter(item -> !item.required())
                .map(CapabilityWorkflowPlan.Requirement::capability).filter(id -> !provider.capabilities().contains(id)).toList());
        }
        response.setMetadata(metadata);
        projectOutcome(response);
        return response;
    }

    /** Shared by sync/API responses and async tasks; bridges do not invent terminal states. */
    public static void projectOutcome(InteractionResponse response) {
        if (response == null || response.getMetadata() == null
            || !(response.getMetadata().get(WorkflowOutcome.METADATA_KEY) instanceof WorkflowOutcome outcome)) return;
        Map<String, Object> metadata = new LinkedHashMap<>(response.getMetadata());
        Map<String, Object> agent = new LinkedHashMap<>();
        if (metadata.get("agent") instanceof Map<?, ?> values)
            values.forEach((key, value) -> { if (key instanceof String name) agent.put(name, value); });
        agent.put("publicStatus", outcome.publicStatus());
        agent.put("workflowOutcomeType", outcome.type().name());
        agent.put("missingRequiredCapabilities", outcome.missingRequiredCapabilities());
        metadata.put("agent", agent);
        response.setMetadata(metadata);
    }

    /** Native runtimes own their plan; normalize their evaluation without fabricating a family. */
    public static void normalizeOutcome(InteractionResponse response) {
        Objects.requireNonNull(response, "Workflow returned no response");
        Map<String, Object> metadata = new LinkedHashMap<>(response.getMetadata() == null ? Map.of() : response.getMetadata());
        metadata.put(WorkflowOutcome.METADATA_KEY, resolveOutcome(null, response, metadata));
        response.setMetadata(metadata);
        projectOutcome(response);
    }

    private static WorkflowOutcome resolveOutcome(com.chatchat.common.runtime.analysis.model.RuntimeWorkflowFamily family,
            InteractionResponse response, Map<String, Object> metadata) {
        WorkflowOutcome outcome = metadata.get(WorkflowOutcome.METADATA_KEY) instanceof WorkflowOutcome supplied
            ? supplied : evaluateExistingRuntime(family, response, metadata);
        Map<?, ?> agent = metadata.get("agent") instanceof Map<?, ?> values ? values : Map.of();
        boolean auditFailed = "FAIL".equalsIgnoreCase(String.valueOf(agent.get("claimCoverageStatus")))
            || Boolean.FALSE.equals(agent.get("answerClaimAuditPassed"));
        // Execution completion and evidence acceptance are separate obligations.
        // Preserve usable results, but a failed final audit cannot be projected as full success.
        if (outcome.type() == WorkflowOutcome.Type.READY_TO_ANSWER && auditFailed) {
            Set<String> missing = new LinkedHashSet<>(outcome.missingRequiredCapabilities());
            missing.add("evidence_verify");
            return new WorkflowOutcome(WorkflowOutcome.Type.PARTIAL_RESULT, "ANSWER_EVIDENCE_AUDIT_FAILED",
                List.copyOf(missing), outcome.missingOptionalCapabilities(), outcome.usableResult());
        }
        return outcome;
    }

    private static WorkflowOutcome evaluateExistingRuntime(com.chatchat.common.runtime.analysis.model.RuntimeWorkflowFamily family,
            InteractionResponse response, Map<String, Object> metadata) {
        Map<?, ?> agent = metadata.get("agent") instanceof Map<?, ?> map ? map : Map.of();
        String status = String.valueOf(agent.get("publicStatus")).toUpperCase(Locale.ROOT);
        String reason = String.valueOf(agent.get("stopReason"));
        boolean content = response.getAnswer() != null && !response.getAnswer().isBlank();
        if (Set.of("CANCELLED", "KILLED", "TIMEOUT_CANCELLED", "REJECTED").contains(status))
            return new WorkflowOutcome(WorkflowOutcome.Type.CANCELLED, reason, List.of(), List.of(), false);
        if (Set.of("TIME_BUDGET_EXHAUSTED", "MODEL_BUDGET_EXHAUSTED").contains(status))
            return new WorkflowOutcome(WorkflowOutcome.Type.valueOf(status), reason, List.of(), List.of(), content);
        if (Boolean.TRUE.equals(metadata.get("confirmationRequired")) || Boolean.TRUE.equals(agent.get("confirmationRequired")))
            return new WorkflowOutcome(WorkflowOutcome.Type.CONFIRMATION_REQUIRED, "CONFIRMATION_REQUIRED", List.of(), List.of(), false);
        if ("NO_EXECUTABLE_PLAN".equalsIgnoreCase(reason))
            return new WorkflowOutcome(WorkflowOutcome.Type.NO_EXECUTABLE_PLAN, reason, List.of(), List.of(), false);
        if (Boolean.TRUE.equals(agent.get("planningAdmissionFailed")) || "FAILED".equals(status))
            return new WorkflowOutcome(WorkflowOutcome.Type.FAILED, reason, List.of(), List.of(), false);
        if (Boolean.TRUE.equals(agent.get("fatalExecutionBlocked")) || Boolean.TRUE.equals(agent.get("mandatoryWorkflowBlocked")))
            return new WorkflowOutcome(WorkflowOutcome.Type.INSUFFICIENT_EVIDENCE, "REQUIRED_CAPABILITY_BLOCKED", List.of("required_execution"), List.of(), false);
        if ("SUCCESS".equals(status) && family == com.chatchat.common.runtime.analysis.model.RuntimeWorkflowFamily.ACTION
            && (response.getToolTraces() == null || response.getToolTraces().stream().noneMatch(trace -> trace != null && trace.isSuccess())))
            return new WorkflowOutcome(WorkflowOutcome.Type.INSUFFICIENT_EVIDENCE, "ACTION_RESULT_NOT_VERIFIED", List.of("action_verify"), List.of(), false);
        if ("SUCCESS".equals(status) && family == com.chatchat.common.runtime.analysis.model.RuntimeWorkflowFamily.DOCUMENT
            && (response.getSources() == null || response.getSources().isEmpty()))
            return new WorkflowOutcome(WorkflowOutcome.Type.INSUFFICIENT_EVIDENCE, "DOCUMENT_EVIDENCE_MISSING", List.of("evidence_verify"), List.of(), false);
        if ("SUCCESS".equals(status) && content)
            return new WorkflowOutcome(WorkflowOutcome.Type.READY_TO_ANSWER, "RUNTIME_VERIFIED", List.of(), List.of(), true);
        if (Set.of("PARTIAL", "PARTIAL_SUCCESS").contains(status))
            return new WorkflowOutcome(WorkflowOutcome.Type.PARTIAL_RESULT, reason, List.of(), List.of(), content);
        // Legacy answer text without an evaluation is not a verification result.
        return new WorkflowOutcome(WorkflowOutcome.Type.INSUFFICIENT_EVIDENCE, "MISSING_RUNTIME_EVALUATION", List.of("evidence_verify"), List.of(), false);
    }
}
