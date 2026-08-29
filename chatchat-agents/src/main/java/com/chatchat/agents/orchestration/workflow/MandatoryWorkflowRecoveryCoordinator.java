package com.chatchat.agents.orchestration.workflow;

import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.agents.orchestration.answer.AgentToolBudgetPort;
import com.chatchat.agents.orchestration.retrieval.McpParamBindingResolver;
import com.chatchat.agents.orchestration.retrieval.ModelAssistedRetrievalBridge;
import com.chatchat.agents.orchestration.tool.AgentToolArgumentResolver;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.runtime.plan.RetrievalQualityGate;
import com.chatchat.agents.runtime.store.AgentRunStore;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.firstNonBlank;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.stringValue;

/**
 * Executes the deterministic recovery path for mandatory workflow tools.
 *
 * <p>The engine supplies only runtime-specific callbacks. Ordering, input validation, quality
 * fallback, result review, progress bookkeeping, and stop semantics live here.</p>
 */
public final class MandatoryWorkflowRecoveryCoordinator {

    public static final String AGENT_RUN_ID_ATTRIBUTE = "__agentRunId";

    private final AgentToolNameResolver toolNames;
    private final AgentToolArgumentResolver toolArguments;
    private final AgentWorkflowToolResolver workflowTools;
    private final AgentWorkflowStatePort workflowState;
    private final MandatoryWorkflowTopology topology;
    private final MandatoryWorkflowRecoveryPolicy policy;
    private final MandatoryWorkflowResultReviewer resultReviewer;
    private final ModelAssistedRetrievalBridge retrievalBridge;
    private final AgentToolBudgetPort toolBudget;
    private final AgentRunStore runStore;
    private final ObjectMapper objectMapper;

    public MandatoryWorkflowRecoveryCoordinator(AgentToolNameResolver toolNames,
                                                AgentToolArgumentResolver toolArguments,
                                                AgentWorkflowToolResolver workflowTools,
                                                AgentWorkflowStatePort workflowState,
                                                MandatoryWorkflowTopology topology,
                                                MandatoryWorkflowRecoveryPolicy policy,
                                                MandatoryWorkflowResultReviewer resultReviewer,
                                                ModelAssistedRetrievalBridge retrievalBridge,
                                                AgentToolBudgetPort toolBudget,
                                                AgentRunStore runStore,
                                                ObjectMapper objectMapper) {
        this.toolNames = toolNames;
        this.toolArguments = toolArguments;
        this.workflowTools = workflowTools;
        this.workflowState = workflowState;
        this.topology = topology;
        this.policy = policy;
        this.resultReviewer = resultReviewer;
        this.retrievalBridge = retrievalBridge;
        this.toolBudget = toolBudget;
        this.runStore = runStore;
        this.objectMapper = objectMapper;
    }

    public void recover(Request request, ToolInvoker toolInvoker,
                        CandidateInputProvider candidateInputs, CandidateReviewer candidateReviewer) {
        Map<String, InteractionToolTrace> reviewedDiscoveryTraces = new LinkedHashMap<>();
        Map<String, Object> runtimeAttributes = request.runtimeAttributes();
        Set<String> completedTools = completedTools(runtimeAttributes, request.traces());
        List<String> fallbackTools = topology.dependencyOrderedFallbackTools(
            value(runtimeAttributes, "authoritativeWorkflowDag"), value(runtimeAttributes, "mcpWorkflow"),
            request.mandatoryTools(), completedTools);
        if (fallbackTools.isEmpty()) {
            return;
        }
        request.metadata().put("mandatoryWorkflowExecutionTools", fallbackTools);
        for (String fallbackTool : fallbackTools) {
            String failedTool = failedMandatoryTool(request.mandatoryTools(), request.traces());
            if (failedTool != null) {
                stop(request, failedTool, "Mandatory workflow fallback stopped because required tool "
                    + failedTool + " already produced a failure observation.");
                return;
            }
            if (policy.shouldSuppressLegacyFallback(fallbackTool, request.metadata())) {
                request.metadata().put("mandatoryWorkflowFallbackSuppressed", true);
                request.metadata().put("mandatoryWorkflowFallbackSuppressedTool", fallbackTool);
                request.metadata().put("mandatoryWorkflowFallbackSuppressionReason", "GOVERNED_DIAGNOSTIC_EXECUTOR_FAILED");
                stop(request, fallbackTool, "Mandatory workflow fallback did not invoke " + fallbackTool
                    + " because the governed diagnostic DAG already attempted that executor and failed. "
                    + "A scalar legacy fallback cannot replace its reviewed multi-result execution contract.");
                return;
            }
            completedTools = completedTools(runtimeAttributes, request.traces());
            if (fallbackTool == null || !request.tools().contains(fallbackTool)
                || containsTool(completedTools, fallbackTool)) {
                continue;
            }
            if (toolBudget.markToolBudgetExceeded(fallbackTool, request.maxToolCalls(),
                request.traces(), request.metadata(), request.observations())) {
                return;
            }
            List<InteractionToolTrace> predecessors = topology.predecessorTraces(
                value(runtimeAttributes, "authoritativeWorkflowDag"), request.mandatoryTools(),
                fallbackTool, request.traces());
            predecessors = reviewedTraces(predecessors, reviewedDiscoveryTraces);
            Map<String, Object> predecessorReview = resultReviewer.reviewPredecessors(fallbackTool, predecessors);
            if (!Boolean.TRUE.equals(predecessorReview.get("satisfied"))) {
                appendReview(request.metadata(), predecessorReview);
                stop(request, String.valueOf(predecessorReview.getOrDefault("predecessorToolName", fallbackTool)),
                    "Mandatory workflow fallback stopped by predecessor result review: " + stringify(predecessorReview));
                return;
            }
            Map<String, Object> configured = candidateInputs.input(runtimeAttributes, fallbackTool);
            Map<String, Object> arguments = toolArguments.applyToolDefaults(fallbackTool,
                configured.isEmpty() ? toolArguments.defaultToolArguments(
                    fallbackTool, request.query(), request.webSearchResultLimit()) : configured,
                request.documentIds(), request.documentTags(), request.query(), request.webSearchResultLimit());
            arguments = toolArguments.applyDeterministicDependencyContracts(
                fallbackTool, arguments, predecessors, request.query());
            if ("DENIED".equals(arguments.get(McpParamBindingResolver.STATUS_KEY))) {
                String code = firstNonBlank(stringValue(arguments.get(McpParamBindingResolver.CODE_KEY)),
                    "INVALID_TOOL_ARGUMENTS");
                String error = firstNonBlank(stringValue(arguments.get(McpParamBindingResolver.ERROR_KEY)),
                    "The predecessor evidence did not authorize a compatible invocation contract.");
                request.metadata().put("mandatoryWorkflowContractCode", code);
                request.metadata().put("mandatoryWorkflowContractError", error);
                stop(request, fallbackTool, "Mandatory workflow fallback did not invoke " + fallbackTool
                    + " because its runtime-owned dependency contract was not executable: " + code + " - " + error);
                return;
            }
            List<String> missing = policy.missingRequiredInputs(fallbackTool, arguments);
            if (!missing.isEmpty()) {
                request.metadata().put("mandatoryWorkflowMissingRequiredInputs", missing);
                stop(request, fallbackTool, "Mandatory workflow fallback did not invoke " + fallbackTool
                    + " because required inputs could not be proven from completed predecessor evidence: "
                    + String.join(", ", missing) + ".");
                return;
            }
            Map<String, Object> originalArguments = new LinkedHashMap<>(arguments);
            ModelAssistedRetrievalBridge.EnrichmentResult enrichment = retrievalBridge.enrichWithGate(
                request.activeChatModel(), fallbackTool, arguments,
                new ModelAssistedRetrievalBridge.RetrievalEvidenceContext(request.query(), Map.of()));
            arguments = enrichment.arguments();
            AgentOrchestrator.ToolCallExecution execution = invoke(request, toolInvoker, fallbackTool,
                arguments, reviewedDiscoveryTraces, completedTools, runtimeAttributes);
            RetrievalQualityGate.Evaluation enhanced = enrichment.qualityGate().isEmpty() ? null
                : RetrievalQualityGate.evaluate(execution.output(), enrichment.qualityGate());
            if (enhanced != null && !enhanced.sufficient()) {
                AgentOrchestrator.ToolCallExecution original = invoke(request, toolInvoker, fallbackTool,
                    originalArguments, reviewedDiscoveryTraces, completedTools, runtimeAttributes);
                RetrievalQualityGate.Evaluation originalQuality = RetrievalQualityGate.evaluate(
                    original.output(), enrichment.qualityGate());
                boolean originalSelected = RetrievalQualityGate.preferFallback(enhanced, originalQuality);
                AgentOrchestrator.ToolCallExecution nonSelected = originalSelected ? execution : original;
                if (nonSelected.trace() != null) {
                    request.traces().add(nonSelected.trace());
                }
                request.observations().add("Retrieval quality gate candidate " + nonSelected.observation());
                if (originalSelected) {
                    execution = original;
                }
                request.metadata().put("mandatoryRetrievalQualityGate:" + fallbackTool,
                    RetrievalQualityGate.report(enhanced, originalQuality, originalSelected));
            } else if (enhanced != null) {
                request.metadata().put("mandatoryRetrievalQualityGate:" + fallbackTool,
                    RetrievalQualityGate.report(enhanced, null, false));
            }
            request.traces().add(execution.trace());
            request.observations().add("Mandatory workflow execution " + execution.observation());
            if (workflowState.isConfirmationRequired(execution)) {
                request.metadata().put("stopReason", "confirmation_required");
                request.metadata().put("confirmationRequired", true);
                return;
            }
            if (execution.trace() == null || !execution.trace().isSuccess()) {
                request.metadata().put("mandatoryWorkflowStoppedOnFailure", fallbackTool);
                return;
            }
            Map<String, Object> resultReview = resultReviewer.review(fallbackTool, execution.output());
            request.observations().add("Mandatory workflow local result review: " + stringify(resultReview));
            appendReview(request.metadata(), resultReview);
            if (!Boolean.TRUE.equals(resultReview.get("satisfied"))) {
                request.metadata().put("mandatoryWorkflowStoppedOnFailure", fallbackTool);
                return;
            }
            SemanticReview semantic = candidateReviewer.review(fallbackTool, arguments, execution.output());
            if (semantic.required()) {
                appendSemanticReview(request.metadata(), semantic);
                request.observations().add("Mandatory workflow semantic candidate review: "
                    + stringify(semantic.auditMetadata()));
                if (!semantic.satisfied()) {
                    request.metadata().put("mandatoryWorkflowStoppedOnFailure", fallbackTool);
                    request.metadata().put("mandatoryWorkflowSemanticReviewBlocked", true);
                    request.metadata().put("mandatoryWorkflowSemanticReviewReason", semantic.reason());
                    return;
                }
                reviewedDiscoveryTraces.put(fallbackTool,
                    projectedTrace(execution.trace(), semantic.projectedOutput(), semantic));
            }
            runtimeAttributes = workflowState.attributesWithCompletedTools(
                runtimeAttributes, completedTools(runtimeAttributes, request.traces()));
        }
        List<String> remaining = workflowTools.missingMandatoryTools(
            request.mandatoryTools(), completedTools(runtimeAttributes, request.traces()));
        request.metadata().put(remaining.isEmpty()
            ? "mandatoryWorkflowRecoveredAfterPlan" : "mandatoryWorkflowStillMissingAfterFallback",
            remaining.isEmpty() ? true : remaining);
    }

    private AgentOrchestrator.ToolCallExecution invoke(Request request, ToolInvoker invoker,
                                                       String tool, Map<String, Object> arguments,
                                                       Map<String, InteractionToolTrace> reviewed,
                                                       Set<String> completed,
                                                       Map<String, Object> runtimeAttributes) {
        return invoker.invoke(tool, arguments, request.conversationId(), request.requestId(),
            request.userId(), request.tenantId(), request.tools(), Map.of(),
            reviewedTraces(request.traces(), reviewed),
            workflowState.attributesWithCompletedTools(runtimeAttributes, completed));
    }

    private Set<String> completedTools(Map<String, Object> attributes, List<InteractionToolTrace> traces) {
        Set<String> completed = new LinkedHashSet<>(workflowState.completedToolsFromTraces(traces));
        String runId = stringValue(value(attributes, AGENT_RUN_ID_ATTRIBUTE));
        if (runId != null && !runId.isBlank()) {
            completed.addAll(workflowState.completedToolsFromEvents(runStore.events(runId)));
        }
        return completed;
    }

    private List<InteractionToolTrace> reviewedTraces(List<InteractionToolTrace> traces,
                                                      Map<String, InteractionToolTrace> replacements) {
        if (traces == null || traces.isEmpty() || replacements.isEmpty()) {
            return traces == null ? List.of() : traces;
        }
        List<InteractionToolTrace> projected = new ArrayList<>(traces.size());
        for (InteractionToolTrace trace : traces) {
            InteractionToolTrace replacement = trace == null ? null : replacements.entrySet().stream()
                .filter(entry -> toolNames.sameToolName(entry.getKey(), trace.getToolName()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
            projected.add(replacement == null ? trace : replacement);
        }
        return List.copyOf(projected);
    }

    private InteractionToolTrace projectedTrace(InteractionToolTrace original, Object output,
                                                 SemanticReview review) {
        Map<String, Object> metadata = new LinkedHashMap<>(original == null
            || original.getRuntimeMetadata() == null ? Map.of() : original.getRuntimeMetadata());
        metadata.put("semanticCandidateReviewSatisfied", true);
        metadata.put("semanticCandidateReview", review.auditMetadata());
        return InteractionToolTrace.builder()
            .toolName(original == null ? null : original.getToolName())
            .displayName(original == null ? null : original.getDisplayName())
            .serviceId(original == null ? null : original.getServiceId())
            .serviceName(original == null ? null : original.getServiceName())
            .success(original == null || original.isSuccess())
            .input(original == null ? Map.of() : original.getInput())
            .output(stringify(output))
            .errorMessage(original == null ? null : original.getErrorMessage())
            .durationMs(original == null ? null : original.getDurationMs())
            .startedAt(original == null ? null : original.getStartedAt())
            .finishedAt(original == null ? null : original.getFinishedAt())
            .runtimeMetadata(metadata).build();
    }

    @SuppressWarnings("unchecked")
    private void appendReview(Map<String, Object> metadata, Map<String, Object> review) {
        List<Map<String, Object>> reviews = metadata.get("mandatoryWorkflowResultReviews") instanceof List<?> values
            ? new ArrayList<>((List<Map<String, Object>>) values) : new ArrayList<>();
        reviews.add(new LinkedHashMap<>(review));
        metadata.put("mandatoryWorkflowResultReviews", reviews);
    }

    @SuppressWarnings("unchecked")
    private void appendSemanticReview(Map<String, Object> metadata, SemanticReview review) {
        List<Map<String, Object>> reviews = metadata.get("mandatorySemanticCandidateReviews") instanceof List<?> values
            ? new ArrayList<>((List<Map<String, Object>>) values) : new ArrayList<>();
        Map<String, Object> audit = new LinkedHashMap<>(review.auditMetadata());
        audit.put("schemaVersion", "mandatory_semantic_candidate_review.v1");
        audit.put("satisfied", review.satisfied());
        audit.put("reason", review.reason());
        reviews.add(Map.copyOf(audit));
        metadata.put("mandatorySemanticCandidateReviews", List.copyOf(reviews));
    }

    private String failedMandatoryTool(List<String> mandatoryTools, List<InteractionToolTrace> traces) {
        if (mandatoryTools == null || traces == null) return null;
        return traces.stream().filter(trace -> trace != null && !trace.isSuccess())
            .map(InteractionToolTrace::getToolName)
            .filter(name -> mandatoryTools.stream().anyMatch(tool -> toolNames.sameToolName(tool, name)))
            .findFirst().orElse(null);
    }

    private boolean containsTool(Set<String> tools, String expected) {
        return tools.stream().anyMatch(tool -> toolNames.sameToolName(expected, tool));
    }

    private void stop(Request request, String tool, String observation) {
        request.metadata().put("mandatoryWorkflowStoppedOnFailure", tool);
        request.observations().add(observation);
    }

    private Object value(Map<String, Object> attributes, String key) {
        return attributes == null ? null : attributes.get(key);
    }

    private String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return text;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ignored) {
            return String.valueOf(value);
        }
    }

    public record Request(ChatModel activeChatModel, List<InteractionToolTrace> traces,
                          List<String> observations, String query, String conversationId,
                          String requestId, String userId, String tenantId, List<String> tools,
                          List<String> mandatoryTools, List<String> documentIds,
                          List<String> documentTags, int webSearchResultLimit,
                          Map<String, Object> metadata, Map<String, Object> runtimeAttributes,
                          int maxToolCalls, String systemPrompt,
                          BooleanSupplier cancellationCheck) { }

    public record SemanticReview(boolean required, boolean satisfied, String reason,
                                 Object projectedOutput, Map<String, Object> auditMetadata) {
        public SemanticReview {
            auditMetadata = auditMetadata == null ? Map.of() : Map.copyOf(auditMetadata);
        }
    }

    @FunctionalInterface
    public interface CandidateReviewer {
        SemanticReview review(String toolName, Map<String, Object> input, ToolOutput output);
    }

    @FunctionalInterface
    public interface CandidateInputProvider {
        Map<String, Object> input(Map<String, Object> runtimeAttributes, String toolName);
    }

    @FunctionalInterface
    public interface ToolInvoker {
        AgentOrchestrator.ToolCallExecution invoke(String toolName, Map<String, Object> arguments,
            String conversationId, String requestId, String userId, String tenantId,
            List<String> availableTools, Map<String, Object> argumentsByTool,
            List<InteractionToolTrace> traces, Map<String, Object> runtimeAttributes);
    }
}
