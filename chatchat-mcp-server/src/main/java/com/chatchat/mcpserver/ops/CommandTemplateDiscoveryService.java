package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.database.DatabaseQuerySqlStep;
import com.chatchat.mcpserver.routing.TargetKindRegistry;
import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.SqlTemplateConfig;
import com.chatchat.mcpserver.sql.SqlTemplateService;
import com.chatchat.mcpserver.template.AgentRuntimeTemplateDsl;
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
import java.util.regex.Pattern;

@Service
@Slf4j
public class CommandTemplateDiscoveryService {

    private static final Set<String> RETIRED_SQL_METADATA_TEMPLATE_CODES = Set.of(
        "MYSQL_SCHEMA_TABLE_OVERVIEW",
        "MYSQL_TABLE_LOCATION",
        "MYSQL_TABLE_METADATA",
        "ORACLE_TABLE_METADATA",
        "POSTGRES_TABLE_METADATA",
        "SQLSERVER_TABLE_METADATA"
    );

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
    private static final Pattern BINDING_PLACEHOLDER_PATTERN = Pattern.compile(
        "\\{\\{\\s*bindings\\.[A-Za-z0-9_.\\-\\[\\]]+\\s*}}"
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
        rejectUnresolvedBindingPlaceholders(filters, "filters");
        rejectConcreteTargetFields(filters);
        TargetKindRegistry.Resolution target = targetKindRegistry.resolveForTool(
            "template_query",
            firstValue(arguments, "assetType", "type"),
            arguments,
            filters
        );
        Set<String> rejectedTemplateIds = excludedTemplateIds(arguments);
        if (!rejectedTemplateIds.isEmpty()) {
            filters.put("excludeTemplateIds", rejectedTemplateIds);
        }
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
        Map<String, Object> sshRetrievalFilters = assetScoped
            ? filtersWithSshAssetSignals(retrievalFilters, hosts)
            : retrievalFilters;
        Map<String, Object> resultFilters = assetScoped
            ? filtersWithSshAssetSignals(filters, hosts)
            : filters;
        Map<String, LuceneMcpSearchService.SearchHit> luceneHits = luceneTemplateHits(
            templates.stream().map(this::templateDoc).toList(),
            assetType,
            null,
            sshRetrievalFilters,
            intent,
            Math.max(limit, MAX_LIMIT)
        );
        List<ScoredTemplate<CommandTemplateConfig>> candidates = templates.stream()
            .filter(template -> !assetScoped || allowedByAsset.contains(normalize(template.getCode())))
            .map(template -> new ScoredTemplate<>(template,
                decision(luceneAdjusted(relevance(template, sshRetrievalFilters), luceneHits.get(template.getCode())),
                    intent, "ssh_host", null, riskLevel(template))))
            .toList();
        List<ScoredTemplate<CommandTemplateConfig>> matched = rankAndFallback(candidates, intent, scored -> scored.template().getCode());
        boolean fallbackUsed = fallbackUsed(candidates, matched, intent);
        log.info("template_query ssh search assetType={} filters={} normalizedIntent={} registryTemplates={} candidates={} luceneHits={} returned={} fallbackUsed={}",
            assetType, compactFilters(filters), intent.type(), templates.size(), candidates.size(), luceneHits.size(),
            Math.min(matched.size(), limit), fallbackUsed);
        Map<String, Object> result = result(assetType, resultFilters, intent, sshAssetMetadata(hosts, filters, assetScoped), limit,
            sshTemplateMetadata(matched, assetType, limit), matched.size() > limit, fallbackUsed, templateSignal(luceneHits));
        Map<String, Object> registryDiagnostics = sshTemplateRegistryDiagnostics(templates, allowedByAsset, assetScoped);
        if (!registryDiagnostics.isEmpty()) {
            result.put("templateRegistryDiagnostics", registryDiagnostics);
        }
        return result;
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
        Map<String, Object> sqlRetrievalFilters = assetScoped
            ? filtersWithSqlAssetSignals(retrievalFilters, datasources)
            : retrievalFilters;
        Map<String, Object> resultFilters = assetScoped
            ? filtersWithSqlAssetSignals(filters, datasources)
            : filters;
        List<SqlTemplateConfig> templates = sqlTemplateService.listEnabled().stream()
            .filter(template -> !isRetiredSqlMetadataTemplate(template))
            .toList();
        Map<String, LuceneMcpSearchService.SearchHit> luceneHits = luceneTemplateHits(
            templates.stream().map(this::templateDoc).toList(),
            assetType,
            dbType,
            sqlRetrievalFilters,
            intent,
            Math.max(limit, MAX_LIMIT)
        );
        List<ScoredTemplate<SqlTemplateConfig>> candidates = templates.stream()
            .filter(template -> templateSearchHit(luceneHits, template.getCode()) != null)
            .filter(template -> sqlTemplateCompatibleWithRequestedType(template, filters, datasources, assetScoped))
            .filter(template -> sqlTemplateAuthorizedByDatasource(template, datasources, assetScoped))
            .map(template -> new ScoredTemplate<>(template,
                decision(luceneAdjusted(relevance(template, sqlRetrievalFilters), templateSearchHit(luceneHits, template.getCode())),
                    intent, template.getDatabaseType(),
                    selectedSqlAssetType(datasources, assetScoped), riskLevel(template))))
            .toList();
        List<ScoredTemplate<SqlTemplateConfig>> matched = candidates.stream()
            .sorted(sqlScoredComparator(intent, filters))
            .toList();
        boolean fallbackUsed = false;
        log.info("template_query sql search assetType={} dbType={} filters={} normalizedIntent={} datasources={} registryTemplates={} candidates={} luceneHits={} returned={} fallbackUsed={} hitIds={}",
            assetType, dbType, compactFilters(filters), intent.type(), datasources.size(), templates.size(), candidates.size(),
            luceneHits.size(), Math.min(matched.size(), limit), fallbackUsed, luceneHits.keySet().stream().limit(limit).toList());
        Map<String, Object> result = result(assetType, resultFilters, intent, sqlAssetMetadata(datasources, filters, assetScoped), limit,
            sqlTemplateMetadata(matched, assetType, limit), matched.size() > limit, fallbackUsed, templateSignal(luceneHits));
        Map<String, Object> registryDiagnostics = sqlTemplateRegistryDiagnostics(templates, datasources, assetScoped);
        if (!registryDiagnostics.isEmpty()) {
            result.put("templateRegistryDiagnostics", registryDiagnostics);
        }
        return result;
    }

    private LuceneMcpSearchService.SearchHit templateSearchHit(
        Map<String, LuceneMcpSearchService.SearchHit> hits, String templateCode) {
        if (hits == null || hits.isEmpty() || templateCode == null) {
            return null;
        }
        LuceneMcpSearchService.SearchHit exact = hits.get(templateCode);
        if (exact != null) {
            return exact;
        }
        String normalizedCode = normalize(templateCode);
        return hits.entrySet().stream()
            .filter(entry -> normalizedCode != null && normalizedCode.equals(normalize(entry.getKey())))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }

    private Map<String, Object> queryHttpTemplates(String assetType,
                                                   Map<String, Object> filters,
                                                   Map<String, Object> retrievalFilters,
                                                   NormalizedIntent intent,
                                                   int limit) {
        List<HttpEndpointConfig> endpoints = httpEndpointConfigService.listEnabled().stream()
            .filter(endpoint -> matchesHttpEndpoint(endpoint, filters))
            .filter(endpoint -> !excludedTemplateIds(filters).contains(normalize(
                firstText(endpoint.getToolName(), firstText(endpoint.getName(), endpoint.getId())))))
            .toList();
        boolean assetScoped = hasAssetScope(filters);
        Map<String, Object> httpRetrievalFilters = assetScoped
            ? filtersWithHttpAssetSignals(retrievalFilters, endpoints)
            : retrievalFilters;
        Map<String, Object> resultFilters = assetScoped
            ? filtersWithHttpAssetSignals(filters, endpoints)
            : filters;
        Map<String, LuceneMcpSearchService.SearchHit> luceneHits = luceneTemplateHits(
            endpoints.stream().map(this::templateDoc).toList(),
            assetType,
            null,
            httpRetrievalFilters,
            intent,
            Math.max(limit, MAX_LIMIT)
        );
        List<ScoredTemplate<HttpEndpointConfig>> candidates = endpoints.stream()
            .map(endpoint -> new ScoredTemplate<>(endpoint,
                decision(luceneAdjusted(relevance(endpoint, httpRetrievalFilters),
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
        return result(assetType, resultFilters, intent, httpAssetMetadata(endpoints, filters, assetScoped), limit,
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
        List<DatabaseQueryConfig> scopedDatabaseQueries = hasAssetScope(filters)
            ? scopedDatabaseQueryTemplates(templates, filters)
            : List.of();
        Map<String, Object> databaseQueryRetrievalFilters = hasAssetScope(filters)
            ? filtersWithDatabaseQueryAssetSignals(retrievalFilters, scopedDatabaseQueries)
            : retrievalFilters;
        Map<String, Object> resultFilters = hasAssetScope(filters)
            ? filtersWithDatabaseQueryAssetSignals(filters, scopedDatabaseQueries)
            : filters;
        Map<String, LuceneMcpSearchService.SearchHit> luceneHits = luceneTemplateHits(
            databaseQueryAssetDocs(templates),
            assetType,
            requestedDatabaseType(filters),
            databaseQueryRetrievalFilters,
            intent,
            Math.max(limit, MAX_LIMIT)
        );
        List<ScoredTemplate<DatabaseQueryConfig>> candidates = templates.stream()
            .filter(template -> databaseQueryDatasourceId(template) != null)
            .filter(template -> luceneHits.containsKey(databaseQueryDatasourceId(template)))
            .map(template -> new ScoredTemplate<>(template,
                marketplaceDecision(
                    luceneAdjusted(relevance(template, databaseQueryRetrievalFilters), luceneHits.get(databaseQueryDatasourceId(template))),
                    luceneHits.get(databaseQueryDatasourceId(template)),
                    intent,
                    databaseQueryRetrievalFilters,
                    template)))
            .toList();
        List<ScoredTemplate<DatabaseQueryConfig>> matched = candidates.stream()
            .sorted(scoredComparator(scored -> scored.template().getToolName()))
            .toList();
        boolean fallbackUsed = false;
        log.info("template_query database-query search assetType={} dbType={} filters={} normalizedIntent={} registryTemplates={} candidates={} luceneHits={} returned={} fallbackUsed={} hitIds={}",
            assetType, requestedDatabaseType(filters), compactFilters(filters), intent.type(), templates.size(), candidates.size(),
            luceneHits.size(), Math.min(matched.size(), limit), fallbackUsed, luceneHits.keySet().stream().limit(limit).toList());
        return result(assetType, resultFilters, intent, databaseQueryAssetMetadata(matched), limit,
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
                "intentSynonymSource", "built-in zh/en intent synonyms plus chatchat.mcp.template-discovery.intent-synonyms and template intentSignals",
                "languageSupport", "Models should generate bilingual Chinese and English retrieval terms in bilingualIntent, bilingualQuery, intentZh, or intentEn; the engine expands them into shared bilingual signals before retrieval and ranking.",
                "selectionHint", "Generate and use bilingual Chinese and English retrieval terms, then choose the returned template whose name, description, intentSignals, relevanceScore, and matchReasons best match the user intent; do not use asset allowed template order as semantic ranking.",
                "fallback", "If authorized candidates exist but intent ranking returns no match, the engine broadens intent and marks resolutionTrace[].fallbackUsed=true.",
                "selectionFields", List.of("templateId", "name", "description", "capabilitySpec", "outputSchema", "dependencySpec", "templateConfig", "intentSignals", "parameterSchema", "requiredParameters", "parameterContract", "invocationExample"),
                "sqlDisclosure", "business_query_template_search returns executable template references and parameter contracts only; it must not return raw SQL text or stored query bodies.",
                "onEmptyResult", "No existing authorized template matched the request after asset, type and authorization filters. Do not suggest a new template name unless the user asks to administer templates."
            ),
            "selectionProtocol", mapOf(
                "schemaVersion", "template_selection_protocol.v1",
                "allowedDecisions", List.of("accept", "refine", "reject"),
                "excludedTemplateIds", excludedTemplateIds(filters),
                "candidateIsNotAcceptance", true,
                "repeatIdenticalQueryForbidden", true
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
                "confidence", intent.confidence(),
                "bilingualRetrieval", bilingualRetrieval(filters, intent)
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
                "confidence", intent.confidence(),
                "bilingualRetrieval", bilingualRetrieval(filters, intent)
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
        return new TemplateSignal(true, hitCount, hitCount > 0 ? "lucene_scored" : "dedicated_index_no_hit");
    }

    private String templateRetrievalStrategy(TemplateSignal signal) {
        if (signal == null || !signal.luceneActive()) {
            return "dedicated_template_index_unavailable";
        }
        if (signal.hitCount() <= 0) {
            return "dedicated_template_index_no_hit";
        }
        return "dedicated_template_index_hit_then_authorized_filter_rank";
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
        LuceneMcpSearchService.TemplateSearchRequest request = new LuceneMcpSearchService.TemplateSearchRequest(
            assetType,
            dbType,
            luceneIntentText(filters, intent),
            limit
        );
        boolean databaseQueryIndex = "database_query".equalsIgnoreCase(assetType == null ? "" : assetType);
        List<LuceneMcpSearchService.SearchHit> hits = databaseQueryIndex
            ? luceneSearchService.searchDatabaseQueryTemplates(docs, request)
            : luceneSearchService.searchTemplates(docs, request);
        boolean strictTemplateIndex = "sql_datasource".equalsIgnoreCase(assetType == null ? "" : assetType)
            || databaseQueryIndex;
        if (!strictTemplateIndex && hits.isEmpty() && intent != null && !"unknown".equals(intent.type())) {
            LuceneMcpSearchService.TemplateSearchRequest fallbackRequest =
                new LuceneMcpSearchService.TemplateSearchRequest(assetType, dbType, null, limit);
            hits = databaseQueryIndex
                ? luceneSearchService.searchDatabaseQueryTemplates(docs, fallbackRequest)
                : luceneSearchService.searchTemplates(docs, fallbackRequest);
        }
        Map<String, LuceneMcpSearchService.SearchHit> byId = new LinkedHashMap<>();
        hits.stream()
            .filter(hit -> !isRetiredSqlMetadataTemplateId(hit.id()))
            .forEach(hit -> byId.put(hit.id(), hit));
        return byId;
    }

    private String luceneIntentText(Map<String, Object> filters, NormalizedIntent intent) {
        LinkedHashSet<String> values = new LinkedHashSet<>(intentTokens(filters));
        values.addAll(bilingualIntentTokens(filters));
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

    private List<LuceneMcpSearchService.TemplateDoc> databaseQueryAssetDocs(List<DatabaseQueryConfig> templates) {
        Map<String, DatabaseQueryAssetTemplates> grouped = new LinkedHashMap<>();
        for (DatabaseQueryConfig config : templates == null ? List.<DatabaseQueryConfig>of() : templates) {
            databaseQueryDatasource(config).ifPresent(datasource -> {
                String key = firstText(datasource.getId(), datasource.getName());
                if (key == null) {
                    return;
                }
                grouped.computeIfAbsent(key, ignored -> new DatabaseQueryAssetTemplates(datasource, new ArrayList<>()))
                    .templates()
                    .add(config);
            });
        }
        return grouped.values().stream()
            .map(this::databaseQueryAssetDoc)
            .toList();
    }

    private LuceneMcpSearchService.TemplateDoc databaseQueryAssetDoc(DatabaseQueryAssetTemplates asset) {
        SqlDatasourceConfig datasource = asset.datasource();
        List<DatabaseQueryConfig> templates = asset.templates();
        List<String> signals = new ArrayList<>();
        addWords(signals, datasource.getName());
        addWords(signals, datasource.getTitle());
        addWords(signals, datasource.getToolName());
        addWords(signals, datasource.getDescription());
        addWords(signals, datasource.getEnvironment());
        addWords(signals, datasource.getDatabaseType());
        addWords(signals, datasource.getMetadataScopeValue());
        addWords(signals, "assetName");
        addWords(signals, "env");
        addWords(signals, "environment");
        addWords(signals, "databaseType");
        addWords(signals, "dbType");
        addWords(signals, "executionContext");
        Set<String> assetSignals = new LinkedHashSet<>();
        addJsonLabels(assetSignals, datasource.getRoutingLabelsJson());
        addJsonLabels(assetSignals, datasource.getCapabilitiesJson());
        addGovernanceSignals(assetSignals, datasource.getGovernanceJson());
        signals.addAll(assetSignals);
        for (DatabaseQueryConfig config : templates) {
            signals.addAll(intentSignals(config));
            addWords(signals, config.getSqlTemplate());
        }
        List<String> distinctSignals = signals.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
        return new LuceneMcpSearchService.TemplateDoc(
            firstText(datasource.getId(), firstText(datasource.getName(), datasource.getToolName())),
            "database_query",
            firstText(datasource.getTitle(), firstText(datasource.getName(), datasource.getToolName())),
            databaseQueryAssetDescription(datasource, templates),
            "sql_template_registry",
            SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType()),
            String.join(" ", distinctSignals),
            "read_only",
            distinctSignals,
            "database_query_asset_registry"
        );
    }

    private String databaseQueryAssetDescription(SqlDatasourceConfig datasource, List<DatabaseQueryConfig> templates) {
        List<String> values = new ArrayList<>();
        addWords(values, datasource.getDescription());
        addWords(values, datasource.getName());
        addWords(values, datasource.getTitle());
        addWords(values, datasource.getToolName());
        addWords(values, datasource.getEnvironment());
        addWords(values, datasource.getDatabaseType());
        for (DatabaseQueryConfig config : templates) {
            addWords(values, config.getTitle());
            addWords(values, config.getDescription());
            addWords(values, config.getTemplateIntent());
            addWords(values, config.getBusinessGroup());
            addWords(values, config.getBusinessGroupName());
            addWords(values, config.getBusinessGroupDescription());
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .limit(80)
            .reduce((left, right) -> left + " " + right)
            .orElse("");
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

    private List<Map<String, Object>> databaseQueryAssetMetadata(List<ScoredTemplate<DatabaseQueryConfig>> scored) {
        if (scored == null || scored.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> assets = new LinkedHashMap<>();
        for (ScoredTemplate<DatabaseQueryConfig> item : scored) {
            databaseQueryDatasource(item.template()).ifPresent(datasource -> {
                String key = firstText(datasource.getId(), firstText(datasource.getName(), datasource.getToolName()));
                if (key != null && !assets.containsKey(key)) {
                    assets.put(key, assetMetadata(
                        "sql_datasource",
                        datasource.getId(),
                        datasource.getName(),
                        datasource.getTitle(),
                        datasource.getToolName(),
                        datasource.getEnvironment(),
                        datasourceLabels(datasource),
                        Map.of()
                    ));
                }
            });
        }
        return new ArrayList<>(assets.values());
    }

    private Map<String, Object> templateMetadata(CommandTemplateConfig template,
                                                 String assetType,
                                                 Relevance relevance,
                                                 int rank) {
        List<String> signals = intentSignals(template);
        Map<String, Object> parameterSchema = parameterSchema(template);
        List<String> requiredParameters = requiredParameters(parameterSchema);
        return mapOf(
            "schemaVersion", TEMPLATE_SCHEMA_VERSION,
            "id", template.getCode(),
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
            "templateDsl", templateDslMetadata(template.getCommandTemplate(), template.getCode(), "LINUX_CMD", "SHELL"),
            "intentSignals", signals,
            "routingHints", mapOf(
                "strongSignals", signals.stream().limit(5).toList(),
                "contextKeys", List.of("assetName", "env", "environment", "cluster", "service", "target", "labels")
            ),
            "parameterSchema", parameterSchema,
            "requiredParameters", requiredParameters,
            "parameterContract", parameterContract(template.getCode(), parameterSchema, "linux_command_execute.parameters", "linux_command_execute"),
            "invocationExample", invocationExample(template.getCode(), parameterSchema, "linux_command_execute", "<assetName from ssh_asset_query>", "<env>"),
            "enabled", template.isEnabled()
        );
    }

    private Map<String, Object> templateMetadata(SqlTemplateConfig template,
                                                 String assetType,
                                                 Relevance relevance,
                                                 int rank) {
        List<String> signals = intentSignals(template);
        Map<String, Object> parameterSchema = parameterSchema(template.getParameterSchemaJson());
        List<String> requiredParameters = requiredParameters(parameterSchema);
        return mapOf(
            "schemaVersion", TEMPLATE_SCHEMA_VERSION,
            "id", template.getCode(),
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
            "semantic", sqlTemplateSemanticMetadata(template),
            "binding", sqlTemplateBindingMetadata(template),
            "supportedAssetTypes", List.of(assetType),
            "templateDsl", templateDslMetadata(template.getSqlTemplate(), template.getCode(), "DB_SQL", "SQL"),
            "intentSignals", signals,
            "routingHints", mapOf(
                "strongSignals", signals.stream().limit(5).toList(),
                "contextKeys", List.of("assetName", "env", "environment", "cluster", "database", "databaseType", "dbType", "dialect", "databaseRole", "service", "target", "labels")
            ),
            "parameterSchema", parameterSchema,
            "requiredParameters", requiredParameters,
            "parameterContract", parameterContract(template.getCode(), parameterSchema, "sql_query_execute.parameters", "sql_query_execute"),
            "invocationExample", invocationExample(template.getCode(), parameterSchema, "sql_query_execute", "<logical datasource assetName from user context or template routing>", "<env>"),
            "enabled", template.isEnabled()
        );
    }

    private Map<String, Object> sqlTemplateSemanticMetadata(SqlTemplateConfig template) {
        String databaseType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(template.getDatabaseType());
        String category = category(template);
        boolean requiresTable = parameterRequired(template.getParameterSchemaJson(), "tableName", "table_name");
        List<String> signals = intentSignals(template);
        boolean metadataIntent = containsIgnoreCase(category, "metadata") || containsAnyIgnoreCase(signals, "metadata", "schema", "column", "describe");
        String targetLevel = requiresTable ? "TABLE" : targetLevelFromCategory(category);
        String operation = requiresTable && metadataIntent ? "TABLE_METADATA_QUERY" : operationFromCategory(category);
        return mapOf(
            "schemaVersion", "sql_template_semantic.v1",
            "operation", operation,
            "targetLevel", targetLevel,
            "dialect", databaseType,
            "dialects", List.of(databaseType),
            "requiresTableName", requiresTable,
            "scope", operation
        );
    }

    private Map<String, Object> templateDslMetadata(String templateBody,
                                                    String templateCode,
                                                    String fallbackTemplateType,
                                                    String fallbackStepType) {
        if (!AgentRuntimeTemplateDsl.looksLikeDsl(templateBody)) {
            return mapOf(
                "schemaVersion", AgentRuntimeTemplateDsl.SCHEMA_VERSION,
                "dsl", false,
                "templateCode", templateCode,
                "templateType", fallbackTemplateType,
                "executionMode", "SINGLE_OR_LEGACY",
                "executorStepType", fallbackStepType,
                "modelHint", "Legacy template body; execute by templateId and parameters from parameterContract."
            );
        }
        try {
            AgentRuntimeTemplateDsl.TemplatePlan plan = AgentRuntimeTemplateDsl.parse(
                templateBody,
                templateCode,
                fallbackTemplateType,
                fallbackStepType
            );
            Map<String, Object> metadata = new LinkedHashMap<>(AgentRuntimeTemplateDsl.metadata(plan));
            metadata.put("dsl", true);
            metadata.put("modelHint", "Use templateDsl.steps[].stepName/analysisHint to judge whether this template covers the user intent. Execute by templateId; do not inline raw commands or SQL.");
            return metadata;
        } catch (IllegalArgumentException ex) {
            return mapOf(
                "schemaVersion", AgentRuntimeTemplateDsl.SCHEMA_VERSION,
                "dsl", true,
                "templateCode", templateCode,
                "templateType", fallbackTemplateType,
                "valid", false,
                "error", ex.getMessage(),
                "modelHint", "DSL template is invalid and should not be selected until fixed."
            );
        }
    }

    private boolean parameterRequired(String parameterSchemaJson, String... names) {
        Map<String, Object> schema = parameterSchema(parameterSchemaJson);
        Object required = schema.get("required");
        if (!(required instanceof Iterable<?> iterable)) {
            return false;
        }
        Set<String> expected = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null) {
                expected.add(name.replace("_", "").toLowerCase(Locale.ROOT));
            }
        }
        for (Object item : iterable) {
            if (item != null && expected.contains(String.valueOf(item).replace("_", "").toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String targetLevelFromCategory(String category) {
        String normalized = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (normalized.contains("schema") || normalized.contains("storage")) {
            return "SCHEMA";
        }
        return "INSTANCE";
    }

    private String operationFromCategory(String category) {
        String normalized = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (normalized.contains("lock")) {
            return "LOCK_DIAGNOSTIC_QUERY";
        }
        if (normalized.contains("connection") || normalized.contains("session")) {
            return "SESSION_DIAGNOSTIC_QUERY";
        }
        if (normalized.contains("storage")) {
            return "STORAGE_DIAGNOSTIC_QUERY";
        }
        if (normalized.contains("metadata")) {
            return "METADATA_QUERY";
        }
        return "INSTANCE_DIAGNOSTIC_QUERY";
    }

    private boolean containsAnyIgnoreCase(List<String> values, String... needles) {
        if (values == null || values.isEmpty() || needles == null) {
            return false;
        }
        for (String value : values) {
            for (String needle : needles) {
                if (containsIgnoreCase(value, needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && needle != null
            && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private Map<String, Object> templateMetadata(HttpEndpointConfig endpoint,
                                                 String assetType,
                                                 Relevance relevance,
                                                 int rank) {
        List<String> signals = intentSignals(endpoint);
        String templateId = firstText(endpoint.getToolName(), firstText(endpoint.getName(), endpoint.getId()));
        Map<String, Object> parameterSchema = parameterSchema(endpoint.getInputSchemaJson());
        List<String> requiredParameters = requiredParameters(parameterSchema);
        return mapOf(
            "schemaVersion", TEMPLATE_SCHEMA_VERSION,
            "id", templateId,
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
            "capabilitySpec", readObject(endpoint.getCapabilitySpecJson()),
            "outputSchema", readObject(endpoint.getOutputSchemaJson()),
            "dependencySpec", readObject(endpoint.getDependencySpecJson()),
            "routingHints", mapOf(
                "strongSignals", signals.stream().limit(5).toList(),
                "contextKeys", List.of("assetName", "env", "environment", "cluster", "service", "target", "labels")
            ),
            "parameterSchema", parameterSchema,
            "requiredParameters", requiredParameters,
            "parameterContract", parameterContract(templateId, parameterSchema, "http_request_execute.parameters", "http_request_execute"),
            "invocationExample", invocationExample(templateId, parameterSchema, "http_request_execute", "<assetName from http_endpoint_asset_query>", "<env>"),
            "enabled", endpoint.isEnabled()
        );
    }

    private Map<String, Object> templateMetadata(DatabaseQueryConfig config,
                                                 String assetType,
                                                 Relevance relevance,
                                                 int rank) {
        List<String> signals = intentSignals(config);
        String toolName = firstText(config.getToolName(), config.getId());
        String executorTool = databaseQueryExecutorTool(config);
        Map<String, Object> parameterSchema = parameterSchema(config.getInputSchemaJson());
        List<String> requiredParameters = requiredParameters(parameterSchema);
        Map<String, Object> executionContext = databaseQueryExecutionContext(config);
        Map<String, Object> datasourceAsset = databaseQueryDatasource(config)
            .<Map<String, Object>>map(datasource -> mapOf(
                "type", "sql_datasource",
                "id", datasource.getId(),
                "name", datasource.getName(),
                "title", datasource.getTitle(),
                "toolName", datasource.getToolName(),
                "environment", datasource.getEnvironment(),
                "databaseType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType())
            ))
            .orElse(Map.of());
        return mapOf(
            "schemaVersion", TEMPLATE_SCHEMA_VERSION,
            "id", toolName,
            "templateId", toolName,
            "databaseQueryId", config.getId(),
            "mcpToolName", executorTool,
            "templateConfig", databaseQueryTemplateConfig(config, datasourceAsset, executionContext, parameterSchema),
            "datasourceAsset", datasourceAsset,
            "executionContext", executionContext,
            "sqlExecutionBinding", mapOf(
                "toolName", executorTool,
                "templateId", toolName,
                "executionContext", executionContext,
                "parametersPath", "parameters"
            ),
            "name", firstText(config.getTitle(), toolName),
            "description", databaseQueryTemplateDescription(config),
            "implementationSteps", firstText(config.getImplementationSteps(), ""),
            "workflow", databaseQueryWorkflowMetadata(config),
            "businessGroup", businessGroupMetadata(config),
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
            "templateDsl", templateDslMetadata(config.getSqlTemplate(), toolName, "DATABASE_QUERY", "SQL"),
            "owner", firstText(config.getOwner(), "admin"),
            "rating", config.getRating(),
            "usageCount", config.getUsageCount(),
            "marketplace", mapOf(
                "registry", "business_database_query",
                "publishMode", "template_via_execution_gateway",
                "governance", mapOf(
                    "intent", firstText(config.getTemplateIntent(), "general_query"),
                    "businessGroup", businessGroupMetadata(config),
                    "dbType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(config.getDatabaseType()),
                    "riskLevel", databaseQueryRiskLevel(config),
                    "owner", firstText(config.getOwner(), "admin")
                )
            ),
            "supportedAssetTypes", List.of(assetType),
            "intentSignals", signals,
            "routingHints", mapOf(
                "strongSignals", signals.stream().limit(5).toList(),
                "contextKeys", List.of("intent", "goal", "category", "businessGroup", "group", "template", "labels",
                    "assetName", "env", "databaseType")
            ),
            "execution", mapOf(
                "mode", "template_execution",
                "executorTool", executorTool,
                "template", toolName,
                "callTool", executorTool,
                "executionContext", executionContext,
                "argumentSchemaPath", "templates[].parameterSchema"
            ),
            "parameterSchema", parameterSchema,
            "requiredParameters", requiredParameters,
            "parameterContract", parameterContract(toolName, parameterSchema,
                executorTool + ".parameters", executorTool),
            "invocationExample", invocationExample(toolName, parameterSchema, executorTool,
                "<assetName from datasourceAsset>", "<env>"),
            "enabled", config.isEnabled()
        );
    }

    private String databaseQueryExecutorTool(DatabaseQueryConfig config) {
        List<DatabaseQuerySqlStep> steps = databaseQuerySqlSteps(config).stream()
            .filter(DatabaseQuerySqlStep::enabled)
            .toList();
        boolean workflow = steps.size() > 1
            || steps.stream().anyMatch(step -> Boolean.TRUE.equals(step.getWorkflowEnabled()));
        return workflow ? "sql_script_execute" : "sql_query_execute";
    }

    private Map<String, Object> databaseQueryExecutionContext(DatabaseQueryConfig config) {
        return databaseQueryDatasource(config)
            .<Map<String, Object>>map(datasource -> mapOf(
                "assetName", firstText(datasource.getName(), firstText(datasource.getTitle(), datasource.getToolName())),
                "env", datasource.getEnvironment(),
                "environment", datasource.getEnvironment(),
                "databaseType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType()),
                "dbType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType())
            ))
            .orElse(Map.of());
    }

    private java.util.Optional<SqlDatasourceConfig> databaseQueryDatasource(DatabaseQueryConfig config) {
        if (config == null || config.getDatasourceId() == null || config.getDatasourceId().isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.ofNullable(datasourceConfigService.getEnabled(config.getDatasourceId()));
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private String databaseQueryDatasourceId(DatabaseQueryConfig config) {
        return databaseQueryDatasource(config)
            .map(datasource -> firstText(datasource.getId(), datasource.getName()))
            .orElse(null);
    }

    private Map<String, Object> businessGroupMetadata(DatabaseQueryConfig config) {
        String code = firstText(config.getBusinessGroup(), "default");
        return mapOf(
            "code", code,
            "name", firstText(config.getBusinessGroupName(), code),
            "description", firstText(config.getBusinessGroupDescription(), "")
        );
    }

    private String databaseQueryTemplateDescription(DatabaseQueryConfig config) {
        String description = firstText(config.getDescription(), "");
        Map<String, Object> businessGroup = businessGroupMetadata(config);
        String code = String.valueOf(businessGroup.getOrDefault("code", "default"));
        String name = String.valueOf(businessGroup.getOrDefault("name", code));
        String groupDescription = String.valueOf(businessGroup.getOrDefault("description", ""));
        StringBuilder builder = new StringBuilder(description);
        if (!builder.isEmpty()) {
            builder.append(" ");
        }
        builder.append("Business group: ").append(name);
        if (!code.equals(name)) {
            builder.append(" (").append(code).append(")");
        }
        if (!groupDescription.isBlank()) {
            builder.append(". Group context: ").append(groupDescription);
        }
        return builder.toString();
    }

    private Map<String, Object> databaseQueryTemplateConfig(DatabaseQueryConfig config,
                                                            Map<String, Object> datasourceAsset,
                                                            Map<String, Object> executionContext,
                                                            Map<String, Object> parameterSchema) {
        return mapOf(
            "id", config.getId(),
            "toolName", firstText(config.getToolName(), config.getId()),
            "title", firstText(config.getTitle(), firstText(config.getToolName(), config.getId())),
            "datasourceId", config.getDatasourceId(),
            "description", databaseQueryTemplateDescription(config),
            "businessGroup", businessGroupMetadata(config),
            "inputSchema", parameterSchema,
            "governance", readObject(config.getGovernanceJson()),
            "routingLabels", readStringArray(config.getRoutingLabelsJson()),
            "capabilities", readStringArray(config.getCapabilitiesJson()),
            "intent", firstText(config.getTemplateIntent(), "general_query"),
            "databaseType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(config.getDatabaseType()),
            "tags", readStringArray(config.getTagsJson()),
            "riskLevel", databaseQueryRiskLevel(config),
            "owner", firstText(config.getOwner(), "admin"),
            "rating", config.getRating(),
            "usageCount", config.getUsageCount(),
            "maxRows", config.getMaxRows(),
            "timeoutSeconds", config.getTimeoutSeconds(),
            "connection", mapOf(
                "datasourceAsset", datasourceAsset,
                "executionContext", executionContext
            ),
            "enabled", config.isEnabled(),
            "createdAt", config.getCreatedAt() == null ? null : config.getCreatedAt().toString(),
            "updatedAt", config.getUpdatedAt() == null ? null : config.getUpdatedAt().toString(),
            "queryBodyReturned", false
        );
    }

    private Map<String, Object> databaseQueryWorkflowMetadata(DatabaseQueryConfig config) {
        List<DatabaseQuerySqlStep> steps = databaseQuerySqlSteps(config);
        boolean dependencyGraph = steps.stream().anyMatch(step -> Boolean.TRUE.equals(step.getWorkflowEnabled()));
        return mapOf(
            "schemaVersion", "database_query_workflow_definition.v1",
            "mode", dependencyGraph ? "DEPENDENCY_GRAPH" : "SEQUENTIAL",
            "implementationSteps", firstText(config.getImplementationSteps(), ""),
            "nodeCount", steps.size(),
            "nodes", steps.stream().filter(DatabaseQuerySqlStep::enabled).map(step -> mapOf(
                "nodeCode", step.getSqlCode(),
                "nodeName", step.getSqlName(),
                "sqlDescription", step.getSqlDescription(),
                "dependencies", step.getDependencies(),
                "parameterMappings", step.getParameterMappings(),
                "resultSemantic", step.getResultSemantic(),
                "failureStrategy", step.getFailureStrategy(),
                "emptyResultStrategy", step.getEmptyResultStrategy(),
                "returnToModel", step.getReturnToModel()
            )).toList(),
            "queryBodyReturned", false
        );
    }

    private List<DatabaseQuerySqlStep> databaseQuerySqlSteps(DatabaseQueryConfig config) {
        try {
            return config.getSqlStepsJson() == null || config.getSqlStepsJson().isBlank()
                ? List.of()
                : objectMapper.readValue(config.getSqlStepsJson(), new TypeReference<List<DatabaseQuerySqlStep>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private NormalizedIntent normalizeIntent(Map<String, Object> filters) {
        List<String> primaryTokens = primaryIntentTokens(filters);
        if (containsAnyToken(primaryTokens, "metadata", "schema", "column", "field", "\u5143\u6570\u636e", "\u8868\u7ed3\u6784", "\u5b57\u6bb5", "\u5217")) {
            return new NormalizedIntent("metadata_query", List.of("metadata", "schema", "column"), "ops", 0.92);
        }
        if (containsAnyToken(primaryTokens, "status", "instance", "database health", "health check",
            "\u72b6\u6001", "\u6570\u636e\u5e93\u72b6\u6001", "\u72b6\u6001\u5206\u6790", "\u5065\u5eb7\u68c0\u67e5")) {
            return new NormalizedIntent("db_status", List.of("status", "health", "instance"), "ops", 0.94);
        }
        if (containsAnyToken(primaryTokens, "performance", "slow", "latency", "cpu", "\u5361", "\u5361\u987f", "\u6162", "\u6027\u80fd", "\u6027\u80fd\u5206\u6790", "\u6162\u67e5\u8be2")) {
            return new NormalizedIntent("performance_issue", List.of("performance", "slow", "health"), "ops", 0.9);
        }
        if (containsAnyToken(primaryTokens, "lock", "blocking", "deadlock", "wait", "\u9501", "\u963b\u585e", "\u7b49\u5f85", "\u6b7b\u9501")) {
            return new NormalizedIntent("lock_check", List.of("lock", "blocking", "deadlock"), "ops", 0.9);
        }
        List<String> tokens = new ArrayList<>(intentTokens(filters));
        tokens.addAll(bilingualIntentTokens(filters));
        tokens = tokens.stream().distinct().toList();
        if (tokens.isEmpty()) {
            return new NormalizedIntent("unknown", List.of(), "ops", 0.0);
        }
        if (containsAnyToken(tokens, "metadata", "schema", "column", "field", "\u5143\u6570\u636e", "\u8868\u7ed3\u6784", "\u5b57\u6bb5", "\u5217")) {
            return new NormalizedIntent("metadata_query", List.of("metadata", "schema", "column"), "ops", 0.88);
        }
        if (containsAnyToken(tokens, "lock", "blocking", "deadlock", "wait", "\u9501", "\u963b\u585e", "\u7b49\u5f85", "\u6b7b\u9501")) {
            return new NormalizedIntent("lock_check", List.of("lock", "blocking", "deadlock"), "ops", 0.88);
        }
        if (containsAnyToken(tokens, "storage", "size", "space", "capacity", "\u7a7a\u95f4", "\u5bb9\u91cf", "\u5927\u5c0f", "\u5b58\u50a8")) {
            return new NormalizedIntent("storage_check", List.of("storage", "size", "space"), "ops", 0.86);
        }
        if (containsAnyToken(tokens, "connection", "connections", "session", "processlist", "\u8fde\u63a5", "\u4f1a\u8bdd", "\u8fde\u63a5\u6570")) {
            return new NormalizedIntent("connection_check", List.of("connection", "session", "processlist"), "ops", 0.86);
        }
        if (containsAnyToken(tokens, "performance", "slow", "latency", "cpu", "\u5361", "\u5361\u987f", "\u6162", "\u6027\u80fd", "\u6027\u80fd\u5206\u6790", "\u6162\u67e5\u8be2")) {
            return new NormalizedIntent("performance_issue", List.of("performance", "slow", "health"), "ops", 0.84);
        }
        if (containsAnyToken(tokens, "status", "health", "instance", "\u72b6\u6001", "\u6570\u636e\u5e93\u72b6\u6001", "\u72b6\u6001\u5206\u6790")) {
            return new NormalizedIntent("db_status", List.of("status", "health", "instance"), "ops", 0.9);
        }
        return new NormalizedIntent(firstText(tokens.get(0), "general_query"), tokens.stream().limit(6).toList(), "ops", 0.55);
    }

    private List<String> primaryIntentTokens(Map<String, Object> filters) {
        List<String> tokens = new ArrayList<>();
        if (filters == null || filters.isEmpty()) {
            return tokens;
        }
        for (String key : List.of("intent", "goal", "intentZh", "intentEn")) {
            Object value = filters.get(key);
            if (value instanceof List<?> list) {
                list.forEach(item -> addWords(tokens, item));
            } else {
                addWords(tokens, value);
            }
        }
        return tokens.stream().distinct().toList();
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
        values.addAll(bilingualIntentValues(filters));
        if (!values.isEmpty()) {
            merged.put("intent", values);
        }
        return merged;
    }

    private Map<String, Object> filtersWithSshAssetSignals(Map<String, Object> filters, List<SshHostConfig> hosts) {
        List<String> signals = sshAssetRetrievalSignals(hosts);
        if (signals.isEmpty()) {
            return filters;
        }
        Map<String, Object> merged = new LinkedHashMap<>(filters);
        appendRetrievalSignals(merged, "intent", signals);
        appendRetrievalSignals(merged, "retrievalSignals", signals);
        appendRetrievalSignals(merged, "queryTerms", signals);
        return merged;
    }

    private List<String> sshAssetRetrievalSignals(List<SshHostConfig> hosts) {
        Set<String> signals = new LinkedHashSet<>();
        for (SshHostConfig host : hosts == null ? List.<SshHostConfig>of() : hosts) {
            addWords(signals, host.getName());
            addWords(signals, host.getTitle());
            addWords(signals, host.getToolName());
            addWords(signals, host.getEnvironment());
            addDelimited(signals, host.getTags());
            addJsonLabels(signals, host.getRoutingLabelsJson());
            addJsonLabels(signals, host.getCapabilitiesJson());
        }
        return signals.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .limit(24)
            .toList();
    }

    private Map<String, Object> filtersWithSqlAssetSignals(Map<String, Object> filters, List<SqlDatasourceConfig> datasources) {
        List<String> signals = sqlAssetRetrievalSignals(datasources);
        if (signals.isEmpty()) {
            return filters;
        }
        Map<String, Object> merged = new LinkedHashMap<>(filters);
        appendRetrievalSignals(merged, "intent", signals);
        appendRetrievalSignals(merged, "retrievalSignals", signals);
        appendRetrievalSignals(merged, "queryTerms", signals);
        return merged;
    }

    private List<String> sqlAssetRetrievalSignals(List<SqlDatasourceConfig> datasources) {
        Set<String> signals = new LinkedHashSet<>();
        for (SqlDatasourceConfig datasource : datasources == null ? List.<SqlDatasourceConfig>of() : datasources) {
            addWords(signals, datasource.getName());
            addWords(signals, datasource.getTitle());
            addWords(signals, datasource.getToolName());
            addWords(signals, datasource.getEnvironment());
            addWords(signals, datasource.getDatabaseType());
            addWords(signals, datasource.getMetadataScopeValue());
            addJsonLabels(signals, datasource.getRoutingLabelsJson());
            addJsonLabels(signals, datasource.getCapabilitiesJson());
            addGovernanceSignals(signals, datasource.getGovernanceJson());
        }
        return signals.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .limit(24)
            .toList();
    }

    private Map<String, Object> filtersWithHttpAssetSignals(Map<String, Object> filters, List<HttpEndpointConfig> endpoints) {
        List<String> signals = httpAssetRetrievalSignals(endpoints);
        if (signals.isEmpty()) {
            return filters;
        }
        Map<String, Object> merged = new LinkedHashMap<>(filters);
        appendRetrievalSignals(merged, "intent", signals);
        appendRetrievalSignals(merged, "retrievalSignals", signals);
        appendRetrievalSignals(merged, "queryTerms", signals);
        return merged;
    }

    private List<String> httpAssetRetrievalSignals(List<HttpEndpointConfig> endpoints) {
        Set<String> signals = new LinkedHashSet<>();
        for (HttpEndpointConfig endpoint : endpoints == null ? List.<HttpEndpointConfig>of() : endpoints) {
            addWords(signals, endpoint.getName());
            addWords(signals, endpoint.getTitle());
            addWords(signals, endpoint.getToolName());
            addWords(signals, endpoint.getEnvironment());
            addWords(signals, endpoint.getCategory());
            addDelimited(signals, endpoint.getTags());
            addJsonLabels(signals, endpoint.getRoutingLabelsJson());
            addJsonLabels(signals, endpoint.getCapabilitiesJson());
            addGovernanceSignals(signals, endpoint.getGovernanceJson());
        }
        return signals.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .limit(24)
            .toList();
    }

    private Map<String, Object> filtersWithDatabaseQueryAssetSignals(Map<String, Object> filters,
                                                                     List<DatabaseQueryConfig> templates) {
        List<String> signals = databaseQueryAssetRetrievalSignals(templates);
        if (signals.isEmpty()) {
            return filters;
        }
        Map<String, Object> merged = new LinkedHashMap<>(filters);
        appendRetrievalSignals(merged, "intent", signals);
        appendRetrievalSignals(merged, "retrievalSignals", signals);
        appendRetrievalSignals(merged, "queryTerms", signals);
        return merged;
    }

    private List<String> databaseQueryAssetRetrievalSignals(List<DatabaseQueryConfig> templates) {
        Set<String> signals = new LinkedHashSet<>();
        for (DatabaseQueryConfig config : templates == null ? List.<DatabaseQueryConfig>of() : templates) {
            addWords(signals, config.getToolName());
            addWords(signals, config.getTitle());
            addWords(signals, config.getDescription());
            addWords(signals, config.getBusinessGroup());
            addWords(signals, config.getBusinessGroupName());
            addWords(signals, config.getBusinessGroupDescription());
            addWords(signals, config.getDatabaseType());
            addJsonLabels(signals, config.getRoutingLabelsJson());
            addJsonLabels(signals, config.getCapabilitiesJson());
            addGovernanceSignals(signals, config.getGovernanceJson());
            databaseQueryDatasource(config).ifPresent(datasource -> signals.addAll(sqlAssetRetrievalSignals(List.of(datasource))));
        }
        return signals.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .limit(32)
            .toList();
    }

    private void appendRetrievalSignals(Map<String, Object> filters, String key, List<String> signals) {
        if (filters == null || key == null || signals == null || signals.isEmpty()) {
            return;
        }
        List<Object> values = new ArrayList<>();
        Object existing = filters.get(key);
        if (existing instanceof List<?> list) {
            values.addAll(list);
        } else if (existing != null) {
            values.add(existing);
        }
        values.addAll(signals);
        filters.put(key, values.stream()
            .filter(value -> value != null && !String.valueOf(value).isBlank())
            .map(value -> String.valueOf(value).trim())
            .distinct()
            .toList());
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
            firstText(config.getDescription(), "") + " "
                + firstText(config.getBusinessGroupName(), "") + " "
                + firstText(config.getBusinessGroupDescription(), ""),
            firstText(config.getBusinessGroup(), "business_database_query"),
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
        return rankAndFallback(candidates, intent, comparator);
    }

    private <T> List<ScoredTemplate<T>> rankAndFallback(List<ScoredTemplate<T>> candidates,
                                                        NormalizedIntent intent,
                                                        java.util.function.Function<ScoredTemplate<T>, String> idExtractor,
                                                        Comparator<ScoredTemplate<T>> comparator) {
        return rankAndFallback(candidates, intent, comparator);
    }

    private <T> List<ScoredTemplate<T>> rankAndFallback(List<ScoredTemplate<T>> candidates,
                                                        NormalizedIntent intent,
                                                        Comparator<ScoredTemplate<T>> comparator) {
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

    private Comparator<ScoredTemplate<SqlTemplateConfig>> sqlScoredComparator(NormalizedIntent intent, Map<String, Object> filters) {
        Comparator<ScoredTemplate<SqlTemplateConfig>> base = scoredComparator(scored -> scored.template().getCode());
        return Comparator
            .<ScoredTemplate<SqlTemplateConfig>>comparingInt(scored -> -sqlTableContextPriority(scored.template(), filters))
            .thenComparing(Comparator
            .<ScoredTemplate<SqlTemplateConfig>>comparingInt(scored -> -sqlIntentFamilyPriority(scored.template(), intent))
            .thenComparing(base));
    }

    private int sqlTableContextPriority(SqlTemplateConfig template, Map<String, Object> filters) {
        if (!hasSqlTableContext(filters)) {
            return 0;
        }
        if (isSqlTableMetadataTemplate(template)) {
            return 6;
        }
        return parameterRequired(template.getParameterSchemaJson(), "tableName", "table_name") ? 3 : 0;
    }

    private boolean isSqlTableMetadataTemplate(SqlTemplateConfig template) {
        if (template == null) {
            return false;
        }
        String code = firstText(template.getCode(), "").toUpperCase(Locale.ROOT);
        if (code.endsWith("_TABLE_METADATA")) {
            return true;
        }
        String category = category(template);
        List<String> signals = intentSignals(template);
        return parameterRequired(template.getParameterSchemaJson(), "tableName", "table_name")
            && (containsIgnoreCase(category, "metadata")
            || containsAnyIgnoreCase(signals, "metadata", "schema", "column", "describe", "table"));
    }

    @SuppressWarnings("unchecked")
    private boolean hasSqlTableContext(Map<String, Object> filters) {
        if (firstValue(filters, "tableName", "table_name", "table") != null) {
            return true;
        }
        Object executionContext = firstValue(filters, "executionContext", "execution_context");
        if (executionContext instanceof Map<?, ?> map) {
            return firstValue((Map<String, Object>) map, "tableName", "table_name", "table") != null;
        }
        return false;
    }

    private int sqlIntentFamilyPriority(SqlTemplateConfig template, NormalizedIntent intent) {
        if (template == null || intent == null || intent.type() == null || "unknown".equals(intent.type())) {
            return 0;
        }
        String searchable = String.join(" ",
            firstText(template.getCode(), ""),
            firstText(template.getTitle(), ""),
            firstText(template.getDescription(), ""),
            firstText(template.getCategory(), ""),
            String.join(" ", intentSignals(template))
        ).toLowerCase(Locale.ROOT);
        return switch (intent.type()) {
            case "metadata_query" -> containsAnyText(searchable, "metadata", "schema", "column", "field", "describe")
                ? 3 : 0;
            case "storage_check" -> containsAnyText(searchable, "storage", "size", "space", "capacity") ? 3 : 0;
            case "db_status" -> containsAnyText(searchable, "status", "health", "instance") ? 3 : 0;
            case "lock_check" -> containsAnyText(searchable, "lock", "blocking", "deadlock", "wait") ? 3 : 0;
            case "connection_check" -> containsAnyText(searchable, "connection", "session", "processlist") ? 3 : 0;
            case "performance_issue" -> containsAnyText(searchable, "performance", "slow", "latency") ? 3 : 0;
            default -> 0;
        };
    }

    private boolean containsAnyText(String text, String... needles) {
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

    private Map<String, Object> sshTemplateRegistryDiagnostics(List<CommandTemplateConfig> templates,
                                                               Set<String> allowedByAsset,
                                                               boolean assetScoped) {
        if (!assetScoped) {
            return Map.of();
        }
        Set<String> registeredEnabled = new LinkedHashSet<>();
        for (CommandTemplateConfig template : templates == null ? List.<CommandTemplateConfig>of() : templates) {
            String code = normalize(template.getCode());
            if (code != null) {
                registeredEnabled.add(code);
            }
        }
        List<String> allowed = allowedByAsset == null ? List.of() : allowedByAsset.stream().toList();
        List<String> notEnabledOrMissing = allowed.stream()
            .filter(code -> !registeredEnabled.contains(code))
            .toList();
        return mapOf(
            "schemaVersion", "template_registry_diagnostics.v1",
            "assetScoped", true,
            "allowedTemplateCount", allowed.size(),
            "registeredEnabledTemplateCount", registeredEnabled.size(),
            "authorizedRegisteredCount", allowed.size() - notEnabledOrMissing.size(),
            "notEnabledOrMissingAllowedTemplates", notEnabledOrMissing,
            "diagnostic", notEnabledOrMissing.isEmpty()
                ? "asset allowlist is fully backed by enabled registered command templates"
                : "asset allowlist references templates that are missing from the enabled command template registry"
        );
    }

    private Map<String, Object> sqlTemplateRegistryDiagnostics(List<SqlTemplateConfig> templates,
                                                               List<SqlDatasourceConfig> datasources,
                                                               boolean assetScoped) {
        if (!assetScoped || datasources == null || datasources.isEmpty()) {
            return Map.of();
        }
        Set<String> registeredEnabled = new LinkedHashSet<>();
        for (SqlTemplateConfig template : templates == null ? List.<SqlTemplateConfig>of() : templates) {
            String code = normalize(template.getCode());
            if (code != null) {
                registeredEnabled.add(code);
            }
        }
        Set<String> allowedByAsset = new LinkedHashSet<>();
        for (SqlDatasourceConfig datasource : datasources) {
            allowedByAsset.addAll(allowedSqlTemplates(datasource));
        }
        if (allowedByAsset.isEmpty()) {
            return Map.of(
                "schemaVersion", "template_registry_diagnostics.v1",
                "assetScoped", true,
                "allowedTemplateCount", 0,
                "registeredEnabledTemplateCount", registeredEnabled.size(),
                "authorizedRegisteredCount", registeredEnabled.size(),
                "notEnabledOrMissingAllowedTemplates", List.of(),
                "diagnostic", "selected datasource does not define an allowlist; enabled registered SQL templates are filtered by datasource/type compatibility"
            );
        }
        List<String> allowed = allowedByAsset.stream().toList();
        List<String> notEnabledOrMissing = allowed.stream()
            .filter(code -> !registeredEnabled.contains(code))
            .toList();
        return mapOf(
            "schemaVersion", "template_registry_diagnostics.v1",
            "assetScoped", true,
            "allowedTemplateCount", allowed.size(),
            "registeredEnabledTemplateCount", registeredEnabled.size(),
            "authorizedRegisteredCount", allowed.size() - notEnabledOrMissing.size(),
            "notEnabledOrMissingAllowedTemplates", notEnabledOrMissing,
            "diagnostic", notEnabledOrMissing.isEmpty()
                ? "datasource allowlist is fully backed by enabled registered SQL templates"
                : "datasource allowlist references SQL templates that are missing from the enabled SQL template registry"
        );
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

    private List<DatabaseQueryConfig> scopedDatabaseQueryTemplates(List<DatabaseQueryConfig> templates, Map<String, Object> filters) {
        List<DatabaseQueryConfig> scoped = (templates == null ? List.<DatabaseQueryConfig>of() : templates).stream()
            .filter(template -> matchesDatabaseQueryTemplateAsset(template, filters))
            .toList();
        return scoped.isEmpty() ? List.of() : scoped;
    }

    private boolean matchesDatabaseQueryTemplateAsset(DatabaseQueryConfig template, Map<String, Object> filters) {
        String assetName = text(firstValue(filters, "assetName", "asset_name", "name", "template", "templateId", "template_id"));
        String env = text(firstValue(filters, "env", "environment"));
        List<String> tokens = contextTokens(filters);
        java.util.Optional<SqlDatasourceConfig> datasource = databaseQueryDatasource(template);
        if (assetName != null
            && !logicalNameMatches(assetName, template.getToolName())
            && !logicalNameMatches(assetName, template.getTitle())
            && !logicalNameMatches(assetName, template.getBusinessGroup())
            && !logicalNameMatches(assetName, template.getBusinessGroupName())
            && datasource.filter(value -> datasourceNameMatches(value, assetName)).isEmpty()) {
            return false;
        }
        if (env != null && datasource.filter(value -> equalsNormalized(env, value.getEnvironment())).isEmpty()) {
            return false;
        }
        String requestedType = requestedDatabaseType(filters);
        if (requestedType != null) {
            String templateType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(template.getDatabaseType());
            boolean templateMatches = "generic".equals(templateType) || templateType.equals(requestedType);
            boolean datasourceMatches = datasource
                .map(value -> equalsNormalized(requestedType, value.getDatabaseType()))
                .orElse(false);
            if (!templateMatches && !datasourceMatches) {
                return false;
            }
        }
        if (!tokens.isEmpty()) {
            Set<String> labels = databaseQueryTemplateLabels(template, datasource.orElse(null));
            if (!labels.containsAll(tokens)) {
                return false;
            }
        }
        return true;
    }

    private Set<String> databaseQueryTemplateLabels(DatabaseQueryConfig template, SqlDatasourceConfig datasource) {
        Set<String> labels = new LinkedHashSet<>();
        addLabel(labels, template.getToolName());
        addLabel(labels, template.getTitle());
        addLabel(labels, template.getBusinessGroup());
        addLabel(labels, template.getBusinessGroupName());
        addLabel(labels, template.getDatabaseType());
        addJsonLabels(labels, template.getRoutingLabelsJson());
        addJsonLabels(labels, template.getCapabilitiesJson());
        if (datasource != null) {
            labels.addAll(datasourceLabels(datasource));
        }
        return labels;
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
            "database", "databaseType", "dbType", "dialect", "databaseRole", "database_role", "businessGroup",
            "business_group", "group", "groupName", "group_name", "template", "templateId", "template_id", "labels") != null;
    }

    private List<String> contextTokens(Map<String, Object> filters) {
        List<String> tokens = new ArrayList<>();
        for (String key : List.of("cluster", "service", "target", "targetType", "target_type", "database", "databaseRole",
            "database_role", "businessGroup", "business_group", "group", "groupName", "group_name", "labels")) {
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
        for (String key : List.of("intent", "goal", "category", "businessGroup", "business_group", "group",
            "groupName", "group_name", "groupDescription", "group_description", "template", "templateId",
            "template_id", "service")) {
            Object value = filters.get(key);
            if (value instanceof List<?> list) {
                list.forEach(item -> addWords(tokens, item));
            } else {
                addWords(tokens, value);
            }
        }
        return tokens.stream().distinct().toList();
    }

    private List<String> bilingualIntentTokens(Map<String, Object> filters) {
        List<String> tokens = new ArrayList<>();
        bilingualIntentValues(filters).forEach(value -> addWords(tokens, value));
        return tokens.stream().distinct().toList();
    }

    private List<Object> bilingualIntentValues(Map<String, Object> filters) {
        List<Object> values = new ArrayList<>();
        for (String key : List.of(
            "bilingualIntent",
            "bilingualQuery",
            "bilingualSearch",
            "queryTerms",
            "searchTerms",
            "intentAliases",
            "keywords",
            "retrievalSignals",
            "intentZh",
            "intentEn"
        )) {
            Object value = filters.get(key);
            if (value instanceof List<?> list) {
                values.addAll(list);
            } else if (value != null && !String.valueOf(value).isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private Map<String, Object> bilingualRetrieval(Map<String, Object> filters, NormalizedIntent intent) {
        List<String> modelGenerated = bilingualIntentValues(filters).stream()
            .map(value -> value == null ? null : String.valueOf(value).trim())
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
        LinkedHashSet<String> generated = new LinkedHashSet<>();
        if (intent != null && !"unknown".equals(intent.type())) {
            generated.add(intent.type());
            generated.addAll(intent.tags());
        }
        generated.addAll(bilingualIntentTokens(filters));
        return mapOf(
            "required", true,
            "modelGenerated", modelGenerated,
            "generatedByEngine", generated.stream().toList(),
            "fields", List.of("bilingualIntent", "bilingualQuery", "intentAliases", "keywords", "retrievalSignals", "intentZh", "intentEn"),
            "languages", List.of("zh", "en")
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
            "bilingualIntent",
            "bilingualQuery",
            "bilingualSearch",
            "queryTerms",
            "searchTerms",
            "intentZh",
            "intentEn",
            "goal",
            "category",
            "database",
            "databaseType",
            "dbType",
            "dialect",
            "databaseRole",
            "database_role",
            "businessGroup",
            "business_group",
            "group",
            "groupName",
            "group_name",
            "groupDescription",
            "group_description",
            "template",
            "templateId",
            "template_id",
            "view",
            "language",
            "queryLanguage",
            "locale"
        )) {
            Object value = arguments.get(key);
            if (value != null) {
                filters.putIfAbsent(key, value);
            }
        }
        return filters;
    }

    private Set<String> excludedTemplateIds(Map<String, Object> filters) {
        Object value = firstValue(filters, "excludeTemplateIds", "excludedTemplateIds");
        if (!(value instanceof Iterable<?> iterable)) {
            return Set.of();
        }
        Set<String> excluded = new LinkedHashSet<>();
        for (Object item : iterable) {
            String normalized = normalize(item == null ? null : String.valueOf(item));
            if (normalized != null) {
                excluded.add(normalized);
            }
        }
        return Set.copyOf(excluded);
    }

    private void rejectConcreteTargetFields(Map<String, Object> filters) {
        for (String field : CONCRETE_TARGET_FIELDS) {
            Object value = filters.get(field);
            if (value != null && !String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("Concrete target or raw execution field is not allowed in template_query: " + field);
            }
        }
    }

    private void rejectUnresolvedBindingPlaceholders(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                rejectUnresolvedBindingPlaceholders(entry.getValue(), path + "." + String.valueOf(entry.getKey()));
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                rejectUnresolvedBindingPlaceholders(list.get(index), path + "[" + index + "]");
            }
            return;
        }
        if (value instanceof String text && BINDING_PLACEHOLDER_PATTERN.matcher(text).find()) {
            throw new IllegalArgumentException("Unresolved Agent Runtime binding placeholder is not allowed in template_query at " + path);
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

    private List<String> requiredParameters(Map<String, Object> parameterSchema) {
        Object required = parameterSchema == null ? null : parameterSchema.get("required");
        if (!(required instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : iterable) {
            if (item != null && !String.valueOf(item).isBlank()) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parameterContract(String templateId,
                                                 Map<String, Object> parameterSchema,
                                                 String argumentContainer,
                                                 String executionTool) {
        Map<String, Object> properties = parameterSchema == null || !(parameterSchema.get("properties") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        List<String> required = requiredParameters(parameterSchema);
        return mapOf(
            "schemaVersion", "template_parameter_contract.v1",
            "templateId", templateId,
            "executionTool", executionTool,
            "argumentContainer", argumentContainer,
            "required", required,
            "optional", properties.keySet().stream()
                .filter(key -> !required.contains(key))
                .toList(),
            "mustPassUnderParameters", true,
            "topLevelTemplateParametersAllowed", false,
            "missingRequiredBehavior", "Do not call the execution tool until every required field is present under parameters."
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> directParameterContract(String toolName, Map<String, Object> parameterSchema) {
        Map<String, Object> properties = parameterSchema == null || !(parameterSchema.get("properties") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        List<String> required = requiredParameters(parameterSchema);
        return mapOf(
            "schemaVersion", "template_parameter_contract.v1",
            "templateId", toolName,
            "executionTool", toolName,
            "argumentContainer", toolName + ".arguments",
            "required", required,
            "optional", properties.keySet().stream()
                .filter(key -> !required.contains(key))
                .toList(),
            "mustPassUnderParameters", false,
            "topLevelTemplateParametersAllowed", true,
            "missingRequiredBehavior", "Do not call the direct MCP tool until every required argument is present."
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invocationExample(String templateId,
                                                 Map<String, Object> parameterSchema,
                                                 String toolName,
                                                 String assetNamePlaceholder,
                                                 String envPlaceholder) {
        Map<String, Object> properties = parameterSchema == null || !(parameterSchema.get("properties") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (String required : requiredParameters(parameterSchema)) {
            parameters.put(required, exampleValue(required, properties.get(required)));
        }
        return mapOf(
            "tool", toolName,
            "templateId", templateId,
            "parameters", parameters,
            "executionContext", mapOf("assetName", assetNamePlaceholder, "env", envPlaceholder)
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> directInvocationExample(String toolName, Map<String, Object> parameterSchema) {
        Map<String, Object> properties = parameterSchema == null || !(parameterSchema.get("properties") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (String required : requiredParameters(parameterSchema)) {
            arguments.put(required, exampleValue(required, properties.get(required)));
        }
        return mapOf(
            "tool", toolName,
            "arguments", arguments
        );
    }

    private String exampleValue(String name, Object schema) {
        String normalized = name == null ? "" : name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        if (normalized.contains("tablename")) {
            return "<tableName from user request>";
        }
        if (normalized.contains("schemaname") || normalized.contains("databasename") || "schema".equals(normalized)
            || "database".equals(normalized)) {
            return "<schemaName resolved by table-location query>";
        }
        if (schema instanceof Map<?, ?> map && map.get("example") != null) {
            return String.valueOf(map.get("example"));
        }
        return "<" + name + ">";
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
        addWords(signals, config.getBusinessGroup());
        addWords(signals, config.getBusinessGroupName());
        addWords(signals, config.getBusinessGroupDescription());
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

    private Map<String, Object> readObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
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

    private boolean sqlTemplateCompatibleWithRequestedType(SqlTemplateConfig template,
                                                           Map<String, Object> filters,
                                                           List<SqlDatasourceConfig> datasources,
                                                           boolean assetScoped) {
        String requestedType = requestedDatabaseType(filters);
        String templateType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(template.getDatabaseType());
        if (requestedType != null && !"generic".equals(templateType) && !templateType.equals(requestedType)) {
            return false;
        }
        if (!assetScoped || datasources == null || datasources.isEmpty()) {
            return true;
        }
        return datasources.stream().anyMatch(datasource -> {
            String datasourceType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType());
            return "generic".equals(templateType) || templateType.equals(datasourceType);
        });
    }

    private boolean sqlTemplateAuthorizedByDatasource(SqlTemplateConfig template,
                                                      List<SqlDatasourceConfig> datasources,
                                                      boolean assetScoped) {
        if (!assetScoped) {
            return true;
        }
        if (datasources == null || datasources.isEmpty()) {
            return false;
        }
        String templateCode = normalize(template.getCode());
        String datasourceId = text(template.getDatasourceId());
        return datasources.stream().anyMatch(datasource -> {
            Set<String> allowedTemplates = allowedSqlTemplates(datasource);
            if (!allowedTemplates.isEmpty() && templateCode != null) {
                return allowedTemplates.contains(templateCode);
            }
            return datasourceId == null || datasourceId.equals(datasource.getId());
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

    private boolean isRetiredSqlMetadataTemplate(SqlTemplateConfig template) {
        String code = template == null ? null : normalize(template.getCode());
        return code != null && RETIRED_SQL_METADATA_TEMPLATE_CODES.contains(code.toUpperCase(Locale.ROOT));
    }

    private boolean isRetiredSqlMetadataTemplateId(String templateId) {
        String code = normalize(templateId);
        return code != null && RETIRED_SQL_METADATA_TEMPLATE_CODES.contains(code.toUpperCase(Locale.ROOT));
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
        addBuiltinIntentSynonyms(words, text, "status", List.of("\u72b6\u6001", "\u6570\u636e\u5e93\u72b6\u6001", "\u72b6\u6001\u5206\u6790", "status", "health", "instance"));
        addBuiltinIntentSynonyms(words, text, "performance", List.of("\u6027\u80fd", "\u5361", "\u5361\u987f", "\u6162", "\u6027\u80fd\u5206\u6790", "\u6162\u67e5\u8be2", "performance", "slow", "latency", "cpu"));
        addBuiltinIntentSynonyms(words, text, "lock", List.of("\u9501", "\u963b\u585e", "\u7b49\u5f85", "\u6b7b\u9501", "lock", "blocking", "deadlock", "wait"));
        addBuiltinIntentSynonyms(words, text, "storage", List.of("\u7a7a\u95f4", "\u5bb9\u91cf", "\u5927\u5c0f", "\u5b58\u50a8", "storage", "size", "space", "capacity"));
        addBuiltinIntentSynonyms(words, text, "metadata", List.of("\u5143\u6570\u636e", "\u8868\u7ed3\u6784", "\u5b57\u6bb5", "\u5217", "metadata", "schema", "column", "field"));
        addBuiltinIntentSynonyms(words, text, "connection", List.of("\u8fde\u63a5", "\u4f1a\u8bdd", "\u8fde\u63a5\u6570", "session", "connection", "connections", "processlist"));
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

    private record DatabaseQueryAssetTemplates(SqlDatasourceConfig datasource, List<DatabaseQueryConfig> templates) {
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
