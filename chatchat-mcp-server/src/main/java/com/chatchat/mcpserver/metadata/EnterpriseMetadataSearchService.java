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
    public static final String REQUIRED_BUNDLE_SCHEMA_VERSION = "enterprise_metadata_search_result.v3";
    private final EnterpriseMetadataCatalog catalog;
    private final EnterpriseMetadataProperties properties;
    private final OpenSearchMcpSearchService openSearch;
    private final EnterpriseMetadataScenarioClassifier scenarioClassifier;
    private final EnterpriseMetadataVectorizer vectorizer;
    private final MetadataGovernancePolicyService policyService;

    public EnterpriseMetadataSearchService(EnterpriseMetadataCatalog catalog,
                                           EnterpriseMetadataProperties properties,
                                           OpenSearchMcpSearchService openSearch,
                                           EnterpriseMetadataScenarioClassifier scenarioClassifier,
                                           EnterpriseMetadataVectorizer vectorizer,
                                           MetadataGovernancePolicyService policyService) {
        this.catalog = catalog;
        this.properties = properties;
        this.openSearch = openSearch;
        this.scenarioClassifier = scenarioClassifier;
        this.vectorizer = vectorizer;
        this.policyService = policyService;
    }

    public Map<String, Object> search(SearchRequest request) {
        return search(request, false);
    }

    /**
     * Executes the MCP capability contract that always attempts all governed
     * enterprise metadata categories while preserving the caller's total limit.
     */
    public Map<String, Object> searchRequiredBundle(SearchRequest request) {
        return search(request, true);
    }

    private Map<String, Object> search(SearchRequest request, boolean requiredBundle) {
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
        List<String> requestedTypes = normalizeTypes(effective.types());
        List<String> statuses = normalizeValues(effective.statuses());
        List<String> scenarios = normalizeValues(effective.scenarios());
        int limit = requestedLimit(effective.limit());
        String expandedQuery = expandedQuery(query);

        String semanticQuery = scenarioClassifier.enrichQuery(expandedQuery);
        List<Float> queryVector = properties.getKnn().isEnabled()
            ? vectorizer.vectorize(semanticQuery)
            : List.of();
        List<Map<String, Object>> results;
        String backend;
        List<SearchBucket> buckets = List.of();
        List<String> requiredTypes = requiredTypes();
        if (requiredBundle) {
            buckets = new ArrayList<>();
            for (String type : requiredTypes) {
                buckets.add(searchRequiredType(
                    query, expandedQuery, type, statuses, scenarios, queryVector, limit));
            }
            results = mergeBucketsWithinTotalLimit(buckets, limit);
            backend = aggregateBackend(buckets);
        } else {
            results = searchOpenSearch(
                expandedQuery, requestedTypes, statuses, scenarios, queryVector, limit);
            backend = "opensearch";
            if (results.isEmpty()) {
                results = searchMemory(
                    query, expandedQuery, requestedTypes, statuses, scenarios, limit);
                backend = "memory";
            }
        }
        Map<String, Object> countsByType = countsByType(results, requiredTypes);
        Map<String, Object> retrievedCountsByType = requiredBundle
            ? bucketCountsByType(buckets, requiredTypes)
            : countsByType;

        List<Map<String, Object>> evidence = results.stream()
            .map(this::evidenceObject)
            .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemaVersion",
            requiredBundle ? REQUIRED_BUNDLE_SCHEMA_VERSION : RESULT_SCHEMA_VERSION);
        response.put("success", true);
        response.put("query", query);
        response.put("expandedQuery", expandedQuery);
        response.put("semanticQuery", semanticQuery);
        response.put("requestedTypes", requestedTypes);
        if (requiredBundle) {
            response.put("requiredTypes", requiredTypes);
        }
        response.put("detectedScenarios", scenarioClassifier.classifyQuery(query));
        response.put("backend", backend);
        response.put("retrievalMode", aggregateRetrievalMode(results, backend));
        response.put("count", results.size());
        response.put("countsByType", countsByType);
        if (requiredBundle) {
            List<String> emptyTypes = requiredTypes.stream()
                .filter(type -> ((Number) retrievedCountsByType.getOrDefault(type, 0)).intValue() == 0)
                .toList();
            List<String> omittedTypes = requiredTypes.stream()
                .filter(type -> ((Number) retrievedCountsByType.getOrDefault(type, 0)).intValue() > 0)
                .filter(type -> ((Number) countsByType.getOrDefault(type, 0)).intValue() == 0)
                .toList();
            response.put("requiredRetrieval", Map.of(
                "allTypesAttempted", true,
                "evidenceComplete", emptyTypes.isEmpty() && omittedTypes.isEmpty(),
                "policy", "enterprise_metadata_search_always_queries_standard_fields_terms_and_dictionaries",
                "types", requiredTypes,
                "attemptedTypes", requiredTypes,
                "emptyTypes", emptyTypes,
                "omittedTypes", omittedTypes,
                "retrievedCountsByType", retrievedCountsByType,
                "returnedCountsByType", countsByType
            ));
        }
        response.put("results", results);
        response.put("evidenceObjects", evidence);
        response.put("evidencePolicy", Map.of(
            "factBoundary", "Only returned enterprise metadata records may be used as factual field or term definitions",
            "sampleValuesIncluded", false,
            "sourceAuthority", "configured_enterprise_metadata_catalog"
        ));
        response.put("claimCoverage", policyService.claimCoverage());
        return response;
    }

    private SearchBucket searchRequiredType(String originalQuery,
                                            String expandedQuery,
                                            String type,
                                            List<String> statuses,
                                            List<String> scenarios,
                                            List<Float> queryVector,
                                            int limit) {
        List<String> singleType = List.of(type);
        List<Map<String, Object>> results = searchOpenSearch(
            expandedQuery, singleType, statuses, scenarios, queryVector, limit);
        if (!results.isEmpty()) {
            return new SearchBucket(type, results, "opensearch");
        }
        return new SearchBucket(
            type,
            searchMemory(originalQuery, expandedQuery, singleType, statuses, scenarios, limit),
            "memory"
        );
    }

    private List<Map<String, Object>> mergeBucketsWithinTotalLimit(List<SearchBucket> buckets,
                                                                   int totalLimit) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        List<SearchBucket> safeBuckets = buckets == null ? List.of() : buckets;
        int index = 0;
        while (merged.size() < totalLimit) {
            boolean added = false;
            for (SearchBucket bucket : safeBuckets) {
                if (index >= bucket.results().size()) {
                    continue;
                }
                Map<String, Object> result = bucket.results().get(index);
                String key = String.valueOf(result.getOrDefault("metadataType", bucket.type()))
                    + ":" + String.valueOf(result.getOrDefault("id", merged.size()));
                if (merged.putIfAbsent(key, result) == null) {
                    added = true;
                    if (merged.size() >= totalLimit) {
                        break;
                    }
                }
            }
            if (!added) {
                boolean exhausted = true;
                for (SearchBucket bucket : safeBuckets) {
                    if (index < bucket.results().size()) {
                        exhausted = false;
                        break;
                    }
                }
                if (exhausted) {
                    break;
                }
            }
            index++;
        }
        return List.copyOf(merged.values());
    }

    private Map<String, Object> bucketCountsByType(List<SearchBucket> buckets,
                                                   List<String> requiredTypes) {
        Map<String, Object> counts = new LinkedHashMap<>();
        requiredTypes.forEach(type -> counts.put(type, 0));
        for (SearchBucket bucket : buckets == null ? List.<SearchBucket>of() : buckets) {
            counts.put(bucket.type(), bucket.results().size());
        }
        return counts;
    }

    private String aggregateRetrievalMode(List<Map<String, Object>> results, String backend) {
        if ("memory".equals(backend)) {
            return "memory_lexical";
        }
        Set<String> modes = new LinkedHashSet<>();
        for (Map<String, Object> result : results == null ? List.<Map<String, Object>>of() : results) {
            Object mode = result.get("retrievalMode");
            if (mode != null && !String.valueOf(mode).isBlank()) {
                modes.add(String.valueOf(mode));
            }
        }
        if (modes.isEmpty()) {
            return "bm25";
        }
        return modes.size() == 1 ? modes.iterator().next() : "mixed";
    }

    private Map<String, Object> countsByType(List<Map<String, Object>> results,
                                             List<String> requiredTypes) {
        Map<String, Object> counts = new LinkedHashMap<>();
        requiredTypes.forEach(type -> counts.put(type, 0));
        for (Map<String, Object> result : results == null ? List.<Map<String, Object>>of() : results) {
            String type = normalize(String.valueOf(result.get("metadataType")));
            if (type != null) {
                counts.put(type, ((Number) counts.getOrDefault(type, 0)).intValue() + 1);
            }
        }
        return counts;
    }

    private String aggregateBackend(List<SearchBucket> buckets) {
        Set<String> backends = new LinkedHashSet<>();
        for (SearchBucket bucket : buckets == null ? List.<SearchBucket>of() : buckets) {
            backends.add(bucket.backend());
        }
        if (backends.isEmpty()) {
            return "none";
        }
        if (backends.size() == 1) {
            return backends.iterator().next();
        }
        return "mixed";
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
                    Math.max(properties.getKnn().getCandidateLimit(), limit),
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
                score += searchPolicy().getExactWeight();
            } else if (normalized.contains(normalizedQuery) || normalizedQuery.contains(normalized)) {
                score += searchPolicy().getContainsWeight();
            }
            long matches = queryTokens.stream()
                .filter(token -> token.length() > 1 && normalized.contains(token))
                .count();
            score += Math.min(1.0D, matches / (double) Math.max(1, queryTokens.size()))
                * searchPolicy().getTokenWeight();
        }
        if (normalizeValues(searchPolicy().getPreferredStatuses())
            .contains(normalize(record.status()))) {
            score += searchPolicy().getPreferredStatusWeight();
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
            .filter(record -> metadataContract().getTermType()
                .equals(record.metadataType()))
            .filter(record -> {
                String name = normalize(record.name());
                return name != null && queryTokens.stream().anyMatch(name::contains);
            })
            .limit(searchPolicy().getTermExpansionLimit())
            .forEach(record -> {
                add(values, record.name());
                add(values, record.technicalName());
                add(values, record.attributes().get(
                    metadataContract().getEnglishNameAttribute()));
                add(values, record.attributes().get(
                    metadataContract().getAbbreviationAttribute()));
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
            "contractVersion", "evidence_object_v1",
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
            .map(value -> metadataContract().getTypeAliases()
                .getOrDefault(value, value))
            .distinct()
            .toList();
    }

    private List<String> normalizeValues(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(this::normalize).filter(java.util.Objects::nonNull).distinct().toList();
    }

    private double confidence(double score) {
        MetadataGovernancePolicy.SearchPolicy weights = searchPolicy();
        return Math.round(Math.min(
            weights.getConfidenceMaximum(),
            weights.getConfidenceBase() + Math.max(0.0D, score) * weights.getConfidenceSlope())
            * 10_000D) / 10_000D;
    }

    private List<String> requiredTypes() {
        List<String> configured = metadataContract().getRequiredBundle();
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException("Enterprise metadata required bundle is not configured");
        }
        return configured.stream().map(this::normalize).filter(java.util.Objects::nonNull)
            .distinct().toList();
    }

    private int requestedLimit(Integer value) {
        int requested = value == null ? properties.getDefaultLimit() : value;
        if (requested < 1) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        return requested;
    }

    private MetadataGovernancePolicy.MetadataContract metadataContract() {
        return policyService.current().getMetadataContract();
    }

    private MetadataGovernancePolicy.SearchPolicy searchPolicy() {
        return policyService.current().getSearch();
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

    private record SearchBucket(String type, List<Map<String, Object>> results, String backend) {
        private SearchBucket {
            results = results == null ? List.of() : List.copyOf(results);
            backend = backend == null || backend.isBlank() ? "unknown" : backend;
        }
    }
}
