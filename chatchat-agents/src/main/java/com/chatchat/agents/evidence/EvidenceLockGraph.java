package com.chatchat.agents.evidence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record EvidenceLockGraph(
    String lockGraphVersion,
    List<LockNode> locks,
    List<Conflict> conflicts,
    Propagation propagation,
    DagFreeze dagFreeze
) {

    public static final String LOCK_GRAPH_VERSION = "evidence_execution_lock_v2";

    public EvidenceLockGraph {
        lockGraphVersion = lockGraphVersion == null || lockGraphVersion.isBlank()
            ? LOCK_GRAPH_VERSION
            : lockGraphVersion;
        locks = locks == null ? List.of() : List.copyOf(locks);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        propagation = propagation == null ? Propagation.empty() : propagation;
        dagFreeze = dagFreeze == null ? DagFreeze.unfrozen() : dagFreeze;
    }

    public static EvidenceLockGraph fromReview(Integer stepId,
                                               String toolName,
                                               Map<String, Object> reviewMetadata,
                                               EvidenceExecutionLock executionLock) {
        Map<String, Object> metadata = reviewMetadata == null ? Map.of() : reviewMetadata;
        Map<String, Object> evaluation = mapValue(metadata.get("evidenceEvaluation"));
        List<String> acceptedRefs = refs(evaluation, "usefulRefs", "accepted_refs", "acceptedRefs");
        List<String> rejectedRefs = refs(evaluation, "rejectedRefs", "rejected_refs", "rejectedRefs");
        double relevance = scoreValue(firstPresent(evaluation, "relevance", "relevanceScore"));
        double answerability = scoreValue(firstPresent(evaluation, "answerability", "answerabilityScore"));
        String usefulness = usefulnessValue(firstPresent(evaluation, "usefulness", "utility"));
        double baseWeight = round(clamp(relevance * answerability));
        String type = executionLock != null && executionLock.locked() ? "HARD" : usefulness.equals("LOW") ? "NONE" : "SOFT";

        List<LockNode> locks = new ArrayList<>();
        if (!"NONE".equals(type) && (!acceptedRefs.isEmpty() || !rejectedRefs.isEmpty() || baseWeight > 0.0)) {
            locks.add(new LockNode(
                "L1",
                baseWeight,
                type,
                acceptedRefs,
                stepId,
                relevance,
                answerability,
                usefulness
            ));
        }

        List<Conflict> conflicts = conflicts(locks, rejectedRefs, baseWeight);
        Propagation propagation = propagation(locks, conflicts);
        DagFreeze dagFreeze = freeze(locks, toolName, relevance, answerability);
        return new EvidenceLockGraph(LOCK_GRAPH_VERSION, locks, conflicts, propagation, dagFreeze);
    }

    public Map<String, Object> toMetadata() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("lockGraphVersion", lockGraphVersion);
        value.put("locks", locks.stream().map(LockNode::toMetadata).toList());
        value.put("conflicts", conflicts.stream().map(Conflict::toMetadata).toList());
        value.put("propagation", propagation.toMetadata());
        value.put("dagFreeze", dagFreeze.toMetadata());
        return value;
    }

    private static List<Conflict> conflicts(List<LockNode> locks, List<String> rejectedRefs, double baseWeight) {
        if (locks == null || locks.isEmpty() || rejectedRefs == null || rejectedRefs.isEmpty()) {
            return List.of();
        }
        LockNode lock = locks.get(0);
        double severity = round(clamp(Math.max(0.35, baseWeight * 0.72)));
        return List.of(new Conflict(
            lock.lockId(),
            "REJECTED_REFS",
            "ASPECT_MISMATCH",
            severity,
            severity > 0.8 ? "suppress_rejected_refs" : "merge_as_context_only"
        ));
    }

    private static Propagation propagation(List<LockNode> locks, List<Conflict> conflicts) {
        Map<String, Double> nodeWeights = new LinkedHashMap<>();
        Map<String, List<String>> nodeLocks = new LinkedHashMap<>();
        Map<String, Double> claimWeights = new LinkedHashMap<>();
        Set<String> suppressed = new LinkedHashSet<>();
        for (LockNode lock : locks == null ? List.<LockNode>of() : locks) {
            double penalty = conflicts == null ? 0.0 : conflicts.stream()
                .filter(conflict -> lock.lockId().equals(conflict.lockA()) || lock.lockId().equals(conflict.lockB()))
                .mapToDouble(conflict -> conflict.severity() * Math.min(1.0, lock.weight()))
                .max()
                .orElse(0.0);
            double finalWeight = round(clamp(lock.weight() * lock.relevance() * (1.0 - Math.min(0.85, penalty))));
            for (String ref : lock.refs()) {
                nodeWeights.put(ref, finalWeight);
                nodeLocks.computeIfAbsent(ref, ignored -> new ArrayList<>()).add(lock.lockId());
                claimWeights.put(ref, round(clamp(finalWeight + lock.answerability() * 0.25)));
            }
            if (penalty > 0.8) {
                suppressed.add(lock.lockId());
            }
        }
        return new Propagation(nodeWeights, nodeLocks, claimWeights, List.copyOf(suppressed));
    }

    private static DagFreeze freeze(List<LockNode> locks, String toolName, double relevance, double answerability) {
        boolean hardLock = locks != null && locks.stream().anyMatch(lock -> "HARD".equalsIgnoreCase(lock.type()));
        double freezeScore = round(clamp(answerability * relevance * (hardLock ? 1.0 : 0.0)));
        if (freezeScore > 0.85) {
            return new DagFreeze(
                "FULLY_FROZEN",
                freezeScore,
                List.of("re-run retrieval", "replan steps", "modify edges", "add nodes"),
                List.of("claim_assembly", "explanation_generation", "final_answer"),
                firstNonBlank(toolName, "").isBlank() ? List.of() : List.of(toolName)
            );
        }
        if (hardLock) {
            return new DagFreeze(
                "PARTIALLY_FROZEN",
                freezeScore,
                List.of("re-run locked step"),
                List.of("claim_assembly", "final_answer"),
                firstNonBlank(toolName, "").isBlank() ? List.of() : List.of(toolName)
            );
        }
        return DagFreeze.unfrozen();
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

    private static List<String> refs(Map<String, Object> values, String... keys) {
        Set<String> refs = new LinkedHashSet<>();
        if (values != null && keys != null) {
            for (String key : keys) {
                refs.addAll(stringList(values.get(key)));
            }
        }
        return List.copyOf(refs);
    }

    private static List<String> stringList(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                .map(EvidenceLockGraph::stringValue)
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

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
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

    public record LockNode(
        String lockId,
        double weight,
        String type,
        List<String> refs,
        Integer sourceStepId,
        double relevance,
        double answerability,
        String usefulness
    ) {

        public LockNode {
            lockId = lockId == null || lockId.isBlank() ? "L1" : lockId;
            weight = round(clamp(weight));
            type = type == null || type.isBlank() ? "SOFT" : type;
            refs = refs == null ? List.of() : List.copyOf(refs);
            relevance = round(clamp(relevance));
            answerability = round(clamp(answerability));
            usefulness = usefulness == null || usefulness.isBlank() ? "LOW" : usefulness;
        }

        public Map<String, Object> toMetadata() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("lockId", lockId);
            value.put("weight", weight);
            value.put("type", type);
            value.put("refs", refs);
            value.put("sourceStepId", sourceStepId);
            value.put("relevance", relevance);
            value.put("answerability", answerability);
            value.put("usefulness", usefulness);
            return value;
        }
    }

    public record Conflict(
        String lockA,
        String lockB,
        String conflictType,
        double severity,
        String resolution
    ) {

        public Conflict {
            lockA = lockA == null ? "" : lockA;
            lockB = lockB == null ? "" : lockB;
            conflictType = conflictType == null || conflictType.isBlank() ? "ASPECT_MISMATCH" : conflictType;
            severity = round(clamp(severity));
            resolution = resolution == null ? "" : resolution;
        }

        public Map<String, Object> toMetadata() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("lockA", lockA);
            value.put("lockB", lockB);
            value.put("conflictType", conflictType);
            value.put("severity", severity);
            value.put("resolution", resolution);
            return value;
        }
    }

    public record Propagation(
        Map<String, Double> nodeWeights,
        Map<String, List<String>> nodeLocks,
        Map<String, Double> claimWeights,
        List<String> suppressedLockIds
    ) {

        public Propagation {
            nodeWeights = nodeWeights == null ? Map.of() : new LinkedHashMap<>(nodeWeights);
            nodeLocks = nodeLocks == null ? Map.of() : new LinkedHashMap<>(nodeLocks);
            claimWeights = claimWeights == null ? Map.of() : new LinkedHashMap<>(claimWeights);
            suppressedLockIds = suppressedLockIds == null ? List.of() : List.copyOf(suppressedLockIds);
        }

        public static Propagation empty() {
            return new Propagation(Map.of(), Map.of(), Map.of(), List.of());
        }

        public Map<String, Object> toMetadata() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("nodeWeights", nodeWeights);
            value.put("nodeLocks", nodeLocks);
            value.put("claimWeights", claimWeights);
            value.put("suppressedLockIds", suppressedLockIds);
            return value;
        }
    }

    public record DagFreeze(
        String status,
        double freezeScore,
        List<String> frozenActions,
        List<String> allowedActions,
        List<String> blockedTools
    ) {

        public DagFreeze {
            status = status == null || status.isBlank() ? "UNFROZEN" : status;
            freezeScore = round(clamp(freezeScore));
            frozenActions = frozenActions == null ? List.of() : List.copyOf(frozenActions);
            allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
            blockedTools = blockedTools == null ? List.of() : List.copyOf(blockedTools);
        }

        public static DagFreeze unfrozen() {
            return new DagFreeze("UNFROZEN", 0.0, List.of(), List.of(), List.of());
        }

        public Map<String, Object> toMetadata() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("status", status);
            value.put("freezeScore", freezeScore);
            value.put("frozenActions", frozenActions);
            value.put("allowedActions", allowedActions);
            value.put("blockedTools", blockedTools);
            return value;
        }
    }
}
