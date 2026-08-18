package com.chatchat.agents.runtime.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Projects model-reviewed asset ids onto the authorized MCP routing candidates. */
public final class EvidenceBasedAssetCandidateEvaluator {

    private static final SemanticCandidateAdmissionPolicy ADMISSION_POLICY =
        new SemanticCandidateAdmissionPolicy();

    public Evaluation evaluate(Object output, Map<String, Object> reviewMetadata) {
        List<String> selected = strings(reviewMetadata == null ? null : reviewMetadata.get("selectedAssetIds"));
        List<String> rejected = strings(reviewMetadata == null ? null : reviewMetadata.get("rejectedAssetIds"));
        List<Map<String, Object>> evaluations = maps(reviewMetadata == null ? null : reviewMetadata.get("assetEvaluations"));
        boolean reviewerUnavailable = reviewMetadata != null
            && (Boolean.TRUE.equals(reviewMetadata.get("toolResultReviewUnavailable"))
            || Boolean.TRUE.equals(reviewMetadata.get("toolResultReviewSkipped")));
        Projection projection = project(output, selected, rejected, evaluations, reviewerUnavailable, 0);
        if (!projection.applied()) {
            return new Evaluation(output, 0, 0, List.of(), evaluations, false,
                "No evidence-reviewed asset selection could be projected onto the authorized candidate set.");
        }
        return new Evaluation(projection.output(), projection.candidateCount(), projection.selectedIds().size(),
            projection.selectedIds(), evaluations, true,
            "Runtime projected evidence-reviewed assets before dependent routing.");
    }

    @SuppressWarnings("unchecked")
    private Projection project(Object output, List<String> selectedIds, List<String> rejectedIds,
                               List<Map<String, Object>> evaluations,
                               boolean reviewerUnavailable,
                               int depth) {
        if (!(output instanceof Map<?, ?> raw) || depth > 7) return Projection.none(output);
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) raw);
        if (map.get("assets") instanceof List<?> candidates) {
            List<Map<String, Object>> assets = candidates.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) new LinkedHashMap<>((Map<String, Object>) item)).toList();
            List<String> candidateIds = assets.stream().map(this::identity).toList();
            SemanticCandidateAdmissionPolicy.Decision admission = ADMISSION_POLICY.decide(
                candidateIds, selectedIds, rejectedIds, reviewerUnavailable);
            if (!admission.decided()) return Projection.none(output);
            Set<String> selected = admission.selectedIds().stream()
                .map(this::normalize)
                .collect(Collectors.toSet());
            List<Map<String, Object>> projected = assets.stream()
                .filter(item -> selected.contains(normalize(identity(item))))
                .toList();
            // Preserve the asset adapter's existing external contract: rejecting all
            // candidates is represented as no projection, while the generic policy
            // still records it as a completed admission decision internally.
            if (projected.isEmpty()) return Projection.none(output);
            List<String> ids = projected.stream().map(this::identity).toList();
            map.put("assets", projected);
            map.put("returnedCount", projected.size());
            map.put("runtimeAssetSelection", Map.of(
                "schemaVersion", "runtime_asset_selection.v1",
                "candidateCount", assets.size(),
                "selectedCount", projected.size(),
                "selectedAssetIds", ids,
                "rejectedAssetIds", rejectedIds,
                "candidateEvaluations", evaluations,
                "selectionAuthority", admission.authority(),
                "candidateIsObservation", false
            ));
            return new Projection(map, assets.size(), ids, true);
        }
        for (String key : List.of("structuredContent", "structured_content", "data", "result", "payload",
            "body", "output", "routingProjection")) {
            Projection nested = project(
                map.get(key), selectedIds, rejectedIds, evaluations, reviewerUnavailable, depth + 1);
            if (nested.applied()) {
                map.put(key, nested.output());
                return new Projection(map, nested.candidateCount(), nested.selectedIds(), true);
            }
        }
        return Projection.none(output);
    }

    private String identity(Map<String, Object> candidate) {
        Map<String, Object> asset = candidate.get("asset") instanceof Map<?, ?> raw
            ? cast(raw) : candidate;
        for (String key : List.of("id", "assetId", "name", "assetName", "toolName")) {
            Object value = asset.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Map<?, ?> value) {
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<String> result = new ArrayList<>();
        iterable.forEach(item -> { if (item != null) result.add(String.valueOf(item)); });
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : iterable) if (item instanceof Map<?, ?> raw) result.add(cast(raw));
        return List.copyOf(result);
    }

    public record Evaluation(Object output, int candidateCount, int selectedCount, List<String> selectedIds,
                             List<Map<String, Object>> candidateEvaluations, boolean applied, String reason) {}

    private record Projection(Object output, int candidateCount, List<String> selectedIds, boolean applied) {
        static Projection none(Object output) { return new Projection(output, 0, List.of(), false); }
    }
}
