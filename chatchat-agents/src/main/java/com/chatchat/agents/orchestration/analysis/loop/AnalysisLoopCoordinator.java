package com.chatchat.agents.orchestration.analysis.loop;

import com.chatchat.agents.assessment.EvidenceAugmentationPolicy;
import com.chatchat.agents.assessment.EvidenceExplorationPolicy;
import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.assessment.TaskContract;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Owns evidence-loop decisions, progress recording and deterministic stop state. */
public final class AnalysisLoopCoordinator {

    private final EvidenceAugmentationPolicy augmentationPolicy = new EvidenceAugmentationPolicy();
    private final EvidenceExplorationPolicy explorationPolicy = new EvidenceExplorationPolicy();
    private final AgentRunResultAdapter runResultAdapter;
    private final String runIdAttribute;

    public AnalysisLoopCoordinator(AgentRunResultAdapter runResultAdapter, String runIdAttribute) {
        this.runResultAdapter = runResultAdapter;
        this.runIdAttribute = runIdAttribute;
    }

    public EvidenceAugmentationPolicy.Outcome decide(Map<String, Object> snapshot,
                                                     boolean executionSuccess,
                                                     boolean explorationAvailable,
                                                     boolean authorizationRequired,
                                                     Map<String, Object> metadata) {
        boolean sufficient = sufficient(snapshot);
        boolean materialGap = !sufficient && (!executionSuccess
            || size(snapshot == null ? null : snapshot.get("remainingMissing")) > 0
            || size(snapshot == null ? null : snapshot.get("conflicts")) > 0);
        return augmentationPolicy.decide(new EvidenceAugmentationPolicy.Context(
            usableEvidence(snapshot), sufficient, materialGap, explorationAvailable,
            authorizationRequired, evidenceRequirement(metadata)));
    }

    public boolean explorationAvailable(Map<String, Object> snapshot,
                                        boolean executionSuccess,
                                        boolean toolsAvailable,
                                        boolean budgetAvailable,
                                        boolean refinementAvailable) {
        return explorationPolicy.available(snapshot, executionSuccess, toolsAvailable,
            budgetAvailable, refinementAvailable);
    }

    public boolean sufficient(Map<String, Object> snapshot) {
        return snapshot != null && truthy(snapshot.get("sufficient"));
    }

    public boolean usableEvidence(Map<String, Object> snapshot) {
        if (snapshot == null || !(snapshot.get("toolEvidence") instanceof Iterable<?> items)) return false;
        for (Object raw : items) {
            if (raw instanceof Map<?, ?> item
                && (meaningful(item.get("outputFacts")) || meaningful(item.get("output")))) return true;
        }
        return false;
    }

    public void recordDecision(EvidenceAugmentationPolicy.Outcome outcome,
                               int iteration,
                               Map<String, Object> runtimeAttributes,
                               Map<String, Object> metadata) {
        if (outcome == null || metadata == null) return;
        Map<String, Object> decision = Map.of(
            "contractVersion", outcome.contractVersion(), "iteration", iteration,
            "decision", outcome.decision().name(), "answerAllowed", outcome.answerAllowed(),
            "continueLoop", outcome.continueLoop(), "reason", outcome.reason());
        history(metadata, "evidenceAugmentationHistory").add(decision);
        metadata.put("evidenceAugmentationDecision", outcome.decision().name());
        metadata.put("evidenceAugmentationAnswerAllowed", outcome.answerAllowed());
        metadata.put("evidenceAugmentationContinueLoop", outcome.continueLoop());
        metadata.put("evidenceAugmentationContractVersion", outcome.contractVersion());
        runResultAdapter.recordRuntimeObservation(runtimeAttributes, runIdAttribute,
            "Evidence augmentation decision for iteration " + iteration + ": "
                + outcome.decision().name() + ".", "evidence_augmentation_decision",
            Map.of("type", "evidence", "workflow", "interpretation_plan",
                "lifecyclePhase", "loop_decision", "decision", decision));
    }

    public void recordStop(Map<String, Object> metadata,
                           Map<String, Object> snapshot,
                           String stopReason,
                           int iterations) {
        if (metadata == null) return;
        Object remaining = snapshot == null ? List.of()
            : snapshot.getOrDefault("remainingMissing", snapshot.getOrDefault("missingEvidence", List.of()));
        if (remaining == null) remaining = List.of();
        double confidence = number(snapshot == null ? null : snapshot.get("confidence"));
        metadata.put("stopReason", stopReason);
        metadata.put("evidenceConfidence", confidence);
        metadata.put("remainingMissing", remaining);
        metadata.put("evidenceIterations", Math.max(0, iterations));
        metadata.put("evidenceStopState", Map.of(
            "contractVersion", "agent_evidence_stop_v1", "stopReason", stopReason,
            "confidence", confidence, "remainingMissing", remaining,
            "iterations", Math.max(0, iterations)));
    }

    public TaskContract.EvidenceRequirement evidenceRequirement(Map<String, Object> metadata) {
        Object contract = metadata == null ? null : metadata.get("taskContract");
        if (contract instanceof TaskContract value) return value.evidenceRequirement();
        Object configured = metadata == null ? null : metadata.get("evidenceRequirement");
        try {
            if (configured != null) return TaskContract.EvidenceRequirement.valueOf(
                String.valueOf(configured).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // Older runtime metadata uses the safe optional default.
        }
        return TaskContract.EvidenceRequirement.OPTIONAL;
    }

    private boolean meaningful(Object value) {
        if (value == null) return false;
        if (value instanceof CharSequence text) {
            String normalized = text.toString().trim();
            return !normalized.isEmpty() && !"null".equalsIgnoreCase(normalized)
                && !"[]".equals(normalized) && !"{}".equals(normalized);
        }
        if (value instanceof Map<?, ?> map) return !map.isEmpty() && map.values().stream().anyMatch(this::meaningful);
        if (value instanceof Iterable<?> values) return values.iterator().hasNext();
        if (value.getClass().isArray()) return Array.getLength(value) > 0;
        return true;
    }

    private int size(Object value) {
        if (value instanceof Collection<?> collection) return collection.size();
        if (value instanceof Map<?, ?> map) return map.size();
        return value == null || String.valueOf(value).isBlank() ? 0 : 1;
    }

    private boolean truthy(Object value) {
        return value instanceof Boolean flag ? flag : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? 0.0 : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> history(Map<String, Object> metadata, String key) {
        Object existing = metadata.get(key);
        if (existing instanceof List<?> list) return (List<Map<String, Object>>) list;
        List<Map<String, Object>> created = new ArrayList<>();
        metadata.put(key, created);
        return created;
    }
}
