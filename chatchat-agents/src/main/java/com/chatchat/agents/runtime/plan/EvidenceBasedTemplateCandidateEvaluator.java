package com.chatchat.agents.runtime.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime-owned semantic admission layer for high-recall MCP template candidates.
 *
 * <p>MCP ranking fields are weak retrieval priors. Only ids returned by MCP may be
 * selected, and model decisions are projected onto the candidate list before any
 * downstream template binding is resolved.</p>
 */
final class EvidenceBasedTemplateCandidateEvaluator {

    Evaluation evaluate(Object output, Map<String, Object> reviewMetadata) {
        Map<String, Object> metadata = reviewMetadata == null ? Map.of() : reviewMetadata;
        List<String> selectedIds = strings(metadata.get("selectedTemplateIds"));
        List<String> rejectedIds = strings(metadata.get("rejectedTemplateIds"));
        List<Map<String, Object>> evaluations = maps(metadata.get("templateEvaluations"));
        if (selectedIds.isEmpty()) {
            selectedIds = evaluations.stream()
                .filter(this::acceptedEvaluation)
                .sorted(Comparator
                    .comparingDouble(this::evaluationScore)
                    .reversed()
                    .thenComparing(
                        item -> text(first(item, "templateId", "template_id")),
                        Comparator.nullsLast(String::compareTo)
                    ))
                .map(item -> text(first(item, "templateId", "template_id")))
                .filter(Objects::nonNull)
                .toList();
        }
        if (selectedIds.isEmpty() && rejectedIds.isEmpty()) {
            return Evaluation.notApplied(output,
                "model review returned no candidate admission decision");
        }
        Projection projection = project(output, selectedIds, rejectedIds, evaluations, 0);
        if (!projection.applied()) {
            return Evaluation.notApplied(output,
                "model-selected template ids were not present in the authorized MCP candidate set");
        }
        return new Evaluation(
            projection.output(),
            projection.originalCount(),
            projection.selectedCount(),
            projection.selectedIds(),
            rejectedIds,
            evaluations,
            true,
            "Runtime projected evidence-reviewed templates before dependency binding and execution."
        );
    }

    @SuppressWarnings("unchecked")
    private Projection project(Object output,
                               List<String> selectedIds,
                               List<String> rejectedIds,
                               List<Map<String, Object>> evaluations,
                               int depth) {
        if (depth > 8) {
            return Projection.notApplied(output);
        }
        if (output instanceof List<?> list) {
            List<Object> projectedItems = new ArrayList<>();
            int originalCount = 0;
            int selectedCount = 0;
            List<String> projectedIds = new ArrayList<>();
            boolean applied = false;
            for (Object item : list) {
                Projection nested = project(item, selectedIds, rejectedIds, evaluations, depth + 1);
                projectedItems.add(nested.output());
                if (nested.applied()) {
                    applied = true;
                    originalCount += nested.originalCount();
                    selectedCount += nested.selectedCount();
                    projectedIds.addAll(nested.selectedIds());
                }
            }
            return applied
                ? new Projection(List.copyOf(projectedItems), originalCount, selectedCount,
                    List.copyOf(new LinkedHashSet<>(projectedIds)), true)
                : Projection.notApplied(output);
        }
        if (!(output instanceof Map<?, ?> raw)) {
            return Projection.notApplied(output);
        }
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) raw);
        if (map.get("templates") instanceof List<?> templates) {
            Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
            for (Object item : templates) {
                if (!(item instanceof Map<?, ?> templateRaw)) {
                    continue;
                }
                Map<String, Object> template = new LinkedHashMap<>((Map<String, Object>) templateRaw);
                String id = templateId(template);
                if (id != null) {
                    byId.putIfAbsent(normalize(id), template);
                }
            }
            Set<String> rejected = rejectedIds.stream()
                .filter(Objects::nonNull)
                .map(this::normalize)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<Map<String, Object>> selected = new ArrayList<>();
            if (!selectedIds.isEmpty()) {
                for (String id : selectedIds) {
                    Map<String, Object> template = id == null ? null : byId.get(normalize(id));
                    if (template != null && !selected.contains(template)) {
                        selected.add(template);
                    }
                }
            } else {
                byId.forEach((id, template) -> {
                    if (!rejected.contains(id)) {
                        selected.add(template);
                    }
                });
            }
            if (selected.isEmpty()) {
                boolean reviewedAndRejectedAll = !byId.isEmpty()
                    && byId.keySet().stream().allMatch(rejected::contains);
                if (!reviewedAndRejectedAll) {
                    return new Projection(output, templates.size(), 0, List.of(), false);
                }
                map.put("templates", List.of());
                map.put("returnedCount", 0);
                map.put("runtimeTemplateSelection", Map.of(
                    "schemaVersion", "runtime_template_selection.v2",
                    "candidateCount", templates.size(),
                    "selectedCount", 0,
                    "selectedTemplateIds", List.of(),
                    "rejectedTemplateIds", rejectedIds,
                    "candidateEvaluations", evaluations,
                    "selectionAuthority", "runtime_evidence_model_review",
                    "mcpScoresAreWeakPriors", true
                ));
                return new Projection(map, templates.size(), 0, List.of(), true);
            }
            List<String> projectedIds = selected.stream()
                .map(this::templateId)
                .filter(Objects::nonNull)
                .toList();
            map.put("templates", List.copyOf(selected));
            map.put("returnedCount", selected.size());
            map.put("runtimeTemplateSelection", Map.of(
                "schemaVersion", "runtime_template_selection.v2",
                "candidateCount", templates.size(),
                "selectedCount", selected.size(),
                "selectedTemplateIds", projectedIds,
                "rejectedTemplateIds", rejectedIds,
                "candidateEvaluations", evaluations,
                "selectionAuthority", "runtime_evidence_model_review",
                "mcpScoresAreWeakPriors", true
            ));
            return new Projection(map, templates.size(), selected.size(), projectedIds, true);
        }
        for (String key : List.of(
            "structuredContent", "structured_content", "data", "result", "payload", "body", "output",
            "routingProjection", "coverage"
        )) {
            Projection nested = project(map.get(key), selectedIds, rejectedIds, evaluations, depth + 1);
            if (nested.applied()) {
                map.put(key, nested.output());
                return new Projection(
                    map,
                    nested.originalCount(),
                    nested.selectedCount(),
                    nested.selectedIds(),
                    true
                );
            }
        }
        return Projection.notApplied(output);
    }

    private boolean acceptedEvaluation(Map<String, Object> evaluation) {
        String decision = text(first(evaluation, "decision", "verdict"));
        return ("accept".equalsIgnoreCase(decision) || "selected".equalsIgnoreCase(decision))
            && evaluationScore(evaluation) >= 0.6;
    }

    private double evaluationScore(Map<String, Object> evaluation) {
        Object value = first(evaluation, "totalScore", "total_score", "score", "relevance");
        if (value instanceof Number number) {
            return normalizeScore(number.doubleValue());
        }
        try {
            return value == null ? 0.0 : normalizeScore(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private double normalizeScore(double score) {
        return score > 1.0 ? Math.min(1.0, score / 100.0) : Math.max(0.0, score);
    }

    private String templateId(Map<String, Object> template) {
        return text(first(template, "templateId", "template_id", "id", "code", "template"));
    }

    private Object first(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            if (map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            String text = text(value);
            return text == null ? List.of() : List.of(text);
        }
        List<String> values = new ArrayList<>();
        for (Object item : iterable) {
            String text = text(item);
            if (text != null) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> map) {
                values.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return List.copyOf(values);
    }

    record Evaluation(
        Object output,
        int candidateCount,
        int selectedCount,
        List<String> selectedIds,
        List<String> rejectedIds,
        List<Map<String, Object>> candidateEvaluations,
        boolean applied,
        String reason
    ) {
        static Evaluation notApplied(Object output, String reason) {
            return new Evaluation(output, 0, 0, List.of(), List.of(), List.of(), false, reason);
        }
    }

    private record Projection(
        Object output,
        int originalCount,
        int selectedCount,
        List<String> selectedIds,
        boolean applied
    ) {
        static Projection notApplied(Object output) {
            return new Projection(output, 0, 0, List.of(), false);
        }
    }
}
