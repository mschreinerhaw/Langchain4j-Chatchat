package com.chatchat.runtime.news.search;

import com.chatchat.common.tool.ToolInput;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Selects the external-provider query from analyzed retrieval signals already present in the call. */
public final class ExternalSearchQueryResolver {
    private static final int MAX_TERMS = 8;
    private static final int MAX_QUERY_LENGTH = 256;

    private ExternalSearchQueryResolver() {
    }

    public static ResolvedQuery resolve(ToolInput input) {
        Map<String, Object> parameters = input == null || input.getParameters() == null
            ? Map.of() : input.getParameters();
        String originalQuery = normalize(parameters.get("query"));

        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addFields(terms, parameters, "queryTerms", "keywords");
        removeOriginalQuestion(terms, originalQuery);
        if (!terms.isEmpty()) return resolved(terms, "analyzed_keywords");

        addFields(terms, parameters, "intent");
        removeOriginalQuestion(terms, originalQuery);
        return terms.isEmpty() ? new ResolvedQuery("", "missing_analyzed_query", List.of())
            : resolved(terms, "analyzed_intent");
    }

    private static ResolvedQuery resolved(LinkedHashSet<String> candidates, String source) {
        List<String> accepted = new ArrayList<>();
        int length = 0;
        for (String candidate : candidates) {
            if (accepted.size() >= MAX_TERMS) break;
            int nextLength = length + (accepted.isEmpty() ? 0 : 1) + candidate.length();
            if (nextLength > MAX_QUERY_LENGTH) continue;
            accepted.add(candidate);
            length = nextLength;
        }
        return new ResolvedQuery(String.join(" ", accepted), source, List.copyOf(accepted));
    }

    private static void addFields(LinkedHashSet<String> target, Map<?, ?> values, String... fields) {
        for (String field : fields) addValues(target, values.get(field));
    }

    private static void addValues(LinkedHashSet<String> target, Object raw) {
        if (raw instanceof Iterable<?> values) {
            for (Object value : values) addValue(target, value);
        } else {
            addValue(target, raw);
        }
    }

    private static void addValue(LinkedHashSet<String> target, Object raw) {
        String value = normalize(raw);
        if (!value.isBlank() && value.codePointCount(0, value.length()) <= 96) target.add(value);
    }

    private static void removeOriginalQuestion(LinkedHashSet<String> terms, String originalQuery) {
        if (!originalQuery.isBlank()) terms.removeIf(term -> term.equalsIgnoreCase(originalQuery));
    }

    private static String normalize(Object value) {
        return value == null ? "" : Normalizer.normalize(String.valueOf(value), Normalizer.Form.NFKC)
            .replaceAll("\\s+", " ").trim();
    }

    public record ResolvedQuery(String query, String source, List<String> terms) {
    }
}
