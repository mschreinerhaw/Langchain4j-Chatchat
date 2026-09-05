package com.chatchat.common.runtime.summary.analysis.semantic.adapter;

import com.chatchat.common.runtime.summary.analysis.semantic.model.ProducerSemanticDeclaration;
import com.chatchat.common.runtime.summary.analysis.semantic.model.SemanticOperation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical producer-boundary parser and analysis-context merger. */
public final class ProducerSemanticDeclarationProtocol {

    public static final String CONTEXT_KEY = "producerSemanticDeclaration";

    private ProducerSemanticDeclarationProtocol() {
    }

    public static ProducerSemanticDeclaration parse(Object value) {
        Map<String, Object> source = map(value);
        if (source.isEmpty()) throw new IllegalArgumentException("producer semantic declaration is required");
        Set<SemanticOperation> operations = new LinkedHashSet<>();
        for (String valueText : strings(source.get("allowedOperations"))) {
            SemanticOperation operation = SemanticOperation.from(valueText);
            if (operation == null) throw new IllegalArgumentException("unsupported semantic operation: " + valueText);
            operations.add(operation);
        }
        List<ProducerSemanticDeclaration.Field> fields = maps(source.get("fields")).stream()
            .map(field -> new ProducerSemanticDeclaration.Field(
                text(field.get("name")), text(field.get("meaning")), text(field.get("unit")),
                text(field.get("additivity"))))
            .toList();
        Map<String, Object> scope = map(source.get("evidenceScope"));
        ProducerSemanticDeclaration.Scope evidenceScope = new ProducerSemanticDeclaration.Scope(
            text(scope.get("grain")), text(scope.get("timeScope")),
            text(scope.get("populationScope")), text(scope.get("completeness")));
        List<ProducerSemanticDeclaration.Rule> rules = maps(source.get("rules")).stream()
            .map(rule -> new ProducerSemanticDeclaration.Rule(
                text(rule.get("ruleId")), SemanticOperation.from(text(rule.get("operation"))),
                text(rule.get("basis")), Set.copyOf(strings(rule.get("inputFields"))),
                text(rule.get("outputUnit")), text(rule.get("grain")),
                text(rule.get("timeScope")), text(rule.get("populationScope"))))
            .toList();
        return new ProducerSemanticDeclaration(
            text(source.get("schemaVersion")), text(source.get("capabilityId")), operations,
            fields, evidenceScope, rules, map(source.get("extensions")));
    }

    /** Returns the producer declaration carried by owner metadata, or {@code null} when absent. */
    public static ProducerSemanticDeclaration find(Object ownerMetadata) {
        Map<String, Object> source = map(ownerMetadata);
        Object declaration = source.containsKey(CONTEXT_KEY)
            ? source.get(CONTEXT_KEY) : source.get("producer_semantic_declaration");
        return declaration == null ? null : parse(declaration);
    }

    /**
     * Validates and canonicalizes a declaration without interpreting any producer-owned semantics.
     * Metadata without a declaration remains valid and therefore operates in OBSERVE-only mode.
     */
    public static Map<String, Object> canonicalizeOwnerMetadata(Object ownerMetadata) {
        Map<String, Object> source = map(ownerMetadata);
        if (source.isEmpty()) return Map.of();
        ProducerSemanticDeclaration declaration = find(source);
        if (declaration == null) return immutable(source);
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.remove("producer_semantic_declaration");
        result.put(CONTEXT_KEY, declaration.toMap());
        return immutable(result);
    }

    public static Readiness readiness(Object ownerMetadata) {
        ProducerSemanticDeclaration declaration = find(ownerMetadata);
        if (declaration == null) return Readiness.MISSING_OBSERVE_ONLY;
        return declaration.allowedOperations().size() == 1
            ? Readiness.DECLARED_OBSERVE_ONLY : Readiness.DECLARED_ANALYTICAL;
    }

    public static Map<String, Object> mergeIntoAnalysisContext(Map<String, Object> base,
                                                                Object declarationValue) {
        ProducerSemanticDeclaration declaration = declarationValue instanceof ProducerSemanticDeclaration typed
            ? typed : parse(declarationValue);
        return immutable(deepMerge(base, declaration.toAnalysisContextSections()));
    }

    private static Map<String, Object> deepMerge(Map<String, Object> parent, Map<String, Object> child) {
        Map<String, Object> result = new LinkedHashMap<>(parent == null ? Map.of() : parent);
        if (child == null) return result;
        child.forEach((key, childValue) -> {
            Object parentValue = result.get(key);
            if (parentValue instanceof Map<?, ?> && childValue instanceof Map<?, ?>) {
                result.put(key, deepMerge(map(parentValue), map(childValue)));
            } else if (childValue != null) result.put(key, childValue);
        });
        return result;
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        iterable.forEach(item -> {
            Map<String, Object> mapped = map(item);
            if (!mapped.isEmpty()) result.add(mapped);
        });
        return List.copyOf(result);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<String> result = new ArrayList<>();
        iterable.forEach(item -> {
            String text = text(item);
            if (!text.isBlank()) result.add(text);
        });
        return result.stream().distinct().toList();
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null && item != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null || value.isEmpty() ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    public enum Readiness {
        MISSING_OBSERVE_ONLY,
        DECLARED_OBSERVE_ONLY,
        DECLARED_ANALYTICAL
    }
}
