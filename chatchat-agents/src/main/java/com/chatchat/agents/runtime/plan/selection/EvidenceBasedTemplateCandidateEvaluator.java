package com.chatchat.agents.runtime.plan.selection;

import com.chatchat.common.knowledge.template.BusinessAnalysisIntent;
import com.chatchat.common.knowledge.template.TemplateAnalysisRole;
import com.chatchat.common.knowledge.template.TemplateMatchAnalysis;
import com.chatchat.common.knowledge.template.TemplateRelationship;
import com.chatchat.common.knowledge.template.TemplateRequirementMatchEvaluation;

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
public final class EvidenceBasedTemplateCandidateEvaluator {

    private static final SemanticCandidateAdmissionPolicy ADMISSION_POLICY =
        new SemanticCandidateAdmissionPolicy();

    public Evaluation evaluate(Object output, Map<String, Object> reviewMetadata) {
        Map<String, Object> metadata = reviewMetadata == null ? Map.of() : reviewMetadata;
        List<String> selectedIds = strings(metadata.get("selectedTemplateIds"));
        List<String> rejectedIds = strings(metadata.get("rejectedTemplateIds"));
        List<Map<String, Object>> evaluations = maps(metadata.get("templateEvaluations"));
        boolean reviewerUnavailable = Boolean.TRUE.equals(metadata.get("toolResultReviewUnavailable"))
            || Boolean.TRUE.equals(metadata.get("toolResultReviewSkipped"));
        if (reviewerUnavailable) {
            return Evaluation.notApplied(output,
                "business-template screening requires the original question and cumulative analysis context");
        }
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
        Projection projection = project(output, selectedIds, rejectedIds, evaluations, 0);
        if (!projection.applied()) {
            return Evaluation.notApplied(output,
                "model-selected template ids were not present in the authorized MCP candidate set");
        }
        List<Map<String, Object>> reviewedInvocations = reviewedInvocations(
            metadata.get("nextActions"), projection.selectedIds());
        Object invocationProjectedOutput = reviewedInvocations.isEmpty()
            ? projection.output()
            : attachReviewedInvocations(projection.output(), reviewedInvocations, 0);
        Map<String, Object> requirementMatch = requirementMatch(
            metadata, projection.selectedIds(), rejectedIds, evaluations,
            "RUNTIME_EVIDENCE_MODEL_REVIEW");
        Object projectedOutput = requirementMatch.isEmpty()
            ? invocationProjectedOutput
            : attachRequirementMatch(invocationProjectedOutput, requirementMatch, 0);
        return new Evaluation(
            projectedOutput,
            projection.originalCount(),
            projection.selectedCount(),
            projection.selectedIds(),
            rejectedIds,
            evaluations,
            requirementMatch,
            true,
            "Runtime projected evidence-reviewed templates before dependency binding and execution."
        );
    }

    private Map<String, Object> requirementMatch(Map<String, Object> metadata,
                                                 List<String> selectedIds,
                                                 List<String> rejectedIds,
                                                 List<Map<String, Object>> evaluations,
                                                 String authority) {
        String question = text(metadata.get("originalUserQuestion"));
        if (question == null || selectedIds == null || selectedIds.isEmpty()) return Map.of();
        Map<String, Object> context = metadata.get("templateRequirementAnalysisContext") instanceof Map<?, ?> raw
            ? cast(raw) : Map.of();
        List<TemplateRequirementMatchEvaluation> typedEvaluations = evaluations.stream()
            .map(this::typedEvaluation)
            .filter(Objects::nonNull)
            .toList();
        List<TemplateRequirementMatchEvaluation> completeEvaluations = completeEvaluations(
            typedEvaluations, selectedIds, rejectedIds);
        return new TemplateMatchAnalysis(
            TemplateMatchAnalysis.SCHEMA_VERSION,
            question,
            context,
            completeEvaluations.stream().map(TemplateRequirementMatchEvaluation::businessGroup)
                .filter(Objects::nonNull).distinct().toList(),
            businessIntent(metadata.get("businessAnalysisIntent"), question, completeEvaluations),
            completeEvaluations,
            relationships(metadata.get("templateRelationships"), selectedIds),
            text(metadata.get("toolResultReviewReason")),
            authority
        ).toMap();
    }

    private List<TemplateRequirementMatchEvaluation> completeEvaluations(
        List<TemplateRequirementMatchEvaluation> evaluations,
        List<String> selectedIds,
        List<String> rejectedIds
    ) {
        Set<String> selected = selectedIds.stream().map(this::normalize)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, TemplateRequirementMatchEvaluation> values = new LinkedHashMap<>();
        evaluations.forEach(item -> {
            boolean admitted = selected.contains(normalize(item.templateId()));
            TemplateAnalysisRole role = admitted
                ? (item.analysisRole() == TemplateAnalysisRole.IRRELEVANT
                    ? TemplateAnalysisRole.CONTEXT : item.analysisRole())
                : TemplateAnalysisRole.IRRELEVANT;
            values.put(normalize(item.templateId()), copyDecision(
                item, admitted ? "ACCEPT" : "REJECT", role));
        });
        selectedIds.forEach(id -> values.putIfAbsent(normalize(id), defaultEvaluation(
            id, "ACCEPT", TemplateAnalysisRole.CONTEXT, "Selected by context-aware reviewer")));
        rejectedIds.forEach(id -> values.putIfAbsent(normalize(id), defaultEvaluation(
            id, "REJECT", TemplateAnalysisRole.IRRELEVANT, "Excluded by context-aware reviewer")));
        return List.copyOf(values.values());
    }

    private TemplateRequirementMatchEvaluation copyDecision(
        TemplateRequirementMatchEvaluation item, String decision, TemplateAnalysisRole role
    ) {
        return new TemplateRequirementMatchEvaluation(
            item.templateId(), item.businessGroup(), item.relevance(), item.evidenceFit(),
            item.parameterReadiness(), item.totalScore(), decision, item.relevanceLevel(), role,
            item.reasons(), item.missingParameters(), item.matchedQuestionAspects(),
            item.relationshipHints());
    }

    private TemplateRequirementMatchEvaluation defaultEvaluation(
        String id, String decision, TemplateAnalysisRole role, String reason
    ) {
        return new TemplateRequirementMatchEvaluation(
            id, null, 0.0, 0.0, 0.0, 0.0, decision, null, role,
            List.of(reason), List.of(), List.of(), List.of());
    }

    private BusinessAnalysisIntent businessIntent(
        Object value, String originalQuestion,
        List<TemplateRequirementMatchEvaluation> evaluations
    ) {
        Map<String, Object> intent = value instanceof Map<?, ?> raw ? cast(raw) : Map.of();
        List<String> derivedFocus = evaluations == null ? List.of() : evaluations.stream()
            .filter(item -> item.analysisRole() != TemplateAnalysisRole.IRRELEVANT)
            .flatMap(item -> item.matchedQuestionAspects().stream()).distinct().toList();
        List<String> derivedRelationships = evaluations == null ? List.of() : evaluations.stream()
            .filter(item -> item.analysisRole() != TemplateAnalysisRole.IRRELEVANT)
            .flatMap(item -> item.relationshipHints().stream()).distinct().toList();
        return new BusinessAnalysisIntent(
            firstText(text(first(intent, "businessGoal", "business_goal")), originalQuestion),
            text(first(intent, "analysisSubject", "analysis_subject")),
            strings(first(intent, "coreEntities", "core_entities")),
            strings(first(intent, "metrics", "businessMetrics", "business_metrics")),
            strings(first(intent, "dimensions", "analysisDimensions", "analysis_dimensions")),
            firstNonEmpty(strings(first(intent, "analysisFocus", "analysis_focus")), derivedFocus),
            text(first(intent, "timeScope", "time_scope")),
            firstNonEmpty(strings(first(intent, "expectedRelationships", "expected_relationships")),
                derivedRelationships)
        );
    }

    private List<String> firstNonEmpty(List<String> preferred, List<String> fallback) {
        return preferred == null || preferred.isEmpty()
            ? (fallback == null ? List.of() : fallback) : preferred;
    }

    private String firstText(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private List<TemplateRelationship> relationships(Object value, List<String> selectedIds) {
        Set<String> admitted = selectedIds == null ? Set.of() : selectedIds.stream()
            .map(this::normalize).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return maps(value).stream().map(item -> {
            String from = text(first(item, "fromTemplateId", "from_template_id", "from"));
            String to = text(first(item, "toTemplateId", "to_template_id", "to"));
            if (from == null || to == null) return null;
            return new TemplateRelationship(
                from, to,
                text(first(item, "relationType", "relation_type", "type")),
                text(first(item, "description", "reason"))
            );
        }).filter(Objects::nonNull)
            .filter(relation -> admitted.contains(normalize(relation.fromTemplateId()))
                && admitted.contains(normalize(relation.toTemplateId())))
            .toList();
    }

    private TemplateRequirementMatchEvaluation typedEvaluation(Map<String, Object> value) {
        String id = text(first(value, "templateId", "template_id"));
        if (id == null) return null;
        return new TemplateRequirementMatchEvaluation(
            id,
            text(first(value, "businessGroup", "business_group")),
            score(first(value, "relevance")),
            score(first(value, "evidenceFit", "evidence_fit")),
            score(first(value, "parameterReadiness", "parameter_readiness")),
            evaluationScore(value),
            text(first(value, "decision", "verdict")),
            text(first(value, "relevanceLevel", "relevance_level")),
            TemplateAnalysisRole.from(first(value, "analysisRole", "analysis_role")),
            strings(first(value, "reasons", "reason")),
            strings(first(value, "missingParameters", "missing_parameters")),
            strings(first(value, "matchedQuestionAspects", "matched_question_aspects")),
            strings(first(value, "relationshipHints", "relationship_hints"))
        );
    }

    private double score(Object value) {
        if (value instanceof Number number) return normalizeScore(number.doubleValue());
        try {
            return value == null ? 0.0 : normalizeScore(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    @SuppressWarnings("unchecked")
    private Object attachRequirementMatch(Object value, Map<String, Object> match, int depth) {
        if (value == null || depth > 8) return value;
        if (value instanceof List<?> list) {
            return list.stream().map(item -> attachRequirementMatch(item, match, depth + 1)).toList();
        }
        if (!(value instanceof Map<?, ?> raw)) return value;
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) raw);
        if (map.get("runtimeTemplateSelection") instanceof Map<?, ?> rawSelection) {
            Map<String, Object> selection = new LinkedHashMap<>((Map<String, Object>) rawSelection);
            selection.put(TemplateMatchAnalysis.ANALYSIS_CONTEXT_KEY, match);
            map.put("runtimeTemplateSelection", Map.copyOf(selection));
            return Map.copyOf(map);
        }
        for (String key : List.of(
            "structuredContent", "structured_content", "data", "result", "payload", "body", "output",
            "routingProjection", "coverage", "preview")) {
            if (map.get(key) != null) {
                map.put(key, attachRequirementMatch(map.get(key), match, depth + 1));
            }
        }
        return Map.copyOf(map);
    }

    /**
     * Carries the reviewer's per-template invocation decisions with the Runtime-owned
     * admission projection.  Planner batch calls are authored before discovery and
     * therefore cannot reliably contain template ids; downstream compilation may use
     * these decisions only after re-validating them against the published contracts.
     */
    @SuppressWarnings("unchecked")
    private Object attachReviewedInvocations(Object value,
                                             List<Map<String, Object>> invocations,
                                             int depth) {
        if (value == null || depth > 8) return value;
        if (value instanceof List<?> list) {
            return list.stream()
                .map(item -> attachReviewedInvocations(item, invocations, depth + 1))
                .toList();
        }
        if (!(value instanceof Map<?, ?> raw)) return value;
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) raw);
        if (map.get("runtimeTemplateSelection") instanceof Map<?, ?> rawSelection) {
            Map<String, Object> selection = new LinkedHashMap<>((Map<String, Object>) rawSelection);
            selection.put("reviewedInvocations", List.copyOf(invocations));
            map.put("runtimeTemplateSelection", Map.copyOf(selection));
            return Map.copyOf(map);
        }
        for (String key : List.of(
            "structuredContent", "structured_content", "data", "result", "payload", "body", "output",
            "routingProjection", "coverage", "preview")) {
            if (map.get(key) != null) {
                map.put(key, attachReviewedInvocations(map.get(key), invocations, depth + 1));
            }
        }
        return Map.copyOf(map);
    }

    private List<Map<String, Object>> reviewedInvocations(Object value,
                                                           List<String> selectedIds) {
        Set<String> admitted = selectedIds == null ? Set.of() : selectedIds.stream()
            .map(this::normalize)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (admitted.isEmpty()) return List.of();
        Map<String, Map<String, Object>> byTemplate = new LinkedHashMap<>();
        Set<String> duplicateIds = new LinkedHashSet<>();
        for (Map<String, Object> action : maps(value)) {
            Map<String, Object> changes = action.get("input_changes") instanceof Map<?, ?> raw
                ? cast(raw)
                : action.get("inputChanges") instanceof Map<?, ?> raw ? cast(raw) : Map.of();
            String templateId = text(first(changes,
                "templateId", "template_id", "template", "commandTemplate", "command_template"));
            String normalizedId = normalize(templateId);
            if (!admitted.contains(normalizedId)) continue;
            Map<String, Object> invocation = new LinkedHashMap<>();
            invocation.put("templateId", templateId);
            String tool = text(first(action, "tool", "toolName", "tool_name"));
            if (tool != null) invocation.put("toolName", tool);
            String intent = text(first(action, "intent", "purpose"));
            if (intent != null) invocation.put("intent", intent);
            invocation.put("arguments", java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(changes)));
            if (byTemplate.putIfAbsent(normalizedId, java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(invocation))) != null) {
                duplicateIds.add(normalizedId);
            }
        }
        duplicateIds.forEach(byTemplate::remove);
        return List.copyOf(byTemplate.values());
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
        String candidateField = map.get("templates") instanceof List<?> ? "templates"
            : map.get("candidates") instanceof List<?> ? "candidates" : null;
        if (candidateField != null && map.get(candidateField) instanceof List<?> templates) {
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
            SemanticCandidateAdmissionPolicy.Decision admission = ADMISSION_POLICY.decide(
                byId.values().stream().map(this::templateId).toList(),
                selectedIds, rejectedIds, false);
            if (!admission.decided()) {
                return new Projection(output, templates.size(), 0, List.of(), false);
            }
            List<Map<String, Object>> selected = new ArrayList<>();
            for (String id : admission.selectedIds()) {
                Map<String, Object> template = id == null ? null : byId.get(normalize(id));
                if (template != null && !selected.contains(template)) {
                    selected.add(template);
                }
            }
            if (selected.isEmpty()) {
                map.put(candidateField, List.of());
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
            map.put(candidateField, List.copyOf(selected));
            map.put("returnedCount", selected.size());
            map.put("runtimeTemplateSelection", Map.of(
                "schemaVersion", "runtime_template_selection.v2",
                "candidateCount", templates.size(),
                "selectedCount", selected.size(),
                "selectedTemplateIds", projectedIds,
                "rejectedTemplateIds", rejectedIds,
                "candidateEvaluations", evaluations,
                "selectionAuthority", admission.authority(),
                "mcpScoresAreWeakPriors", true
            ));
            return new Projection(map, templates.size(), selected.size(), projectedIds, true);
        }
        for (String key : List.of(
            "structuredContent", "structured_content", "data", "result", "payload", "body", "output",
            "routingProjection", "coverage", "preview"
        )) {
            Projection nested = project(
                map.get(key), selectedIds, rejectedIds, evaluations, depth + 1);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Map<?, ?> value) {
        return new LinkedHashMap<>((Map<String, Object>) value);
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

    public record Evaluation(
        Object output,
        int candidateCount,
        int selectedCount,
        List<String> selectedIds,
        List<String> rejectedIds,
        List<Map<String, Object>> candidateEvaluations,
        Map<String, Object> templateMatchAnalysis,
        boolean applied,
        String reason
    ) {
        static Evaluation notApplied(Object output, String reason) {
            return new Evaluation(output, 0, 0, List.of(), List.of(), List.of(), Map.of(), false, reason);
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
