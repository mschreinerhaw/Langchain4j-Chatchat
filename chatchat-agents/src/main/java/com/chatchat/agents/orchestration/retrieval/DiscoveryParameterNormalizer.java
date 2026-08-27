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
                            String originalQuery) {
        Map<String, Object> values = arguments == null ? Map.of() : arguments;
        Map<String, Object> filters = new LinkedHashMap<>();
        Map<String, String> provenance = new LinkedHashMap<>();
        List<Conflict> conflicts = new ArrayList<>();
        List<Repair> repairs = new ArrayList<>();

        // Lowest precedence first. Higher-precedence sources replace values deterministically.
        merge(filters, provenance, conflicts, inferredContext, "inferred_context", false);
        mergeTopLevel(filters, provenance, conflicts, values);
        merge(filters, provenance, conflicts, map(values.get("mcpExecutionContext")),
            "mcp_execution_context", true);
        merge(filters, provenance, conflicts, map(values.get("executionContext")),
            "execution_context", true);
        merge(filters, provenance, conflicts, map(values.get("filters")), "explicit_filters", true);

        String query = normalizeText(originalQuery);
        demoteSentenceShapedIdentity(filters, provenance, repairs, query);
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
                               Map<String, Object> source) {
        if (source == null || source.isEmpty()) return;
        Map<String, Object> logical = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (isLogicalField(entry.getKey()) && meaningful(entry.getValue())) {
                logical.put(entry.getKey(), entry.getValue());
            }
        }
        merge(target, provenance, conflicts, logical, "top_level", true);
    }

    private void merge(Map<String, Object> target,
                       Map<String, String> provenance,
                       List<Conflict> conflicts,
                       Map<String, Object> source,
                       String sourceName,
                       boolean replace) {
        if (source == null || source.isEmpty()) return;
        // Canonical spellings are processed last so assetName beats asset_name inside one source.
        source.entrySet().stream()
            .filter(entry -> meaningful(entry.getValue()))
            .sorted((left, right) -> Boolean.compare(
                canonicalField(left.getKey()).equals(left.getKey()),
                canonicalField(right.getKey()).equals(right.getKey())))
            .forEach(entry -> {
                String key = canonicalField(entry.getKey());
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
                                               String query) {
        String assetName = normalizeText(filters.get("assetName"));
        if (assetName == null || query == null || !canonicalText(assetName).equals(canonicalText(query))) {
            return;
        }
        boolean sentenceShaped = !normalizedDates(query).isEmpty()
            || SENTENCE_PUNCTUATION.matcher(query).find()
            || query.codePointCount(0, query.length()) > 64;
        if (!sentenceShaped) return;

        filters.remove("assetName");
        String source = provenance.remove("assetName");
        filters.putIfAbsent("intent", query);
        provenance.putIfAbsent("intent", "identity_role_repair");
        repairs.add(new Repair(
            "assetName",
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

    private String canonicalField(String key) {
        if (key == null) return "";
        String canonical = key.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return switch (canonical) {
            case "assetname", "name" -> "assetName";
            case "environment" -> "env";
            case "targettype" -> "targetType";
            case "databasetype" -> "databaseType";
            case "dbtype" -> "dbType";
            case "databaserole" -> "databaseRole";
            case "hostselector" -> "hostSelector";
            case "queryterms", "searchterms" -> "queryTerms";
            case "retrievalsignals" -> "retrievalSignals";
            case "bilingualintent" -> "bilingualIntent";
            case "bilingualquery", "bilingualsearch" -> "bilingualQuery";
            case "intentzh" -> "intentZh";
            case "intenten" -> "intentEn";
            case "intentaliases" -> "intentAliases";
            case "intentcandidates" -> "intentCandidates";
            case "businessgroup" -> "businessGroup";
            case "groupname" -> "groupName";
            case "groupdescription" -> "groupDescription";
            case "templateid" -> "templateId";
            case "toolname" -> "toolName";
            case "querylanguage" -> "queryLanguage";
            default -> key.trim();
        };
    }

    private boolean isLogicalField(String key) {
        String canonical = canonicalField(key);
        return List.of(
            "env", "cluster", "namespace", "target", "targetType", "assetName",
            "hostSelector", "database", "databaseType", "dbType", "dialect",
            "databaseRole", "service", "labels", "intent", "goal", "category",
            "queryTerms", "retrievalSignals", "intentCandidates", "bilingualIntent",
            "bilingualQuery", "intentZh", "intentEn", "intentAliases", "keywords",
            "businessGroup", "group", "groupName", "groupDescription", "toolName", "template",
            "templateId", "view", "language", "queryLanguage", "locale"
        ).contains(canonical);
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
