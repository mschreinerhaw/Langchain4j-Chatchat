package com.chatchat.agents.runtime.plan.selection;

import com.chatchat.agents.runtime.batch.ToolCallBatchSchema;
import com.chatchat.common.knowledge.template.matching.TemplateMatchAnalysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates model-selected identities against returned MCP candidates.
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
        List<String> deferredIds = strings(metadata.get("deferredTemplateIds"));
        List<Map<String, Object>> evaluations = maps(metadata.get("templateEvaluations"));
        boolean reviewerUnavailable = Boolean.TRUE.equals(metadata.get("toolResultReviewUnavailable"))
            || Boolean.TRUE.equals(metadata.get("toolResultReviewSkipped"));
        if (reviewerUnavailable && selectedIds.isEmpty()) {
            return notApplied(output,
                "business-template screening requires the original question and cumulative analysis context");
        }
        if (selectedIds.isEmpty() && !metadata.containsKey("selectedTemplateIds")) {
            selectedIds = evaluations.stream()
                .filter(this::acceptedEvaluation)
                .map(item -> text(first(item, "templateId", "template_id")))
                .filter(Objects::nonNull)
                .toList();
        }
        if (selectedIds.isEmpty() && !metadata.containsKey("selectedTemplateIds") && evaluations.isEmpty() && rejectedIds.isEmpty()) {
            return notApplied(output, "Template selection response omitted selectedTemplateIds.");
        }
        Set<String> returnedIds=candidateIds(output,0);
        if(selectedIds.stream().anyMatch(id->!returnedIds.contains(normalize(id))))
            return notApplied(output,"INVALID_TEMPLATE_ID: selection contains an ID not returned by MCP");
        Set<String> parameterBlocked = unresolvedParameterTemplateIds(
            evaluations, metadata.get("parameterProtocols"));
        // Keep model-selected identities. Parameter failures belong to individual
        // invocation preflight receipts, not to discovery or independent siblings.
        String supplyAuthority = Boolean.TRUE.equals(metadata.get("publishedDataSupply"))
            ? "runtime_published_data_supply" : null;
        Projection projection = project(output, selectedIds, deferredIds, rejectedIds, evaluations, 0, supplyAuthority);
        if (!projection.applied()) {
            return notApplied(output,
                "model-selected template ids were not present in the authorized MCP candidate set");
        }
        Object parameterProtocols = metadata.get("parameterProtocols");
        List<Map<String, Object>> reviewedInvocations = parameterProtocolInvocations(
            parameterProtocols, projection.selectedIds());
        if (!(parameterProtocols instanceof Iterable<?>)) {
            reviewedInvocations = reviewedInvocations(
                metadata.get("nextActions"), projection.selectedIds());
        }
        Object invocationProjectedOutput = reviewedInvocations.isEmpty()
            ? projection.output()
            : attachReviewedInvocations(projection.output(), reviewedInvocations, 0);
        Map<String, Object> requirementMatch = requirementMatch(
            metadata, projection.selectedIds(), rejectedIds, evaluations,
            projection.output(), parameterBlocked, supplyAuthority == null
                ? "RUNTIME_EVIDENCE_MODEL_REVIEW" : "RUNTIME_PUBLISHED_DATA_SUPPLY");
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
            parameterBlocked.isEmpty()?"Runtime validated model selection identities and parameter contracts."
                : "TEMPLATE_PARAMETERS_REQUIRED: "+parameterBlocked
        );
    }

    private Evaluation notApplied(Object output,String reason) {
        return new Evaluation(output,candidateIds(output,0).size(),0,List.of(),List.of(),List.of(),Map.of(),false,reason);
    }

    private Set<String> candidateIds(Object output,int depth) {
        Set<String> ids=new LinkedHashSet<>();if(output==null||depth>8)return ids;
        if(output instanceof Iterable<?> items) {for(Object item:items)ids.addAll(candidateIds(item,depth+1));return ids;}
        if(!(output instanceof Map<?,?> raw))return ids;
        Map<String,Object> map=cast(raw);
        Object candidates=map.get("templates") instanceof Iterable<?>?map.get("templates"):map.get("candidates");
        if(candidates instanceof Iterable<?> items)for(Object item:items)if(item instanceof Map<?,?> candidate) {
            String id=templateId(cast(candidate));if(id!=null)ids.add(normalize(id));
        }
        for(String key:List.of("structuredContent","structured_content","data","result","payload","body","output","routingProjection","coverage","preview"))
            ids.addAll(candidateIds(map.get(key),depth+1));
        return ids;
    }

    private Map<String, Object> requirementMatch(Map<String, Object> metadata,
            List<String> selectedIds, List<String> rejectedIds, List<Map<String,Object>> evaluations,
            Object output, Set<String> parameterBlocked, String authority) {
        if(text(metadata.get("originalUserQuestion"))==null) return Map.of();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("schemaVersion",TemplateMatchAnalysis.SCHEMA_VERSION);
        result.put("objectType",TemplateMatchAnalysis.OBJECT_TYPE);result.put("event",TemplateMatchAnalysis.EVENT_TYPE);
        result.put("globalAnalysisContext",metadata.getOrDefault("templateRequirementAnalysisContext",Map.of()));
        result.put("userQuestion",metadata.get("originalUserQuestion"));
        result.put("selectionAuthority","MODEL");result.put("selectedTemplateIds",selectedIds);
        result.put("excludedTemplateIds",rejectedIds);result.put("candidateEvaluations",evaluations);
        Map<String,Map<String,Object>> byId=new LinkedHashMap<>();
        for(var evaluation:evaluations) {
            String id=text(first(evaluation,"templateId","template_id"));
            if(id!=null)byId.put(id,canonicalModelFields(evaluation));
        }
        result.put("templateMatches",selectedIds.stream().map(id->byId.getOrDefault(id,Map.of("templateId",id,"decision","ACCEPT"))).toList());
        result.put("excludedTemplates",rejectedIds.stream().map(id->byId.getOrDefault(id,Map.of("templateId",id,"decision","REJECT"))).toList());
        result.put("businessGroups",byId.values().stream().map(item->item.get("businessGroup")).filter(Objects::nonNull).distinct().toList());
        if(metadata.get("businessAnalysisIntent") instanceof Map<?,?> intent)result.put("analysisIntent",canonicalModelFields(cast(intent)));
        if(metadata.get("templateRelationships")!=null)result.put("templateRelationships",maps(metadata.get("templateRelationships")).stream().map(this::canonicalModelFields).toList());
        if(metadata.get("coverageDecision")!=null)result.put("coverageDecision",metadata.get("coverageDecision"));
        if(metadata.get("evidenceGaps")!=null)result.put("evidenceGaps",metadata.get("evidenceGaps"));
        result.put("pageRef",pageRef(output));
        result.put("retrievalOutcome",metadata.getOrDefault("retrievalOutcome",
            !parameterBlocked.isEmpty()?"PARAMS_UNRESOLVABLE":
            "NEED_NEXT_PAGE".equals(metadata.get("coverageDecision"))&&booleanIn(output,"hasMore")?"PAGE_EXHAUSTED_HAS_MORE":
            !selectedIds.isEmpty()?"SELECTED":textIn(output,"retrievalStopReason")!=null?"CAPABILITY_EXCEEDED":
            booleanIn(output,"hasMore")?"PAGE_EXHAUSTED_HAS_MORE":"MODEL_SELECTED_NONE"));
        return Map.copyOf(result);
    }

    private Map<String,Object> canonicalModelFields(Map<String,Object> fields) {
        Map<String,Object> result=new LinkedHashMap<>();
        fields.forEach((key,value)->result.put(java.util.regex.Pattern.compile("_([a-z])").matcher(key)
            .replaceAll(match->match.group(1).toUpperCase(Locale.ROOT)),value));
        return result;
    }

    private Map<String, Object> pageRef(Object output) {
        Map<String, Object> source = findMapContaining(output, "hasMore", 0);
        if (source.isEmpty()) return Map.of();
        Map<String, Object> ref = new LinkedHashMap<>();
        for (String key : List.of("nextCursor", "pageIndex", "hasMore", "pageFloorScore",
            "maxPages", "scannedCandidateCount", "maxCandidateCount", "policyVersion", "queryHash")) {
            if (source.get(key) != null) ref.put(key, source.get(key));
        }
        return Map.copyOf(ref);
    }

    private Set<String> unresolvedParameterTemplateIds(List<Map<String, Object>> evaluations,
                                                        Object rawProtocols) {
        Set<String> resolvedByProtocol = new LinkedHashSet<>();
        for (Map<String, Object> protocol : maps(rawProtocols)) {
            String id = text(first(protocol, "template_id", "templateId"));
            if (id != null && !protocol.containsKey("binding_error")) {
                resolvedByProtocol.add(normalize(id));
            }
        }
        Set<String> blocked = new LinkedHashSet<>();
        for (Map<String, Object> item : maps(rawProtocols)) {
            String id = text(first(item, "templateId", "template_id"));
            if (id != null && item.containsKey("binding_error") && !resolvedByProtocol.contains(normalize(id))) {
                blocked.add(normalize(id));
            }
        }
        return Set.copyOf(blocked);
    }

    private boolean booleanIn(Object value, String key) {
        Object found = findMapContaining(value, key, 0).get(key);
        return Boolean.TRUE.equals(found) || "true".equalsIgnoreCase(String.valueOf(found));
    }

    private String textIn(Object value, String key) {
        return text(findMapContaining(value, key, 0).get(key));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findMapContaining(Object value, String key, int depth) {
        if (value == null || depth > 8) return Map.of();
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = (Map<String, Object>) raw;
            if (map.containsKey(key)) return map;
            for (Object nested : map.values()) {
                Map<String, Object> found = findMapContaining(nested, key, depth + 1);
                if (!found.isEmpty()) return found;
            }
        } else if (value instanceof Iterable<?> values) {
            for (Object nested : values) {
                Map<String, Object> found = findMapContaining(nested, key, depth + 1);
                if (!found.isEmpty()) return found;
            }
        }
        return Map.of();
    }

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
            map.put("runtimeTemplateSelection", immutableNullableMap(selection));
            return immutableNullableMap(map);
        }
        for (String key : List.of(
            "structuredContent", "structured_content", "data", "result", "payload", "body", "output",
            "routingProjection", "coverage", "preview")) {
            if (map.get(key) != null) {
                map.put(key, attachRequirementMatch(map.get(key), match, depth + 1));
            }
        }
        return immutableNullableMap(map);
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
            map.put("runtimeTemplateSelection", immutableNullableMap(selection));
            return immutableNullableMap(map);
        }
        for (String key : List.of(
            "structuredContent", "structured_content", "data", "result", "payload", "body", "output",
            "routingProjection", "coverage", "preview")) {
            if (map.get(key) != null) {
                map.put(key, attachReviewedInvocations(map.get(key), invocations, depth + 1));
            }
        }
        return immutableNullableMap(map);
    }

    /**
     * MCP payloads may legitimately contain explicit JSON null values. Map.copyOf rejects
     * those values, so enrichment must retain them while still preventing mutation of the
     * reviewed runtime projection.
     */
    private Map<String, Object> immutableNullableMap(Map<String, Object> value) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(value));
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

    /** Converts reviewed parameter protocols into executor-neutral invocation intents.
     * The execution bridge re-verifies every evidence pointer and applies all omitted defaults. */
    private List<Map<String, Object>> parameterProtocolInvocations(Object value,
                                                                    List<String> selectedIds) {
        Set<String> admitted = selectedIds == null ? Set.of() : selectedIds.stream()
            .map(this::normalize)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (admitted.isEmpty() || !(value instanceof Iterable<?> protocols)) return List.of();
        List<Map<String, Object>> invocations = new ArrayList<>();
        Set<String> coveredTemplates = new LinkedHashSet<>();
        Set<String> bindingFingerprints = new LinkedHashSet<>();
        int ordinal = 0;
        for (Object raw : protocols) {
            if (invocations.size() >= ToolCallBatchSchema.DEFAULT_MAX_CALLS) break;
            if (!(raw instanceof Map<?, ?> rawProtocol)) continue;
            Map<String, Object> protocol = cast(rawProtocol);
            String templateId = text(first(protocol, "template_id", "templateId"));
            String normalizedId = normalize(templateId);
            if (!admitted.contains(normalizedId)) continue;
            String bindingId = text(first(protocol, "binding_id", "bindingId"));
            String fingerprint = normalizedId + "|" + (bindingId == null
                ? String.valueOf(protocol.get("arguments")) : bindingId);
            if (!bindingFingerprints.add(fingerprint)) continue;
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("templateId", templateId);
            arguments.put("parameterProtocol",
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(protocol)));
            Map<String, Object> invocation = new LinkedHashMap<>();
            invocation.put("templateId", templateId);
            invocation.put("bindingId", bindingId == null ? "binding-" + (++ordinal) : bindingId);
            invocation.put("arguments", Map.copyOf(arguments));
            invocations.add(Map.copyOf(invocation));
            coveredTemplates.add(normalizedId);
        }
        // A partial protocol set must never discard selected siblings whose defaults may be
        // sufficient. Fall back to the normal deterministic batch compiler in that case.
        return coveredTemplates.equals(admitted) ? List.copyOf(invocations) : List.of();
    }

    @SuppressWarnings("unchecked")
    private Projection project(Object output,
                               List<String> selectedIds,
                               List<String> deferredIds,
                               List<String> rejectedIds,
                               List<Map<String, Object>> evaluations,
                               int depth, String supplyAuthority) {
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
                Projection nested = project(item, selectedIds, deferredIds, rejectedIds, evaluations, depth + 1, supplyAuthority);
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
            SemanticCandidateAdmissionPolicy.Decision admission = selectedIds.isEmpty()
                ? new SemanticCandidateAdmissionPolicy.Decision(true, List.of(), "runtime_evidence_model_review")
                : ADMISSION_POLICY.decide(
                byId.values().stream().map(this::templateId).toList(),
                selectedIds.stream().filter(id->byId.containsKey(normalize(id))).toList(),
                rejectedIds.stream().filter(id->byId.containsKey(normalize(id))).toList(), false);
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
                    "deferredTemplateIds", deferredIds,
                    "rejectedTemplateIds", rejectedIds,
                    "candidateEvaluations", evaluations,
                    "selectionAuthority", supplyAuthority == null ? "runtime_evidence_model_review" : supplyAuthority,
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
                "deferredTemplateIds", deferredIds,
                "rejectedTemplateIds", rejectedIds,
                "candidateEvaluations", evaluations,
                "selectionAuthority", supplyAuthority == null ? admission.authority() : supplyAuthority,
                "mcpScoresAreWeakPriors", true
            ));
            return new Projection(map, templates.size(), selected.size(), projectedIds, true);
        }
        for (String key : List.of(
            "structuredContent", "structured_content", "data", "result", "payload", "body", "output",
            "routingProjection", "coverage", "preview"
        )) {
            Projection nested = project(
                map.get(key), selectedIds, deferredIds, rejectedIds, evaluations, depth + 1, supplyAuthority);
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
        return "accept".equalsIgnoreCase(decision) || "selected".equalsIgnoreCase(decision);
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
