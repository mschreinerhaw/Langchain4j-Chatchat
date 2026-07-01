package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class SqlMetadataSearchService {

    public static final String RESULT_SCHEMA_VERSION = "sql_metadata_search_result.v1";

    private final LuceneMcpSearchService luceneSearchService;
    private final SqlDatasourceConfigService datasourceConfigService;
    private final MetadataIndexService metadataIndexService;

    public Map<String, Object> search(Map<String, Object> arguments) {
        long startedAt = System.nanoTime();
        Map<String, Object> input = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
        Map<String, Object> executionContext = objectMap(input.get("executionContext"));
        String rawQuery = firstText(text(input.get("query")), text(input.get("q")));
        String requestedTableName = firstText(
            text(input.get("tableName")),
            text(input.get("table_name")),
            text(executionContext.get("tableName")),
            text(executionContext.get("table_name")),
            looksLikeTableIdentifier(rawQuery) ? rawQuery : null
        );
        String requestedDatabase = firstText(
            text(input.get("database")),
            text(input.get("schema")),
            text(input.get("schemaName")),
            text(input.get("databaseName")),
            text(executionContext.get("database")),
            text(executionContext.get("schema")),
            text(executionContext.get("schemaName")),
            text(executionContext.get("databaseName"))
        );
        SqlTableNameParser.QualifiedTable qualifiedTable = SqlTableNameParser.parse(requestedTableName, requestedDatabase);
        String tableName = qualifiedTable.table();
        String database = qualifiedTable.database();
        String schema = qualifiedTable.schema();
        String lookupNamespace = firstText(schema, database);
        String assetName = firstText(
            text(input.get("assetName")),
            text(input.get("asset_name")),
            text(executionContext.get("assetName")),
            text(executionContext.get("asset_name")),
            text(executionContext.get("name"))
        );
        String env = firstText(
            text(input.get("env")),
            text(input.get("environment")),
            text(executionContext.get("env")),
            text(executionContext.get("environment"))
        );
        String databaseType = firstText(
            text(input.get("databaseType")),
            text(input.get("dbType")),
            text(input.get("dialect")),
            text(executionContext.get("databaseType")),
            text(executionContext.get("dbType")),
            text(executionContext.get("dialect"))
        );
        String query = firstText(rawQuery, requestedTableName, tableName, assetName);
        int limit = boundedInt(input.get("limit"), 10, 1, 30);
        boolean includeColumns = includeColumnsByDefault(input.get("includeColumns"));

        List<Candidate> candidates = new ArrayList<>();
        candidates.addAll(luceneCandidates(query, tableName, lookupNamespace, env, databaseType, limit));
        if (candidates.isEmpty()) {
            candidates.addAll(cacheCandidates(query, tableName, lookupNamespace, assetName, env, databaseType, limit));
        }

        List<Map<String, Object>> results = candidates.stream()
            .filter(candidate -> matchesDatabase(candidate.table(), lookupNamespace))
            .filter(candidate -> matchesTable(candidate.table(), tableName))
            .collect(java.util.stream.Collectors.toMap(
                candidate -> candidate.datasource().getId() + "::" + normalize(candidate.table().database()) + "::" + normalize(candidate.table().table()),
                candidate -> candidate,
                (left, right) -> left.score() >= right.score() ? left : right,
                LinkedHashMap::new
            ))
            .values()
            .stream()
            .sorted(Comparator.comparingDouble(Candidate::score).reversed())
            .limit(limit)
            .map(candidate -> toResult(candidate, includeColumns, database))
            .toList();

        return mapOf(
            "schemaVersion", RESULT_SCHEMA_VERSION,
            "success", true,
            "query", query,
            "filters", compactMap(mapOf(
                "assetName", assetName,
                "env", env,
                "databaseType", databaseType,
                "database", database,
                "schema", schema,
                "lookupNamespace", lookupNamespace,
                "tableName", tableName,
                "requestedTableName", requestedTableName
            )),
            "count", results.size(),
            "results", results,
            "usage", mapOf(
                "nextStep", "Use results[].sqlExecutionBinding when calling sql_query_execute. Do not invent schemaName/databaseName.",
                "templateStep", "Call database_ops_template_search for the same assetName/env, then use the returned templateId and parameterSchema."
            ),
            "diagnostics", mapOf(
                "source", luceneSearchService != null && luceneSearchService.enabled() ? "lucene_metadata_table_index" : "metadata_cache",
                "durationMs", (System.nanoTime() - startedAt) / 1_000_000
            )
        );
    }

    private List<Candidate> luceneCandidates(String query,
                                             String tableName,
                                             String database,
                                             String env,
                                             String databaseType,
                                             int limit) {
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
        List<LuceneMcpSearchService.SearchHit> hits = luceneSearchService.searchAssets(
            new LuceneMcpSearchService.AssetSearchRequest(
                "sql_datasource",
                query,
                env,
                databaseType,
                labels,
                Math.max(limit * 3, limit)
            )
        );
        List<Candidate> values = new ArrayList<>();
        Map<String, SqlDatasourceConfig> datasources = datasourceById();
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

    private List<Candidate> cacheCandidates(String query,
                                            String tableName,
                                            String database,
                                            String assetName,
                                            String env,
                                            String databaseType,
                                            int limit) {
        String normalizedQuery = normalize(firstText(tableName, query));
        List<Candidate> values = new ArrayList<>();
        for (SqlDatasourceConfig datasource : datasourceConfigService.listEnabled()) {
            if (!matchesDatasource(datasource, assetName, env, databaseType)) {
                continue;
            }
            for (TableLocation table : metadataIndexService.allTables(datasource)) {
                if (!matchesDatabase(table, database) || !matchesTable(table, tableName)) {
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
            .limit(limit)
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

    private TableLocation indexedTable(SqlDatasourceConfig datasource, String database, String tableName) {
        if (datasource == null || tableName == null || tableName.isBlank()) {
            return null;
        }
        for (TableLocation table : metadataIndexService.allTables(datasource)) {
            if (matchesDatabase(table, database) && matchesTable(table, tableName)) {
                return table;
            }
        }
        return null;
    }

    private Map<String, SqlDatasourceConfig> datasourceById() {
        Map<String, SqlDatasourceConfig> values = new LinkedHashMap<>();
        for (SqlDatasourceConfig datasource : datasourceConfigService.listEnabled()) {
            if (datasource.getId() != null) {
                values.put(datasource.getId(), datasource);
            }
        }
        return values;
    }

    private boolean matchesDatasource(SqlDatasourceConfig datasource, String assetName, String env, String databaseType) {
        if (datasource == null) {
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

    private int boundedInt(Object value, int fallback, int min, int max) {
        try {
            int parsed = value == null ? fallback : Integer.parseInt(String.valueOf(value));
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception ignored) {
            return fallback;
        }
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
