package com.chatchat.agents.orchestration.analysis.insight;

import com.chatchat.agents.orchestration.analysis.model.SemanticInsightContract;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Machine-readable catalog for maintaining and validating source-neutral recipe parameters. */
public final class SemanticInsightRecipeCatalog {
    private static final Map<String, Definition> DEFINITIONS = definitions();

    private SemanticInsightRecipeCatalog() {
    }

    public static Map<String, Definition> definitionsByOperator() {
        return DEFINITIONS;
    }

    public static Parameter parameter(String operator, String key) {
        if (operator == null || key == null) return null;
        Definition definition = DEFINITIONS.get(operator.trim().toUpperCase(Locale.ROOT));
        if (definition == null) return null;
        return definition.parameters().stream()
            .filter(parameter -> parameter.key().equals(key))
            .findFirst().orElse(null);
    }

    public static List<String> validate(SemanticInsightContract.Recipe recipe) {
        if (recipe == null || recipe.operator() == null) return List.of("operator is required");
        Definition definition = DEFINITIONS.get(recipe.operator().trim().toUpperCase(Locale.ROOT));
        if (definition == null) return List.of("unsupported operator: " + recipe.operator());
        List<String> issues = new ArrayList<>();
        for (Parameter parameter : definition.parameters()) {
            Object value = recipe.parameters().get(parameter.key());
            if (parameter.required() && (value == null || String.valueOf(value).isBlank())) {
                issues.add(parameter.key() + " is required");
            } else if (value != null && !compatible(parameter.type(), value)) {
                issues.add(parameter.key() + " must be " + parameter.type());
            }
        }
        Set<String> allowed = definition.parameters().stream().map(Parameter::key)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        recipe.parameters().keySet().stream().filter(key -> !allowed.contains(key))
            .forEach(key -> issues.add("unsupported parameter: " + key));
        return List.copyOf(issues);
    }

    private static boolean compatible(String type, Object value) {
        return switch (type) {
            case "BOOLEAN" -> value instanceof Boolean
                || "true".equalsIgnoreCase(String.valueOf(value))
                || "false".equalsIgnoreCase(String.valueOf(value));
            case "INTEGER" -> {
                try { Integer.parseInt(String.valueOf(value)); yield true; }
                catch (NumberFormatException ignored) { yield false; }
            }
            case "NUMBER" -> {
                try { new BigDecimal(String.valueOf(value)); yield true; }
                catch (NumberFormatException ignored) { yield false; }
            }
            // Identifiers and match values are persisted as text. Legacy JSON may
            // represent a code-like match value as a number, so accept any scalar
            // and let the structure migrator normalize it to a string.
            default -> !(value instanceof Map<?, ?>) && !(value instanceof Iterable<?>);
        };
    }

    private static Map<String, Definition> definitions() {
        Map<String, Definition> values = new LinkedHashMap<>();
        values.put("SUM", definition("DATASET", required("metric", "STRING")));
        values.put("TOP_N", definition("DATASET",
            required("valueMetric", "STRING"), required("groupBy", "STRING"),
            optional("topN", "INTEGER"), optional("absoluteValues", "BOOLEAN")));
        values.put("CONTRIBUTION", values.get("TOP_N"));
        values.put("CONCENTRATION", values.get("TOP_N"));
        values.put("RECONCILIATION", definition("DATASET",
            required("leftExpression", "STRING"), required("rightExpression", "STRING"),
            optional("tolerance", "NUMBER"), optional("emitWhen", "STRING"),
            optional("maxFindings", "INTEGER")));
        values.put("OUTLIER_RATIO", definition("DATASET",
            required("numerator", "STRING"), required("denominator", "STRING"),
            required("entity", "STRING"), required("threshold", "NUMBER"),
            optional("comparator", "STRING"), optional("absolute", "BOOLEAN"),
            optional("maxFindings", "INTEGER")));
        values.put("TAG_MATCH", definition("DATASET",
            required("field", "STRING"), required("value", "STRING"),
            optional("matchMode", "STRING"), optional("tag", "STRING"),
            optional("maxFindings", "INTEGER")));
        values.put("BUNDLE_RECONCILIATION", definition("BUNDLE",
            required("leftExpression", "STRING"), required("rightExpression", "STRING"),
            optional("tolerance", "NUMBER")));
        values.put("BUNDLE_RATIO", definition("BUNDLE",
            required("leftExpression", "STRING"), required("rightExpression", "STRING")));
        return Map.copyOf(values);
    }

    private static Definition definition(String scope, Parameter... parameters) {
        return new Definition(scope, List.of(parameters));
    }
    private static Parameter required(String key, String type) { return new Parameter(key, type, true); }
    private static Parameter optional(String key, String type) { return new Parameter(key, type, false); }

    public record Definition(String scope, List<Parameter> parameters) {
        public Definition { parameters = List.copyOf(parameters); }
    }
    public record Parameter(String key, String type, boolean required) {}
}
