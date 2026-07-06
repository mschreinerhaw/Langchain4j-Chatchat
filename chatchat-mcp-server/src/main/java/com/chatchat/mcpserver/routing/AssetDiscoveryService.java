package com.chatchat.mcpserver.routing;

import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.SshHostConfigService;
import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@Slf4j
public class AssetDiscoveryService {

    public static final String QUERY_SCHEMA_VERSION = "asset_query.v1";
    public static final String RESULT_SCHEMA_VERSION = "asset_query_result.v1";
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 20;
    private static final double FUZZY_NAME_MIN_SCORE = 0.46D;
    private static final double FUZZY_NAME_NEAR_TIE_DELTA = 0.08D;

    private static final List<String> CONTEXT_FILTER_KEYS = List.of(
        "env",
        "environment",
        "cluster",
        "namespace",
        "target",
        "targetType",
        "target_type",
        "assetName",
        "asset_name",
        "name",
        "database",
        "databaseType",
        "dbType",
        "dialect",
        "databaseRole",
        "database_role",
        "service",
        "labels"
    );

    private static final List<String> RETRIEVAL_FILTER_KEYS = List.of(
        "intent",
        "goal",
        "query",
        "q",
        "bilingualIntent",
        "bilingualQuery",
        "intentZh",
        "intentEn",
        "intentAliases",
        "keywords",
        "keyword",
        "queryTerms",
        "searchTerms",
        "retrievalSignals",
        "intentCandidates",
        "intent_candidates"
    );

    private static final List<String> CONCRETE_TARGET_FIELDS = List.of(
        "hostId",
        "host",
        "hostname",
        "ip",
        "ipAddress",
        "address",
        "datasourceId",
        "jdbcUrl",
        "url",
        "connectionString",
        "endpointId",
        "uri"
    );

    private final SshHostConfigService hostConfigService;
    private final SqlDatasourceConfigService datasourceConfigService;
    private final HttpEndpointConfigService httpEndpointConfigService;
    private final AssetMetadataFactory assetMetadataFactory;
    private final LuceneMcpSearchService luceneSearchService;
    private final TargetKindRegistry targetKindRegistry;

    public AssetDiscoveryService(SshHostConfigService hostConfigService,
                                 SqlDatasourceConfigService datasourceConfigService,
                                 HttpEndpointConfigService httpEndpointConfigService,
                                 AssetMetadataFactory assetMetadataFactory) {
        this(hostConfigService, datasourceConfigService, httpEndpointConfigService, assetMetadataFactory, null, new TargetKindRegistry());
    }

    @Autowired
    public AssetDiscoveryService(SshHostConfigService hostConfigService,
                                 SqlDatasourceConfigService datasourceConfigService,
                                 HttpEndpointConfigService httpEndpointConfigService,
                                 AssetMetadataFactory assetMetadataFactory,
                                 LuceneMcpSearchService luceneSearchService,
                                 TargetKindRegistry targetKindRegistry) {
        this.hostConfigService = hostConfigService;
        this.datasourceConfigService = datasourceConfigService;
        this.httpEndpointConfigService = httpEndpointConfigService;
        this.assetMetadataFactory = assetMetadataFactory;
        this.luceneSearchService = luceneSearchService;
        this.targetKindRegistry = targetKindRegistry == null ? new TargetKindRegistry() : targetKindRegistry;
    }

    public Map<String, Object> query(Map<String, Object> arguments) {
        long startedAt = System.nanoTime();
        Map<String, Object> filters = filters(arguments);
        rejectConcreteTargetFields(filters);
        boolean broadDiscovery = !hasContextFilter(filters) && !hasRetrievalFilter(filters);

        TargetKindRegistry.Resolution target = targetKindRegistry.resolveForTool(
            "asset_query",
            firstValue(arguments, "assetType", "type"),
            arguments,
            filters
        );
        String assetType = target.definition().assetType();
        filters.putIfAbsent("filtersSchemaVersion", target.filtersSchemaVersion());
        int limit = limit(arguments);
        if (target.reviewRequired()) {
            return reviewResult(target, filters, limit, startedAt);
        }
        List<Map<String, Object>> allAssets = allAssets(assetType);
        List<Map<String, Object>> matchedAll = matchingAssetsFromLucene(allAssets, assetType, filters, Math.max(limit + 1, MAX_LIMIT));
        List<Map<String, Object>> matched = matchedAll.stream().limit(limit).toList();
        List<Map<String, Object>> unavailableMatched = unavailableAssets(assetType, filters, limit);
        Map<String, Object> compactFilters = compactFilters(filters);

        return mapOf(
            "schemaVersion", RESULT_SCHEMA_VERSION,
            "querySchemaVersion", QUERY_SCHEMA_VERSION,
            "success", true,
            "view", view(arguments),
            "routingPolicyVersion", AssetMetadataFactory.ROUTING_POLICY_VERSION,
            "targetKind", target.definition().targetKind(),
            "filtersSchemaVersion", target.filtersSchemaVersion(),
            "discoveryPolicy", mapOf(
                "readOnly", true,
                "requiresContextFilter", false,
                "broadDiscovery", "allowed_redacted_candidates_only",
                "maxResults", MAX_LIMIT,
                "redaction", "concrete target fields are never returned"
            ),
            "filters", compactFilters,
            "assetType", assetType,
            "limit", limit,
            "returnedCount", matched.size(),
            "unavailableCount", unavailableMatched.size(),
            "possiblyTruncated", matchedAll.size() > limit,
            "broadDiscovery", broadDiscovery,
            "broadDiscoveryAdvice", broadDiscovery ? broadDiscoveryAdvice(matched.size()) : null,
            "emptyResultAdvice", matched.isEmpty() ? emptyResultAdvice(compactFilters, unavailableMatched) : null,
            "routingDecision", routingDecision(target, startedAt),
            "assets", matched,
            "unavailableAssets", unavailableMatched
        );
    }

    private Map<String, Object> reviewResult(TargetKindRegistry.Resolution target,
                                             Map<String, Object> filters,
                                             int limit,
                                             long startedAt) {
        return mapOf(
            "schemaVersion", RESULT_SCHEMA_VERSION,
            "querySchemaVersion", QUERY_SCHEMA_VERSION,
            "success", false,
            "status", TargetKindRegistry.DECISION_REVIEW_REQUIRED,
            "reason", "LOW_CONFIDENCE_TARGET_KIND",
            "targetKind", target.definition().targetKind(),
            "assetType", target.definition().assetType(),
            "filtersSchemaVersion", target.filtersSchemaVersion(),
            "filters", compactFilters(filters),
            "limit", limit,
            "returnedCount", 0,
            "assets", List.of(),
            "unavailableAssets", List.of(),
            "routingDecision", routingDecision(target, startedAt),
            "review", mapOf(
                "required", true,
                "reason", "confidence below routing threshold",
                "threshold", TargetKindRegistry.MIN_CONFIDENCE,
                "confidence", target.confidence()
            )
        );
    }

    private Map<String, Object> routingDecision(TargetKindRegistry.Resolution target, long startedAt) {
        return mapOf(
            "routingDecision", target.definition().targetKind() + " -> " + target.definition().assetType(),
            "targetKind", target.definition().targetKind(),
            "finalDecision", target.finalDecision(),
            "assetType", target.definition().assetType(),
            "confidence", target.confidence(),
            "threshold", TargetKindRegistry.MIN_CONFIDENCE,
            "latencyMs", elapsedMs(startedAt),
            "decision", target.decision(),
            "candidateSet", mapOf(
                "finalDecision", target.finalDecision(),
                "candidates", routingCandidates(target)
            ),
            "trace", target.trace()
        );
    }

    private List<Map<String, Object>> routingCandidates(TargetKindRegistry.Resolution target) {
        return target.candidates().stream()
            .map(candidate -> mapOf(
                "targetKind", candidate.targetKind(),
                "assetType", candidate.assetType(),
                "confidence", candidate.confidence(),
                "feasible", candidate.feasible(),
                "feasibilityReasons", candidate.feasibilityReasons(),
                "latencyEstimateMs", candidate.latencyEstimateMs(),
                "historicalSuccessRate", candidate.historicalSuccessRate(),
                "score", candidate.score()
            ))
            .toList();
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private List<Map<String, Object>> matchingAssetsFromLucene(List<Map<String, Object>> assets,
                                                               String assetType,
                                                               Map<String, Object> filters,
                                                               int limit) {
        if (luceneSearchService == null || !luceneSearchService.enabled()) {
            AssetFallback fallback = registryFallbackAssets(assets, filters, limit);
            log.info("asset_query registry search assetType={} filters={} registryCandidates={} returned={} reason=lucene_disabled fuzzyFallbackUsed={}",
                assetType, compactFilters(filters), assets.size(), fallback.assets().size(), fallback.fuzzyUsed());
            return fallback.assets();
        }
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        assets.forEach(asset -> {
            String id = assetId(asset);
            if (id != null) {
                byId.put(id, asset);
            }
        });
        LuceneMcpSearchService.AssetSearchRequest request = assetSearchRequest(assetType, filters, limit);
        List<LuceneMcpSearchService.SearchHit> hits = luceneSearchService.searchAssets(request);
        List<Map<String, Object>> luceneMatched = hits.stream()
            .map(hit -> byId.get(hit.id()))
            .filter(asset -> asset != null)
            .limit(limit)
            .toList();
        if (!luceneMatched.isEmpty()) {
            log.info("asset_query lucene search assetType={} filters={} registryCandidates={} luceneHits={} returned={} hitIds={}",
                assetType, compactFilters(filters), assets.size(), hits.size(), luceneMatched.size(),
                hits.stream().map(LuceneMcpSearchService.SearchHit::id).limit(limit).toList());
            return luceneMatched;
        }
        AssetFallback fallback = registryFallbackAssets(assets, filters, limit);
        log.info("asset_query lucene empty fallback assetType={} filters={} registryCandidates={} luceneHits=0 fallbackReturned={} fuzzyFallbackUsed={}",
            assetType, compactFilters(filters), assets.size(), fallback.assets().size(), fallback.fuzzyUsed());
        return fallback.assets();
    }

    private AssetFallback registryFallbackAssets(List<Map<String, Object>> assets, Map<String, Object> filters, int limit) {
        List<Map<String, Object>> exact = hasContextFilter(filters)
            ? assets.stream()
                .filter(asset -> matches(asset, filters))
                .limit(limit)
                .toList()
            : List.of();
        if (!exact.isEmpty()) {
            return new AssetFallback(exact, false);
        }
        List<Map<String, Object>> fuzzy = fuzzyAssetNameFallback(assets, filters, limit);
        if (!fuzzy.isEmpty() || hasContextFilter(filters) || hasRetrievalFilter(filters)) {
            return new AssetFallback(fuzzy, !fuzzy.isEmpty());
        }
        return new AssetFallback(assets.stream().limit(limit).toList(), false);
    }

    private LuceneMcpSearchService.AssetSearchRequest assetSearchRequest(String assetType,
                                                                         Map<String, Object> filters,
                                                                         int limit) {
        return new LuceneMcpSearchService.AssetSearchRequest(
            normalize(assetType),
            firstText(text(firstValue(filters, "assetName", "asset_name", "name")), retrievalText(filters)),
            text(firstValue(filters, "env", "environment")),
            text(firstValue(filters, "databaseType", "dbType", "dialect")),
            contextTokens(filters),
            limit
        );
    }

    @SuppressWarnings("unchecked")
    private LuceneMcpSearchService.AssetDoc assetDoc(Map<String, Object> metadata) {
        Map<String, Object> asset = metadata == null || !(metadata.get("asset") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        Map<String, Object> routingHints = metadata == null || !(metadata.get("routingHints") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        Map<String, Object> capabilities = metadata == null || !(metadata.get("capabilities") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        return new LuceneMcpSearchService.AssetDoc(
            text(asset.get("id")),
            text(metadata == null ? null : metadata.get("assetType")),
            text(asset.get("name")),
            text(asset.get("displayName")),
            text(asset.get("toolName")),
            text(asset.get("environment")),
            text(capabilities.get("databaseType")),
            labels(routingHints.get("labels")),
            "asset_query"
        );
    }

    @SuppressWarnings("unchecked")
    private String assetId(Map<String, Object> metadata) {
        if (metadata == null || !(metadata.get("asset") instanceof Map<?, ?> map)) {
            return null;
        }
        return text(((Map<String, Object>) map).get("id"));
    }

    private List<Map<String, Object>> allAssets(String assetType) {
        List<Map<String, Object>> assets = new ArrayList<>();
        if (assetType == null || equalsNormalized(assetType, "ssh_host")) {
            assets.addAll(safeList(hostConfigService.listEnabled()).stream().map(assetMetadataFactory::sshAsset).toList());
        }
        if (assetType == null || equalsNormalized(assetType, "sql_datasource")) {
            assets.addAll(safeList(datasourceConfigService.listEnabled()).stream().map(assetMetadataFactory::sqlDatasource).toList());
        }
        if (assetType == null || equalsNormalized(assetType, "http_endpoint")) {
            assets.addAll(safeList(httpEndpointConfigService.listEnabled()).stream().map(assetMetadataFactory::httpEndpoint).toList());
        }
        return assets;
    }

    private List<Map<String, Object>> allRegisteredAssets(String assetType) {
        List<Map<String, Object>> assets = new ArrayList<>();
        if (assetType == null || equalsNormalized(assetType, "ssh_host")) {
            assets.addAll(safeList(hostConfigService.listAll()).stream().map(assetMetadataFactory::sshAsset).toList());
        }
        if (assetType == null || equalsNormalized(assetType, "sql_datasource")) {
            assets.addAll(safeList(datasourceConfigService.listAll()).stream().map(assetMetadataFactory::sqlDatasource).toList());
        }
        if (assetType == null || equalsNormalized(assetType, "http_endpoint")) {
            assets.addAll(safeList(httpEndpointConfigService.listAll()).stream().map(assetMetadataFactory::httpEndpoint).toList());
        }
        return assets;
    }

    private List<Map<String, Object>> unavailableAssets(String assetType, Map<String, Object> filters, int limit) {
        if (!hasContextFilter(filters)) {
            return List.of();
        }
        return allRegisteredAssets(assetType).stream()
            .filter(asset -> matches(asset, filters))
            .filter(asset -> {
                Object assetNode = asset.get("asset");
                return assetNode instanceof Map<?, ?> map && !Boolean.TRUE.equals(map.get("enabled"));
            })
            .limit(limit)
            .map(this::unavailableAssetSummary)
            .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unavailableAssetSummary(Map<String, Object> metadata) {
        Map<String, Object> asset = metadata == null || !(metadata.get("asset") instanceof Map<?, ?> map)
            ? Map.of()
            : new LinkedHashMap<>((Map<String, Object>) map);
        return mapOf(
            "schemaVersion", AssetMetadataFactory.SCHEMA_VERSION,
            "kind", "asset_unavailable",
            "assetType", metadata == null ? null : metadata.get("assetType"),
            "asset", asset,
            "availability", mapOf(
                "available", false,
                "reason", "registered_but_disabled_or_not_published",
                "nextAction", "Enable and publish this asset before executing tools against it."
            )
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filters(Map<String, Object> arguments) {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (arguments == null || arguments.isEmpty()) {
            return filters;
        }
        Object rawFilters = arguments.get("filters");
        if (rawFilters instanceof Map<?, ?> map) {
            filters.putAll((Map<String, Object>) map);
        }
        Object context = firstValue(arguments, "executionContext", "mcpExecutionContext");
        if (context instanceof Map<?, ?> map) {
            filters.putAll((Map<String, Object>) map);
        }
        Object looseContext = arguments.get("context");
        if (looseContext instanceof Map<?, ?> map) {
            filters.putAll((Map<String, Object>) map);
        } else if (looseContext != null && !String.valueOf(looseContext).isBlank()) {
            filters.putIfAbsent("service", String.valueOf(looseContext).trim());
        }
        for (String key : CONTEXT_FILTER_KEYS) {
            if (arguments.get(key) != null) {
                filters.putIfAbsent(key, arguments.get(key));
            }
        }
        for (String key : RETRIEVAL_FILTER_KEYS) {
            if (arguments.get(key) != null) {
                filters.putIfAbsent(key, arguments.get(key));
            }
        }
        return filters;
    }

    private boolean matches(Map<String, Object> assetMetadata, Map<String, Object> filters) {
        Map<?, ?> asset = (Map<?, ?>) assetMetadata.get("asset");
        Map<?, ?> routingHints = (Map<?, ?>) assetMetadata.get("routingHints");
        List<String> labels = labels(routingHints == null ? null : routingHints.get("labels"));
        String env = text(firstValue(filters, "env", "environment"));
        if (env != null && !equalsNormalized(env, asset == null ? null : asset.get("environment"))) {
            return false;
        }
        String assetName = text(firstValue(filters, "assetName", "asset_name", "name"));
        if (assetName != null && !assetNameMatches(asset, assetName)) {
            return false;
        }
        return contextTokens(filters).stream().allMatch(token -> labelMatches(labels, token));
    }

    private List<Map<String, Object>> fuzzyAssetNameFallback(List<Map<String, Object>> assets,
                                                            Map<String, Object> filters,
                                                            int limit) {
        String requestedAssetName = firstText(
            text(firstValue(filters, "assetName", "asset_name", "name")),
            retrievalText(filters)
        );
        if (requestedAssetName == null) {
            return List.of();
        }
        List<AssetNameCandidate> candidates = assets.stream()
            .filter(asset -> nonNameFiltersMatch(asset, filters))
            .map(asset -> assetNameCandidate(asset, requestedAssetName))
            .filter(candidate -> candidate.score() >= FUZZY_NAME_MIN_SCORE)
            .sorted(Comparator.comparingDouble(AssetNameCandidate::score).reversed())
            .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        AssetNameCandidate best = candidates.get(0);
        if (candidates.size() > 1 && best.score() - candidates.get(1).score() < FUZZY_NAME_NEAR_TIE_DELTA) {
            log.info("asset_query fuzzy name fallback ambiguous requestedAssetName={} bestScore={} secondScore={} candidateCount={}",
                requestedAssetName, best.score(), candidates.get(1).score(), candidates.size());
            return List.of();
        }
        return candidates.stream()
            .limit(Math.max(1, limit))
            .map(candidate -> annotateFuzzyMatch(candidate.asset(), requestedAssetName, candidate.field(), candidate.score()))
            .toList();
    }

    private boolean nonNameFiltersMatch(Map<String, Object> assetMetadata, Map<String, Object> filters) {
        Map<?, ?> asset = (Map<?, ?>) assetMetadata.get("asset");
        Map<?, ?> routingHints = (Map<?, ?>) assetMetadata.get("routingHints");
        List<String> labels = labels(routingHints == null ? null : routingHints.get("labels"));
        String env = text(firstValue(filters, "env", "environment"));
        if (env != null && !equalsNormalized(env, asset == null ? null : asset.get("environment"))) {
            return false;
        }
        return contextTokens(filters).stream().allMatch(token -> labelMatches(labels, token));
    }

    private AssetNameCandidate assetNameCandidate(Map<String, Object> metadata, String requestedAssetName) {
        Map<?, ?> asset = metadata == null || !(metadata.get("asset") instanceof Map<?, ?> map) ? Map.of() : map;
        AssetNameCandidate best = new AssetNameCandidate(metadata, null, 0.0D);
        for (String field : List.of("name", "displayName", "toolName")) {
            Object value = asset.get(field);
            double score = nameSimilarity(requestedAssetName, value == null ? null : String.valueOf(value));
            if (score > best.score()) {
                best = new AssetNameCandidate(metadata, field, score);
            }
        }
        return best;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> annotateFuzzyMatch(Map<String, Object> metadata,
                                                   String requestedAssetName,
                                                   String matchedField,
                                                   double score) {
        Map<String, Object> annotated = new LinkedHashMap<>(metadata);
        Map<String, Object> routingHints = metadata.get("routingHints") instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
        routingHints.put("assetQueryMatch", mapOf(
            "strategy", "fuzzy_name_unique_candidate",
            "requestedAssetName", requestedAssetName,
            "matchedField", matchedField,
            "score", Math.round(score * 1000.0D) / 1000.0D,
            "confidence", "low",
            "reviewHint", "Matched only after exact assetName filtering returned no result; prefer canonical assets[].asset.name in downstream executionContext."
        ));
        annotated.put("routingHints", routingHints);
        return annotated;
    }

    private double nameSimilarity(String queryValue, String assetValue) {
        String query = normalizeForSimilarity(queryValue);
        String assetName = normalizeForSimilarity(assetValue);
        if (query == null || assetName == null) {
            return 0.0D;
        }
        if (query.equals(assetName)) {
            return 1.0D;
        }
        return Math.max(longestCommonSubstringScore(query, assetName), diceGramScore(query, assetName));
    }

    private double longestCommonSubstringScore(String left, String right) {
        int best = 0;
        int[] previous = new int[right.length() + 1];
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            for (int j = 1; j <= right.length(); j++) {
                if (left.charAt(i - 1) == right.charAt(j - 1)) {
                    current[j] = previous[j - 1] + 1;
                    best = Math.max(best, current[j]);
                }
            }
            previous = current;
        }
        return best == 0 ? 0.0D : (2.0D * best) / (left.length() + right.length());
    }

    private double diceGramScore(String left, String right) {
        Set<String> leftGrams = grams(left);
        Set<String> rightGrams = grams(right);
        if (leftGrams.isEmpty() || rightGrams.isEmpty()) {
            return 0.0D;
        }
        long overlap = leftGrams.stream().filter(rightGrams::contains).count();
        return (2.0D * overlap) / (leftGrams.size() + rightGrams.size());
    }

    private Set<String> grams(String value) {
        Set<String> grams = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return grams;
        }
        if (value.length() == 1) {
            grams.add(value);
            return grams;
        }
        for (int index = 0; index < value.length() - 1; index++) {
            grams.add(value.substring(index, index + 2));
        }
        return grams;
    }

    private String normalizeForSimilarity(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (Character.isLetterOrDigit(ch)) {
                normalized.append(Character.toLowerCase(ch));
            }
        }
        return normalized.isEmpty() ? null : normalized.toString();
    }

    private boolean hasContextFilter(Map<String, Object> filters) {
        return CONTEXT_FILTER_KEYS.stream()
            .map(filters::get)
            .anyMatch(value -> value != null && !String.valueOf(value).isBlank());
    }

    private boolean hasRetrievalFilter(Map<String, Object> filters) {
        return RETRIEVAL_FILTER_KEYS.stream()
            .map(filters::get)
            .anyMatch(value -> value != null && !String.valueOf(value).isBlank());
    }

    private List<String> contextTokens(Map<String, Object> filters) {
        List<String> tokens = new ArrayList<>();
        for (String key : List.of(
            "cluster",
            "namespace",
            "target",
            "targetType",
            "target_type",
            "database",
            "databaseType",
            "dbType",
            "dialect",
            "databaseRole",
            "database_role",
            "service",
            "labels"
        )) {
            Object value = filters.get(key);
            if (value instanceof List<?> list) {
                list.forEach(item -> addToken(tokens, item));
            } else {
                addToken(tokens, value);
            }
        }
        return tokens.stream().distinct().toList();
    }

    private List<String> labels(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(String::valueOf)
                .map(this::normalize)
                .filter(item -> item != null && !item.isBlank())
                .toList();
        }
        return List.of();
    }

    private boolean labelMatches(List<String> labels, String token) {
        String normalized = normalize(token);
        if (normalized == null) {
            return false;
        }
        return labels.stream().anyMatch(label -> label.equals(normalized) || label.endsWith(":" + normalized));
    }

    private boolean assetNameMatches(Map<?, ?> asset, String assetName) {
        if (asset == null || assetName == null || assetName.isBlank()) {
            return false;
        }
        return equalsNormalized(assetName, asset.get("name"))
            || equalsNormalized(assetName, asset.get("displayName"))
            || equalsNormalized(assetName, asset.get("toolName"))
            || assetNameAppearsInQuery(assetName, asset.get("name"))
            || assetNameAppearsInQuery(assetName, asset.get("displayName"))
            || assetNameAppearsInQuery(assetName, asset.get("toolName"));
    }

    private boolean assetNameAppearsInQuery(String queryValue, Object assetValue) {
        String query = normalize(queryValue);
        String assetName = normalize(assetValue == null ? null : String.valueOf(assetValue));
        if (query == null || assetName == null || assetName.length() < 3 || query.equals(assetName)) {
            return false;
        }
        Pattern pattern = Pattern.compile("(^|[^a-z0-9_-])" + Pattern.quote(assetName) + "($|[^a-z0-9_-])");
        return pattern.matcher(query).find();
    }

    private String view(Map<String, Object> arguments) {
        String view = text(firstValue(arguments, "view"));
        return equalsNormalized(view, "system") ? "system" : "model";
    }

    private int limit(Map<String, Object> arguments) {
        Object value = firstValue(arguments, "limit", "maxResults");
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            return Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(String.valueOf(value))));
        } catch (NumberFormatException ignored) {
            return DEFAULT_LIMIT;
        }
    }

    private void rejectConcreteTargetFields(Map<String, Object> filters) {
        for (String field : CONCRETE_TARGET_FIELDS) {
            Object value = filters.get(field);
            if (value != null && !String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("Concrete target field is not allowed in asset_query: " + field);
            }
        }
    }

    private Map<String, Object> compactFilters(Map<String, Object> filters) {
        Map<String, Object> compact = new LinkedHashMap<>();
        CONTEXT_FILTER_KEYS.forEach(key -> {
            Object value = filters.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                compact.put(key, value);
            }
        });
        RETRIEVAL_FILTER_KEYS.forEach(key -> {
            Object value = filters.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                compact.put(key, value);
            }
        });
        return compact;
    }

    private String retrievalText(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        List<String> terms = new ArrayList<>();
        for (String key : RETRIEVAL_FILTER_KEYS) {
            Object value = filters.get(key);
            if (value instanceof List<?> list) {
                list.forEach(item -> addRetrievalValue(terms, item));
            } else {
                addRetrievalValue(terms, value);
            }
        }
        return terms.isEmpty() ? null : String.join(" ", terms);
    }

    private void addRetrievalValue(List<String> terms, Object value) {
        if (value instanceof Map<?, ?> map) {
            addRawText(terms, firstValueMap(map, "intent", "query", "term", "text", "label", "name"));
            return;
        }
        addRawText(terms, value);
    }

    private Object firstValueMap(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void addRawText(List<String> terms, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isBlank()) {
            terms.add(text);
        }
    }

    private Map<String, Object> emptyResultAdvice(Map<String, Object> filters, List<Map<String, Object>> unavailableAssets) {
        boolean hasUnavailable = unavailableAssets != null && !unavailableAssets.isEmpty();
        return mapOf(
            "reason", hasUnavailable
                ? "A registered asset matched the filters, but it is disabled or not published."
                : "No enabled and published asset matched the exact logical filters.",
            "doNotConclude", "Do not conclude the requested system/service does not exist solely from this empty result; it may be registered but disabled or not published.",
            "doNotInvent", "Do not invent or transform service labels such as service:<topic> from the user's natural-language intent.",
            "nextAction", hasUnavailable
                ? "Tell the user the matching asset exists but must be enabled/published before query or command execution."
                : "Ask the user to confirm the exact assetName/env/cluster/service label and whether the asset is enabled/published, or retry only with values explicitly provided by the user or returned by prior tool observations.",
            "receivedFilters", filters == null ? Map.of() : filters
        );
    }

    private Map<String, Object> broadDiscoveryAdvice(int returnedCount) {
        return mapOf(
            "reason", "No exact logical context filter was supplied, so asset_query returned redacted candidate assets.",
            "selectionPolicy", "Select an asset only when its asset.name/environment/capabilities clearly match the user request; otherwise ask for assetName/env/cluster/service.",
            "templateHint", "For execution-template questions, prefer assets whose capabilities.allowedCommandTemplates[].templateId, allowedCommandTemplateIds[], authorizedSqlTemplates[], or authorizedHttpTemplates[] contains a matching registered template id.",
            "returnedCount", returnedCount
        );
    }

    private Object firstValue(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void addToken(List<String> tokens, Object value) {
        String token = normalize(value == null ? null : String.valueOf(value));
        if (token != null) {
            tokens.add(token);
        }
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean equalsNormalized(Object first, Object second) {
        String left = normalize(first == null ? null : String.valueOf(first));
        String right = normalize(second == null ? null : String.valueOf(second));
        return left != null && left.equals(right);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    private record AssetFallback(List<Map<String, Object>> assets, boolean fuzzyUsed) {
    }

    private record AssetNameCandidate(Map<String, Object> asset, String field, double score) {
    }
}
