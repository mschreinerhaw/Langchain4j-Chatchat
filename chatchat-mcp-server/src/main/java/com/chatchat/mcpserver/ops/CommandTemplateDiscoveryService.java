package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.routing.TargetKindRegistry;
import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.SqlTemplateConfig;
import com.chatchat.mcpserver.sql.SqlTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class CommandTemplateDiscoveryService {

    public static final String QUERY_SCHEMA_VERSION = "template_query.v1";
    public static final String RESULT_SCHEMA_VERSION = "template_query_result.v1";
    public static final String TEMPLATE_SCHEMA_VERSION = "command_template.v1";
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 20;
    private static final double INTENT_WEIGHT = 0.40;
    private static final double LEXICAL_WEIGHT = 0.30;
    private static final double TYPE_WEIGHT = 0.20;
    private static final double POPULARITY_WEIGHT = 0.05;
    private static final double SAFETY_WEIGHT = 0.05;

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
        "uri",
        "command",
        "rawCommand",
        "shell",
        "sql",
        "rawSql",
        "body",
        "bodyTemplate"
    );

    private final CommandTemplateService templateService;
    private final SshHostConfigService hostConfigService;
    private final SqlTemplateService sqlTemplateService;
    private final SqlDatasourceConfigService datasourceConfigService;
    private final HttpEndpointConfigService httpEndpointConfigService;
    private final DatabaseQueryConfigService databaseQueryConfigService;
    private final ObjectMapper objectMapper;
    private final TemplateDiscoveryProperties properties;
    private final LuceneMcpSearchService luceneSearchService;
    private final TargetKindRegistry targetKindRegistry;

    public CommandTemplateDiscoveryService(CommandTemplateService templateService,
                                           SshHostConfigService hostConfigService,
                                           SqlTemplateService sqlTemplateService,
                                           SqlDatasourceConfigService datasourceConfigService,
                                           HttpEndpointConfigService httpEndpointConfigService,
                                           ObjectMapper objectMapper,
                                           TemplateDiscoveryProperties properties) {
        this(templateService, hostConfigService, sqlTemplateService, datasourceConfigService,
            httpEndpointConfigService, null, objectMapper, properties, null, new TargetKindRegistry());
    }

    public CommandTemplateDiscoveryService(CommandTemplateService templateService,
                                           SshHostConfigService hostConfigService,
                                           SqlTemplateService sqlTemplateService,
                                           SqlDatasourceConfigService datasourceConfigService,
                                           HttpEndpointConfigService httpEndpointConfigService,
                                           ObjectMapper objectMapper,
                                           TemplateDiscoveryProperties properties,
                                           LuceneMcpSearchService luceneSearchService) {
        this(templateService, hostConfigService, sqlTemplateService, datasourceConfigService,
            httpEndpointConfigService, null, objectMapper, properties, luceneSearchService, new TargetKindRegistry());
    }

    public CommandTemplateDiscoveryService(CommandTemplateService templateService,
                                           SshHostConfigService hostConfigService,
                                           SqlTemplateService sqlTemplateService,
                                           SqlDatasourceConfigService datasourceConfigService,
                                           HttpEndpointConfigService httpEndpointConfigService,
                                           DatabaseQueryConfigService databaseQueryConfigService,
                                           ObjectMapper objectMapper,
                                           TemplateDiscoveryProperties properties,
                                           LuceneMcpSearchService luceneSearchService) {
        this(templateService, hostConfigService, sqlTemplateService, datasourceConfigService,
            httpEndpointConfigService, databaseQueryConfigService, objectMapper, properties,
            luceneSearchService, new TargetKindRegistry());
    }

    @Autowired
    public CommandTemplateDiscoveryService(CommandTemplateService templateService,
                                           SshHostConfigService hostConfigService,
                                           SqlTemplateService sqlTemplateService,
                                           SqlDatasourceConfigService datasourceConfigService,
                                           HttpEndpointConfigService httpEndpointConfigService,
                                           DatabaseQueryConfigService databaseQueryConfigService,
                                           ObjectMapper objectMapper,
                                           TemplateDiscoveryProperties properties,
                                           LuceneMcpSearchService luceneSearchService,
                                           TargetKindRegistry targetKindRegistry) {
        this.templateService = templateService;
        this.hostConfigService = hostConfigService;
        this.sqlTemplateService = sqlTemplateService;
        this.datasourceConfigService = datasourceConfigService;
        this.httpEndpointConfigService = httpEndpointConfigService;
        this.databaseQueryConfigService = databaseQueryConfigService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.luceneSearchService = luceneSearchService;
        this.targetKindRegistry = targetKindRegistry == null ? new TargetKindRegistry() : targetKindRegistry;
    }

    public Map<String, Object> query(Map<String, Object> arguments) {
        long startedAt = System.nanoTime();
        Map<String, Object> filters = filters(arguments);
        rejectConcreteTargetFields(filters);
        TargetKindRegistry.Resolution target = targetKindRegistry.resolveForTool(
            "template_query",
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
        NormalizedIntent intent = normalizeIntent(filters);
        Map<String, Object> retrievalFilters = filtersWithNormalizedIntent(filters, intent);
        Map<String, Object> result;
        if ("sql_datasource".equals(assetType)) {
            result = querySqlTemplates(assetType, filters, retrievalFilters, intent, limit);
        } else if ("http_endpoint".equals(assetType)) {
            result = queryHttpTemplates(assetType, filters, retrievalFilters, intent, limit);
        } else if ("database_query".equals(assetType)) {
            result = queryDatabaseQueryTemplates(assetType, filters, retrievalFilters, intent, limit);
        } else if (!"ssh_host".equals(assetType)) {
            result = result(assetType, filters, intent, List.of(), limit, List.of(), false, false);
        } else {
            result = querySshTemplates(assetType, filters, retrievalFilters, intent, limit);
        }
        result.put("routingDecision", routingDecision(target, startedAt));
        return result;
    }

    private Map<String, Object> querySshTemplates(String assetType,
                                                  Map<String, Object> filters,
                                                  Map<String, Object> retrievalFilters,
                                                  NormalizedIntent intent,
                                                  int limit) {
        List<SshHostConfig> hosts = matchingHosts(filters);
        Set<String> allowedByAsset = allowedTemplatesForHosts(hosts, filters);
        boolean assetScoped = hasAssetScope(filters);
        List<CommandTemplateConfig> templates = templateService.listEnabled();
        Map<String, LuceneMcpSearchService.SearchHit> luceneHits = luceneTemplateHits(
            templates.stream().map(this::templateDoc).toList(),
            assetType,
            null,
            retrievalFilters,
            intent,
            Math.max(limit, MAX_LIMIT)
        );
        List<ScoredTemplate<CommandTemplateConfig>> candidates = templates.stream()
            .filter(template -> !assetScoped || allowedByAsset.contains(normalize(template.getCode())))
            .map(template -> new ScoredTemplate<>(template,
                decision(luceneAdjusted(relevance(template, retrievalFilters), luceneHits.get(template.getCode())),
                    intent, "ssh_host", null, riskLevel(template))))
            .toList();
        List<ScoredTemplate<CommandTemplateConfig>> matched = rankAndFallback(candidates, intent, scored -> scored.template().getCode());
        boolean fallbackUsed = fallbackUsed(candidates, matched, intent);
        log.info("template_query ssh search assetType={} filters={} normalizedIntent={} registryTemplates={} candidates={} luceneHits={} returned={} fallbackUsed={}",
            assetType, compactFilters(filters), intent.type(), templates.size(), candidates.size(), luceneHits.size(),
            Math.min(matched.size(), limit), fallbackUsed);
        return result(assetType, filters, intent, sshAssetMetadata(hosts, filters, assetScoped), limit,
            sshTemplateMetadata(matched, assetType, limit), matched.size() > limit, fallbackUsed, templateSignal(luceneHits));
    }

    private Map<String, Object> querySqlTemplates(String assetType,
                                                  Map<String, Object> filters,
                                                  Map<String, Object> retrievalFilters,
                                                  NormalizedIntent intent,
                                                  int limit) {
        boolean assetScoped = hasAssetScope(filters);
        List<SqlDatasourceConfig> datasources = matchingDatasources(filters);
        if (assetScoped && datasources.isEmpty()) {
            return result(assetType, filters, intent, List.of(), limit, List.of(), false, false);
        }
        String dbType = firstText(selectedSqlAssetType(datasources, assetScoped), requestedDatabaseType(filters));
        List<SqlTemplateConfig> templates = sqlTemplateService.listEnabled();
        Map<String, LuceneMcpSearchService.SearchHit> luceneHits = luceneTemplateHits(
            templates.stream().map(this::templateDoc).toList(),
            assetType,
            dbType,
            retrievalFilters,
            intent,
            Math.max(limit, MAX_LIMIT)
        );
        List<ScoredTemplate<SqlTemplateConfig>> candidates = templates.stream()
            .filter(template -> matchesSqlTemplateBinding(template, filters, datasources, assetScoped))
            .map(template -> new ScoredTemplate<>(template,
                decision(luceneAdjusted(relevance(template, retrievalFilters), luceneHits.get(template.getCode())),
                    intent, template.getDatabaseType(),
                    selectedSqlAssetType(datasources, assetScoped), riskLevel(template))))
            .toList();
        List<ScoredTemplate<SqlTemplateConfig>> matched = rankAndFallback(candidates, intent, scored -> scored.template().getCode());
        boolean fallbackUsed = fallbackUsed(candidates, matched, intent);
        log.info("template_query sql search assetType={} dbType={} filters={} normalizedIntent={} datasources={} registryTemplates={} candidates={} luceneHits={} returned={} fallbackUsed={} hitIds={}",
            assetType, dbType, compactFilters(filters), intent.type(), datasources.size(), templates.size(), candidates.size(),
            luceneHits.size(), Math.min(matched.size(), limit), fallbackUsed, luceneHits.keySet().stream().limit(limit).toList());
        return result(assetType, filters, intent, sqlAssetMetadata(datasources, filters, assetScoped), limit,
            sqlTemplateMetadata(matched, assetType, limit), matched.size() > limit, fallbackUsed, templateSignal(luceneHits));
    }

    private Map<String, Object> queryHttpTemplates(String assetType,
                                                   Map<String, Object> filters,
                                                   Map<String, Object> retrievalFilters,
                                                   NormalizedIntent intent,
                                                   int limit) {
        List<HttpEndpointConfig> endpoints = httpEndpointConfigService.listEnabled().stream()
            .filter(endpoint -> matchesHttpEndpoint(endpoint, filters))
            .toList();
        Map<String, LuceneMcpSearchService.SearchHit> luceneHits = luceneTemplateHits(
            endpoints.stream().map(this::templateDoc).toList(),
            assetType,
            null,
            retrievalFilters,
            intent,
            Math.max(limit, MAX_LIMIT)
        );
        List<ScoredTemplate<HttpEndpointConfig>> candidates = endpoints.stream()
            .map(endpoint -> new ScoredTemplate<>(endpoint,
                decision(luceneAdjusted(relevance(endpoint, retrievalFilters),
                    luceneHits.get(firstText(endpoint.getToolName(), endpoint.getName()))),
                    intent, "http_endpoint", null, httpRiskLevel(endpoint))))
            .toList();
        List<ScoredTemplate<HttpEndpointConfig>> matched = rankAndFallback(
            candidates,
            intent,
            scored -> firstText(scored.template().getToolName(), scored.template().getName())
        );
        boolean fallbackUsed = fallbackUsed(candidates, matched, intent);
        log.info("template_query http search assetType={} filters={} normalizedIntent={} endpoints={} candidates={} luceneHits={} returned={} fallbackUsed={}",
            assetType, compactFilters(filters), intent.type(), endpoints.size(), candidates.size(), luceneHits.size(),
            Math.min(matched.size(), limit), fallbackUsed);
        return result(assetType, filters, intent, httpAssetMetadata(endpoints, filters, hasAssetScope(filters)), limit,
            httpTemplateMetadata(matched, assetType, limit), matched.size() > limit, fallbackUsed, templateSignal(luceneHits));
    }

    private Map<String, Object> queryDatabaseQueryTemplates(String assetType,
                                                            Map<String, Object> filters,
                                                            Map<String, Object> retrievalFilters,
                                                            NormalizedIntent intent,
                                                            int limit) {
        List<DatabaseQueryConfig> templates = databaseQueryConfigService == null
            ? List.of()
            : databaseQueryConfigService.listEnabled();
        Map<String, LuceneMcpSearchService.SearchHit> luceneHits = luceneTemplateHits(
            templates.stream().map(this::templateDoc).toList(),
            assetType,
            requestedDatabaseType(filters),
            retrievalFilters,
            intent,
            Math.max(limit, MAX_LIMIT)
        );
        List<ScoredTemplate<DatabaseQueryConfig>> candidates = templates.stream()
            .map(template -> new ScoredTemplate<>(template,
                marketplaceDecision(
                    luceneAdjusted(relevance(template, retrievalFilters), luceneHits.get(template.getToolName())),
                    luceneHits.get(template.getToolName()),
                    intent,
                    filters,
                    template)))
            .toList();
        List<ScoredTemplate<DatabaseQueryConfig>> matched = rankAndFallback(candidates, intent,
            scored -> scored.template().getToolName());
        boolean fallbackUsed = fallbackUsed(candidates, matched, intent);
        log.info("template_query database-query search assetType={} dbType={} filters={} normalizedIntent={} registryTemplates={} candidates={} luceneHits={} returned={} fallbackUsed={} hitIds={}",
            assetType, requestedDatabaseType(filters), compactFilters(filters), intent.type(), templates.size(), candidates.size(),
            luceneHits.size(), Math.min(matched.size(), limit), fallbackUsed, luceneHits.keySet().stream().limit(limit).toList());
        return result(assetType, filters, intent, List.of(), limit,
            databaseQueryTemplateMetadata(matched, assetType, limit), matched.size() > limit, fallbackUsed, templateSignal(luceneHits));
    }

    private Map<String, Object> result(String assetType,
                                       Map<String, Object> filters,
                                       NormalizedIntent intent,
                                       List<Map<String, Object>> resolvedAssets,
                                       int limit,
                                       List<Map<String, Object>> templateMetadata,
                                       boolean possiblyTruncated,
                                       boolean fallbackUsed) {
        return result(assetType, filters, intent, resolvedAssets, limit, templateMetadata, possiblyTruncated,
            fallbackUsed, templateSignal(Map.of()));
    }

    private Map<String, Object> result(String assetType,
                                       Map<String, Object> filters,
                                       NormalizedIntent intent,
                                       List<Map<String, Object>> resolvedAssets,
                                       int limit,
                                       List<Map<String, Object>> templateMetadata,
                                       boolean possiblyTruncated,
                                       boolean fallbackUsed,
                                       TemplateSignal templateSignal) {
        return mapOf(
            "schemaVersion", RESULT_SCHEMA_VERSION,
            "querySchemaVersion", QUERY_SCHEMA_VERSION,
            "success", true,
            "view", view(filters),
            "targetKind", targetKindRegistry.targetKindForAssetType(assetType),
            "assetType", assetType,
            "filtersSchemaVersion", filtersSchemaVersion(filters),
            "filters", compactFilters(filters),
            "queryIr", queryIr(assetType, filters, intent, resolvedAssets),
            "resolutionTrace", resolutionTrace(filters, intent, resolvedAssets, templateMetadata, fallbackUsed, templateSignal),
            "limit", limit,
            "returnedCount", templateMetadata.size(),
            "possiblyTruncated", possiblyTruncated,
            "templateSelectionPolicy", mapOf(
                "templateIdSource", "templates[].templateId",
                "mustUseReturnedTemplateId", true,
                "doNotInventTemplateNames", true,
                "engine", "mcp_template_lucene_decision_v2_no_vector",
                "orderedBy", "templates[] is recalled by Lucene BM25, then ranked by decisionScore desc from intentMatch, lexicalScore, typeMatch, popularity, safetyScore",
                "intentSynonymSource", "chatchat.mcp.template-discovery.intent-synonyms plus template intentSignals",
                "selectionHint", "Choose the returned template whose name, description, intentSignals, relevanceScore, and matchReasons best match the user intent; do not use asset allowed template order as semantic ranking.",
                "fallback", "If authorized candidates exist but intent ranking returns no match, the engine broadens intent and marks resolutionTrace[].fallbackUsed=true.",
                "onEmptyResult", "No existing authorized template matched the request after asset, type and authorization filters. Do not suggest a new template name unless the user asks to administer templates."
            ),
            "templates", templateMetadata
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
            "templates", List.of(),
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

    private Map<String, Object> queryIr(String assetType,
                                        Map<String, Object> filters,
                                        NormalizedIntent intent,
                                        List<Map<String, Object>> resolvedAssets) {
        return mapOf(
            "schemaVersion", "template_query_ir.v2",
            "asset", mapOf(
                "type", assetType,
                "targetKind", targetKindRegistry.targetKindForAssetType(assetType),
                "scoped", hasAssetScope(filters),
                "selected", resolvedAssets.isEmpty() ? null : resolvedAssets.get(0),
                "candidates", resolvedAssets
            ),
            "intent", mapOf(
                "type", intent.type(),
                "lane", intent.lane(),
                "tags", intent.tags(),
                "confidence", intent.confidence()
            )
        );
    }

    private List<Map<String, Object>> resolutionTrace(Map<String, Object> filters,
                                                       NormalizedIntent intent,
                                                       List<Map<String, Object>> resolvedAssets,
                                                       List<Map<String, Object>> templates,
                                                       boolean fallbackUsed,
                                                       TemplateSignal templateSignal) {
        return List.of(
            mapOf(
                "stage", "asset_resolution",
                "strategy", luceneActive() ? "lucene_asset_index_bm25_with_exact_filters" : "exact_alias_contains",
                "scoped", hasAssetScope(filters),
                "candidateCount", resolvedAssets.size()
            ),
            mapOf(
                "stage", "intent_normalization",
                "intent", intent.type(),
                "tags", intent.tags(),
                "confidence", intent.confidence()
            ),
            mapOf(
                "stage", "template_retrieval",
                "strategy", templateRetrievalStrategy(templateSignal),
                "universeLayer", mapOf(
                    "source", "registered_templates",
                    "truthSource", true
                ),
                "signalLayer", mapOf(
                    "luceneActive", templateSignal.luceneActive(),
                    "hitCount", templateSignal.hitCount(),
                    "mode", templateSignal.mode(),
                    "softSignal", true
                ),
                "constraintLayer", mapOf(
                    "hardConstraints", List.of("targetKind_registry", "asset_authorization", "schema_binding"),
                    "signalMissDoesNotDeny", true
                ),
                "fallbackUsed", fallbackUsed,
                "returnedCount", templates.size()
            )
        );
    }

    private boolean luceneActive() {
        return luceneSearchService != null && luceneSearchService.enabled();
    }

    private TemplateSignal templateSignal(Map<String, LuceneMcpSearchService.SearchHit> hits) {
        if (!luceneActive()) {
            return new TemplateSignal(false, 0, "registry_only");
        }
        int hitCount = hits == null ? 0 : hits.size();
        return new TemplateSignal(true, hitCount, hitCount > 0 ? "lucene_scored" : "lucene_empty_registry_fallback");
    }

    private String templateRetrievalStrategy(TemplateSignal signal) {
        if (signal == null || !signal.luceneActive()) {
            return "authorized_filter_then_bm25_lite_feature_rank";
        }
        if (signal.hitCount() <= 0) {
            return "registry_universe_with_lucene_empty_signal_then_authorized_feature_rank";
        }
        return "registry_universe_with_lucene_score_then_authorized_feature_rank";
    }

    private String filtersSchemaVersion(Map<String, Object> filters) {
        return firstText(
            text(firstValue(filters, "filtersSchemaVersion", "filters_schema_version")),
            TargetKindRegistry.FILTERS_SCHEMA_VERSION
        );
    }

    private Map<String, LuceneMcpSearchService.SearchHit> luceneTemplateHits(List<LuceneMcpSearchService.TemplateDoc> docs,
                                                                             String assetType,
                                                                             String dbType,
                                                                             Map<String, Object> filters,
                                                                             NormalizedIntent intent,
                                                                             int limit) {
        if (!luceneActive()) {
            return Map.of();
        }
        List<LuceneMcpSearchService.SearchHit> hits = luceneSearchService.searchTemplates(
            docs,
            new LuceneMcpSearchService.TemplateSearchRequest(
                assetType,
                dbType,
                luceneIntentText(filters, intent),
                limit
            )
        );
        if (hits.isEmpty() && intent != null && !"unknown".equals(intent.type())) {
            hits = luceneSearchService.searchTemplates(
                docs,
                new LuceneMcpSearchService.TemplateSearchRequest(assetType, dbType, null, limit)
            );
        }
        Map<String, LuceneMcpSearchService.SearchHit> byId = new LinkedHashMap<>();
        hits.forEach(hit -> byId.put(hit.id(), hit));
        return byId;
    }

    private String luceneIntentText(Map<String, Object> filters, NormalizedIntent intent) {
        LinkedHashSet<String> values = new LinkedHashSet<>(intentTokens(filters));
        if (intent != null && !"unknown".equals(intent.type())) {
            values.add(intent.type());
            values.addAll(intent.tags());
        }
        return values.isEmpty() ? null : String.join(" ", values);
    }

    private Relevance luceneAdjusted(Relevance relevance, LuceneMcpSearchService.SearchHit hit) {
        if (hit == null) {
            return relevance;
        }
        int score = relevance.score() + Math.min(100, Math.round(hit.score() * 10));
        List<String> reasons = new ArrayList<>(relevance.reasons());
        reasons.add("lucene template index matched bm25=" + round(hit.score()));
        return new Relevance(score, reasons.stream().limit(10).toList());
    }

    private LuceneMcpSearchService.TemplateDoc templateDoc(CommandTemplateConfig template) {
        return new LuceneMcpSearchService.TemplateDoc(
            template.getCode(),
            "ssh_host",
            firstText(template.getTitle(), template.getCode()),
            firstText(template.getDescription(), ""),
            category(template),
            "generic",
            String.join(" ", intentSignals(template)),
            riskLevel(template),
            intentSignals(template),
            "command_template"
        );
    }

    private LuceneMcpSearchService.TemplateDoc templateDoc(SqlTemplateConfig template) {
        return new LuceneMcpSearchService.TemplateDoc(
            template.getCode(),
            "sql_datasource",
            firstText(template.getTitle(), template.getCode()),
            firstText(template.getDescription(), ""),
            category(template),
            SqlDatasourceConfigService.normalizeDatabaseTypeToken(template.getDatabaseType()),
            String.join(" ", intentSignals(template)),
            riskLevel(template),
            intentSignals(template),
            "sql_template"
        );
    }

    private LuceneMcpSearchService.TemplateDoc templateDoc(HttpEndpointConfig endpoint) {
        String templateId = firstText(endpoint.getToolName(), endpoint.getName());
        return new LuceneMcpSearchService.TemplateDoc(
            templateId,
            "http_endpoint",
            firstText(endpoint.getTitle(), templateId),
            firstText(endpoint.getDescription(), ""),
            firstText(endpoint.getCategory(), "http_request"),
            "generic",
            String.join(" ", intentSignals(endpoint)),
            httpRiskLevel(endpoint),
            intentSignals(endpoint),
            "http_endpoint"
        );
    }

    private LuceneMcpSearchService.TemplateDoc templateDoc(DatabaseQueryConfig config) {
        List<String> signals = intentSignals(config);
        return new LuceneMcpSearchService.TemplateDoc(
            firstText(config.getToolName(), config.getId()),
            "database_query",
            firstText(config.getTitle(), config.getToolName()),
            firstText(config.getDescription(), ""),
            "sql_template_registry",
            SqlDatasourceConfigService.normalizeDatabaseTypeToken(config.getDatabaseType()),
            String.join(" ", signals),
            databaseQueryRiskLevel(config),
            signals,
            "database_query_registry"
        );
    }

    private List<Map<String, Object>> sshTemplateMetadata(List<ScoredTemplate<CommandTemplateConfig>> scored,
                                                          String assetType,
                                                          int limit) {
        List<Map<String, Object>> metadata = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, scored.size()); index++) {
            ScoredTemplate<CommandTemplateConfig> item = scored.get(index);
            metadata.add(templateMetadata(item.template(), assetType, item.relevance(), index + 1));
        }
        return metadata;
    }

    private List<Map<String, Object>> sqlTemplateMetadata(List<ScoredTemplate<SqlTemplateConfig>> scored,
                                                          String assetType,
                                                          int limit) {
        List<Map<String, Object>> metadata = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, scored.size()); index++) {
            ScoredTemplate<SqlTemplateConfig> item = scored.get(index);
            metadata.add(templateMetadata(item.template(), assetType, item.relevance(), index + 1));
        }
        return metadata;
    }

    private List<Map<String, Object>> httpTemplateMetadata(List<ScoredTemplate<HttpEndpointConfig>> scored,
                                                           String assetType,
                                                           int limit) {
        List<Map<String, Object>> metadata = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, scored.size()); index++) {
            ScoredTemplate<HttpEndpointConfig> item = scored.get(index);
            metadata.add(templateMetadata(item.template(), assetType, item.relevance(), index + 1));
        }
        return metadata;
    }

    private List<Map<String, Object>> databaseQueryTemplateMetadata(List<ScoredTemplate<DatabaseQueryConfig>> scored,
                                                                    String assetType,
                                                                    int limit) {
        List<Map<String, Object>> metadata = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, scored.size()); index++) {
            ScoredTemplate<DatabaseQueryConfig> item = scored.get(index);
            metadata.add(templateMetadata(item.template(), assetType, item.relevance(), index + 1));
        }
        return metadata;
    }

    private Map<String, Object> templateMetadata(CommandTemplateConfig template,
                                                 String assetType,
                                                 Relevance relevance,
                                                 int rank) {
        List<String> signals = intentSignals(template);
        return mapOf(
            "schemaVersion", TEMPLATE_SCHEMA_VERSION,
            "templateId", template.getCode(),
            "name", firstText(template.getTitle(), template.getCode()),
            "description", firstText(template.getDescription(), ""),
            "rank", rank,
            "relevanceScore", relevance.score(),
            "decisionScore", relevance.finalScore(),
            "rankingFeatures", relevance.features(),
            "mcpDecision", decisionMetadata(relevance),
            "matchReasons", relevance.reasons(),
            "category", category(template),
            "riskLevel", riskLevel(template),
            "supportedAssetTypes", List.of(assetType),
            "intentSignals", signals,
            "routingHints", mapOf(
                "strongSignals", signals.stream().limit(5).toList(),
                "contextKeys", List.of("assetName", "env", "environment", "cluster", "service", "target", "labels")
            ),
            "parameterSchema", parameterSchema(template),
            "enabled", template.isEnabled()
        );
    }

    private Map<String, Object> templateMetadata(SqlTemplateConfig template,
                                                 String assetType,
                                                 Relevance relevance,
                                                 int rank) {
        List<String> signals = intentSignals(template);
        return mapOf(
            "schemaVersion", TEMPLATE_SCHEMA_VERSION,
            "templateId", template.getCode(),
            "name", firstText(template.getTitle(), template.getCode()),
            "description", firstText(template.getDescription(), ""),
            "rank", rank,
            "relevanceScore", relevance.score(),
            "decisionScore", relevance.finalScore(),
            "rankingFeatures", relevance.features(),
            "mcpDecision", decisionMetadata(relevance),
            "matchReasons", relevance.reasons(),
            "category", category(template),
            "riskLevel", riskLevel(template),
            "databaseType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(template.getDatabaseType()),
            "binding", sqlTemplateBindingMetadata(template),
            "supportedAssetTypes", List.of(assetType),
            "intentSignals", signals,
            "routingHints", mapOf(
                "strongSignals", signals.stream().limit(5).toList(),
                "contextKeys", List.of("assetName", "env", "environment", "cluster", "database", "databaseType", "dbType", "dialect", "databaseRole", "service", "target", "labels")
            ),
            "parameterSchema", parameterSchema(template.getParameterSchemaJson()),
            "enabled", template.isEnabled()
        );
    }

    private Map<String, Object> templateMetadata(HttpEndpointConfig endpoint,
                                                 String assetType,
                                                 Relevance relevance,
                                                 int rank) {
        List<String> signals = intentSignals(endpoint);
        String templateId = firstText(endpoint.getToolName(), firstText(endpoint.getName(), endpoint.getId()));
        return mapOf(
            "schemaVersion", TEMPLATE_SCHEMA_VERSION,
            "templateId", templateId,
            "name", firstText(endpoint.getTitle(), templateId),
            "description", firstText(endpoint.getDescription(), ""),
            "rank", rank,
            "relevanceScore", relevance.score(),
            "decisionScore", relevance.finalScore(),
            "rankingFeatures", relevance.features(),
            "mcpDecision", decisionMetadata(relevance),
            "matchReasons", relevance.reasons(),
            "category", firstText(endpoint.getCategory(), "http_request"),
            "riskLevel", httpRiskLevel(endpoint),
            "supportedAssetTypes", List.of(assetType),
            "intentSignals", signals,
            "routingHints", mapOf(
                "strongSignals", signals.stream().limit(5).toList(),
                "contextKeys", List.of("assetName", "env", "environment", "cluster", "service", "target", "labels")
            ),
            "parameterSchema", parameterSchema(endpoint.getInputSchemaJson()),
            "enabled", endpoint.isEnabled()
        );
    }

    private Map<String, Object> templateMetadata(DatabaseQueryConfig config,
                                                 String assetType,
                                                 Relevance relevance,
                                                 int rank) {
        List<String> signals = intentSignals(config);
        String toolName = firstText(config.getToolName(), config.getId());
        return mapOf(
            "schemaVersion", TEMPLATE_SCHEMA_VERSION,
            "templateId", toolName,
            "databaseQueryId", config.getId(),
            "mcpToolName", toolName,
            "name", firstText(config.getTitle(), toolName),
            "description", firstText(config.getDescription(), ""),
            "rank", rank,
            "relevanceScore", relevance.score(),
            "decisionScore", relevance.finalScore(),
            "rankingFeatures", relevance.features(),
            "mcpDecision", decisionMetadata(relevance),
            "matchReasons", relevance.reasons(),
            "category", "sql_template_registry",
            "intent", firstText(config.getTemplateIntent(), "general_query"),
            "databaseType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(config.getDatabaseType()),
            "tags", readStringArray(config.getTagsJson()),
            "riskLevel", databaseQueryRiskLevel(config),
            "owner", firstText(config.getOwner(), "admin"),
            "rating", config.getRating(),
            "usageCount", config.getUsageCount(),
            "marketplace", mapOf(
                "registry", "business_database_query",
                "publishMode", "template_to_mcp_tool",
                "governance", mapOf(
                    "intent", firstText(config.getTemplateIntent(), "general_query"),
                    "dbType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(config.getDatabaseType()),
                    "riskLevel", databaseQueryRiskLevel(config),
                    "owner", firstText(config.getOwner(), "admin")
                )
            ),
            "supportedAssetTypes", List.of(assetType),
            "intentSignals", signals,
            "routingHints", mapOf(
                "strongSignals", signals.stream().limit(5).toList(),
                "contextKeys", List.of("intent", "goal", "category", "template", "labels")
            ),
            "execution", mapOf(
                "mode", "direct_mcp_tool",
                "callTool", toolName,
                "argumentSchemaPath", "templates[].parameterSchema"
            ),
            "parameterSchema", parameterSchema(config.getInputSchemaJson()),
            "enabled", config.isEnabled()
        );
    }

    private NormalizedIntent normalizeIntent(Map<String, Object> filters) {
        List<String> tokens = intentTokens(filters);
        if (tokens.isEmpty()) {
            return new NormalizedIntent("unknown", List.of(), "ops", 0.0);
        }
        if (containsAnyToken(tokens, "metadata", "schema", "column", "元数据", "表结构", "字段", "列")) {
            return new NormalizedIntent("metadata_query", List.of("metadata", "schema", "column"), "ops", 0.88);
        }
        if (containsAnyToken(tokens, "lock", "blocking", "deadlock", "锁", "阻塞", "等待", "死锁")) {
            return new NormalizedIntent("lock_check", List.of("lock", "blocking", "deadlock"), "ops", 0.88);
        }
        if (containsAnyToken(tokens, "storage", "size", "space", "空间", "容量", "大小", "存储")) {
            return new NormalizedIntent("storage_check", List.of("storage", "size", "space"), "ops", 0.86);
        }
        if (containsAnyToken(tokens, "connection", "session", "processlist", "连接", "会话", "连接数")) {
            return new NormalizedIntent("connection_check", List.of("connection", "session", "processlist"), "ops", 0.86);
        }
        if (containsAnyToken(tokens, "performance", "slow", "cpu", "卡", "卡顿", "慢", "性能", "性能分析")) {
            return new NormalizedIntent("performance_issue", List.of("performance", "slow", "health"), "ops", 0.84);
        }
        if (containsAnyToken(tokens, "status", "health", "instance", "状态", "数据库状态", "状态分析")) {
            return new NormalizedIntent("db_status", List.of("status", "health", "instance"), "ops", 0.9);
        }
        return new NormalizedIntent(firstText(tokens.get(0), "general_query"), tokens.stream().limit(6).toList(), "ops", 0.55);
    }

    private Map<String, Object> filtersWithNormalizedIntent(Map<String, Object> filters, NormalizedIntent intent) {
        Map<String, Object> merged = new LinkedHashMap<>(filters);
        List<Object> values = new ArrayList<>();
        Object rawIntent = firstValue(filters, "intent", "goal", "category", "template", "templateId", "template_id", "service");
        if (rawIntent instanceof List<?> list) {
            values.addAll(list);
        } else if (rawIntent != null) {
            values.add(rawIntent);
        }
        if (!"unknown".equals(intent.type())) {
            values.add(intent.type());
            values.addAll(intent.tags());
        }
        if (!values.isEmpty()) {
            merged.put("intent", values);
        }
        return merged;
    }

    private boolean containsAnyToken(List<String> tokens, String... probes) {
        for (String token : tokens) {
            if (containsAny(token, probes)) {
                return true;
            }
        }
        return false;
    }

    private Relevance relevance(CommandTemplateConfig template, Map<String, Object> filters) {
        return relevanceText(
            template.getCode(),
            template.getTitle(),
            template.getDescription(),
            category(template),
            intentSignals(template),
            filters
        );
    }

    private Relevance relevance(SqlTemplateConfig template, Map<String, Object> filters) {
        return relevanceText(
            template.getCode(),
            template.getTitle(),
            template.getDescription(),
            category(template),
            intentSignals(template),
            filters
        );
    }

    private Relevance relevance(HttpEndpointConfig endpoint, Map<String, Object> filters) {
        return relevanceText(
            firstText(endpoint.getToolName(), endpoint.getName()),
            endpoint.getTitle(),
            endpoint.getDescription(),
            firstText(endpoint.getCategory(), "http_request"),
            intentSignals(endpoint),
            filters
        );
    }

    private Relevance relevance(DatabaseQueryConfig config, Map<String, Object> filters) {
        return relevanceText(
            firstText(config.getToolName(), config.getId()),
            config.getTitle(),
            config.getDescription(),
            "business_database_query",
            intentSignals(config),
            filters
        );
    }

    private Relevance relevanceText(String code,
                                    String title,
                                    String description,
                                    String category,
                                    List<String> signals,
                                    Map<String, Object> filters) {
        List<String> tokens = intentTokens(filters);
        if (tokens.isEmpty()) {
            return new Relevance(0, List.of("no_intent_filter"));
        }
        Map<String, Integer> weightedFields = new LinkedHashMap<>();
        weightedFields.put(firstText(code, ""), 35);
        weightedFields.put(firstText(title, ""), 30);
        weightedFields.put(firstText(description, ""), 24);
        weightedFields.put(firstText(category, ""), 14);
        signals.forEach(signal -> weightedFields.put(signal, 28));
        int score = 0;
        Set<String> reasons = new LinkedHashSet<>();
        for (String token : tokens) {
            int bestWeight = 0;
            String bestField = null;
            for (Map.Entry<String, Integer> entry : weightedFields.entrySet()) {
                MatchStrength strength = matchStrength(entry.getKey(), token);
                if (strength == MatchStrength.NONE) {
                    continue;
                }
                int weight = strength == MatchStrength.WORD ? entry.getValue() : Math.max(1, entry.getValue() / 2);
                if (weight > bestWeight) {
                    bestWeight = weight;
                    bestField = entry.getKey();
                }
            }
            if (bestWeight > 0) {
                score += bestWeight;
                reasons.add("matched intent token '" + token + "' in '" + truncateReason(bestField) + "'");
            }
        }
        return new Relevance(score, reasons.stream().limit(8).toList());
    }

    private Relevance decision(Relevance relevance,
                               NormalizedIntent intent,
                               String templateType,
                               String assetType,
                               String riskLevel) {
        double lexicalScore = lexicalScore(relevance);
        double intentMatch = intentMatchScore(relevance, intent);
        double typeMatch = typeMatchScore(templateType, assetType);
        double popularity = 0.0;
        double safety = safetyScore(riskLevel);
        List<TemplateScoringFeature> scoringFeatures = List.of(
            scoringFeature("intentMatch", intentMatch, INTENT_WEIGHT, "normalized intent-token match against template signals"),
            scoringFeature("lexicalScore", lexicalScore, LEXICAL_WEIGHT, "registry lexical relevance plus Lucene score boost when present"),
            scoringFeature("typeMatch", typeMatch, TYPE_WEIGHT, "template database/type compatibility with selected asset"),
            scoringFeature("popularity", popularity, POPULARITY_WEIGHT, "reserved historical usage/success feature"),
            scoringFeature("safetyScore", safety, SAFETY_WEIGHT, "risk-level safety preference")
        );
        double finalScore = weightedScore(scoringFeatures);
        Map<String, Object> features = featureMap(scoringFeatures);
        List<String> reasons = new ArrayList<>(relevance.reasons());
        reasons.add("decision score=" + round(finalScore)
            + " from intent=" + round(intentMatch)
            + ", lexical=" + round(lexicalScore)
            + ", type=" + round(typeMatch)
            + ", safety=" + round(safety));
        return new Relevance(relevance.score(), reasons.stream().limit(10).toList(), round(finalScore), features);
    }

    private Relevance marketplaceDecision(Relevance relevance,
                                          LuceneMcpSearchService.SearchHit hit,
                                          NormalizedIntent intent,
                                          Map<String, Object> filters,
                                          DatabaseQueryConfig config) {
        double intentMatch = intentMatchScore(relevance, intent);
        double dbTypeMatch = databaseTypeMatch(config.getDatabaseType(), requestedDatabaseType(filters));
        double luceneScore = hit == null ? lexicalScore(relevance) : Math.min(1.0, Math.max(0.0, hit.score() / 10.0));
        double riskScore = databaseQueryRiskScore(config.getRiskLevel());
        double usageScore = databaseQueryUsageScore(config);
        List<TemplateScoringFeature> scoringFeatures = List.of(
            scoringFeature("intentMatch", intentMatch, 0.40, "normalized intent-token match against business SQL template signals"),
            scoringFeature("dbTypeMatch", dbTypeMatch, 0.25, "requested database type compatibility"),
            scoringFeature("luceneScore", luceneScore, 0.20, "Lucene BM25 retrieval score normalized to ranking feature"),
            scoringFeature("riskScore", riskScore, 0.10, "governance risk preference"),
            scoringFeature("usageScore", usageScore, 0.05, "historical usage/success placeholder")
        );
        double finalScore = weightedScore(scoringFeatures);
        Map<String, Object> features = featureMap(scoringFeatures);
        List<String> reasons = new ArrayList<>(relevance.reasons());
        reasons.add("marketplace decision score=" + round(finalScore)
            + " from intent=" + round(intentMatch)
            + ", dbType=" + round(dbTypeMatch)
            + ", lucene=" + round(luceneScore)
            + ", risk=" + round(riskScore)
            + ", usage=" + round(usageScore));
        return new Relevance(relevance.score(), reasons.stream().limit(10).toList(), round(finalScore), features);
    }

    private Map<String, Object> decisionMetadata(Relevance relevance) {
        return mapOf(
            "engine", "mcp_template_ranking_v2_no_vector",
            "formula", relevance.features().containsKey("dbTypeMatch")
                ? "SQL Template Marketplace: final score = 0.40*intentMatch + 0.25*dbTypeMatch + 0.20*luceneScore + 0.10*riskScore + 0.05*usageScore"
                : "Lucene BM25 recall contributes to lexicalScore; final score = 0.40*intentMatch + 0.30*lexicalScore + 0.20*typeMatch + 0.05*popularity + 0.05*safetyScore",
            "score", relevance.finalScore(),
            "features", relevance.features()
        );
    }

    private TemplateScoringFeature scoringFeature(String name, double rawScore, double weight, String reason) {
        double normalized = Math.min(1.0, Math.max(0.0, rawScore));
        return new TemplateScoringFeature(name, round(rawScore), round(normalized), weight, reason);
    }

    private double weightedScore(List<TemplateScoringFeature> features) {
        if (features == null || features.isEmpty()) {
            return 0.0;
        }
        return features.stream()
            .mapToDouble(feature -> feature.normalizedScore() * feature.weight())
            .sum();
    }

    private Map<String, Object> featureMap(List<TemplateScoringFeature> features) {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> weights = new LinkedHashMap<>();
        List<Map<String, Object>> featureList = new ArrayList<>();
        for (TemplateScoringFeature feature : features == null ? List.<TemplateScoringFeature>of() : features) {
            map.put(feature.name(), feature.normalizedScore());
            weights.put(feature.name(), feature.weight());
            featureList.add(mapOf(
                "name", feature.name(),
                "rawScore", feature.rawScore(),
                "normalizedScore", feature.normalizedScore(),
                "weight", feature.weight(),
                "weightedScore", round(feature.normalizedScore() * feature.weight()),
                "reason", feature.reason()
            ));
        }
        map.put("weights", weights);
        map.put("featureList", featureList);
        return map;
    }

    private double lexicalScore(Relevance relevance) {
        if (relevance == null || relevance.reasons().contains("no_intent_filter")) {
            return 0.6;
        }
        return Math.min(1.0, Math.max(0.0, relevance.score() / 100.0));
    }

    private double intentMatchScore(Relevance relevance, NormalizedIntent intent) {
        if (intent == null || "unknown".equals(intent.type())) {
            return relevance != null && relevance.reasons().contains("no_intent_filter") ? 0.5 : 0.0;
        }
        if (relevance != null && relevance.score() > 0) {
            return 1.0;
        }
        return 0.0;
    }

    private double typeMatchScore(String templateType, String assetType) {
        String template = SqlDatasourceConfigService.normalizeDatabaseTypeToken(templateType);
        String asset = assetType == null ? null : SqlDatasourceConfigService.normalizeDatabaseTypeToken(assetType);
        if (asset == null || asset.isBlank()) {
            return 0.8;
        }
        if ("generic".equals(template) || template.equals(asset)) {
            return 1.0;
        }
        return 0.0;
    }

    private double databaseTypeMatch(String templateType, String requestedType) {
        String template = SqlDatasourceConfigService.normalizeDatabaseTypeToken(templateType);
        String requested = requestedType == null ? null : SqlDatasourceConfigService.normalizeDatabaseTypeToken(requestedType);
        if (requested == null || requested.isBlank()) {
            return 0.8;
        }
        if ("generic".equals(template) || template.equals(requested)) {
            return 1.0;
        }
        return 0.0;
    }

    private double databaseQueryRiskScore(String riskLevel) {
        return switch (firstText(riskLevel, "read_only").toLowerCase(Locale.ROOT)) {
            case "read_only", "readonly", "read" -> 1.0;
            case "safe", "low" -> 0.8;
            case "dangerous", "high", "critical" -> 0.2;
            default -> 0.6;
        };
    }

    private double databaseQueryUsageScore(DatabaseQueryConfig config) {
        double ratingScore = Math.max(0.0, Math.min(1.0, config.getRating() / 5.0));
        double usageScore = Math.min(1.0, Math.log10(Math.max(0L, config.getUsageCount()) + 1.0));
        return Math.max(ratingScore, usageScore);
    }

    private double safetyScore(String riskLevel) {
        return switch (firstText(riskLevel, "LOW").toUpperCase(Locale.ROOT)) {
            case "LOW" -> 1.0;
            case "READ_ONLY" -> 1.0;
            case "SAFE" -> 0.8;
            case "MEDIUM" -> 0.65;
            case "HIGH" -> 0.35;
            case "DANGEROUS" -> 0.2;
            case "CRITICAL" -> 0.1;
            default -> 0.5;
        };
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private boolean matchesIntent(ScoredTemplate<?> scored) {
        return scored.relevance().score() > 0 || scored.relevance().reasons().contains("no_intent_filter");
    }

    private <T> List<ScoredTemplate<T>> rankAndFallback(List<ScoredTemplate<T>> candidates,
                                                        NormalizedIntent intent,
                                                        java.util.function.Function<ScoredTemplate<T>, String> idExtractor) {
        Comparator<ScoredTemplate<T>> comparator = scoredComparator(idExtractor);
        List<ScoredTemplate<T>> matched = candidates.stream()
            .filter(this::matchesIntent)
            .sorted(comparator)
            .toList();
        if (!matched.isEmpty() || candidates.isEmpty() || intent == null || "unknown".equals(intent.type())) {
            return matched;
        }
        return candidates.stream()
            .sorted(comparator)
            .toList();
    }

    private boolean fallbackUsed(List<? extends ScoredTemplate<?>> candidates,
                                 List<? extends ScoredTemplate<?>> matched,
                                 NormalizedIntent intent) {
        return intent != null
            && !"unknown".equals(intent.type())
            && matched != null
            && !matched.isEmpty()
            && candidates != null
            && candidates.stream().noneMatch(this::matchesIntent);
    }

    private <T> Comparator<ScoredTemplate<T>> scoredComparator(java.util.function.Function<ScoredTemplate<T>, String> idExtractor) {
        return Comparator
            .<ScoredTemplate<T>>comparingDouble(scored -> -scored.relevance().finalScore())
            .thenComparingInt(scored -> -scored.relevance().score())
            .thenComparingInt(scored -> riskPriority(riskLevelOf(scored.template())))
            .thenComparing(scored -> firstText(idExtractor.apply(scored), ""));
    }

    private MatchStrength matchStrength(String fieldText, String token) {
        String normalizedToken = normalize(token);
        String normalizedField = normalize(fieldText);
        if (normalizedToken == null || normalizedField == null) {
            return MatchStrength.NONE;
        }
        Set<String> words = new LinkedHashSet<>();
        addWords(words, normalizedField);
        if (words.contains(normalizedToken)) {
            return MatchStrength.WORD;
        }
        if (normalizedToken.length() >= 2 && normalizedField.contains(normalizedToken)) {
            return MatchStrength.SUBSTRING;
        }
        return MatchStrength.NONE;
    }

    private String truncateReason(String value) {
        String text = firstText(value, "");
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }

    private String riskLevelOf(Object template) {
        if (template instanceof CommandTemplateConfig commandTemplate) {
            return riskLevel(commandTemplate);
        }
        if (template instanceof SqlTemplateConfig sqlTemplate) {
            return riskLevel(sqlTemplate);
        }
        if (template instanceof HttpEndpointConfig endpoint) {
            return httpRiskLevel(endpoint);
        }
        if (template instanceof DatabaseQueryConfig databaseQuery) {
            return databaseQueryRiskLevel(databaseQuery);
        }
        return "LOW";
    }

    private int riskPriority(String riskLevel) {
        return switch (firstText(riskLevel, "LOW").toUpperCase(Locale.ROOT)) {
            case "READ_ONLY" -> 0;
            case "LOW" -> 0;
            case "SAFE" -> 1;
            case "MEDIUM" -> 1;
            case "DANGEROUS" -> 2;
            case "HIGH" -> 2;
            case "CRITICAL" -> 3;
            default -> 4;
        };
    }

    private Set<String> allowedTemplatesForHosts(List<SshHostConfig> hosts, Map<String, Object> filters) {
        if (!hasAssetScope(filters)) {
            return Set.of();
        }
        Set<String> allowed = new LinkedHashSet<>();
        hosts.forEach(host -> allowedCommands(host).forEach(code -> {
            String normalized = normalize(code);
            if (normalized != null) {
                allowed.add(normalized);
            }
        }));
        return allowed;
    }

    private List<Map<String, Object>> sshAssetMetadata(List<SshHostConfig> hosts, Map<String, Object> filters, boolean scoped) {
        if (!scoped) {
            return List.of();
        }
        return hosts.stream()
            .map(host -> assetMetadata("ssh_host", host.getId(), host.getName(), host.getTitle(), host.getToolName(),
                host.getEnvironment(), hostLabels(host), filters))
            .toList();
    }

    private List<Map<String, Object>> sqlAssetMetadata(List<SqlDatasourceConfig> datasources, Map<String, Object> filters, boolean scoped) {
        if (!scoped) {
            return List.of();
        }
        return datasources.stream()
            .map(datasource -> assetMetadata("sql_datasource", datasource.getId(), datasource.getName(),
                datasource.getTitle(), datasource.getToolName(), datasource.getEnvironment(), datasourceLabels(datasource), filters))
            .toList();
    }

    private List<Map<String, Object>> httpAssetMetadata(List<HttpEndpointConfig> endpoints, Map<String, Object> filters, boolean scoped) {
        if (!scoped) {
            return List.of();
        }
        return endpoints.stream()
            .map(endpoint -> assetMetadata("http_endpoint", endpoint.getId(), endpoint.getName(),
                endpoint.getTitle(), endpoint.getToolName(), endpoint.getEnvironment(), endpointLabels(endpoint), filters))
            .toList();
    }

    private Map<String, Object> assetMetadata(String type,
                                              String id,
                                              String name,
                                              String title,
                                              String toolName,
                                              String environment,
                                              Set<String> labels,
                                              Map<String, Object> filters) {
        Map<String, Object> match = assetMatchMetadata(name, title, toolName, labels, filters);
        return mapOf(
            "type", type,
            "id", id,
            "name", firstText(name, toolName),
            "title", title,
            "toolName", toolName,
            "environment", environment,
            "match", match,
            "labels", labels.stream().limit(12).toList()
        );
    }

    private Map<String, Object> assetMatchMetadata(String name,
                                                   String title,
                                                   String toolName,
                                                   Set<String> labels,
                                                   Map<String, Object> filters) {
        String requested = text(firstValue(filters, "assetName", "asset_name", "name", "template", "templateId", "template_id"));
        if (requested == null) {
            return mapOf("method", "unscoped", "confidence", 0.4);
        }
        if (equalsNormalized(requested, name) || equalsNormalized(requested, title) || equalsNormalized(requested, toolName)) {
            return mapOf("method", "exact", "confidence", 0.98);
        }
        String normalizedRequested = normalize(requested);
        if (normalizedRequested != null && labels.stream().anyMatch(label -> label.equals(normalizedRequested))) {
            return mapOf("method", "alias", "confidence", 0.92);
        }
        if (logicalNameMatches(requested, name) || logicalNameMatches(requested, title) || logicalNameMatches(requested, toolName)) {
            return mapOf("method", "contains", "confidence", 0.78);
        }
        return mapOf("method", "context_filter", "confidence", 0.65);
    }

    private List<SshHostConfig> matchingHosts(Map<String, Object> filters) {
        String assetName = text(firstValue(filters, "assetName", "asset_name", "name"));
        String env = text(firstValue(filters, "env", "environment"));
        List<String> tokens = contextTokens(filters);
        return hostConfigService.listEnabled().stream()
            .filter(host -> assetName == null || assetNameMatches(host, assetName))
            .filter(host -> env == null || equalsNormalized(env, host.getEnvironment()))
            .filter(host -> tokens.isEmpty() || hostLabels(host).containsAll(tokens))
            .toList();
    }

    private boolean assetNameMatches(SshHostConfig host, String assetName) {
        return logicalNameMatches(assetName, host.getName())
            || logicalNameMatches(assetName, host.getTitle())
            || logicalNameMatches(assetName, host.getToolName());
    }

    private List<SqlDatasourceConfig> matchingDatasources(Map<String, Object> filters) {
        String assetName = text(firstValue(filters, "assetName", "asset_name", "name"));
        String env = text(firstValue(filters, "env", "environment"));
        List<String> tokens = contextTokens(filters);
        return datasourceConfigService.listEnabled().stream()
            .filter(datasource -> assetName == null || datasourceNameMatches(datasource, assetName))
            .filter(datasource -> env == null || equalsNormalized(env, datasource.getEnvironment()))
            .filter(datasource -> matchesRequestedDatabaseType(datasource, filters))
            .filter(datasource -> tokens.isEmpty() || datasourceLabels(datasource).containsAll(tokens))
            .toList();
    }

    private boolean datasourceNameMatches(SqlDatasourceConfig datasource, String assetName) {
        return logicalNameMatches(assetName, datasource.getName())
            || logicalNameMatches(assetName, datasource.getTitle())
            || logicalNameMatches(assetName, datasource.getToolName());
    }

    private boolean matchesHttpEndpoint(HttpEndpointConfig endpoint, Map<String, Object> filters) {
        String assetName = text(firstValue(filters, "assetName", "asset_name", "name", "template", "templateId", "template_id"));
        String env = text(firstValue(filters, "env", "environment"));
        List<String> tokens = contextTokens(filters);
        return (assetName == null || endpointNameMatches(endpoint, assetName))
            && (env == null || equalsNormalized(env, endpoint.getEnvironment()))
            && (tokens.isEmpty() || endpointLabels(endpoint).containsAll(tokens));
    }

    private boolean endpointNameMatches(HttpEndpointConfig endpoint, String assetName) {
        return logicalNameMatches(assetName, endpoint.getName())
            || logicalNameMatches(assetName, endpoint.getTitle())
            || logicalNameMatches(assetName, endpoint.getToolName());
    }

    private Set<String> hostLabels(SshHostConfig host) {
        Set<String> labels = new LinkedHashSet<>();
        addLabel(labels, host.getName());
        addLabel(labels, host.getTitle());
        addLabel(labels, host.getToolName());
        addLabel(labels, host.getEnvironment());
        addDelimited(labels, host.getTags());
        addJsonLabels(labels, host.getRoutingLabelsJson());
        addJsonLabels(labels, host.getCapabilitiesJson());
        return labels;
    }

    private Set<String> datasourceLabels(SqlDatasourceConfig datasource) {
        Set<String> labels = new LinkedHashSet<>();
        addLabel(labels, datasource.getName());
        addLabel(labels, datasource.getTitle());
        addLabel(labels, datasource.getToolName());
        addLabel(labels, datasource.getEnvironment());
        addLabel(labels, datasource.getDatabaseType());
        addJsonLabels(labels, datasource.getRoutingLabelsJson());
        addJsonLabels(labels, datasource.getCapabilitiesJson());
        return labels;
    }

    private Set<String> endpointLabels(HttpEndpointConfig endpoint) {
        Set<String> labels = new LinkedHashSet<>();
        addLabel(labels, endpoint.getName());
        addLabel(labels, endpoint.getTitle());
        addLabel(labels, endpoint.getToolName());
        addLabel(labels, endpoint.getEnvironment());
        addLabel(labels, endpoint.getCategory());
        addDelimited(labels, endpoint.getTags());
        addJsonLabels(labels, endpoint.getRoutingLabelsJson());
        addJsonLabels(labels, endpoint.getCapabilitiesJson());
        return labels;
    }

    private boolean hasAssetScope(Map<String, Object> filters) {
        return firstValue(filters, "assetName", "asset_name", "name", "env", "environment", "cluster", "service", "target",
            "database", "databaseType", "dbType", "dialect", "databaseRole", "database_role", "labels") != null;
    }

    private List<String> contextTokens(Map<String, Object> filters) {
        List<String> tokens = new ArrayList<>();
        for (String key : List.of("cluster", "service", "target", "targetType", "target_type", "database", "databaseRole",
            "database_role", "labels")) {
            Object value = filters.get(key);
            if (value instanceof List<?> list) {
                list.forEach(item -> addToken(tokens, item));
            } else {
                addToken(tokens, value);
            }
        }
        return tokens.stream().distinct().toList();
    }

    private List<String> intentTokens(Map<String, Object> filters) {
        List<String> tokens = new ArrayList<>();
        for (String key : List.of("intent", "goal", "category", "template", "templateId", "template_id", "service")) {
            Object value = filters.get(key);
            if (value instanceof List<?> list) {
                list.forEach(item -> addWords(tokens, item));
            } else {
                addWords(tokens, value);
            }
        }
        return tokens.stream().distinct().toList();
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
        for (String key : List.of(
            "assetName",
            "asset_name",
            "name",
            "env",
            "environment",
            "cluster",
            "service",
            "target",
            "targetType",
            "target_type",
            "labels",
            "intent",
            "goal",
            "category",
            "database",
            "databaseType",
            "dbType",
            "dialect",
            "databaseRole",
            "database_role",
            "template",
            "templateId",
            "template_id",
            "view"
        )) {
            Object value = arguments.get(key);
            if (value != null) {
                filters.putIfAbsent(key, value);
            }
        }
        return filters;
    }

    private void rejectConcreteTargetFields(Map<String, Object> filters) {
        for (String field : CONCRETE_TARGET_FIELDS) {
            Object value = filters.get(field);
            if (value != null && !String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("Concrete target or raw execution field is not allowed in template_query: " + field);
            }
        }
    }

    private List<String> allowedCommands(SshHostConfig host) {
        if (host.getAllowedCommandsJson() == null || host.getAllowedCommandsJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(host.getAllowedCommandsJson(), new TypeReference<>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> parameterSchema(CommandTemplateConfig template) {
        return parameterSchema(template.getParameterSchemaJson());
    }

    private Map<String, Object> parameterSchema(String parameterSchemaJson) {
        if (parameterSchemaJson == null || parameterSchemaJson.isBlank()) {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }
        try {
            return objectMapper.readValue(parameterSchemaJson, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }
    }

    private List<String> intentSignals(CommandTemplateConfig template) {
        Set<String> signals = new LinkedHashSet<>();
        readStringArray(template.getIntentSignalsJson()).forEach(signal -> addWords(signals, signal));
        addWords(signals, template.getCode());
        addWords(signals, template.getTitle());
        addWords(signals, template.getDescription());
        addWords(signals, category(template));
        return signals.stream().limit(12).toList();
    }

    private List<String> intentSignals(SqlTemplateConfig template) {
        Set<String> signals = new LinkedHashSet<>();
        readStringArray(template.getIntentSignalsJson()).forEach(signal -> addWords(signals, signal));
        addWords(signals, template.getCode());
        addWords(signals, template.getTitle());
        addWords(signals, template.getDescription());
        addWords(signals, category(template));
        return signals.stream().limit(12).toList();
    }

    private List<String> intentSignals(HttpEndpointConfig endpoint) {
        Set<String> signals = new LinkedHashSet<>();
        addWords(signals, endpoint.getToolName());
        addWords(signals, endpoint.getName());
        addWords(signals, endpoint.getTitle());
        addWords(signals, endpoint.getDescription());
        addWords(signals, endpoint.getCategory());
        addDelimited(signals, endpoint.getTags());
        return signals.stream().limit(12).toList();
    }

    private List<String> intentSignals(DatabaseQueryConfig config) {
        Set<String> signals = new LinkedHashSet<>();
        addWords(signals, config.getToolName());
        addWords(signals, config.getTitle());
        addWords(signals, config.getDescription());
        addWords(signals, config.getTemplateIntent());
        addWords(signals, config.getDatabaseType());
        addWords(signals, config.getRiskLevel());
        addWords(signals, config.getOwner());
        addJsonLabels(signals, config.getRoutingLabelsJson());
        addJsonLabels(signals, config.getCapabilitiesJson());
        addJsonLabels(signals, config.getTagsJson());
        addGovernanceSignals(signals, config.getGovernanceJson());
        return signals.stream().limit(16).toList();
    }

    private void addGovernanceSignals(Set<String> signals, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            flattenGovernanceSignals(signals, map);
        } catch (Exception ignored) {
            // Invalid stale governance metadata is ignored for discovery.
        }
    }

    private void flattenGovernanceSignals(Set<String> signals, Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                addWords(signals, key);
                flattenGovernanceSignals(signals, item);
            });
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> flattenGovernanceSignals(signals, item));
            return;
        }
        addWords(signals, value);
    }

    private String category(CommandTemplateConfig template) {
        String configured = normalize(template.getCategory());
        if (configured != null) {
            return configured;
        }
        String code = normalize(template.getCode());
        if (code == null) {
            return "system_diagnostic";
        }
        if (code.contains("log")) {
            return "log_diagnostic";
        }
        if (code.contains("service")) {
            return "service_diagnostic";
        }
        if (code.contains("disk") || code.contains("memory") || code.contains("cpu") || code.contains("system")) {
            return "system_diagnostic";
        }
        return "host_diagnostic";
    }

    private String category(SqlTemplateConfig template) {
        String configured = normalize(template.getCategory());
        if (configured != null) {
            return configured;
        }
        String code = normalize(template.getCode());
        if (code != null && code.contains("count")) {
            return "sql_count";
        }
        if (code != null && (code.contains("recent") || code.contains("data"))) {
            return "sql_sample";
        }
        return "sql_diagnostic";
    }

    private String riskLevel(CommandTemplateConfig template) {
        String configured = normalize(template.getRiskLevel());
        if (configured != null) {
            return configured.toUpperCase(Locale.ROOT);
        }
        String code = normalize(template.getCode());
        if (code != null && (code.contains("tail") || code.contains("log") || code.contains("service"))) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String riskLevel(SqlTemplateConfig template) {
        String configured = normalize(template.getRiskLevel());
        if (configured != null) {
            return configured.toUpperCase(Locale.ROOT);
        }
        return "MEDIUM";
    }

    private String httpRiskLevel(HttpEndpointConfig endpoint) {
        String method = normalize(endpoint.getMethod());
        if ("get".equals(method)) {
            return "LOW";
        }
        if ("post".equals(method)) {
            return "MEDIUM";
        }
        return "HIGH";
    }

    private String databaseQueryRiskLevel(DatabaseQueryConfig config) {
        String risk = normalize(config == null ? null : config.getRiskLevel());
        if (risk == null) {
            return "read_only";
        }
        return switch (risk) {
            case "readonly", "read" -> "read_only";
            case "safe", "low" -> "safe";
            case "dangerous", "high", "critical" -> "dangerous";
            default -> "read_only";
        };
    }

    private List<String> readStringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<?> values = objectMapper.readValue(json, new TypeReference<>() {});
            return values.stream()
                .map(value -> value == null ? null : String.valueOf(value).trim())
                .filter(value -> value != null && !value.isBlank())
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private boolean matchesSqlTemplateBinding(SqlTemplateConfig template,
                                              Map<String, Object> filters,
                                              List<SqlDatasourceConfig> datasources,
                                              boolean assetScoped) {
        String requestedType = requestedDatabaseType(filters);
        String templateType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(template.getDatabaseType());
        if (requestedType != null && !"generic".equals(templateType) && !templateType.equals(requestedType)) {
            return false;
        }
        String datasourceId = text(template.getDatasourceId());
        List<String> templateLabels = readStringArray(template.getRoutingLabelsJson()).stream()
            .map(this::normalize)
            .filter(value -> value != null)
            .toList();
        if (!assetScoped) {
            return true;
        }
        return datasources.stream().anyMatch(datasource -> {
            Set<String> allowedTemplates = allowedSqlTemplates(datasource);
            if (!allowedTemplates.isEmpty() && !allowedTemplates.contains(normalize(template.getCode()))) {
                return false;
            }
            if (datasourceId != null && !datasourceId.equals(datasource.getId())) {
                return false;
            }
            String datasourceType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType());
            if (!"generic".equals(templateType) && !templateType.equals(datasourceType)) {
                return false;
            }
            if (!templateLabels.isEmpty() && !datasourceLabels(datasource).containsAll(templateLabels)) {
                return false;
            }
            return true;
        });
    }

    private Set<String> allowedSqlTemplates(SqlDatasourceConfig datasource) {
        if (datasource == null || datasource.getAllowedTemplatesJson() == null || datasource.getAllowedTemplatesJson().isBlank()) {
            return Set.of();
        }
        try {
            List<String> values = objectMapper.readValue(datasource.getAllowedTemplatesJson(), new TypeReference<>() {});
            Set<String> allowed = new LinkedHashSet<>();
            values.forEach(value -> {
                String normalized = normalize(value);
                if (normalized != null) {
                    allowed.add(normalized);
                }
            });
            return allowed;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private boolean matchesRequestedDatabaseType(SqlDatasourceConfig datasource, Map<String, Object> filters) {
        String requested = requestedDatabaseType(filters);
        return requested == null || equalsNormalized(requested, datasource.getDatabaseType());
    }

    private String selectedSqlAssetType(List<SqlDatasourceConfig> datasources, boolean assetScoped) {
        if (!assetScoped || datasources == null || datasources.isEmpty()) {
            return null;
        }
        return datasources.stream()
            .map(SqlDatasourceConfig::getDatabaseType)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
    }

    private String requestedDatabaseType(Map<String, Object> filters) {
        String value = text(firstValue(filters, "databaseType", "dbType", "dialect"));
        return value == null ? null : SqlDatasourceConfigService.normalizeDatabaseTypeToken(value);
    }

    private Map<String, Object> sqlTemplateBindingMetadata(SqlTemplateConfig template) {
        String datasourceId = text(template.getDatasourceId());
        List<String> labels = readStringArray(template.getRoutingLabelsJson());
        String scope = datasourceId != null ? "datasource_asset" : (!labels.isEmpty() ? "routing_labels" : "database_type");
        String assetName = null;
        if (datasourceId != null) {
            assetName = datasourceConfigService.listAll().stream()
                .filter(datasource -> datasourceId.equals(datasource.getId()))
                .map(datasource -> firstText(datasource.getName(), datasource.getToolName()))
                .findFirst()
                .orElse(null);
        }
        return mapOf(
            "scope", scope,
            "assetName", assetName,
            "routingLabels", labels
        );
    }

    private Map<String, Object> compactFilters(Map<String, Object> filters) {
        Map<String, Object> compact = new LinkedHashMap<>();
        filters.forEach((key, value) -> {
            if (value != null && !String.valueOf(value).isBlank()) {
                compact.put(key, value);
            }
        });
        return compact;
    }

    private String view(Map<String, Object> filters) {
        String view = text(firstValue(filters, "view"));
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

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private boolean equalsNormalized(Object first, Object second) {
        String left = normalize(first == null ? null : String.valueOf(first));
        String right = normalize(second == null ? null : String.valueOf(second));
        return left != null && left.equals(right);
    }

    private boolean logicalNameMatches(String requested, String candidate) {
        String left = normalize(requested);
        String right = normalize(candidate);
        if (left == null || right == null) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        return right.length() >= 3 && left.contains(right)
            || left.length() >= 3 && right.contains(left);
    }

    private void addDelimited(Set<String> labels, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String item : value.split("[,;\\s]+")) {
            addLabel(labels, item);
        }
    }

    private void addJsonLabels(Set<String> labels, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() {});
            values.forEach(value -> addLabel(labels, value));
        } catch (Exception ignored) {
            // Stale invalid labels are ignored for discovery.
        }
    }

    private void addLabel(Set<String> labels, Object value) {
        String normalized = normalize(value == null ? null : String.valueOf(value));
        if (normalized != null) {
            labels.add(normalized);
        }
    }

    private void addToken(List<String> tokens, Object value) {
        String normalized = normalize(value == null ? null : String.valueOf(value));
        if (normalized != null) {
            tokens.add(normalized);
        }
    }

    private void addWords(Set<String> words, Object value) {
        addWordsToCollection(words, value);
    }

    private void addWords(List<String> words, Object value) {
        addWordsToCollection(words, value);
    }

    private void addWordsToCollection(java.util.Collection<String> words, Object value) {
        String text = normalize(value == null ? null : String.valueOf(value));
        if (text == null) {
            return;
        }
        words.add(text);
        for (String token : text.split("[^a-z0-9]+")) {
            if (!token.isBlank()) {
                words.add(token);
            }
        }
        addBuiltinIntentSynonyms(words, text);
        addConfiguredIntentSynonyms(words, text);
    }

    private void addBuiltinIntentSynonyms(java.util.Collection<String> words, String text) {
        addBuiltinIntentSynonyms(words, text, "status", List.of("状态", "数据库状态", "状态分析", "status", "health", "instance"));
        addBuiltinIntentSynonyms(words, text, "performance", List.of("性能", "卡", "卡顿", "慢", "性能分析", "performance", "slow"));
        addBuiltinIntentSynonyms(words, text, "lock", List.of("锁", "阻塞", "等待", "死锁", "lock", "blocking", "deadlock"));
        addBuiltinIntentSynonyms(words, text, "storage", List.of("空间", "容量", "大小", "存储", "storage", "size", "space"));
        addBuiltinIntentSynonyms(words, text, "metadata", List.of("元数据", "表结构", "字段", "列", "metadata", "schema", "column"));
        addBuiltinIntentSynonyms(words, text, "connection", List.of("连接", "会话", "连接数", "session", "connection", "processlist"));
    }

    private void addBuiltinIntentSynonyms(java.util.Collection<String> words,
                                          String text,
                                          String canonical,
                                          List<String> synonyms) {
        if (!containsAny(text, synonyms)) {
            return;
        }
        addNormalizedWord(words, canonical);
        synonyms.forEach(value -> addNormalizedWord(words, value));
    }

    private void addConfiguredIntentSynonyms(java.util.Collection<String> words, String text) {
        if (properties == null || properties.getIntentSynonyms() == null || properties.getIntentSynonyms().isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : properties.getIntentSynonyms().entrySet()) {
            List<String> values = entry.getValue();
            if (!containsAny(text, entry.getKey()) && !containsAny(text, values == null ? List.of() : values)) {
                continue;
            }
            addNormalizedWord(words, entry.getKey());
            if (values != null) {
                values.forEach(value -> addNormalizedWord(words, value));
            }
        }
    }

    private boolean containsAny(String text, List<String> probes) {
        if (probes == null || probes.isEmpty()) {
            return false;
        }
        return containsAny(text, probes.toArray(String[]::new));
    }

    private boolean containsAny(String text, String... probes) {
        if (text == null || probes == null) {
            return false;
        }
        for (String probe : probes) {
            String normalized = normalize(probe);
            if (normalized != null && text.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private void addNormalizedWord(java.util.Collection<String> words, Object value) {
        String normalized = normalize(value == null ? null : String.valueOf(value));
        if (normalized != null) {
            words.add(normalized);
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    private record ScoredTemplate<T>(T template, Relevance relevance) {
    }

    private record Relevance(int score, List<String> reasons, double finalScore, Map<String, Object> features) {

        private Relevance(int score, List<String> reasons) {
            this(score, reasons, 0.0, Map.of());
        }
    }

    private record NormalizedIntent(String type, List<String> tags, String lane, double confidence) {
    }

    private record TemplateSignal(boolean luceneActive, int hitCount, String mode) {
    }

    private record TemplateScoringFeature(String name,
                                          double rawScore,
                                          double normalizedScore,
                                          double weight,
                                          String reason) {
    }

    private enum MatchStrength {
        NONE,
        SUBSTRING,
        WORD
    }
}
