package com.chatchat.agents.orchestration.retrieval;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a deterministic discovery-parameter boundary before asset/template retrieval.
 *
 * <p>The normalizer only understands protocol field roles. It deliberately contains no
 * product, industry, asset or template vocabulary. Explicit {@code filters} win over
 * execution context, top-level aliases and inferred context. Conflicts and repairs are
 * returned for audit instead of being silently hidden.</p>
 */
public final class DiscoveryParameterNormalizer {

    private static final Pattern DATE_PATTERN = Pattern.compile(
        "(?<!\\d)(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})(?:日)?(?!\\d)"
    );
    private static final Pattern SENTENCE_PUNCTUATION = Pattern.compile("[?？!！。；;，,]");

    public Normalization normalize(Map<String, Object> arguments,
                            Map<String, Object> inferredContext,
                            String originalQuery,
                            McpArgumentBindingFieldPolicy policy) {
        Map<String, Object> values = arguments == null ? Map.of() : arguments;
        Map<String, Object> filters = new LinkedHashMap<>();
        Map<String, String> provenance = new LinkedHashMap<>();
        List<Conflict> conflicts = new ArrayList<>();
        List<Repair> repairs = new ArrayList<>();

        // Lowest precedence first. Higher-precedence sources replace values deterministically.
        merge(filters, provenance, conflicts, inferredContext, "inferred_context", false, policy);
        mergeTopLevel(filters, provenance, conflicts, values, policy);
        merge(filters, provenance, conflicts, map(values.get("mcpExecutionContext")),
            "mcp_execution_context", true, policy);
        merge(filters, provenance, conflicts, map(values.get("executionContext")),
            "execution_context", true, policy);
        merge(filters, provenance, conflicts, map(values.get("filters")), "explicit_filters", true, policy);

        String query = normalizeText(originalQuery);
        demoteSentenceShapedIdentity(filters, provenance, repairs, query, policy);
        return new Normalization(
            Map.copyOf(filters),
            query,
            normalizedDates(query),
            Map.copyOf(provenance),
            List.copyOf(conflicts),
            List.copyOf(repairs)
        );
    }

    private void mergeTopLevel(Map<String, Object> target,
                               Map<String, String> provenance,
                               List<Conflict> conflicts,
                               Map<String, Object> source,
                               McpArgumentBindingFieldPolicy policy) {
        if (source == null || source.isEmpty()) return;
        Map<String, Object> logical = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (isLogicalField(entry.getKey(), policy) && meaningful(entry.getValue())) {
                logical.put(entry.getKey(), entry.getValue());
            }
        }
        merge(target, provenance, conflicts, logical, "top_level", true, policy);
    }

    private void merge(Map<String, Object> target,
                       Map<String, String> provenance,
                       List<Conflict> conflicts,
                       Map<String, Object> source,
                       String sourceName,
                       boolean replace,
                       McpArgumentBindingFieldPolicy policy) {
        if (source == null || source.isEmpty()) return;
        // Canonical spellings are processed last so assetName beats asset_name inside one source.
        source.entrySet().stream()
            .filter(entry -> meaningful(entry.getValue()))
            .sorted((left, right) -> Boolean.compare(
                canonicalField(left.getKey(), policy).equals(left.getKey()),
                canonicalField(right.getKey(), policy).equals(right.getKey())))
            .forEach(entry -> {
                String key = canonicalField(entry.getKey(), policy);
                Object previous = target.get(key);
                if (meaningful(previous) && !Objects.equals(previous, entry.getValue())) {
                    conflicts.add(new Conflict(key, previous, entry.getValue(),
                        provenance.get(key), sourceName));
                }
                if (replace || !target.containsKey(key)) {
                    target.put(key, entry.getValue());
                    provenance.put(key, sourceName);
                }
            });
    }

    private void demoteSentenceShapedIdentity(Map<String, Object> filters,
                                               Map<String, String> provenance,
                                               List<Repair> repairs,
                                               String query,
                                               McpArgumentBindingFieldPolicy policy) {
        String identityField = policy.identityField();
        String semanticField = policy.semanticField();
        String assetName = normalizeText(filters.get(identityField));
        if (assetName == null || query == null || !canonicalText(assetName).equals(canonicalText(query))) {
            return;
        }
        boolean sentenceShaped = !normalizedDates(query).isEmpty()
            || SENTENCE_PUNCTUATION.matcher(query).find()
            || query.codePointCount(0, query.length()) > 64;
        if (!sentenceShaped) return;

        filters.remove(identityField);
        String source = provenance.remove(identityField);
        filters.putIfAbsent(semanticField, query);
        provenance.putIfAbsent(semanticField, "identity_role_repair");
        repairs.add(new Repair(
            identityField,
            "IDENTITY_SENTENCE_DEMOTED_TO_SEMANTIC_QUERY",
            assetName,
            query,
            source
        ));
    }

    private List<String> normalizedDates(String query) {
        if (query == null) return List.of();
        List<String> dates = new ArrayList<>();
        Matcher matcher = DATE_PATTERN.matcher(query);
        while (matcher.find()) {
            try {
                String normalized = LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
                ).format(DateTimeFormatter.ISO_LOCAL_DATE);
                if (!dates.contains(normalized)) dates.add(normalized);
            } catch (DateTimeException | NumberFormatException ignored) {
                // Invalid calendar dates remain part of semanticQuery but are not trusted constraints.
            }
        }
        return List.copyOf(dates);
    }

    private String canonicalField(String key, McpArgumentBindingFieldPolicy policy) {
        return policy.canonicalFilterField(key);
    }

    private boolean isLogicalField(String key, McpArgumentBindingFieldPolicy policy) {
        return policy.logicalFilterFields().contains(canonicalField(key, policy));
    }

    private String normalizeText(Object value) {
        if (!meaningful(value)) return null;
        return Normalizer.normalize(String.valueOf(value), Normalizer.Form.NFKC)
            .trim().replaceAll("\\s+", " ");
    }

    private String canonicalText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？；]", "");
    }

    private boolean meaningful(Object value) {
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source ? (Map<String, Object>) source : Map.of();
    }

    public record Normalization(Map<String, Object> filters,
                         String semanticQuery,
                         List<String> temporalConstraints,
                         Map<String, String> provenance,
                         List<Conflict> conflicts,
                         List<Repair> repairs) {
    }

    public record Conflict(String field, Object retainedValue, Object competingValue,
                    String retainedSource, String competingSource) {
    }

    public record Repair(String field, String code, Object originalValue, Object repairedValue, String source) {
    }
}
