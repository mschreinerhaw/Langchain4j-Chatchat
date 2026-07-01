package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.cache.McpRocksDbStore;
import com.chatchat.tools.builtin.DynamicJdbcDriverLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MetadataIndexService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int MAX_CACHED_ENTRIES = 512;
    private static final String ROCKS_INDEX_PREFIX = "metadata:index:";

    private final Map<String, CacheEntry<MetadataIndex>> indexCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<String>>> databaseCache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SystemMetadataQueryProvider metadataQueryProvider = new SystemMetadataQueryProvider();

    @Autowired(required = false)
    private McpRocksDbStore rocksDbStore;

    @Autowired(required = false)
    private SqlMetadataAssetRegistryService metadataAssetRegistryService;

    @Autowired(required = false)
    private DynamicJdbcDriverLoader driverLoader;

    public List<String> listDatabases(SqlDatasourceConfig datasource) {
        if (datasource == null) {
            return List.of();
        }
        MetadataIndex index = indexFor(datasource);
        if (index.datasourceSchemas() != null && !index.datasourceSchemas().isEmpty()) {
            return index.datasourceSchemas();
        }
        return metadataScopeValues(datasource).stream()
            .map(ScopeValue::value)
            .distinct()
            .sorted()
            .toList();
    }

    public List<String> listTables(SqlDatasourceConfig datasource, String database) {
        if (datasource == null) {
            return List.of();
        }
        String schema = firstText(database, defaultSchemaName(datasource));
        if (schema == null) {
            return List.of();
        }
        MetadataIndex index = indexFor(datasource);
        List<String> values = index.schemaTables().get(normalizeIdentifier(schema));
        return values == null ? List.of() : values;
    }

    public List<TableLocation> findTables(SqlDatasourceConfig datasource, String tableName) {
        if (datasource == null || tableName == null || tableName.isBlank()) {
            return List.of();
        }
        MetadataIndex index = indexFor(datasource);
        List<TableLocation> values = index.tableIndex().get(normalizeIdentifier(tableName));
        return values == null ? List.of() : values;
    }

    public List<TableLocation> allTables(SqlDatasourceConfig datasource) {
        if (datasource == null) {
            return List.of();
        }
        return indexFor(datasource).tables();
    }

    public List<MetadataColumn> columns(SqlDatasourceConfig datasource, TableLocation table) {
        if (datasource == null || table == null) {
            return List.of();
        }
        List<MetadataColumn> values = indexFor(datasource).tableColumns().get(tableKey(table.database(), table.table()));
        return values == null ? List.of() : values;
    }

    public MetadataIndex indexFor(SqlDatasourceConfig datasource) {
        if (datasource == null) {
            return MetadataIndex.failed(null, "generic", "datasource is required");
        }
        String datasourceType = resolvedDatabaseType(datasource);
        if (!isSupportedMetadataIndexType(datasourceType)) {
            return MetadataIndex.failed(datasource.getId(), datasourceType, "unsupported_database_type");
        }
        if (datasource.getId() == null || datasource.getId().isBlank()) {
            return MetadataIndex.failed(null, datasourceType, "datasource_id_required");
        }
        String cacheKey = datasource.getId();
        CacheEntry<MetadataIndex> cached = indexCache.get(cacheKey);
        if (cached != null && !cached.expired()) {
            MetadataIndex value = cached.value();
            return new MetadataIndex(
                value.datasourceId(),
                value.databaseType(),
                value.tables(),
                value.tableIndex(),
                value.schemaTables(),
                value.tableColumns(),
                value.datasourceSchemas(),
                value.refreshedAtMs(),
                true,
                value.error()
            );
        }
        MetadataIndex persisted = loadPersistedIndex(datasource);
        if (persisted != null) {
            indexCache.put(cacheKey, new CacheEntry<>(persisted));
            trimCache(indexCache);
            return cachedIndex(persisted, true);
        }
        return MetadataIndex.failed(datasource.getId(), datasourceType, "metadata_index_not_refreshed");
    }

    public void refreshEnabledDatasources(List<SqlDatasourceConfig> datasources) {
        if (datasources == null || datasources.isEmpty()) {
            return;
        }
        for (SqlDatasourceConfig datasource : datasources) {
            if (datasource == null || datasource.getId() == null) {
                continue;
            }
            if (!autoRefreshEnabled(datasource)) {
                continue;
            }
            try {
                refreshDatasource(datasource);
            } catch (Exception ex) {
                log.warn("Metadata index refresh failed: datasourceId={}, error={}", datasource.getId(), ex.getMessage());
            }
        }
        trimCache(indexCache);
    }

    public MetadataRefreshResult refreshDatasource(SqlDatasourceConfig datasource) {
        long startedAt = System.currentTimeMillis();
        if (datasource == null) {
            MetadataIndex failed = MetadataIndex.failed(null, "generic", "datasource is required");
            return refreshResult(failed, startedAt, PersistState.skipped("datasource is required"));
        }
        String datasourceType = resolvedDatabaseType(datasource);
        if (!isSupportedMetadataIndexType(datasourceType)) {
            MetadataIndex failed = MetadataIndex.failed(datasource.getId(), datasourceType, "unsupported_database_type");
            return refreshResult(failed, startedAt, PersistState.skipped("unsupported_database_type"));
        }
        ensureRegistered(datasource);
        MetadataIndex refreshed = refresh(datasource);
        if ((refreshed.error() == null || refreshed.error().isBlank()) && datasource.getId() != null && !datasource.getId().isBlank()) {
            indexCache.put(datasource.getId(), new CacheEntry<>(refreshed));
            databaseCache.remove("databases:" + datasource.getId());
            trimCache(indexCache);
        }
        PersistState persistState = refreshed.error() == null || refreshed.error().isBlank()
            ? persistIndex(datasource, refreshed)
            : PersistState.skipped("metadata refresh failed; previous persisted snapshot was preserved");
        markRegistryIndexed(datasource, refreshed);
        return refreshResult(refreshed, startedAt, persistState);
    }

    public void invalidate(String datasourceId) {
        if (datasourceId == null || datasourceId.isBlank()) {
            indexCache.clear();
            databaseCache.clear();
            if (rocksDbStore != null && rocksDbStore.isUsable()) {
                for (McpRocksDbStore.KeyValue entry : rocksDbStore.scan(ROCKS_INDEX_PREFIX, MAX_CACHED_ENTRIES)) {
                    try {
                        rocksDbStore.delete(entry.key());
                    } catch (Exception ex) {
                        log.debug("Metadata persisted index delete failed: {}", ex.getMessage());
                    }
                }
            }
            return;
        }
        indexCache.remove(datasourceId.trim());
        databaseCache.remove("databases:" + datasourceId.trim());
        deletePersistedIndex(datasourceId.trim());
    }

    private MetadataIndex cachedIndex(MetadataIndex value, boolean cacheHit) {
        return new MetadataIndex(
            value.datasourceId(),
            value.databaseType(),
            value.tables(),
            value.tableIndex(),
            value.schemaTables(),
            value.tableColumns(),
            value.datasourceSchemas(),
            value.refreshedAtMs(),
            cacheHit,
            value.error()
        );
    }

    private MetadataIndex loadPersistedIndex(SqlDatasourceConfig datasource) {
        if (datasource == null || datasource.getId() == null || rocksDbStore == null || !rocksDbStore.isUsable()) {
            return null;
        }
        try {
            byte[] bytes = rocksDbStore.get(rocksKey(datasource.getId()));
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            MetadataIndex value = objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8), MetadataIndex.class);
            if (value == null || value.refreshedAtMs() <= 0) {
                return null;
            }
            return value;
        } catch (Exception ex) {
            log.debug("Metadata persisted index load failed: datasourceId={}, error={}", datasource.getId(), ex.getMessage());
            return null;
        }
    }

    private PersistState persistIndex(SqlDatasourceConfig datasource, MetadataIndex index) {
        if (rocksDbStore == null || !rocksDbStore.isUsable()) {
            return PersistState.disabled("RocksDB store is disabled or unavailable");
        }
        if (datasource == null || datasource.getId() == null || datasource.getId().isBlank() || index == null) {
            return PersistState.skipped("datasourceId and metadata index are required");
        }
        try {
            rocksDbStore.put(rocksKey(datasource.getId()), objectMapper.writeValueAsBytes(index));
            return PersistState.persisted();
        } catch (Exception ex) {
            log.debug("Metadata persisted index write failed: datasourceId={}, error={}", datasource.getId(), ex.getMessage());
            return PersistState.failed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void deletePersistedIndex(String datasourceId) {
        if (datasourceId == null || datasourceId.isBlank() || rocksDbStore == null || !rocksDbStore.isUsable()) {
            return;
        }
        try {
            rocksDbStore.delete(rocksKey(datasourceId));
        } catch (Exception ex) {
            log.debug("Metadata persisted index delete failed: datasourceId={}, error={}", datasourceId, ex.getMessage());
        }
    }

    private String rocksKey(String datasourceId) {
        return ROCKS_INDEX_PREFIX + datasourceId;
    }

    private MetadataIndex refresh(SqlDatasourceConfig datasource) {
        String datasourceType = resolvedDatabaseType(datasource);
        try {
            List<ScopeValue> scopeValues = metadataScopeValues(datasource);
            if (scopeValues.isEmpty()) {
                return MetadataIndex.failed(datasource.getId(), datasourceType, "metadata_asset_registry_empty");
            }
            List<TableLocation> tables = queryAllTableLocations(datasource, datasourceType, scopeValues).stream()
                .filter(table -> schemaAllowed(scopeValues, table.database()))
                .toList();
            Map<String, List<TableLocation>> tableIndex = tables.stream()
                .collect(Collectors.groupingBy(
                    table -> normalizeIdentifier(table.table()),
                    LinkedHashMap::new,
                    Collectors.toList()
                ));
            Map<String, List<String>> schemaTables = tables.stream()
                .collect(Collectors.groupingBy(
                    table -> normalizeIdentifier(table.database()),
                    LinkedHashMap::new,
                    Collectors.mapping(TableLocation::table, Collectors.collectingAndThen(Collectors.toList(), this::sortedDistinct))
                ));
            Set<String> indexedTableKeys = tables.stream()
                .map(table -> tableKey(table.database(), table.table()))
                .collect(Collectors.toSet());
            Map<String, List<MetadataColumn>> tableColumns = queryAllColumns(datasource, datasourceType, scopeValues).stream()
                .filter(column -> schemaAllowed(scopeValues, column.database()))
                .filter(column -> !indexedTableKeys.isEmpty() && indexedTableKeys.contains(tableKey(column.database(), column.table())))
                .collect(Collectors.groupingBy(
                    column -> tableKey(column.database(), column.table()),
                    LinkedHashMap::new,
                    Collectors.toList()
                ));
            List<String> schemas = sortedDistinct(tables.stream().map(TableLocation::database).toList());
            return new MetadataIndex(
                datasource.getId(),
                datasourceType,
                tables,
                tableIndex,
                schemaTables,
                tableColumns,
                schemas,
                System.currentTimeMillis(),
                false,
                null
            );
        } catch (Exception ex) {
            log.warn("Metadata index build failed: datasourceId={}, errorType={}, error={}",
                datasource.getId(), ex.getClass().getSimpleName(), ex.getMessage(), ex);
            return MetadataIndex.failed(datasource.getId(), datasourceType, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private List<String> queryDatabases(SqlDatasourceConfig datasource) {
        String datasourceType = resolvedDatabaseType(datasource);
        String sql = metadataQueryProvider.databaseSql(datasourceType).sql();
        try (Connection connection = openConnection(datasource);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (resultSet.next()) {
                String value = blankToNull(resultSet.getString(1));
                if (value != null) {
                    values.add(value);
                }
            }
            return values;
        } catch (Exception ex) {
            log.warn("Metadata database discovery failed: datasourceId={}, error={}", datasource.getId(), ex.getMessage());
            return List.of();
        }
    }

    private List<TableLocation> queryAllTableLocations(SqlDatasourceConfig datasource, String datasourceType, List<ScopeValue> scopeValues)
        throws Exception {
        SystemMetadataQueryProvider.MetadataSql sql = metadataQueryProvider.tableIndexSql(datasourceType);
        try (Connection connection = openConnection(datasource)) {
            List<TableLocation> candidates = new ArrayList<>();
            for (String databaseName : queryDatabaseNames(sql, scopeValues)) {
                try (PreparedStatement statement = connection.prepareStatement(sql.sql())) {
                    bindDatabaseName(statement, sql.databaseNameParameterCount(), databaseName);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next() && candidates.size() < 20000) {
                            String schema = blankToNull(resultSet.getString("table_schema"));
                            String actualTable = blankToNull(resultSet.getString("table_name"));
                            String tableType = readOptionalString(resultSet, "table_type");
                            Long rows = readOptionalLong(resultSet, "table_rows");
                            String tableComment = blankToNull(readOptionalString(resultSet, "table_comment"));
                            String databaseComment = firstText(datasource.getDescription(), datasource.getTitle(), datasource.getName());
                            if (schema != null && actualTable != null) {
                                candidates.add(new TableLocation(datasource.getId(), schema, schema, actualTable, tableType, rows, tableComment, databaseComment, 0.0));
                            }
                        }
                    }
                }
            }
            return candidates;
        }
    }

    private List<MetadataColumn> queryAllColumns(SqlDatasourceConfig datasource, String datasourceType, List<ScopeValue> scopeValues) {
        SystemMetadataQueryProvider.MetadataSql sql = metadataQueryProvider.columnIndexSql(datasourceType);
        try (Connection connection = openConnection(datasource)) {
            List<MetadataColumn> columns = new ArrayList<>();
            for (String databaseName : queryDatabaseNames(sql, scopeValues)) {
                try (PreparedStatement statement = connection.prepareStatement(sql.sql())) {
                    bindDatabaseName(statement, sql.databaseNameParameterCount(), databaseName);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next() && columns.size() < 50000) {
                            String schema = blankToNull(resultSet.getString("table_schema"));
                            String table = blankToNull(resultSet.getString("table_name"));
                            String name = blankToNull(resultSet.getString("column_name"));
                            if (schema == null || table == null || name == null) {
                                continue;
                            }
                            columns.add(new MetadataColumn(
                                datasource.getId(),
                                schema,
                                schema,
                                table,
                                name,
                                readOptionalString(resultSet, "data_type"),
                                readOptionalString(resultSet, "column_type"),
                                readOptionalString(resultSet, "column_key"),
                                blankToNull(readOptionalString(resultSet, "column_comment")),
                                !"NO".equalsIgnoreCase(readOptionalString(resultSet, "is_nullable")),
                                readOptionalInteger(resultSet, "ordinal_position")
                            ));
                        }
                    }
                }
            }
            return columns;
        } catch (Exception ex) {
            log.warn("Metadata column index build failed: datasourceId={}, error={}", datasource.getId(), ex.getMessage());
            return List.of();
        }
    }

    private Connection openConnection(SqlDatasourceConfig datasource) throws Exception {
        Connection connection;
        if (driverLoader == null) {
            if (datasource.getDriverClass() != null && !datasource.getDriverClass().isBlank()) {
                Class.forName(datasource.getDriverClass().trim());
            }
            connection = DriverManager.getConnection(
                datasource.getJdbcUrl(), datasource.getUsername(), datasource.getPassword());
        } else {
            DataSource dataSource = driverLoader.createDataSource(
                datasource.getJdbcUrl(),
                datasource.getUsername(),
                datasource.getPassword(),
                datasource.getDriverClass(),
                resolvedDatabaseType(datasource)
            );
            connection = dataSource.getConnection();
        }
        connection.setReadOnly(true);
        String schemaName = defaultSchemaName(datasource);
        if (schemaName != null && Set.of("mysql", "mariadb").contains(resolvedDatabaseType(datasource))) {
            try {
                connection.setCatalog(schemaName);
            } catch (Exception ignored) {
                // Metadata queries still work through information_schema without switching catalog.
            }
        }
        return connection;
    }

    private List<String> queryDatabaseNames(SystemMetadataQueryProvider.MetadataSql sql, List<ScopeValue> scopeValues) {
        if (sql == null || sql.databaseNameParameterCount() <= 0) {
            return java.util.Collections.singletonList(null);
        }
        if (scopeValues == null || scopeValues.isEmpty()) {
            return java.util.Collections.singletonList(null);
        }
        return scopeValues.stream()
            .map(ScopeValue::value)
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    private void bindDatabaseName(PreparedStatement statement, int parameterCount, String databaseName) throws Exception {
        for (int index = 1; index <= parameterCount; index++) {
            statement.setString(index, databaseName);
        }
    }

    private <T> void trimCache(Map<String, CacheEntry<T>> cache) {
        if (cache.size() <= MAX_CACHED_ENTRIES) {
            return;
        }
        cache.entrySet().removeIf(entry -> entry.getValue().expired());
    }

    private List<String> sortedDistinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList();
    }

    private String resolvedDatabaseType(SqlDatasourceConfig datasource) {
        if (datasource == null) {
            return "generic";
        }
        return SqlDatasourceConfigService.normalizeDatabaseType(
            datasource.getDatabaseType(),
            datasource.getJdbcUrl(),
            datasource.getDriverClass()
        );
    }

    private boolean isSupportedMetadataIndexType(String datasourceType) {
        return metadataQueryProvider.supportsMetadataIndexType(datasourceType);
    }

    private String defaultSchemaName(SqlDatasourceConfig datasource) {
        return databaseNameFromJdbcUrl(datasource == null ? null : datasource.getJdbcUrl());
    }

    private List<ScopeValue> metadataScopeValues(SqlDatasourceConfig datasource) {
        if (datasource == null) {
            return List.of();
        }
        if (metadataAssetRegistryService != null && datasource.getId() != null && !datasource.getId().isBlank()) {
            List<ScopeValue> values = metadataAssetRegistryService.listEnabledByDatasource(datasource.getId()).stream()
                .map(SqlMetadataAssetRegistry::getDatabaseName)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> new ScopeValue(value.trim(), normalizeIdentifier(value)))
                .distinct()
                .toList();
            if (!values.isEmpty()) {
                return values;
            }
            return List.of();
        }
        String scopeType = firstText(datasource.getMetadataScopeType(), "JDBC_DATABASE")
            .toUpperCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
        if ("ALL_VISIBLE_SCHEMAS".equals(scopeType)) {
            return List.of();
        }
        String explicit = firstText(datasource.getMetadataScopeValue());
        String jdbcDatabase = defaultSchemaName(datasource);
        String username = datasource.getUsername();
        if (explicit != null) {
            List<ScopeValue> explicitValues = splitScopeValues(explicit).stream()
                .map(value -> new ScopeValue(value, normalizeIdentifier(value)))
                .distinct()
                .toList();
            if (!explicitValues.isEmpty()) {
                return explicitValues;
            }
        }
        String value = switch (scopeType) {
            case "LOGIN_USER_SCHEMA" -> firstText(username, jdbcDatabase);
            case "JDBC_DATABASE" -> firstText(jdbcDatabase, username);
            default -> firstText(jdbcDatabase, username);
        };
        return value == null ? List.of() : List.of(new ScopeValue(value, normalizeIdentifier(value)));
    }

    private List<String> splitScopeValues(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("[,;\\r\\n]+"))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .distinct()
            .toList();
    }

    private void ensureRegistered(SqlDatasourceConfig datasource) {
        if (metadataAssetRegistryService == null || datasource == null || datasource.getId() == null || datasource.getId().isBlank()) {
            return;
        }
        if (metadataAssetRegistryService.listEnabledByDatasource(datasource.getId()).isEmpty()) {
            metadataAssetRegistryService.syncDefaultForDatasource(datasource);
        }
    }

    private boolean autoRefreshEnabled(SqlDatasourceConfig datasource) {
        if (datasource == null) {
            return false;
        }
        if (metadataAssetRegistryService == null || datasource.getId() == null || datasource.getId().isBlank()) {
            return datasource.isMetadataAutoRefreshEnabled();
        }
        List<SqlMetadataAssetRegistry> registries = metadataAssetRegistryService.listEnabledByDatasource(datasource.getId());
        if (registries.isEmpty()) {
            SqlMetadataAssetRegistry synced = metadataAssetRegistryService.syncDefaultForDatasource(datasource);
            return synced != null && "AUTO".equalsIgnoreCase(synced.getRefreshMode());
        }
        return registries.stream().anyMatch(registry -> "AUTO".equalsIgnoreCase(registry.getRefreshMode()));
    }

    private void markRegistryIndexed(SqlDatasourceConfig datasource, MetadataIndex index) {
        if (metadataAssetRegistryService == null || datasource == null || datasource.getId() == null || index == null) {
            return;
        }
        try {
            metadataAssetRegistryService.markIndexed(datasource.getId(), index.datasourceSchemas(), index.error());
        } catch (Exception ex) {
            log.debug("Metadata asset registry status update failed: datasourceId={}, error={}", datasource.getId(), ex.getMessage());
        }
    }

    private boolean schemaAllowed(List<ScopeValue> scopeValues, String schema) {
        if (scopeValues == null || scopeValues.isEmpty()) {
            return true;
        }
        String normalized = normalizeIdentifier(schema);
        return normalized != null && scopeValues.stream().anyMatch(scope -> normalized.equals(scope.normalized()));
    }

    private String databaseNameFromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        String trimmed = jdbcUrl.trim();
        Matcher parameterMatcher = Pattern.compile("(?i)[;?&](?:databaseName|database)=([^;?&]+)").matcher(trimmed);
        if (parameterMatcher.find()) {
            return blankToNull(parameterMatcher.group(1));
        }
        Matcher matcher = Pattern.compile("(?i)^jdbc:[^:]+://[^/]+/([^?;]+)").matcher(trimmed);
        return matcher.find() ? blankToNull(matcher.group(1)) : null;
    }

    private String readOptionalString(ResultSet resultSet, String column) {
        try {
            return resultSet.getString(column);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long readOptionalLong(ResultSet resultSet, String column) {
        try {
            long value = resultSet.getLong(column);
            return resultSet.wasNull() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer readOptionalInteger(ResultSet resultSet, String column) {
        try {
            int value = resultSet.getInt(column);
            return resultSet.wasNull() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String tableKey(String schema, String table) {
        return normalizeIdentifier(schema) + "." + normalizeIdentifier(table);
    }

    private String normalizeIdentifier(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private MetadataRefreshResult refreshResult(MetadataIndex index, long startedAt, PersistState persistState) {
        int columnCount = index.tableColumns() == null
            ? 0
            : index.tableColumns().values().stream().mapToInt(List::size).sum();
        PersistState state = persistState == null ? PersistState.skipped("persist state was not evaluated") : persistState;
        return new MetadataRefreshResult(
            index.datasourceId(),
            index.databaseType(),
            index.datasourceSchemas() == null ? 0 : index.datasourceSchemas().size(),
            index.tables() == null ? 0 : index.tables().size(),
            columnCount,
            index.refreshedAtMs(),
            System.currentTimeMillis() - startedAt,
            "PERSISTED".equals(state.status()),
            state,
            index.error()
        );
    }

    private record ScopeValue(String value, String normalized) {
    }

    private record CacheEntry<T>(T value, long createdAtMs) {
        CacheEntry(T value) {
            this(value, System.currentTimeMillis());
        }

        boolean expired() {
            return System.currentTimeMillis() - createdAtMs > CACHE_TTL.toMillis();
        }
    }

    public record MetadataRefreshResult(
        String datasourceId,
        String databaseType,
        int schemaCount,
        int tableCount,
        int columnCount,
        long refreshedAtMs,
        long durationMs,
        boolean persistedToRocksDb,
        PersistState persistState,
        String error
    ) {
    }

    public record PersistState(
        boolean enabled,
        String status,
        String message
    ) {
        static PersistState persisted() {
            return new PersistState(true, "PERSISTED", null);
        }

        static PersistState disabled(String message) {
            return new PersistState(false, "DISABLED", message);
        }

        static PersistState skipped(String message) {
            return new PersistState(false, "SKIPPED", message);
        }

        static PersistState failed(String message) {
            return new PersistState(true, "FAILED", message);
        }
    }
}
