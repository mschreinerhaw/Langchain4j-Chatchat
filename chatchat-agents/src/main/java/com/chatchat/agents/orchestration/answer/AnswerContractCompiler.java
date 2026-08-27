package com.chatchat.agents.orchestration.answer;

import com.chatchat.agents.protocol.AnswerContract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiles explicit request constraints without domain keywords or business branches.
 */
public class AnswerContractCompiler {

    private static final int MAX_DELIVERABLES = 12;
    private static final int MAX_CONSTRAINTS = 12;

    public AnswerContract compile(String query, String systemPrompt, Map<String, Object> metadata) {
        String goal = normalize(query);
        List<String> deliverables = explicitList(metadata, "answerDeliverables", "requiredDeliverables");
        if (deliverables.isEmpty()) {
            deliverables = splitExplicitParts(goal);
        }
        if (deliverables.isEmpty() && !goal.isBlank()) {
            deliverables = List.of(goal);
        }

        List<String> constraints = new ArrayList<>(explicitList(metadata,
            "answerConstraints", "responseConstraints"));
        String responseSchema = firstText(metadata, "responseSchema", "outputSchema");
        if (!responseSchema.isBlank()) {
            constraints.add("responseSchema=" + responseSchema);
        }
        String explicitSystemConstraint = normalize(systemPrompt);
        if (!explicitSystemConstraint.isBlank()) {
            constraints.add(explicitSystemConstraint);
        }

        return new AnswerContract(
            AnswerContract.VERSION,
            goal,
            bounded(deliverables, MAX_DELIVERABLES),
            normalizedToken(firstText(metadata, "answerOutputFormat", "outputFormat"), "MARKDOWN"),
            normalizedToken(firstText(metadata, "answerLanguage", "responseLanguage"), "AUTO"),
            evidencePolicy(metadata),
            bounded(constraints, MAX_CONSTRAINTS)
        );
    }

    private String evidencePolicy(Map<String, Object> metadata) {
        Object explicit = firstValue(metadata, "answerEvidenceRequired", "evidenceRequired");
        if (explicit instanceof Boolean bool) {
            return bool ? AnswerContract.EVIDENCE_REQUIRED : AnswerContract.EVIDENCE_NOT_REQUIRED;
        }
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            String value = String.valueOf(explicit).trim();
            if ("true".equalsIgnoreCase(value)) return AnswerContract.EVIDENCE_REQUIRED;
            if ("false".equalsIgnoreCase(value)) return AnswerContract.EVIDENCE_NOT_REQUIRED;
        }
        String configured = normalizedToken(firstText(metadata, "answerEvidencePolicy", "evidencePolicy"), "");
        return switch (configured) {
            case AnswerContract.EVIDENCE_REQUIRED -> AnswerContract.EVIDENCE_REQUIRED;
            case AnswerContract.EVIDENCE_NOT_REQUIRED -> AnswerContract.EVIDENCE_NOT_REQUIRED;
            default -> AnswerContract.EVIDENCE_OPTIONAL;
        };
    }

    private List<String> splitExplicitParts(String query) {
        if (query == null || query.isBlank()) return List.of();
        String[] parts = query.split("(?:\\r?\\n)+|(?<=[?？])\\s+|\\s*[;；]\\s*");
        Set<String> values = new LinkedHashSet<>();
        for (String part : parts) {
            String value = normalize(part).replaceFirst("^[\\-+*\\d.、)）\\s]+", "").trim();
            if (!value.isBlank()) values.add(value);
            if (values.size() >= MAX_DELIVERABLES) break;
        }
        return List.copyOf(values);
    }

    private List<String> explicitList(Map<String, Object> metadata, String... keys) {
        Object value = firstValue(metadata, keys);
        if (value instanceof Iterable<?> items) {
            Set<String> values = new LinkedHashSet<>();
            for (Object item : items) {
                String text = normalize(item == null ? null : String.valueOf(item));
                if (!text.isBlank()) values.add(text);
            }
            return List.copyOf(values);
        }
        return List.of();
    }

    private List<String> bounded(List<String> values, int limit) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
            .map(String::trim).distinct().limit(limit).toList();
    }

    private Object firstValue(Map<String, Object> metadata, String... keys) {
        if (metadata == null) return null;
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null) return value;
        }
        return null;
    }

    private String firstText(Map<String, Object> metadata, String... keys) {
        Object value = firstValue(metadata, keys);
        return value == null ? "" : normalize(String.valueOf(value));
    }

    private String normalizedToken(String value, String fallback) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_.-]", "_");
        return normalized.isBlank() ? fallback : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
