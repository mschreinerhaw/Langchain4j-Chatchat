package com.chatchat.common.runtime.summary.analysis.semantic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Producer-owned, domain-neutral semantic declaration. Runtime OS consumes this declaration but
 * never supplies business fields, formulas, units, grains, time ranges, or population scopes.
 */
public record ProducerSemanticDeclaration(
    String schemaVersion,
    String capabilityId,
    Set<SemanticOperation> allowedOperations,
    List<Field> fields,
    Scope evidenceScope,
    List<Rule> rules,
    Map<String, Object> extensions
) {
    public static final String SCHEMA_VERSION = "producer_semantic_declaration.v1";

    public ProducerSemanticDeclaration {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported producer semantic declaration version: " + schemaVersion);
        }
        capabilityId = required(capabilityId, "capabilityId");
        allowedOperations = allowedOperations == null ? Set.of() : Set.copyOf(allowedOperations);
        fields = fields == null ? List.of() : List.copyOf(fields);
        evidenceScope = evidenceScope == null ? Scope.unknown() : evidenceScope;
        rules = rules == null ? List.of() : List.copyOf(rules);
        extensions = immutableMap(extensions);
        validate(allowedOperations, fields, evidenceScope, rules);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", schemaVersion);
        result.put("capabilityId", capabilityId);
        result.put("allowedOperations", allowedOperations.stream().map(Enum::name).sorted().toList());
        result.put("fields", fields.stream().map(Field::toMap).toList());
        result.put("evidenceScope", evidenceScope.toMap());
        result.put("rules", rules.stream().map(Rule::toMap).toList());
        result.put("extensions", extensions);
        return Collections.unmodifiableMap(result);
    }

    /** Canonical sections merged into DataAnalysisContextProtocol by producer adapters. */
    public Map<String, Object> toAnalysisContextSections() {
        Set<String> units = new LinkedHashSet<>();
        Set<String> grains = new LinkedHashSet<>();
        Set<String> timeScopes = new LinkedHashSet<>();
        Set<String> populationScopes = new LinkedHashSet<>();
        fields.stream().map(Field::unit).filter(value -> !value.isBlank()).forEach(units::add);
        rules.stream().map(Rule::outputUnit).filter(value -> !value.isBlank()).forEach(units::add);
        add(grains, evidenceScope.grain());
        add(timeScopes, evidenceScope.timeScope());
        add(populationScopes, evidenceScope.populationScope());
        rules.forEach(rule -> {
            add(grains, rule.grain());
            add(timeScopes, rule.timeScope());
            add(populationScopes, rule.populationScope());
        });

        Map<String, Object> semantics = new LinkedHashMap<>();
        semantics.put("semanticBasis", rules.stream().map(Rule::basis).distinct().toList());
        semantics.put("rules", rules.stream().map(Rule::toMap).toList());
        semantics.put("units", List.copyOf(units));
        semantics.put("grains", List.copyOf(grains));
        semantics.put("timeScopes", List.copyOf(timeScopes));
        semantics.put("populationScopes", List.copyOf(populationScopes));
        return Map.of(
            "capability", Map.of(
                "capabilityId", capabilityId,
                "allowedOperations", allowedOperations.stream().map(Enum::name).sorted().toList()),
            "schema", Map.of("fields", fields.stream().map(Field::toMap).toList()),
            "semantics", Collections.unmodifiableMap(semantics),
            "quality", Map.of("producerDeclaredScope", evidenceScope.toMap()),
            "producerSemanticDeclaration", toMap());
    }

    private static void validate(Set<SemanticOperation> operations,
                                 List<Field> fields,
                                 Scope scope,
                                 List<Rule> rules) {
        if (operations.isEmpty() || !operations.contains(SemanticOperation.OBSERVE)) {
            throw new IllegalArgumentException("allowedOperations must explicitly include OBSERVE");
        }
        Set<String> fieldNames = new LinkedHashSet<>();
        for (Field field : fields) {
            if (!fieldNames.add(field.name())) throw new IllegalArgumentException("duplicate field: " + field.name());
        }
        Set<SemanticOperation> ruledOperations = new LinkedHashSet<>();
        for (Rule rule : rules) {
            if (!operations.contains(rule.operation())) {
                throw new IllegalArgumentException("rule operation is not allowed: " + rule.operation());
            }
            if (rule.operation() == SemanticOperation.OBSERVE) {
                throw new IllegalArgumentException("OBSERVE does not require a semantic rule");
            }
            if (!fieldNames.isEmpty() && !fieldNames.containsAll(rule.inputFields())) {
                throw new IllegalArgumentException("rule inputFields must be producer-declared fields");
            }
            ruledOperations.add(rule.operation());
        }
        Set<SemanticOperation> missingRules = new LinkedHashSet<>(operations);
        missingRules.remove(SemanticOperation.OBSERVE);
        missingRules.removeAll(ruledOperations);
        if (!missingRules.isEmpty()) {
            throw new IllegalArgumentException("non-observe operations require explicit rules: " + missingRules);
        }
        if (operations.size() > 1 && scope.isUnknown()) {
            throw new IllegalArgumentException("derived or inferred operations require producer-declared evidenceScope");
        }
    }

    public record Field(String name, String meaning, String unit, String additivity) {
        public Field {
            name = required(name, "field.name");
            meaning = clean(meaning);
            unit = clean(unit);
            additivity = clean(additivity);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            put(result, "meaning", meaning);
            put(result, "unit", unit);
            put(result, "additivity", additivity);
            return Collections.unmodifiableMap(result);
        }
    }

    public record Scope(String grain, String timeScope, String populationScope, String completeness) {
        public Scope {
            grain = clean(grain);
            timeScope = clean(timeScope);
            populationScope = clean(populationScope);
            completeness = clean(completeness);
        }

        public static Scope unknown() {
            return new Scope("", "", "", "UNKNOWN");
        }

        public boolean isUnknown() {
            return grain.isBlank() || timeScope.isBlank() || populationScope.isBlank();
        }

        public Map<String, Object> toMap() {
            return Map.of(
                "grain", grain,
                "timeScope", timeScope,
                "populationScope", populationScope,
                "completeness", completeness.isBlank() ? "UNKNOWN" : completeness);
        }
    }

    public record Rule(String ruleId,
                       SemanticOperation operation,
                       String basis,
                       Set<String> inputFields,
                       String outputUnit,
                       String grain,
                       String timeScope,
                       String populationScope) {
        public Rule {
            ruleId = required(ruleId, "rule.ruleId");
            if (operation == null) throw new IllegalArgumentException("rule.operation is required");
            basis = required(basis, "rule.basis");
            inputFields = inputFields == null ? Set.of() : inputFields.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            outputUnit = clean(outputUnit);
            grain = required(grain, "rule.grain");
            timeScope = required(timeScope, "rule.timeScope");
            populationScope = required(populationScope, "rule.populationScope");
            if (Set.of(SemanticOperation.DERIVE, SemanticOperation.AGGREGATE,
                SemanticOperation.COMPARE, SemanticOperation.RANK, SemanticOperation.TREND)
                .contains(operation) && inputFields.isEmpty()) {
                throw new IllegalArgumentException("analytical rule inputFields are required");
            }
            if (Set.of(SemanticOperation.DERIVE, SemanticOperation.AGGREGATE)
                .contains(operation) && outputUnit.isBlank()) {
                throw new IllegalArgumentException("derived rule outputUnit is required");
            }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ruleId", ruleId);
            result.put("operation", operation.name());
            result.put("basis", basis);
            result.put("inputFields", inputFields.stream().sorted().toList());
            put(result, "outputUnit", outputUnit);
            result.put("grain", grain);
            result.put("timeScope", timeScope);
            result.put("populationScope", populationScope);
            return Collections.unmodifiableMap(result);
        }
    }

    private static void add(Set<String> target, String value) {
        if (value != null && !value.isBlank()) target.add(value);
    }

    private static void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && value != null) result.put(key, immutableValue(value));
        });
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> {
                if (key != null && item != null) result.put(String.valueOf(key), immutableValue(item));
            });
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof Iterable<?> source) {
            List<Object> result = new java.util.ArrayList<>();
            source.forEach(item -> {
                if (item != null) result.add(immutableValue(item));
            });
            return List.copyOf(result);
        }
        return value;
    }
}
