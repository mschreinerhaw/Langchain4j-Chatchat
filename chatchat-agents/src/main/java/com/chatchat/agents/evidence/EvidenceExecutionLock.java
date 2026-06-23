package com.chatchat.agents.evidence;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record EvidenceExecutionLock(
    String lockVersion,
    String status,
    Trigger trigger,
    LockedState lockedState,
    ExecutionConstraints executionConstraints
) {

    public static final String LOCK_VERSION = "evidence_execution_lock_v1";

    public EvidenceExecutionLock {
        lockVersion = lockVersion == null || lockVersion.isBlank() ? LOCK_VERSION : lockVersion;
        status = status == null || status.isBlank() ? "UNLOCKED" : status;
        trigger = trigger == null ? Trigger.empty() : trigger;
        lockedState = lockedState == null ? LockedState.empty() : lockedState;
        executionConstraints = executionConstraints == null ? ExecutionConstraints.empty() : executionConstraints;
    }

    public static EvidenceExecutionLock fromReview(Integer stepId,
                                                   String toolName,
                                                   String reason,
                                                   Map<String, Object> reviewMetadata) {
        Map<String, Object> metadata = reviewMetadata == null ? Map.of() : reviewMetadata;
        Map<String, Object> evaluation = mapValue(metadata.get("evidenceEvaluation"));
        boolean locked = shouldLock(evaluation);
        String status = locked ? "LOCKED" : "UNLOCKED";
        String lockReason = locked ? "sufficient_evidence" : unlockReason(evaluation);
        return new EvidenceExecutionLock(
            LOCK_VERSION,
            status,
            new Trigger(stepId, firstNonBlank(lockReason, reason), "tool_result_review"),
            new LockedState(
                stringList(firstPresent(evaluation, "usefulRefs", "accepted_refs", "acceptedRefs")),
                stringList(firstPresent(evaluation, "rejectedRefs", "rejected_refs", "rejectedRefs")),
                evaluation
            ),
            locked
                ? new ExecutionConstraints(
                    stepId == null ? List.of() : List.of(stepId),
                    firstNonBlank(toolName, "").isBlank() ? List.of() : List.of(toolName),
                    stepId == null ? List.of() : List.of("replan_step_" + stepId),
                    List.of("final_answer")
                )
                : ExecutionConstraints.empty()
        );
    }

    public boolean locked() {
        return "LOCKED".equalsIgnoreCase(status);
    }

    public Map<String, Object> toMetadata() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("lockVersion", lockVersion);
        value.put("contractVersion", lockVersion);
        value.put("status", status);
        value.put("lock", locked());
        value.put("lockLevel", locked() ? "HARD" : "NONE");
        value.put("trigger", trigger.toMetadata());
        value.put("lockedState", lockedState.toMetadata());
        value.put("executionConstraints", executionConstraints.toMetadata());
        value.put("lockedSteps", executionConstraints.immutableSteps());
        value.put("reason", trigger.reason());
        return value;
    }

    private static boolean shouldLock(Map<String, Object> evaluation) {
        if (evaluation == null || evaluation.isEmpty()) {
            return false;
        }
        if (booleanValue(firstPresent(evaluation, "shouldExpandQuery", "should_expand_query", "expandQuery"))) {
            return false;
        }
        double answerability = scoreValue(firstPresent(evaluation, "answerability", "answerabilityScore"));
        double relevance = scoreValue(firstPresent(evaluation, "relevance", "relevanceScore"));
        String usefulness = usefulnessValue(firstPresent(evaluation, "usefulness", "utility"));
        return answerability >= 0.85 && relevance >= 0.8 && "HIGH".equals(usefulness);
    }

    private static String unlockReason(Map<String, Object> evaluation) {
        if (evaluation == null || evaluation.isEmpty()) {
            return "missing_evaluation";
        }
        if (booleanValue(firstPresent(evaluation, "shouldExpandQuery", "should_expand_query", "expandQuery"))) {
            return "query_expansion_requested";
        }
        double answerability = scoreValue(firstPresent(evaluation, "answerability", "answerabilityScore"));
        if (answerability < 0.6) {
            return "low_answerability";
        }
        return "below_lock_threshold";
    }

    private static Object firstPresent(Map<String, Object> values, String... keys) {
        if (values == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    result.put(String.valueOf(key), item);
                }
            });
            return result;
        }
        return Map.of();
    }

    private static List<String> stringList(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                .map(EvidenceExecutionLock::stringValue)
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .toList();
        }
        String text = stringValue(value);
        return text == null || text.isBlank() ? List.of() : List.of(text.trim());
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static double scoreValue(Object value) {
        if (value instanceof Number number) {
            return clamp(number.doubleValue());
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return clamp(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private static double clamp(double value) {
        if (value > 1.0) {
            value = value / 100.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String usefulnessValue(Object value) {
        String text = stringValue(value);
        if (text == null || text.isBlank()) {
            return "LOW";
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "HIGH", "MEDIUM", "LOW" -> normalized;
            case "STRONG", "USEFUL", "RELEVANT" -> "HIGH";
            case "PARTIAL", "SOME", "WEAK" -> "MEDIUM";
            default -> "LOW";
        };
    }

    public record Trigger(
        Integer sourceStepId,
        String reason,
        String evaluationContractRef
    ) {

        public Trigger {
            reason = reason == null ? "" : reason;
            evaluationContractRef = evaluationContractRef == null || evaluationContractRef.isBlank()
                ? "tool_result_review"
                : evaluationContractRef;
        }

        public static Trigger empty() {
            return new Trigger(null, "", "tool_result_review");
        }

        public Map<String, Object> toMetadata() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("sourceStepId", sourceStepId);
            value.put("reason", reason);
            value.put("evaluationContractRef", evaluationContractRef);
            return value;
        }
    }

    public record LockedState(
        List<String> acceptedRefs,
        List<String> rejectedRefs,
        Map<String, Object> evaluation
    ) {

        public LockedState {
            acceptedRefs = acceptedRefs == null ? List.of() : List.copyOf(acceptedRefs);
            rejectedRefs = rejectedRefs == null ? List.of() : List.copyOf(rejectedRefs);
            evaluation = evaluation == null ? Map.of() : new LinkedHashMap<>(evaluation);
        }

        public static LockedState empty() {
            return new LockedState(List.of(), List.of(), Map.of());
        }

        public Map<String, Object> toMetadata() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("accepted_refs", acceptedRefs);
            value.put("rejected_refs", rejectedRefs);
            value.put("evaluation", evaluation);
            return value;
        }
    }

    public record ExecutionConstraints(
        List<Integer> immutableSteps,
        List<String> blockedTools,
        List<String> blockedActions,
        List<String> allowOnly
    ) {

        public ExecutionConstraints {
            immutableSteps = immutableSteps == null ? List.of() : List.copyOf(immutableSteps);
            blockedTools = blockedTools == null ? List.of() : List.copyOf(blockedTools);
            blockedActions = blockedActions == null ? List.of() : List.copyOf(blockedActions);
            allowOnly = allowOnly == null ? List.of() : List.copyOf(allowOnly);
        }

        public static ExecutionConstraints empty() {
            return new ExecutionConstraints(List.of(), List.of(), List.of(), List.of());
        }

        public Map<String, Object> toMetadata() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("immutable_steps", immutableSteps);
            value.put("blocked_tools", blockedTools);
            value.put("blocked_actions", blockedActions);
            value.put("allow_only", allowOnly);
            return value;
        }
    }
}
