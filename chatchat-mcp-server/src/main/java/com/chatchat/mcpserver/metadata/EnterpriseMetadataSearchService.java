package com.chatchat.mcpserver.metadata;

import com.chatchat.mcpserver.search.OpenSearchMcpSearchService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class EnterpriseMetadataSearchService {

    public static final String RESULT_SCHEMA_VERSION = "enterprise_metadata_search_result.v2";

    private final EnterpriseMetadataCatalog catalog;
    private final EnterpriseMetadataProperties properties;
    private final OpenSearchMcpSearchService openSearch;
    private final EnterpriseMetadataScenarioClassifier scenarioClassifier;
    private final EnterpriseMetadataVectorizer vectorizer;

    public EnterpriseMetadataSearchService(EnterpriseMetadataCatalog catalog,
                                           EnterpriseMetadataProperties properties,
                                           OpenSearchMcpSearchService openSearch,
                                           EnterpriseMetadataScenarioClassifier scenarioClassifier,
                                           EnterpriseMetadataVectorizer vectorizer) {
        this.catalog = catalog;
        this.properties = properties;
        this.openSearch = openSearch;
        this.scenarioClassifier = scenarioClassifier;
        this.vectorizer = vectorizer;
    }

    public Map<String, Object> search(SearchRequest request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Enterprise metadata capability is disabled");
        }
        SearchRequest effective = request == null
            ? new SearchRequest(null, List.of(), List.of(), List.of(), null)
            : request;
        String query = text(effective.query());
        if (query == null) {
            throw new IllegalArgumentException("query is required");
        }
        List<String> types = normalizeTypes(effective.types());
        List<String> statuses = normalizeValues(effective.statuses());
        List<String> scenarios = normalizeValues(effective.scenarios());
        int limit = effective.limit() == null ? properties.getDefaultLimit() : effective.limit();
        limit = Math.max(1, Math.min(limit, properties.getMaxResults()));
        String expandedQuery = expandedQuery(query);

        String semanticQuery = scenarioClassifier.enrichQuery(expandedQuery);
        List<Float> queryVector = properties.getKnn().isEnabled()
            ? vectorizer.vectorize(semanticQuery)
            : List.of();
        List<Map<String, Object>> results = searchOpenSearch(
            expandedQuery, types, statuses, scenarios, queryVector, limit);
        String backend = "opensearch";
        if (results.isEmpty()) {
            results = searchMemory(query, expandedQuery, types, statuses, scenarios, limit);
            backend = "memory";
        }

        List<Map<String, Object>> evidence = results.stream()
            .map(this::evidenceObject)
            .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemaVersion", RESULT_SCHEMA_VERSION);
        response.put("success", true);
        response.put("query", query);
        response.put("expandedQuery", expandedQuery);
        response.put("semanticQuery", semanticQuery);
        response.put("detectedScenarios", scenarioClassifier.classifyQuery(query));
        response.put("backend", backend);
        response.put("retrievalMode", "memory".equals(backend)
            ? "memory_lexical"
            : results.stream()
                .map(item -> String.valueOf(item.getOrDefault("retrievalMode", "bm25")))
                .filter(mode -> mode.contains("knn"))
                .findFirst()
                .orElse("bm25"));
        response.put("count", results.size());
        response.put("results", results);
        response.put("evidenceObjects", evidence);
        response.put("evidencePolicy", Map.of(
            "factBoundary", "Only returned enterprise metadata records may be used as factual field or term definitions",
            "sampleValuesIncluded", false,
            "sourceAuthority", "configured_enterprise_metadata_catalog"
        ));
        return response;
    }

    private List<Map<String, Object>> searchOpenSearch(String query,
                                                       List<String> types,
                                                       List<String> statuses,
                                                       List<String> scenarios,
                                                       List<Float> queryVector,
                                                       int limit) {
        if (openSearch == null || !openSearch.enabled()) {
            return List.of();
        }
        try {
            return openSearch.searchEnterpriseMetadata(
                    properties.getIndexName(), query, types, statuses, scenarios, queryVector,
                    properties.getKnn().getVectorField(),
                    properties.getKnn().getCandidateLimit(),
                    properties.getKnn().getBm25Weight(),
                    properties.getKnn().getVectorWeight(),
                    limit)
                .stream()
                .map(this::flattenOpenSearchResult)
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> flattenOpenSearchResult(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        Object attributes = result.remove("attributes");
        if (attributes instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.putIfAbsent(String.valueOf(key), value));
        }
        return Map.copyOf(result);
    }

    private List<Map<String, Object>> searchMemory(String originalQuery,
                                                   String expandedQuery,
                                                   List<String> types,
                                                   List<String> statuses,
                                                   List<String> scenarios,
                                                   int limit) {
        Set<String> queryTokens = tokens(expandedQuery);
        return catalog.records().stream()
            .filter(record -> types.isEmpty() || types.contains(normalize(record.metadataType())))
            .filter(record -> statuses.isEmpty() || statuses.contains(normalize(record.status())))
            .filter(record -> scenarios.isEmpty() || scenarioCodes(record).stream().anyMatch(scenarios::contains))
            .map(record -> scored(record, originalQuery, queryTokens))
            .filter(item -> ((Number) item.get("relevanceScore")).doubleValue() > 0.0D)
            .sorted(Comparator
                .comparingDouble((Map<String, Object> item) ->
                    ((Number) item.get("relevanceScore")).doubleValue()).reversed()
                .thenComparing(item -> String.valueOf(item.getOrDefault("name", ""))))
            .limit(limit)
            .toList();
    }

    private Map<String, Object> scored(EnterpriseMetadataRecord record,
                                       String originalQuery,
                                       Set<String> queryTokens) {
        String normalizedQuery = normalize(originalQuery);
        List<String> candidateValues = flattenedValues(record);
        double score = 0.0D;
        for (String candidate : candidateValues) {
            String normalized = normalize(candidate);
            if (normalized == null) continue;
            if (normalized.equals(normalizedQuery)) {
                score += properties.getSearchWeights().getExact();
            } else if (normalized.contains(normalizedQuery) || normalizedQuery.contains(normalized)) {
                score += properties.getSearchWeights().getContains();
            }
            long matches = queryTokens.stream()
                .filter(token -> token.length() > 1 && normalized.contains(token))
                .count();
            score += Math.min(1.0D, matches / (double) Math.max(1, queryTokens.size()))
                * properties.getSearchWeights().getToken();
        }
        if ("标准".equals(record.status()) || "active".equalsIgnoreCase(record.status())) {
            score += properties.getSearchWeights().getStandard();
        }
        Map<String, Object> result = new LinkedHashMap<>(record.toMap());
        result.put("relevanceScore", Math.round(score * 10_000D) / 10_000D);
        result.put("physicalIndex", properties.getIndexName());
        result.put("retrievalMode", "memory_lexical");
        return Map.copyOf(result);
    }

    private List<String> scenarioCodes(EnterpriseMetadataRecord record) {
        Object value = record.attributes().get("scenarioCodes");
        if (value instanceof Iterable<?> iterable) {
            List<String> result = new ArrayList<>();
            iterable.forEach(item -> {
                String normalized = normalize(String.valueOf(item));
                if (normalized != null) {
                    result.add(normalized);
                }
            });
            return result;
        }
        String normalized = normalize(value == null ? null : String.valueOf(value));
        return normalized == null ? List.of() : List.of(normalized);
    }

    private String expandedQuery(String query) {
        Set<String> values = new LinkedHashSet<>();
        values.add(query);
        Set<String> queryTokens = tokens(query);
        catalog.records().stream()
            .filter(record -> "metadata_term".equals(record.metadataType()))
            .filter(record -> {
                String name = normalize(record.name());
                return name != null && queryTokens.stream().anyMatch(name::contains);
            })
            .limit(30)
            .forEach(record -> {
                add(values, record.name());
                add(values, record.technicalName());
                add(values, record.attributes().get("englishName"));
                add(values, record.attributes().get("abbreviation"));
            });
        return String.join(" ", values);
    }

    private Map<String, Object> evidenceObject(Map<String, Object> result) {
        String metadataType = String.valueOf(result.getOrDefault("metadataType", "metadata"));
        String id = String.valueOf(result.getOrDefault("id", ""));
        double score = result.get("relevanceScore") instanceof Number number ? number.doubleValue() : 0.0D;
        Map<String, Object> content = new LinkedHashMap<>(result);
        content.remove("relevanceScore");
        content.remove("physicalIndex");
        return Map.of(
            "evidenceId", "EM-" + digest(metadataType + ":" + id),
            "type", metadataType,
            "source", String.valueOf(result.getOrDefault("logicalIndex", properties.getIndexName())),
            "content", Map.copyOf(content),
            "confidence", confidence(score),
            "quality", Map.of(
                "sourceAuthority", "enterprise_standard",
                "traceable", true,
                "containsSampleValue", false
            )
        );
    }

    private List<String> flattenedValues(EnterpriseMetadataRecord record) {
        List<String> values = new ArrayList<>();
        add(values, record.name());
        add(values, record.technicalName());
        add(values, record.description());
        record.attributes().values().forEach(value -> add(values, value));
        return values;
    }

    private Set<String> tokens(String value) {
        String normalized = normalize(value);
        if (normalized == null) return Set.of();
        Set<String> values = new LinkedHashSet<>();
        for (String part : normalized.split("[\\s_\\-/.,，。;；:：()（）]+")) {
            if (!part.isBlank()) values.add(part);
            if (containsCjk(part) && part.length() > 2) {
                for (int index = 0; index < part.length() - 1; index++) {
                    values.add(part.substring(index, index + 2));
                }
            }
        }
        return values;
    }

    private boolean containsCjk(String value) {
        return value.codePoints().anyMatch(codePoint ->
            Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private List<String> normalizeTypes(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
            .map(this::normalize)
            .filter(java.util.Objects::nonNull)
            .map(value -> switch (value) {
                case "field", "standard_field" -> "metadata_field";
                case "term", "root", "root_word" -> "metadata_term";
                case "dictionary", "code_dictionary" -> "metadata_dictionary";
                default -> value;
            })
            .distinct()
            .toList();
    }

    private List<String> normalizeValues(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(this::normalize).filter(java.util.Objects::nonNull).distinct().toList();
    }

    private double confidence(double score) {
        return Math.round(Math.min(0.99D, 0.55D + Math.max(0.0D, score) * 0.15D) * 10_000D) / 10_000D;
    }

    private String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 8).toUpperCase(Locale.ROOT);
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode()).toUpperCase(Locale.ROOT);
        }
    }

    private void add(java.util.Collection<String> target, Object value) {
        String text = text(value);
        if (text != null) target.add(text);
    }

    private String normalize(String value) {
        String text = text(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    public record SearchRequest(String query,
                                List<String> types,
                                List<String> statuses,
                                List<String> scenarios,
                                Integer limit) {
        public SearchRequest {
            types = types == null ? List.of() : List.copyOf(types);
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        }

        public SearchRequest(String query, List<String> types, List<String> statuses, Integer limit) {
            this(query, types, statuses, List.of(), limit);
        }
    }
}
