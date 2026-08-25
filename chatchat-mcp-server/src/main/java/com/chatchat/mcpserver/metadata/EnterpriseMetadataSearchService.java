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
    public static final String CARDINALITY_SCHEMA_VERSION = "enterprise_metadata_search_result.v4";
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

    /**
     * Searches independent metadata requirements without exposing the expanded
     * candidate pool.  The public result cardinality can never exceed the
     * caller's requirement cardinality; unmatched or ambiguous requirements are
     * retained as explicit slots instead of being filled with noisy candidates.
     */
    public Map<String, Object> searchRequirements(SearchRequest request,
                                                   List<String> requirements) {
        SearchRequest effective = request == null
            ? new SearchRequest(null, List.of(), List.of(), List.of(), null)
            : request;
        List<String> demand = requirementTerms(requirements, effective.query());
        if (demand.isEmpty()) {
            throw new IllegalArgumentException("query or queryTerms is required");
        }
        if (effective.limit() != null && effective.limit() < 1) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        // queryTerms is the public demand contract.  A legacy limit controls how
        // many candidates are inspected for each term, but must never silently
        // discard terms supplied by the caller.
        int requested = demand.size();
        int candidateHint = effective.limit() == null ? requested
            : Math.max(requested, effective.limit());
        int candidateLimit = expandedCandidateLimit(Math.max(1, candidateHint));
        List<Map<String, Object>> selected = new ArrayList<>();
        List<Map<String, Object>> slots = new ArrayList<>();
        Set<String> backends = new LinkedHashSet<>();

        for (int index = 0; index < demand.size(); index++) {
            String requirement = demand.get(index);
            Map<String, Object> retrieval = search(new SearchRequest(
                requirement,
                effective.types(),
                effective.statuses(),
                effective.scenarios(),
                candidateLimit
            ), true);
            backends.add(String.valueOf(retrieval.getOrDefault("backend", "none")));
            List<Map<String, Object>> retrievedCandidates = maps(retrieval.get("results"));
            // Requirements are independent slots.  Two synonymous query terms
            // may legitimately resolve to the same governed record; collapsing
            // that record here makes the returned row count unrelated to the
            // caller's keyword count.
            List<Map<String, Object>> candidates = retrievedCandidates;
            Selection selection = selectCandidate(candidates);
            Map<String, Object> retrievalCoverage = objectMap(retrieval.get("requiredRetrieval"));
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("requirementIndex", index);
            slot.put("requirement", requirement);
            slot.put("retrievedCandidateCount", retrievedCandidates.size());
            slot.put("eligibleCandidateCount", candidates.size());
            slot.put("duplicateCandidateCount", 0);
            slot.put("allMetadataTypesAttempted",
                Boolean.TRUE.equals(retrievalCoverage.get("allTypesAttempted")));
            slot.put("retrievedCountsByType",
                objectMap(retrievalCoverage.get("retrievedCountsByType")));
            slot.put("matched", selection.selected() != null);
            slot.put("selectionStatus", selection.status());
            slot.put("qualityThreshold", searchPolicy().getMinimumQualityScore());
            slot.put("selectionMargin", selection.margin());
            if (selection.selected() != null) {
                Map<String, Object> result = requirementResult(
                    selection.selected(), index, requirement);
                selected.add(result);
                slot.put("selectedResult", selectionReference(result));
            }
            slots.add(Map.copyOf(slot));
        }

        List<Map<String, Object>> evidence = selected.stream().map(this::evidenceObject).toList();
        List<String> requiredTypes = requiredTypes();
        Map<String, Object> countsByType = countsByType(selected, requiredTypes);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemaVersion", CARDINALITY_SCHEMA_VERSION);
        response.put("success", true);
        response.put("query", effective.query());
        response.put("inputTerms", List.copyOf(demand));
        response.put("requestedRequirementCount", demand.size());
        response.put("returnedMetadataCount", selected.size());
        response.put("matchedRequirementCount", selected.size());
        response.put("unmatchedRequirementCount", demand.size() - selected.size());
        response.put("cardinalityPreserved", selected.size() <= demand.size());
        response.put("keywordCardinalitySatisfied", selected.size() == demand.size());
        response.put("candidateExpansionInternalOnly", true);
        response.put("candidateReturnPolicy", "ONE_OR_ZERO_PER_REQUIREMENT");
        response.put("backend", aggregateValues(backends));
        response.put("count", selected.size());
        response.put("countsByType", countsByType);
        response.put("requirementMatches", List.copyOf(slots));
        response.put("results", List.copyOf(selected));
        response.put("evidenceObjects", evidence);
        response.put("evidenceBundle", evidenceBundle(
            String.join(" ", demand), scenarioClassifier.classifyQuery(String.join(" ", demand)),
            evidence, countsByType, Map.of(
                "cardinalityPreserved", true,
                "requestedRequirementCount", demand.size(),
                "returnedMetadataCount", selected.size()
            ), true));
        response.put("evidencePolicy", Map.of(
            "factBoundary", "Only selected enterprise metadata records may be used as factual field or term definitions",
            "sampleValuesIncluded", false,
            "sourceAuthority", "configured_enterprise_metadata_catalog"
        ));
        response.put("evidenceCoverage", policyService.evidenceCoverage());
        return Map.copyOf(response);
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
        int candidateLimit = expandedCandidateLimit(limit);
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
                    query, expandedQuery, type, statuses, scenarios, queryVector, candidateLimit));
            }
            results = rerankAndFilter(query,
                mergeBucketsWithinTotalLimit(buckets, candidateLimit), limit);
            backend = aggregateBackend(buckets);
        } else {
            results = searchOpenSearch(
                expandedQuery, requestedTypes, statuses, scenarios, queryVector, candidateLimit);
            backend = "opensearch";
            if (results.isEmpty()) {
                results = searchMemory(
                    query, expandedQuery, requestedTypes, statuses, scenarios, candidateLimit);
                backend = "memory";
            }
            results = rerankAndFilter(query, results, limit);
        }
        Map<String, Object> countsByType = countsByType(results, requiredTypes);
        Map<String, Object> retrievedCountsByType = requiredBundle
            ? bucketCountsByType(buckets, requiredTypes)
            : countsByType;

        List<String> detectedScenarios = scenarioClassifier.classifyQuery(query);
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
        response.put("detectedScenarios", detectedScenarios);
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
        response.put("evidenceBundle", evidenceBundle(
            query,
            detectedScenarios,
            evidence,
            countsByType,
            response.get("requiredRetrieval")
        ));
        response.put("evidencePolicy", Map.of(
            "factBoundary", "Only returned enterprise metadata records may be used as factual field or term definitions",
            "sampleValuesIncluded", false,
            "sourceAuthority", "configured_enterprise_metadata_catalog"
        ));
        response.put("evidenceCoverage", policyService.evidenceCoverage());
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

    private List<Map<String, Object>> rerankAndFilter(String query,
                                                       List<Map<String, Object>> candidates,
                                                       int limit) {
        return (candidates == null ? List.<Map<String, Object>>of() : candidates).stream()
            .map(candidate -> qualityScored(query, candidate))
            .filter(candidate -> numericScore(candidate.get("qualityScore"))
                >= searchPolicy().getMinimumQualityScore())
            .sorted(Comparator
                .comparingDouble((Map<String, Object> candidate) ->
                    numericScore(candidate.get("qualityScore"))).reversed()
                .thenComparing(candidate -> String.valueOf(candidate.getOrDefault("name", ""))))
            .limit(limit)
            .toList();
    }

    private Map<String, Object> qualityScored(String query, Map<String, Object> candidate) {
        double lexical = lexicalQuality(query, candidate);
        double retrieval = Math.max(0.0D,
            Math.min(1.0D, numericScore(candidate.get("relevanceScore"))));
        boolean preferred = normalizeValues(searchPolicy().getPreferredStatuses())
            .contains(normalize(String.valueOf(candidate.get("status"))));
        double quality = lexical * searchPolicy().getLexicalQualityWeight()
            + retrieval * searchPolicy().getRetrievalQualityWeight()
            + (preferred ? searchPolicy().getStatusQualityWeight() : 0.0D);
        Map<String, Object> result = new LinkedHashMap<>(candidate);
        result.put("lexicalQualityScore", round(lexical));
        result.put("qualityScore", round(quality));
        result.put("qualityGatePassed", quality >= searchPolicy().getMinimumQualityScore());
        return Map.copyOf(result);
    }

    private double lexicalQuality(String query, Map<String, Object> candidate) {
        String normalizedQuery = compact(query);
        if (normalizedQuery.isEmpty()) return 0.0D;
        List<String> values = new ArrayList<>();
        add(values, candidate.get("name"));
        add(values, candidate.get("technicalName"));
        add(values, candidate.get("description"));
        add(values, candidate.get(metadataContract().getEnglishNameAttribute()));
        add(values, candidate.get(metadataContract().getAbbreviationAttribute()));
        double best = 0.0D;
        Set<String> queryTokens = tokens(query);
        for (String value : values) {
            String normalizedValue = compact(value);
            if (normalizedValue.equals(normalizedQuery)) return 1.0D;
            if (normalizedValue.contains(normalizedQuery) || normalizedQuery.contains(normalizedValue)) {
                best = Math.max(best, 0.90D);
            }
            Set<String> candidateTokens = tokens(value);
            Set<String> overlap = new LinkedHashSet<>(queryTokens);
            overlap.retainAll(candidateTokens);
            double coverage = overlap.size() / (double) Math.max(1, queryTokens.size());
            double precision = overlap.size() / (double) Math.max(1, candidateTokens.size());
            best = Math.max(best, coverage * 0.7D + precision * 0.3D);
        }
        return Math.min(1.0D, best);
    }

    private Selection selectCandidate(List<Map<String, Object>> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new Selection(null, "NO_QUALIFIED_CANDIDATE", 0.0D);
        }
        Map<String, Object> first = candidates.get(0);
        double firstScore = numericScore(first.get("qualityScore"));
        double margin = candidates.size() < 2 ? firstScore
            : firstScore - numericScore(candidates.get(1).get("qualityScore"));
        if (candidates.size() > 1
            && margin < searchPolicy().getMinimumSelectionMargin()
            && numericScore(first.get("lexicalQualityScore")) < 0.85D) {
            return new Selection(null, "AMBIGUOUS_TOP_CANDIDATES", round(margin));
        }
        return new Selection(first, "MATCHED", round(margin));
    }

    private int expandedCandidateLimit(int requested) {
        return Math.max(requested, Math.min(500,
            requested * Math.max(1, searchPolicy().getCandidateExpansionFactor())));
    }

    private List<String> requirementTerms(List<String> requirements, String fallbackQuery) {
        List<String> result = new ArrayList<>();
        if (requirements != null) {
            for (String requirement : requirements) {
                String value = text(requirement);
                if (value != null) result.add(value);
            }
        }
        if (result.isEmpty()) {
            String value = text(fallbackQuery);
            if (value != null) result.add(value);
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> converted = new LinkedHashMap<>();
                map.forEach((key, itemValue) -> converted.put(String.valueOf(key), itemValue));
                result.add(Map.copyOf(converted));
            }
        }
        return List.copyOf(result);
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, itemValue) -> result.put(String.valueOf(key), itemValue));
        return Map.copyOf(result);
    }

    private Map<String, Object> requirementResult(Map<String, Object> result,
                                                  int requirementIndex,
                                                  String requirement) {
        Map<String, Object> scoped = new LinkedHashMap<>(result);
        scoped.put("requirementIndex", requirementIndex);
        scoped.put("matchedQueryTerm", requirement);
        return Map.copyOf(scoped);
    }

    private Map<String, Object> selectionReference(Map<String, Object> result) {
        Map<String, Object> reference = new LinkedHashMap<>();
        copyPresent(reference, result, "id", "metadataType", "name", "technicalName",
            "qualityScore", "lexicalQualityScore");
        return Map.copyOf(reference);
    }

    private String aggregateValues(Set<String> values) {
        if (values == null || values.isEmpty()) return "none";
        return values.size() == 1 ? values.iterator().next() : "mixed";
    }

    private String compact(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private double round(double value) {
        return Math.round(value * 10_000D) / 10_000D;
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
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("contractVersion", "evidence_object_v1");
        evidence.put("evidenceId", "EM-" + digest(metadataType + ":" + id));
        evidence.put("type", metadataType);
        evidence.put("evidenceType", standardEvidenceType(metadataType));
        evidence.put("evidenceRole", "STANDARD_EVIDENCE");
        evidence.put("source", String.valueOf(result.getOrDefault("logicalIndex", properties.getIndexName())));
        evidence.put("targetConcept", firstPresentText(result.get("name"), result.get("technicalName")));
        evidence.put("facts", standardEvidenceFacts(result));
        evidence.put("content", Map.copyOf(content));
        evidence.put("confidence", confidence(score));
        evidence.put("assumptions", List.of());
        evidence.put("constraints", List.of(
            "Describes an enterprise standard reference, not the target object's physical schema",
            "Returned enterprise metadata is standard reference data; the model determines conclusions from all available evidence"
        ));
        evidence.put("quality", Map.of(
                "sourceAuthority", "enterprise_standard",
                "traceable", true,
                "containsSampleValue", false
            ));
        return Map.copyOf(evidence);
    }

    private Map<String, Object> evidenceBundle(String query,
                                               List<String> detectedScenarios,
                                               List<Map<String, Object>> evidence,
                                               Map<String, Object> countsByType,
                                               Object requiredRetrieval) {
        return evidenceBundle(query, detectedScenarios, evidence, countsByType,
            requiredRetrieval, false);
    }

    private Map<String, Object> evidenceBundle(String query,
                                               List<String> detectedScenarios,
                                               List<Map<String, Object>> evidence,
                                               Map<String, Object> countsByType,
                                               Object requiredRetrieval,
                                               boolean cardinalityBounded) {
        List<Map<String, Object>> standardReferences = evidence.stream()
            .map(item -> {
                Map<String, Object> reference = new LinkedHashMap<>();
                copyPresent(reference, item, "evidenceId", "evidenceType", "source",
                    "targetConcept", "facts", "confidence", "constraints");
                return Map.copyOf(reference);
            })
            .toList();
        List<Map<String, Object>> selectedStandardReferences = cardinalityBounded
            ? standardReferences : highestConfidenceReference(standardReferences);
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("contractVersion", "enterprise_metadata_evidence_bundle.v1");
        bundle.put("targetContext", Map.of(
            "query", query,
            "detectedScenarioCandidates", detectedScenarios == null ? List.of() : detectedScenarios
        ));
        bundle.put("factEvidence", Map.of(
            "status", "NOT_PROVIDED_BY_THIS_TOOL",
            "items", List.of(),
            "meaning", "No target database/table/column fact is established by enterprise-standard retrieval"
        ));
        bundle.put("standardEvidence", Map.of(
            "status", standardReferences.isEmpty() ? "EMPTY_RESULT" : "DATA_RETURNED",
            "count", standardReferences.size(),
            "selectedCount", selectedStandardReferences.size(),
            "countsByType", countsByType == null ? Map.of() : countsByType,
            "items", selectedStandardReferences
        ));
        bundle.put("inferenceEvidence", Map.of(
            "status", "MODEL_REASONING_REQUIRED",
            "items", List.of(),
            "guidance", List.of(
                "Use standard-field references as comparison candidates, never as observed target columns",
                "Use term references to interpret business meaning without asserting the target entity",
                "Use dictionary references to propose a validation checkpoint, not to assert target code values",
                "Label every domain interpretation or design recommendation as inference until target facts are available"
            )
        ));
        bundle.put("retrievalCoverage", requiredRetrieval == null ? Map.of() : requiredRetrieval);
        bundle.put("reasoningContract", Map.of(
            "factAndStandardEvidenceSeparated", true,
            "inferenceMustBeLabeled", true,
            "rawResultsAreRetrievalCandidates", true,
            "standardReferencesDoNotProveTargetSchema", true,
            "candidateReturnPolicy", cardinalityBounded
                ? "ONE_OR_ZERO_PER_REQUIREMENT" : "ALL_RETRIEVED_CANDIDATES_IN_RESULTS",
            "reasoningSelectionPolicy", cardinalityBounded
                ? "QUALITY_GATE_THEN_HIGHEST_SCORE" : "HIGHEST_CONFIDENCE_ONE"
        ));
        return Map.copyOf(bundle);
    }

    private List<Map<String, Object>> highestConfidenceReference(
        List<Map<String, Object>> references
    ) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        Map<String, Object> selected = references.get(0);
        double highest = numericScore(selected.get("confidence"));
        for (int index = 1; index < references.size(); index++) {
            Map<String, Object> candidate = references.get(index);
            double score = numericScore(candidate.get("confidence"));
            if (score > highest) {
                selected = candidate;
                highest = score;
            }
        }
        return List.of(selected);
    }

    private double numericScore(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? Double.NEGATIVE_INFINITY
                : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return Double.NEGATIVE_INFINITY;
        }
    }

    private Map<String, Object> standardEvidenceFacts(Map<String, Object> result) {
        Map<String, Object> facts = new LinkedHashMap<>();
        copyPresent(facts, result, "id", "metadataType", "name", "technicalName",
            "description", "dataType", "status", "source", "logicalIndex");
        return Map.copyOf(facts);
    }

    private String standardEvidenceType(String metadataType) {
        return switch (normalize(metadataType)) {
            case "metadata_field" -> "STANDARD_FIELD_REFERENCE";
            case "metadata_term" -> "BUSINESS_TERM_REFERENCE";
            case "metadata_dictionary" -> "CODE_DICTIONARY_REFERENCE";
            default -> "ENTERPRISE_METADATA_REFERENCE";
        };
    }

    private void copyPresent(Map<String, Object> target,
                             Map<String, Object> source,
                             String... keys) {
        if (target == null || source == null || keys == null) {
            return;
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && (!(value instanceof String text) || !text.isBlank())) {
                target.put(key, value);
            }
        }
    }

    private String firstPresentText(Object first, Object second) {
        String firstText = first == null ? null : String.valueOf(first).trim();
        if (firstText != null && !firstText.isBlank()) {
            return firstText;
        }
        String secondText = second == null ? null : String.valueOf(second).trim();
        return secondText == null ? "" : secondText;
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

    private record Selection(Map<String, Object> selected, String status, double margin) {
    }
}
