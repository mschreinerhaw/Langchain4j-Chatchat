package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SqlMetadataSearchService {

    public static final String RESULT_SCHEMA_VERSION = "sql_metadata_search_result.v1";
    private static final int DEFAULT_DETAIL_LIMIT = 5;
    private static final int DEFAULT_CATALOG_LIMIT = 200;
    private static final Charset GBK = Charset.forName("GBK");
    private static final ObjectMapper TRACE_OBJECT_MAPPER = new ObjectMapper();

    private final LuceneMcpSearchService luceneSearchService;
    private final SqlDatasourceConfigService datasourceConfigService;
    private final MetadataIndexService metadataIndexService;

    public Map<String, Object> search(Map<String, Object> arguments) {
        long startedAt = System.nanoTime();
        Map<String, Object> input = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
        Map<String, Object> executionContext = objectMap(input.get("executionContext"));
        Map<String, Object> defaultDataAsset = objectMap(input.get("defaultDataAsset"));
        Map<String, Object> mcpContext = objectMap(input.get("mcpContext"));
        String searchRequestId = firstText(
            text(input.get("searchRequestId")),
            text(input.get("requestId")),
            text(mcpContext.get("searchRequestId")),
            text(mcpContext.get("traceId")),
            shortUuid()
        );
        String rawTableName = repairMojibake(firstText(
            text(input.get("tableName")),
            text(input.get("table_name")),
            text(executionContext.get("tableName")),
            text(executionContext.get("table_name"))
        ));
        String rawQuery = repairMojibake(firstText(
            text(input.get("query")),
            text(input.get("q")),
            text(input.get("searchTerm")),
            text(input.get("search_term")),
            text(input.get("keyword")),
            text(input.get("keywords")),
            text(input.get("searchText")),
            text(input.get("search_text")),
            looksLikeTableIdentifier(rawTableName) ? null : rawTableName
        ));
        String assetName = firstText(
            repairMojibake(text(input.get("assetName"))),
            repairMojibake(text(input.get("asset_name"))),
            repairMojibake(text(executionContext.get("assetName"))),
            repairMojibake(text(executionContext.get("asset_name"))),
            repairMojibake(text(executionContext.get("name"))),
            repairMojibake(text(defaultDataAsset.get("assetName"))),
            repairMojibake(text(defaultDataAsset.get("asset_name"))),
            repairMojibake(text(defaultDataAsset.get("name")))
        );
        String requestedTableName = firstText(
            looksLikeTableIdentifier(rawTableName) ? rawTableName : null,
            looksLikeTableIdentifier(rawQuery) ? rawQuery : null
        );
        String rawRequestedDatabase = firstText(
            repairMojibake(text(input.get("database"))),
            repairMojibake(text(input.get("schema"))),
            repairMojibake(text(input.get("schemaName"))),
            repairMojibake(text(input.get("databaseName"))),
            repairMojibake(text(executionContext.get("database"))),
            repairMojibake(text(executionContext.get("schema"))),
            repairMojibake(text(executionContext.get("schemaName"))),
            repairMojibake(text(executionContext.get("databaseName")))
        );
        String requestedDatabase = looksLikeTableIdentifier(rawRequestedDatabase) ? rawRequestedDatabase : null;
        SqlTableNameParser.QualifiedTable qualifiedTable = SqlTableNameParser.parse(requestedTableName, requestedDatabase);
        String tableName = qualifiedTable.table();
        String database = qualifiedTable.database();
        String schema = qualifiedTable.schema();
        String lookupNamespace = firstText(schema, database);
        String assetId = firstText(
            text(input.get("assetId")),
            text(input.get("asset_id")),
            text(executionContext.get("assetId")),
            text(executionContext.get("asset_id")),
            text(defaultDataAsset.get("assetId")),
            text(defaultDataAsset.get("asset_id")),
            text(defaultDataAsset.get("id"))
        );
        String env = firstText(
            text(input.get("env")),
            text(input.get("environment")),
            text(executionContext.get("env")),
            text(executionContext.get("environment")),
            text(defaultDataAsset.get("env")),
            text(defaultDataAsset.get("environment"))
        );
        String databaseType = firstText(
            text(input.get("databaseType")),
            text(input.get("dbType")),
            text(input.get("dialect")),
            text(executionContext.get("databaseType")),
            text(executionContext.get("dbType")),
            text(executionContext.get("dialect")),
            text(defaultDataAsset.get("databaseType")),
            text(defaultDataAsset.get("dbType")),
            text(defaultDataAsset.get("dialect"))
        );
        String query = firstText(rawQuery, requestedTableName, tableName, assetName);
        String retrievalQuery = expandMetadataSearchQuery(query);
        Integer legacyLimit = optionalPositiveInt(input.get("limit"));
        int detailLimit = positiveIntOrDefault(input.get("detailLimit"), DEFAULT_DETAIL_LIMIT);
        int catalogLimit = positiveIntOrDefault(input.get("catalogLimit"), DEFAULT_CATALOG_LIMIT);
        boolean includeColumns = includeColumnsByDefault(input.get("includeColumns"));
        String exactTableFilter = tableName;
        String tableLabelFilter = looksLikeTableIdentifier(requestedTableName) ? tableName : null;
        Map<String, Long> stageTimings = new LinkedHashMap<>();

        log.info("sql_metadata_search started searchRequestId={} query={} retrievalQuery={} assetId={} assetName={} env={} databaseType={} database={} table={} includeColumns={} detailLimit={} catalogLimit={} legacyLimit={} defaultAsset={}",
            searchRequestId, query, retrievalQuery, assetId, assetName, env, databaseType, lookupNamespace, tableName, includeColumns, detailLimit, catalogLimit, legacyLimit,
            compactMap(defaultDataAsset));

        List<Candidate> candidates = new ArrayList<>();
        long stageStartedAt = System.nanoTime();
        candidates.addAll(luceneCandidates(searchRequestId, retrievalQuery, tableLabelFilter, lookupNamespace, assetId, assetName, env, databaseType, null));
        stageTimings.put("luceneMs", elapsedMs(stageStartedAt));
        stageStartedAt = System.nanoTime();
        candidates.addAll(cacheCandidates(retrievalQuery, lookupNamespace, assetId, assetName, env, databaseType));
        stageTimings.put("cacheLookupMs", elapsedMs(stageStartedAt));

        stageStartedAt = System.nanoTime();
        List<Candidate> uniqueCandidates = candidates.stream()
            .filter(candidate -> matchesDatabase(candidate.table(), lookupNamespace))
            .collect(java.util.stream.Collectors.toMap(
                candidate -> candidate.datasource().getId() + "::" + normalize(candidate.table().database()) + "::" + normalize(candidate.table().table()),
                candidate -> candidate,
                (left, right) -> left.score() >= right.score() ? left : right,
                LinkedHashMap::new
            ))
            .values()
            .stream()
            .sorted(Comparator.comparingDouble(Candidate::score).reversed())
            .toList();
        stageTimings.put("dedupeAndFilterMs", elapsedMs(stageStartedAt));
        List<Candidate> tableFilteredCandidates = exactTableMatches(uniqueCandidates, exactTableFilter);
        boolean tableNameFilterApplied = !tableFilteredCandidates.isEmpty();
        List<Candidate> selectedCandidates = tableNameFilterApplied ? tableFilteredCandidates : uniqueCandidates;

        stageStartedAt = System.nanoTime();
        List<Map<String, Object>> tableCatalog = applyLimit(selectedCandidates, catalogLimit).stream()
            .map(this::toCatalogItem)
            .toList();
        List<Map<String, Object>> topTables = applyLimit(selectedCandidates, detailLimit).stream()
            .map(candidate -> toResult(candidate, includeColumns, database))
            .toList();
        stageTimings.put("resultAssemblyMs", elapsedMs(stageStartedAt));
        long durationMs = elapsedMs(startedAt);

        log.info("sql_metadata_search completed searchRequestId={} query={} assetId={} assetName={} count={} candidates={} durationMs={} stages={}",
            searchRequestId, query, assetId, assetName, topTables.size(), uniqueCandidates.size(), durationMs, stageTimings);
        log.info("sql_metadata_search retrieval snapshot searchRequestId={} snapshot={}",
            searchRequestId,
            safeJson(compactMap(mapOf(
                "requestId", searchRequestId,
                "question", firstText(rawQuery, requestedTableName, query),
                "query", query,
                "retrievalQuery", retrievalQuery,
                "filters", compactMap(mapOf(
                    "assetName", assetName,
                    "assetId", assetId,
                    "env", env,
                    "databaseType", databaseType,
                    "database", database,
                    "schema", schema,
                    "tableName", tableName
                )),
                "totalMatched", selectedCandidates.size(),
                "catalogReturnedCount", tableCatalog.size(),
                "detailReturnedCount", topTables.size(),
                "selectedTables", tableCatalog.stream().limit(20).toList(),
                "stageTimingsMs", stageTimings,
                "durationMs", durationMs
            ))));

        return mapOf(
            "schemaVersion", RESULT_SCHEMA_VERSION,
            "success", true,
            "searchRequestId", searchRequestId,
            "query", query,
            "totalMatched", selectedCandidates.size(),
            "catalogReturnedCount", tableCatalog.size(),
            "returnedDetailCount", topTables.size(),
            "detailReturnedCount", topTables.size(),
            "catalogLimit", catalogLimit,
            "detailLimit", detailLimit,
            "hasMore", selectedCandidates.size() > detailLimit,
            "truncated", selectedCandidates.size() > detailLimit,
            "catalogTruncated", selectedCandidates.size() > catalogLimit,
            "detailTruncated", selectedCandidates.size() > detailLimit,
            "limit", "deprecated_use_catalogLimit_and_detailLimit",
            "filters", compactMap(mapOf(
                "assetName", assetName,
                "assetId", assetId,
                "env", env,
                "databaseType", databaseType,
                "database", database,
                "schema", schema,
                "lookupNamespace", lookupNamespace,
                "tableName", tableName,
                "requestedTableName", requestedTableName,
                "retrievalQuery", retrievalQuery
            )),
            "count", topTables.size(),
            "tableCatalog", tableCatalog,
            "topTables", topTables,
            "results", topTables,
            "usage", mapOf(
                "catalogRule", "tableCatalog is the lightweight list of matched tables. Do not treat topTables/results as the full matched set.",
                "detailRule", "topTables/results contain detailed metadata only for the highest ranked tables.",
                "answerRule", "Use totalMatched, catalogReturnedCount, catalogTruncated, and returnedDetailCount when describing completeness. Do not say tool response limits hid results unless catalogTruncated is true.",
                "evidenceRule", "Always cite exact database/schema/tableName identifiers from tableCatalog or topTables[].location. When topTables[].columns is present, list those exact physical column names with the corresponding table. Keep business descriptions separate and never invent or translate identifiers.",
                "nextStep", "Use topTables[].sqlExecutionBinding or a tableCatalog row when calling sql_query_execute. Do not invent schemaName/databaseName.",
                "templateStep", "Call database_ops_template_search for the same assetName/env, then use the returned templateId and parameterSchema."
            ),
            "diagnostics", mapOf(
                "source", luceneSearchService != null && luceneSearchService.enabled() ? "lucene_metadata_table_index" : "metadata_cache",
                "candidateCountBeforeTableFilter", uniqueCandidates.size(),
                "candidateCountRaw", candidates.size(),
                "retrievalQueryExpanded", retrievalQuery != null && !retrievalQuery.equals(query),
                "selectedCandidateCountBeforeLimit", selectedCandidates.size(),
                "possiblyTruncated", selectedCandidates.size() > detailLimit,
                "legacyLimitIgnoredForDetail", legacyLimit,
                "tableNameFilterApplied", tableNameFilterApplied,
                "tableNameFilterMode", tableNameFilterApplied ? "exact_table_match" : "no_exact_match_returning_semantic_candidates",
                "assetFilterApplied", assetId != null || assetName != null || env != null || databaseType != null,
                "defaultDataAssetApplied", !defaultDataAsset.isEmpty() && (assetId != null || assetName != null),
                "inputEncodingRepaired", inputEncodingRepaired(input),
                "stageTimingsMs", stageTimings,
                "durationMs", durationMs
            )
        );
    }

    private List<Candidate> luceneCandidates(String searchRequestId,
                                             String query,
                                             String tableName,
                                             String database,
                                             String assetId,
                                             String assetName,
                                             String env,
                                             String databaseType,
                                             Integer limit) {
        if (luceneSearchService == null || !luceneSearchService.enabled()) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        addLabel(labels, "metadata_table");
        if (database != null) {
            addLabel(labels, "database:" + database);
            addLabel(labels, "schema:" + database);
        }
        if (tableName != null) {
            addLabel(labels, "table:" + tableName);
        }
        List<LuceneMcpSearchService.SearchHit> hits = searchAssetHits(searchRequestId, query, env, databaseType, labels, luceneSearchLimit(limit));
        if (hits.isEmpty() && !labels.isEmpty()) {
            List<LuceneMcpSearchService.SearchHit> fallbackHits = searchAssetHits(searchRequestId, query, env, databaseType, List.of(), luceneSearchLimit(limit));
            if (!fallbackHits.isEmpty()) {
                log.info("sql_metadata_search label fallback recovered candidates searchRequestId={} query={} labels={} fallbackHits={}",
                    searchRequestId, query, labels, fallbackHits.size());
            }
            hits = fallbackHits;
        }
        List<Candidate> values = new ArrayList<>();
        Map<String, SqlDatasourceConfig> datasources = datasourceById(assetId, assetName, env, databaseType);
        for (LuceneMcpSearchService.SearchHit hit : hits) {
            if (hit == null || !"metadata_table".equals(hit.source())) {
                continue;
            }
            SqlDatasourceConfig datasource = datasources.get(hit.id());
            if (datasource == null) {
                continue;
            }
            TableLocation table = indexedTable(datasource, hit.database(), hit.table());
            if (table == null) {
                table = new TableLocation(datasource.getId(), hit.database(), hit.database(), hit.table(), "TABLE", null,
                    hit.tableComment(), hit.databaseComment(), hit.score());
            }
            values.add(new Candidate(datasource, table, hit.score(), "lucene", hit));
        }
        return values;
    }

    private String expandMetadataSearchQuery(String query) {
        String baseQuery = stripBooleanOperators(query);
        String normalized = normalize(baseQuery);
        if (normalized == null) {
            return query;
        }
        Set<String> terms = new LinkedHashSet<>();
        terms.add(baseQuery);
        addExpansionTerms(terms, normalized, "融资融券", "marg", "margin", "crdt", "credit");
        addExpansionTerms(terms, normalized, "两融", "marg", "margin", "融资融券");
        addExpansionTerms(terms, normalized, "融资", "marg", "fin", "financing");
        addExpansionTerms(terms, normalized, "融券", "marg", "slo", "securities lending");
        addExpansionTerms(terms, normalized, "负债", "liab", "liability", "debt", "ast liab");
        addExpansionTerms(terms, normalized, "资产负债", "ast liab", "asset liability", "liab");
        addExpansionTerms(terms, normalized, "维持担保比例", "marg", "mars", "scr", "guarantee ratio", "maintenance margin");
        addExpansionTerms(terms, normalized, "担保比例", "marg", "mars", "guarantee ratio", "maintenance margin");
        addExpansionTerms(terms, normalized, "担保品", "marg", "mars", "scr", "collateral", "pledge");
        addExpansionTerms(terms, normalized, "担保", "marg", "mars", "guarantee", "collateral", "pledge");
        addExpansionTerms(terms, normalized, "折算率", "marg", "conv", "rate", "collateral");
        addExpansionTerms(terms, normalized, "信用账户", "crdt", "cred", "cust", "account");
        addExpansionTerms(terms, normalized, "合约", "cont", "contract", "agmt");
        addExpansionTerms(terms, normalized, "客户", "cust", "customer");
        addExpansionTerms(terms, normalized, "证券", "scr", "secu", "security");
        if (terms.size() <= 1) {
            return query;
        }
        return terms.stream()
            .filter(item -> item != null && !item.isBlank())
            .collect(java.util.stream.Collectors.joining(" "));
    }

    private String stripBooleanOperators(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        return query.replaceAll("(?i)\\b(OR|AND|NOT)\\b", " ")
            .replaceAll("[()]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private void addExpansionTerms(Set<String> terms, String normalizedQuery, String trigger, String... expansions) {
        if (normalizedQuery == null || trigger == null || !normalizedQuery.contains(normalize(trigger))) {
            return;
        }
        for (String expansion : expansions) {
            if (expansion != null && !expansion.isBlank()) {
                terms.add(expansion);
            }
        }
    }

    private List<LuceneMcpSearchService.SearchHit> searchAssetHits(String searchRequestId,
                                                                   String query,
                                                                   String env,
                                                                   String databaseType,
                                                                   List<String> labels,
                                                                   int limit) {
        return luceneSearchService.searchAssets(new LuceneMcpSearchService.AssetSearchRequest(
            "sql_datasource",
            query,
            env,
            databaseType,
            labels,
            limit,
            searchRequestId
        ));
    }

    private List<Candidate> cacheCandidates(String query,
                                            String database,
                                            String assetId,
                                            String assetName,
                                            String env,
                                            String databaseType) {
        String normalizedQuery = normalize(query);
        List<Candidate> values = new ArrayList<>();
        for (SqlDatasourceConfig datasource : matchingDatasources(assetId, assetName, env, databaseType)) {
            for (TableLocation table : metadataIndexService.allTables(datasource)) {
                if (!matchesDatabase(table, database)) {
                    continue;
                }
                double score = cacheScore(normalizedQuery, table);
                if (score <= 0.0 && normalizedQuery != null) {
                    continue;
                }
                values.add(new Candidate(datasource, table, score, "metadata_cache", null));
            }
        }
        return values.stream()
            .sorted(Comparator.comparingDouble(Candidate::score).reversed())
            .toList();
    }

    private Map<String, Object> toResult(Candidate candidate, boolean includeColumns, String requestedDatabase) {
        SqlDatasourceConfig datasource = candidate.datasource();
        TableLocation table = candidate.table();
        String assetName = firstText(datasource.getName(), datasource.getTitle(), datasource.getToolName());
        Map<String, Object> value = mapOf(
            "asset", compactMap(mapOf(
                "id", datasource.getId(),
                "name", datasource.getName(),
                "displayName", datasource.getTitle(),
                "toolName", datasource.getToolName(),
                "environment", datasource.getEnvironment(),
                "databaseType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType())
            )),
            "location", compactMap(mapOf(
                "database", table.database(),
                "schema", table.schema(),
                "table", table.table(),
                "tableName", table.table(),
                "tableType", table.tableType(),
                "tableRows", table.tableRows(),
                "tableComment", table.tableComment(),
                "databaseComment", table.databaseComment(),
                "fullPath", joinPath(assetName, table.database(), table.table())
            )),
            "score", round(candidate.score()),
            "source", candidate.source(),
            "evidence", candidate.hit() == null ? List.of("metadata_cache") : candidate.hit().reasons(),
            "routingContext", compactMap(mapOf(
                "assetName", datasource.getName(),
                "env", datasource.getEnvironment(),
                "databaseType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType())
            )),
            "sqlExecutionBinding", compactMap(mapOf(
                "tool", "sql_query_execute",
                "executionContext", compactMap(mapOf(
                    "assetName", datasource.getName(),
                    "env", datasource.getEnvironment(),
                    "databaseType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType())
                )),
                "parameters", compactMap(mapOf(
                    "tableName", table.table(),
                    "schemaName", table.schema(),
                    "databaseName", firstText(requestedDatabase, table.database())
                ))
            ))
        );
        List<MetadataColumn> columns = metadataIndexService.columns(datasource, table);
        value.put("columnCount", columns.size());
        if (includeColumns) {
            value.put("columns", columns.stream()
                .map(MetadataColumn::toDiagnostic)
                .toList());
        }
        return value;
    }

    private Map<String, Object> toCatalogItem(Candidate candidate) {
        SqlDatasourceConfig datasource = candidate.datasource();
        TableLocation table = candidate.table();
        return compactMap(mapOf(
            "assetId", datasource.getId(),
            "assetName", datasource.getName(),
            "database", table.database(),
            "schema", table.schema(),
            "table", table.table(),
            "tableName", table.table(),
            "tableType", table.tableType(),
            "tableRows", table.tableRows(),
            "tableComment", table.tableComment(),
            "databaseComment", table.databaseComment(),
            "score", round(candidate.score()),
            "source", candidate.source(),
            "matchReason", matchReason(candidate)
        ));
    }

    private String matchReason(Candidate candidate) {
        if (candidate == null) {
            return null;
        }
        if (candidate.hit() != null && candidate.hit().reasons() != null && !candidate.hit().reasons().isEmpty()) {
            return String.join(", ", candidate.hit().reasons());
        }
        TableLocation table = candidate.table();
        if (table != null && table.tableComment() != null && !table.tableComment().isBlank()) {
            return "metadata_cache: table comment matched " + table.tableComment();
        }
        return candidate.source();
    }

    private TableLocation indexedTable(SqlDatasourceConfig datasource, String database, String tableName) {
        if (datasource == null || tableName == null || tableName.isBlank()) {
            return null;
        }
        for (TableLocation table : metadataIndexService.findTables(datasource, tableName)) {
            if (matchesDatabase(table, database) && matchesTable(table, tableName)) {
                return table;
            }
        }
        return null;
    }

    private Map<String, SqlDatasourceConfig> datasourceById(String assetId, String assetName, String env, String databaseType) {
        Map<String, SqlDatasourceConfig> values = new LinkedHashMap<>();
        for (SqlDatasourceConfig datasource : matchingDatasources(assetId, assetName, env, databaseType)) {
            if (datasource.getId() != null) {
                values.put(datasource.getId(), datasource);
            }
        }
        return values;
    }

    private List<SqlDatasourceConfig> matchingDatasources(String assetId, String assetName, String env, String databaseType) {
        return datasourceConfigService.listEnabled().stream()
            .filter(datasource -> matchesDatasource(datasource, assetId, assetName, env, databaseType))
            .toList();
    }

    private boolean matchesDatasource(SqlDatasourceConfig datasource, String assetId, String assetName, String env, String databaseType) {
        if (datasource == null) {
            return false;
        }
        if (assetId != null && !equalsNormalized(assetId, datasource.getId())) {
            return false;
        }
        if (env != null && !equalsNormalized(env, datasource.getEnvironment())) {
            return false;
        }
        if (databaseType != null && !equalsNormalized(
            SqlDatasourceConfigService.normalizeDatabaseTypeToken(databaseType),
            SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType()))) {
            return false;
        }
        if (assetName == null) {
            return true;
        }
        String normalized = normalize(assetName);
        return equalsNormalized(normalized, datasource.getName())
            || equalsNormalized(normalized, datasource.getTitle())
            || equalsNormalized(normalized, datasource.getToolName());
    }

    private boolean matchesDatabase(TableLocation table, String database) {
        return database == null
            || table == null
            || equalsNormalized(database, table.database())
            || equalsNormalized(database, table.schema());
    }

    private boolean matchesTable(TableLocation table, String tableName) {
        return tableName == null
            || table == null
            || equalsNormalized(tableName, table.table());
    }

    private List<Candidate> exactTableMatches(List<Candidate> candidates, String tableName) {
        if (candidates == null || candidates.isEmpty() || tableName == null || tableName.isBlank()) {
            return List.of();
        }
        return candidates.stream()
            .filter(candidate -> matchesTable(candidate.table(), tableName))
            .toList();
    }

    private double cacheScore(String query, TableLocation table) {
        if (table == null) {
            return 0.0;
        }
        if (query == null) {
            return table.score() > 0 ? table.score() : 0.5;
        }
        String normalizedTable = normalize(table.table());
        String normalizedDatabase = normalize(table.database());
        String normalizedTableComment = normalize(table.tableComment());
        String normalizedDatabaseComment = normalize(table.databaseComment());
        if (query.equals(normalizedTable)) {
            return 1.0;
        }
        if (normalizedTable != null && normalizedTable.contains(query)) {
            return 0.85;
        }
        if (query.contains(String.valueOf(normalizedTable))) {
            return 0.8;
        }
        if (normalizedDatabase != null && query.contains(normalizedDatabase)) {
            return 0.45;
        }
        if (normalizedTableComment != null && normalizedTableComment.contains(query)) {
            return 0.75;
        }
        if (normalizedDatabaseComment != null && normalizedDatabaseComment.contains(query)) {
            return 0.55;
        }
        if (queryContainsAnyToken(query, normalizedTableComment)) {
            return 0.5;
        }
        if (queryContainsAnyToken(query, normalizedDatabaseComment)) {
            return 0.35;
        }
        return 0.0;
    }

    private boolean queryContainsAnyToken(String query, String text) {
        if (query == null || text == null || text.isBlank()) {
            return false;
        }
        for (String token : text.split("[\\s,，。；;、/\\\\|()（）\\[\\]{}<>《》:：]+")) {
            if (token.length() >= 2 && query.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeTableIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String text = value.trim();
        if (text.length() > 160 || text.contains(" ")) {
            return false;
        }
        return text.matches("[`\"\\[\\]A-Za-z0-9_.$]+");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private Integer optionalPositiveInt(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            return parsed > 0 ? parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private int positiveIntOrDefault(Object value, int fallback) {
        Integer parsed = optionalPositiveInt(value);
        return parsed == null ? fallback : parsed;
    }

    private int luceneSearchLimit(Integer limit) {
        if (limit != null) {
            long expanded = Math.max((long) limit * 3L, limit);
            return (int) Math.min(Integer.MAX_VALUE, expanded);
        }
        return 10_000;
    }

    private List<Candidate> applyLimit(List<Candidate> candidates, Integer limit) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (limit == null) {
            return candidates;
        }
        return candidates.stream().limit(limit).toList();
    }

    private boolean booleanValue(Object value) {
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean includeColumnsByDefault(Object configured) {
        if (configured != null) {
            return booleanValue(configured);
        }
        return true;
    }

    private void addLabel(List<String> labels, String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            labels.add(normalized);
        }
    }

    private String joinPath(String... values) {
        List<String> parts = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    parts.add(value.trim());
                }
            }
        }
        return String.join(".", parts);
    }

    private Map<String, Object> compactMap(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value != null) {
            value.forEach((key, item) -> {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.put(key, item);
                }
            });
        }
        return result;
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

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String repairMojibake(String value) {
        if (value == null || !looksLikeMojibake(value)) {
            return value;
        }
        try {
            String repaired = new String(value.getBytes(GBK), StandardCharsets.UTF_8)
                .replace("\uFFFD", "")
                .replace("?", "");
            if (repaired.isBlank() || looksLikeMojibake(repaired)) {
                return value;
            }
            log.warn("sql_metadata_search repaired mojibake input original={} repaired={}", value, repaired);
            return repaired.trim();
        } catch (RuntimeException ex) {
            return value;
        }
    }

    private boolean inputEncodingRepaired(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        return containsMojibake(input.get("query"))
            || containsMojibake(input.get("q"))
            || containsMojibake(input.get("tableName"))
            || containsMojibake(input.get("assetName"))
            || containsMojibake(input.get("executionContext"))
            || containsMojibake(input.get("defaultDataAsset"));
    }

    private boolean containsMojibake(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(this::containsMojibake);
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (containsMojibake(item)) {
                    return true;
                }
            }
            return false;
        }
        return looksLikeMojibake(text(value));
    }

    private boolean looksLikeMojibake(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("�")
            || lower.contains("鏁")
            || lower.contains("嵁")
            || lower.contains("浠")
            || lower.contains("簱")
            || lower.contains("铻")
            || lower.contains("璐")
            || lower.contains("缁")
            || lower.contains("鎷")
            || lower.contains("斾")
            || lower.contains("緥")
            || lower.contains("锛")
            || lower.contains("涓")
            || lower.contains("涔")
            || lower.contains("泦");
    }

    private boolean equalsNormalized(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private double round(double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }

    private long elapsedMs(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    private String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String safeJson(Object value) {
        try {
            return TRACE_OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    private record Candidate(SqlDatasourceConfig datasource,
                             TableLocation table,
                             double score,
                             String source,
                             LuceneMcpSearchService.SearchHit hit) {
    }
}
