package com.chatchat.mcpserver.sql;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RetrievalPlanner {

    private static final double SCHEMA_CONFIDENCE_THRESHOLD = 0.70d;

    public RetrievalPlan plan(String question,
                              List<String> requestedTables,
                              List<TableLocation> resolvedTables,
                              SemanticIR semanticIR) {
        List<String> triggers = new ArrayList<>();
        double schemaConfidence = schemaConfidence(resolvedTables);
        boolean hasRequestedTables = requestedTables != null && !requestedTables.isEmpty();
        boolean hasResolvedTables = resolvedTables != null && !resolvedTables.isEmpty();
        if (hasRequestedTables && !hasResolvedTables) {
            triggers.add("table_not_found");
        }
        if (hasResolvedTables && schemaConfidence < SCHEMA_CONFIDENCE_THRESHOLD) {
            triggers.add("schema_confidence_below_threshold");
        }
        if (containsBusinessTerm(question)) {
            triggers.add("business_term");
        }
        if (containsMetricDefinitionNeed(question)) {
            triggers.add("metric_definition");
        }
        boolean needed = !triggers.isEmpty();
        return new RetrievalPlan(
            "retrieval_plan.v1",
            needed,
            needed ? "document_search" : "none",
            priority(triggers),
            needed ? reason(triggers) : "No retrieval required by compiler rules.",
            retrievalQuery(question, requestedTables, semanticIR),
            triggers,
            mapOf(
                "schemaConfidence", round(schemaConfidence),
                "schemaConfidenceThreshold", SCHEMA_CONFIDENCE_THRESHOLD,
                "requestedTables", requestedTables == null ? List.of() : requestedTables,
                "resolvedTableCount", resolvedTables == null ? 0 : resolvedTables.size()
            )
        );
    }

    private double schemaConfidence(List<TableLocation> tables) {
        if (tables == null || tables.isEmpty()) {
            return 0.0d;
        }
        return tables.stream()
            .map(TableLocation::score)
            .min(Comparator.naturalOrder())
            .orElse(0.0d);
    }

    private boolean containsBusinessTerm(String question) {
        String normalized = normalize(question);
        return containsAny(normalized, "business term", "glossary", "definition", "meaning", "业务", "术语", "口径", "含义");
    }

    private boolean containsMetricDefinitionNeed(String question) {
        String normalized = normalize(question);
        return containsAny(normalized, "metric", "indicator", "kpi", "definition", "meaning", "指标", "统计口径", "口径", "解释", "含义");
    }

    private String priority(List<String> triggers) {
        if (triggers == null || triggers.isEmpty()) {
            return "NONE";
        }
        if (triggers.contains("table_not_found") || triggers.contains("metric_definition")) {
            return "HIGH";
        }
        return "MEDIUM";
    }

    private String reason(List<String> triggers) {
        if (triggers.contains("table_not_found")) {
            return "Requested table was not confidently resolved; retrieve document evidence for schema or business context.";
        }
        if (triggers.contains("metric_definition")) {
            return "Question asks for metric meaning or calculation context; retrieve document evidence for definitions.";
        }
        if (triggers.contains("business_term")) {
            return "Question contains business terminology that may not be encoded in table metadata.";
        }
        if (triggers.contains("schema_confidence_below_threshold")) {
            return "Resolved schema confidence is below threshold; retrieve document evidence for disambiguation.";
        }
        return "Retrieval required by compiler rule.";
    }

    private String retrievalQuery(String question, List<String> requestedTables, SemanticIR semanticIR) {
        StringBuilder query = new StringBuilder();
        if (question != null && !question.isBlank()) {
            query.append(question.trim());
        }
        if (requestedTables != null && !requestedTables.isEmpty()) {
            query.append(" tables=").append(String.join(",", requestedTables));
        }
        if (semanticIR != null && semanticIR.root() != null && semanticIR.root().operation() != null) {
            query.append(" operation=").append(semanticIR.root().operation());
        }
        return query.toString().trim();
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank() || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }
}
