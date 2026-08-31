package com.chatchat.agents.orchestration.analysis.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries producer-declared analysis semantics into Workers without deriving business meaning
 * from field names, values, repetition, or other record-shape heuristics.
 */
public final class AnalysisSemanticContractCompiler {

    public static final String SCHEMA_VERSION = "analysis_semantic_contract.v1";

    public Map<String, Object> compile(Map<String, Object> analysisContext) {
        Map<String, Object> context = map(analysisContext);
        Map<String, Object> completeness = map(context.get("contextCompleteness"));
        List<String> suppliedSections = strings(completeness.get("suppliedSections"));
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("schemaVersion", SCHEMA_VERSION);
        contract.put("semanticAuthority", suppliedSections.stream().anyMatch(this::semanticSection)
            ? "PRODUCER_DECLARED" : "UNDECLARED");
        contract.put("declaredSections", suppliedSections.stream().filter(this::semanticSection).toList());
        copyDeclared(contract, context, suppliedSections, "capability");
        copyDeclared(contract, context, suppliedSections, "schema");
        copyDeclared(contract, context, suppliedSections, "semantics");
        copyDeclared(contract, context, suppliedSections, "relationships");
        copyDeclared(contract, context, suppliedSections, "quality");
        copyDeclared(contract, context, suppliedSections, "analysisPolicy");
        contract.put("runtimeInvariants", List.of(
            "STRUCTURAL_STATISTICS_HAVE_NO_BUSINESS_SEMANTICS",
            "NO_UNDECLARED_AGGREGATION_OR_ADDITIVITY",
            "NO_UNDECLARED_PROXY_OR_CAUSAL_RELATION",
            "NO_UNDECLARED_POPULATION_OR_COMPLETENESS_SCOPE"));
        return Collections.unmodifiableMap(contract);
    }

    private void copyDeclared(Map<String, Object> target,
                              Map<String, Object> context,
                              List<String> suppliedSections,
                              String section) {
        if (suppliedSections.contains(section) && context.get(section) != null) {
            target.put(section, immutableValue(context.get(section)));
        }
    }

    private boolean semanticSection(String value) {
        return List.of("capability", "schema", "semantics", "relationships", "quality", "analysisPolicy")
            .contains(value);
    }

    private Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> source) return Collections.unmodifiableMap(map(source));
        if (value instanceof Iterable<?> source) {
            List<Object> result = new ArrayList<>();
            source.forEach(item -> {
                if (item != null) result.add(immutableValue(item));
            });
            return List.copyOf(result);
        }
        return value;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null && item != null) result.put(String.valueOf(key), immutableValue(item));
        });
        return result;
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> source)) return List.of();
        List<String> result = new ArrayList<>();
        source.forEach(item -> {
            if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item));
        });
        return result.stream().distinct().toList();
    }
}
