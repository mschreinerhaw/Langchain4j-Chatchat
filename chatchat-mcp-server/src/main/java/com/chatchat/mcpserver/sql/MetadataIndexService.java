package com.chatchat.mcpserver.sql;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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

    private final Map<String, CacheEntry<MetadataIndex>> indexCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<String>>> databaseCache = new ConcurrentHashMap<>();

    public List<String> listDatabases(SqlDatasourceConfig datasource) {
        if (datasource == null) {
            return List.of();
        }
        String cacheKey = "databases:" + datasource.getId();
        CacheEntry<List<String>> cached = databaseCache.get(cacheKey);
        if (cached != null && !cached.expired()) {
            return cached.value();
        }
        List<String> values = queryDatabases(datasource);
        databaseCache.put(cacheKey, new CacheEntry<>(values));
        trimCache(databaseCache);
        return values;
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
        if (!Set.of("mysql", "mariadb", "postgresql", "sqlserver").contains(datasourceType)) {
            return MetadataIndex.failed(datasource.getId(), datasourceType, "unsupported_database_type");
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
        MetadataIndex refreshed = refresh(datasource);
        indexCache.put(cacheKey, new CacheEntry<>(refreshed));
        trimCache(indexCache);
        return refreshed;
    }

    public void refreshEnabledDatasources(List<SqlDatasourceConfig> datasources) {
        if (datasources == null || datasources.isEmpty()) {
            return;
        }
        for (SqlDatasourceConfig datasource : datasources) {
            if (datasource == null || datasource.getId() == null) {
                continue;
            }
            try {
                indexCache.put(datasource.getId(), new CacheEntry<>(refresh(datasource)));
            } catch (Exception ex) {
                log.warn("Metadata index refresh failed: datasourceId={}, error={}", datasource.getId(), ex.getMessage());
            }
        }
        trimCache(indexCache);
    }

    public void invalidate(String datasourceId) {
        if (datasourceId == null || datasourceId.isBlank()) {
            indexCache.clear();
            databaseCache.clear();
            return;
        }
        indexCache.remove(datasourceId.trim());
        databaseCache.remove("databases:" + datasourceId.trim());
    }

    private MetadataIndex refresh(SqlDatasourceConfig datasource) {
        String datasourceType = resolvedDatabaseType(datasource);
        try {
            List<TableLocation> tables = queryAllTableLocations(datasource, datasourceType);
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
            Map<String, List<MetadataColumn>> tableColumns = queryAllColumns(datasource, datasourceType).stream()
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
            log.warn("Metadata index build failed: datasourceId={}, error={}", datasource.getId(), ex.getMessage());
            return MetadataIndex.failed(datasource.getId(), datasourceType, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private List<String> queryDatabases(SqlDatasourceConfig datasource) {
        String datasourceType = resolvedDatabaseType(datasource);
        String sql = switch (datasourceType) {
            case "postgresql" -> "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN ('pg_catalog', 'information_schema') ORDER BY schema_name";
            case "sqlserver" -> "SELECT name AS schema_name FROM sys.databases ORDER BY name";
            default -> "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys') ORDER BY schema_name";
        };
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

    private List<TableLocation> queryAllTableLocations(SqlDatasourceConfig datasource, String datasourceType)
        throws Exception {
        try (Connection connection = openConnection(datasource);
             PreparedStatement statement = connection.prepareStatement(tableIndexSql(datasourceType));
             ResultSet resultSet = statement.executeQuery()) {
            List<TableLocation> candidates = new ArrayList<>();
            while (resultSet.next() && candidates.size() < 20000) {
                String schema = blankToNull(resultSet.getString("table_schema"));
                String actualTable = blankToNull(resultSet.getString("table_name"));
                String tableType = readOptionalString(resultSet, "table_type");
                Long rows = readOptionalLong(resultSet, "table_rows");
                if (schema != null && actualTable != null) {
                    candidates.add(new TableLocation(datasource.getId(), schema, schema, actualTable, tableType, rows, 0.0));
                }
            }
            return candidates;
        }
    }

    private List<MetadataColumn> queryAllColumns(SqlDatasourceConfig datasource, String datasourceType) {
        try (Connection connection = openConnection(datasource);
             PreparedStatement statement = connection.prepareStatement(columnIndexSql(datasourceType));
             ResultSet resultSet = statement.executeQuery()) {
            List<MetadataColumn> columns = new ArrayList<>();
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
                    !"NO".equalsIgnoreCase(readOptionalString(resultSet, "is_nullable")),
                    readOptionalInteger(resultSet, "ordinal_position")
                ));
            }
            return columns;
        } catch (Exception ex) {
            log.warn("Metadata column index build failed: datasourceId={}, error={}", datasource.getId(), ex.getMessage());
            return List.of();
        }
    }

    private Connection openConnection(SqlDatasourceConfig datasource) throws Exception {
        if (datasource.getDriverClass() != null && !datasource.getDriverClass().isBlank()) {
            Class.forName(datasource.getDriverClass().trim());
        }
        Connection connection = DriverManager.getConnection(
            datasource.getJdbcUrl(), datasource.getUsername(), datasource.getPassword());
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

    private String tableIndexSql(String datasourceType) {
        if ("sqlserver".equals(datasourceType)) {
            return """
                SELECT table_schema, table_name, table_type, CAST(NULL AS BIGINT) AS table_rows
                FROM information_schema.tables
                ORDER BY table_schema, table_name
                """;
        }
        if ("postgresql".equals(datasourceType)) {
            return """
                SELECT table_schema, table_name, table_type, CAST(NULL AS BIGINT) AS table_rows
                FROM information_schema.tables
                WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
                ORDER BY table_schema, table_name
                """;
        }
        return """
            SELECT table_schema, table_name, table_type, table_rows
            FROM information_schema.tables
            WHERE table_schema NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')
            ORDER BY table_schema, table_name
            """;
    }

    private String columnIndexSql(String datasourceType) {
        if ("postgresql".equals(datasourceType)) {
            return """
                SELECT table_schema, table_name, column_name, data_type,
                       data_type AS column_type, CAST(NULL AS VARCHAR) AS column_key,
                       is_nullable, ordinal_position
                FROM information_schema.columns
                WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
                ORDER BY table_schema, table_name, ordinal_position
                """;
        }
        if ("sqlserver".equals(datasourceType)) {
            return """
                SELECT table_schema, table_name, column_name, data_type,
                       data_type AS column_type, CAST(NULL AS VARCHAR) AS column_key,
                       is_nullable, ordinal_position
                FROM information_schema.columns
                ORDER BY table_schema, table_name, ordinal_position
                """;
        }
        return """
            SELECT table_schema, table_name, column_name, data_type, column_type, column_key,
                   is_nullable, ordinal_position
            FROM information_schema.columns
            WHERE table_schema NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')
            ORDER BY table_schema, table_name, ordinal_position
            """;
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

    private String defaultSchemaName(SqlDatasourceConfig datasource) {
        return databaseNameFromJdbcUrl(datasource == null ? null : datasource.getJdbcUrl());
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

    private record CacheEntry<T>(T value, long createdAtMs) {
        CacheEntry(T value) {
            this(value, System.currentTimeMillis());
        }

        boolean expired() {
            return System.currentTimeMillis() - createdAtMs > CACHE_TTL.toMillis();
        }
    }
}
