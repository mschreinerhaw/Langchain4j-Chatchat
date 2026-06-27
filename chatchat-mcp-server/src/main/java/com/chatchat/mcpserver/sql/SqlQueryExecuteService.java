package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqlQueryExecuteService {

    private static final int LOG_SQL_LIMIT = 4000;
    private static final int LOG_ROWS_LIMIT = 20;

    private final SqlDatasourceConfigService datasourceConfigService;
    private final SqlSafetyService safetyService;
    private final SqlTemplateService templateService;
    private final InvocationAuditService auditService;
    private final ObjectMapper objectMapper;

    public SqlQueryResult execute(Map<String, Object> arguments) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> request = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        SqlDatasourceConfig datasource = null;
        String normalizedSql = null;
        SqlQueryResult result;
        int timeoutSeconds = 30;
        int maxRows = 1000;
        try {
            datasource = datasourceConfigService.getEnabled(text(request, "datasourceId"));
            assertExecutionCapability(datasource);
            timeoutSeconds = normalizeTimeout(request.get("timeoutSeconds"), datasource.getDefaultTimeoutSeconds());
            maxRows = normalizeMaxRows(request.get("maxRows"), datasource.getDefaultMaxRows());
            String sql = resolveSql(request, datasource);
            normalizedSql = safetyService.validateAndNormalize(sql, maxRows);
            validateAllowedTables(datasource, normalizedSql);
            log.info("MCP SQL query execution requested: datasourceId={}, datasourceName={}, env={}, tool={}, templateId={}, timeoutSeconds={}, maxRows={}, purpose={}, sourceTaskId={}, sql={}",
                datasource.getId(), datasource.getName(), datasource.getEnvironment(), datasource.getToolName(),
                requestedTemplate(request), timeoutSeconds, maxRows, text(request, "purpose"), text(request, "sourceTaskId"),
                truncateSql(normalizedSql));
            result = query(datasource, sql, normalizedSql, timeoutSeconds, maxRows,
                text(request, "purpose"), text(request, "sourceTaskId"), startedAt);
        } catch (Exception ex) {
            long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
            result = new SqlQueryResult(
                false,
                datasource == null ? text(request, "datasourceId") : datasource.getId(),
                datasource == null ? null : datasource.getName(),
                datasource == null ? null : datasource.getToolName(),
                datasource == null ? null : datasource.getEnvironment(),
                text(request, "sql"),
                normalizedSql,
                timeoutSeconds,
                maxRows,
                List.of(),
                List.of(),
                0,
                false,
                durationMs,
                text(request, "purpose"),
                text(request, "sourceTaskId"),
                ex.getMessage()
            );
            log.warn("MCP SQL query execution failed before/while running: datasourceId={}, durationMs={}, error={}, sql={}",
                result.datasourceId(), durationMs, ex.getMessage(), truncateSql(normalizedSql));
        }
        logSqlResult(result);
        auditService.recordSqlQueryCall(datasource, request, result);
        return result;
    }

    public SqlQueryResult execute(SqlDatasourceConfig datasource, Map<String, Object> arguments) {
        Map<String, Object> request = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        request.put("datasourceId", datasource.getId());
        return execute(request);
    }

    public SqlQueryResult testConnection(SqlDatasourceConfig datasource) {
        long startedAt = System.currentTimeMillis();
        int timeoutSeconds = Math.max(1, Math.min(datasource.getDefaultTimeoutSeconds(), 60));
        log.info("MCP SQL execution capability probe started: datasourceName={}, tool={}, env={}, jdbcUrl={}, timeoutSeconds={}",
            datasource.getName(), datasource.getToolName(), datasource.getEnvironment(), redactJdbcUrl(datasource.getJdbcUrl()), timeoutSeconds);
        try {
            assertExecutionCapability(datasource);
            if (datasource.getDriverClass() != null && !datasource.getDriverClass().isBlank()) {
                Class.forName(datasource.getDriverClass().trim());
            }
            try (Connection connection = DriverManager.getConnection(
                datasource.getJdbcUrl(), datasource.getUsername(), datasource.getPassword());
                 Statement statement = connection.createStatement()) {
                connection.setReadOnly(true);
                statement.setQueryTimeout(timeoutSeconds);
                ProbeQueryResult probe = executeProbeQuery(statement);
                long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
                SqlQueryResult result = new SqlQueryResult(
                    true,
                    datasource.getId(),
                    datasource.getName(),
                    datasource.getToolName(),
                    datasource.getEnvironment(),
                    probe.sql(),
                    probe.sql(),
                    timeoutSeconds,
                    1,
                    List.of("probe"),
                    List.of(Map.of("probe", probe.value())),
                    1,
                    false,
                    durationMs,
                    "asset_connection_test",
                    "asset-center",
                    null
                );
                logSqlResult(result);
                return result;
            }
        } catch (Exception ex) {
            long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
            SqlQueryResult result = new SqlQueryResult(
                false,
                datasource.getId(),
                datasource.getName(),
                datasource.getToolName(),
                datasource.getEnvironment(),
                "connection_test",
                null,
                timeoutSeconds,
                1,
                List.of(),
                List.of(),
                0,
                false,
                durationMs,
                "asset_connection_test",
                "asset-center",
                ex.getMessage()
            );
            logSqlResult(result);
            return result;
        }
    }

    private String resolveSql(Map<String, Object> request, SqlDatasourceConfig datasource) {
        String sql = text(request, "sql");
        String template = requestedTemplate(request);
        if (sql != null && template != null) {
            throw new IllegalArgumentException("Use either sql or SQL template, not both");
        }
        if (sql != null && !sql.isBlank()) {
            return sql;
        }
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("Either sql or template is required");
        }
        return templateService.render(template, mapValue(request.get("parameters")), datasource, request);
    }

    private String requestedTemplate(Map<String, Object> request) {
        return firstText(text(request, "template"), text(request, "templateId"), text(request, "template_id"));
    }

    private SqlQueryResult query(SqlDatasourceConfig datasource, String originalSql, String sql,
                                 int timeoutSeconds, int maxRows, String purpose,
                                 String sourceTaskId, long startedAt) throws Exception {
        if (datasource.getDriverClass() != null && !datasource.getDriverClass().isBlank()) {
            Class.forName(datasource.getDriverClass().trim());
        }
        try (Connection connection = DriverManager.getConnection(
            datasource.getJdbcUrl(), datasource.getUsername(), datasource.getPassword());
             Statement statement = connection.createStatement()) {
            connection.setReadOnly(true);
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxRows);
            log.info("MCP SQL query JDBC connected: datasourceId={}, datasourceName={}, env={}, jdbcUrl={}, readOnly=true, timeoutSeconds={}, maxRows={}",
                datasource.getId(), datasource.getName(), datasource.getEnvironment(), redactJdbcUrl(datasource.getJdbcUrl()),
                timeoutSeconds, maxRows);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                List<String> columns = columns(metaData);
                Set<String> sensitiveFields = sensitiveFields(datasource);
                boolean maskAll = matchesSensitiveTable(datasource, sql);
                List<Map<String, Object>> columnMetadata = columnMetadata(connection, metaData, columns, sensitiveFields, maskAll);
                List<Map<String, Object>> rows = new ArrayList<>();
                while (resultSet.next() && rows.size() < maxRows) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= metaData.getColumnCount(); index++) {
                        String column = columns.get(index - 1);
                        Object value = resultSet.getObject(index);
                        row.put(column, maskAll || shouldMask(column, sensitiveFields) ? "***" : value);
                    }
                    rows.add(row);
                }
                long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
                return new SqlQueryResult(
                    true,
                    datasource.getId(),
                    datasource.getName(),
                    datasource.getToolName(),
                    datasource.getEnvironment(),
                    originalSql,
                    sql,
                    timeoutSeconds,
                    maxRows,
                    columns,
                    columnMetadata,
                    rows,
                    rows.size(),
                    rows.size() >= maxRows,
                    durationMs,
                    purpose,
                    sourceTaskId,
                    null
                );
            }
        }
    }

    private void assertExecutionCapability(SqlDatasourceConfig datasource) {
        if (datasource.getCapabilitiesJson() == null || datasource.getCapabilitiesJson().isBlank()) {
            log.warn("MCP SQL datasource has no protocol capabilities configured; allowing legacy execution: datasourceId={}, datasourceName={}, tool={}",
                datasource.getId(), datasource.getName(), datasource.getToolName());
            return;
        }
        Set<String> capabilities;
        try {
            capabilities = objectMapper.readValue(datasource.getCapabilitiesJson(), new TypeReference<List<String>>() {}).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        } catch (Exception ex) {
            throw new IllegalArgumentException("SQL datasource capabilities config is invalid");
        }
        if (capabilities.stream().noneMatch(value -> value.equals("sql_query_execute")
            || value.equals("sql_exec")
            || value.equals("sql")
            || value.equals("jdbc"))) {
            throw new IllegalArgumentException("SQL datasource does not declare SQL query execution capability: " + datasource.getToolName());
        }
    }

    private ProbeQueryResult executeProbeQuery(Statement statement) throws Exception {
        List<String> probes = List.of("SELECT 1", "SELECT 1 FROM DUAL");
        Exception lastError = null;
        for (String sql : probes) {
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                Object value = resultSet.next() ? resultSet.getObject(1) : null;
                return new ProbeQueryResult(sql, value);
            } catch (Exception ex) {
                lastError = ex;
            }
        }
        throw lastError == null ? new IllegalStateException("No SQL probe query configured") : lastError;
    }

    private void logSqlResult(SqlQueryResult result) {
        if (result == null) {
            return;
        }
        Map<String, Object> rowPreview = new LinkedHashMap<>();
        rowPreview.put("columns", result.columns());
        rowPreview.put("rowCount", result.rowCount());
        rowPreview.put("possiblyTruncated", result.possiblyTruncated());
        rowPreview.put("rows", result.rows() == null ? List.of() : result.rows().stream().limit(LOG_ROWS_LIMIT).toList());
        String message = "MCP SQL query execution result: success={}, datasourceId={}, datasourceName={}, tool={}, env={}, timeoutSeconds={}, maxRows={}, rowCount={}, durationMs={}, sql={}, output={}, error={}";
        if (result.success()) {
            log.info(message,
                result.success(), result.datasourceId(), result.datasourceName(), result.toolName(), result.environment(),
                result.timeoutSeconds(), result.maxRows(), result.rowCount(), result.durationMs(),
                truncateSql(result.normalizedSql() == null ? result.sql() : result.normalizedSql()), rowPreview, result.errorMessage());
        } else {
            log.warn(message,
                result.success(), result.datasourceId(), result.datasourceName(), result.toolName(), result.environment(),
                result.timeoutSeconds(), result.maxRows(), result.rowCount(), result.durationMs(),
                truncateSql(result.normalizedSql() == null ? result.sql() : result.normalizedSql()), rowPreview, result.errorMessage());
        }
    }

    private String truncateSql(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace("\r", "\\r").replace("\n", "\\n");
        if (normalized.length() <= LOG_SQL_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, LOG_SQL_LIMIT) + "...<truncated>";
    }

    private String redactJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return jdbcUrl;
        }
        return jdbcUrl.replaceAll("(?i)(password|pwd)=([^;&]+)", "$1=***")
            .replaceAll("(?i)(user|username)=([^;&]+)", "$1=***");
    }

    private record ProbeQueryResult(String sql, Object value) {
    }

    private List<String> columns(ResultSetMetaData metaData) throws Exception {
        List<String> columns = new ArrayList<>();
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
            String label = metaData.getColumnLabel(index);
            columns.add(label == null || label.isBlank() ? metaData.getColumnName(index) : label);
        }
        return columns;
    }

    private List<Map<String, Object>> columnMetadata(Connection connection, ResultSetMetaData metaData,
                                                     List<String> labels, Set<String> sensitiveFields,
                                                     boolean maskAll) throws Exception {
        List<Map<String, Object>> columns = new ArrayList<>();
        DatabaseMetaData databaseMetaData = connection.getMetaData();
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
            String name = metaData.getColumnName(index);
            String label = labels.get(index - 1);
            String tableName = blankToNull(metaData.getTableName(index));
            String schemaName = blankToNull(metaData.getSchemaName(index));
            String catalogName = blankToNull(metaData.getCatalogName(index));
            boolean masked = maskAll || shouldMask(label, sensitiveFields) || shouldMask(name, sensitiveFields);
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", firstText(name, label));
            column.put("label", label);
            column.put("comment", columnComment(databaseMetaData, catalogName, schemaName, tableName, name));
            column.put("tableName", tableName);
            column.put("schemaName", schemaName);
            column.put("catalogName", catalogName);
            column.put("jdbcType", metaData.getColumnType(index));
            column.put("typeName", metaData.getColumnTypeName(index));
            column.put("displaySize", metaData.getColumnDisplaySize(index));
            column.put("nullable", metaData.isNullable(index) != ResultSetMetaData.columnNoNulls);
            column.put("masked", masked);
            columns.add(column);
        }
        return columns;
    }

    private String columnComment(DatabaseMetaData databaseMetaData, String catalogName, String schemaName,
                                 String tableName, String columnName) {
        if (databaseMetaData == null || tableName == null || columnName == null || columnName.isBlank()) {
            return null;
        }
        String[] schemaCandidates = schemaName == null ? new String[] { null } : new String[] { schemaName, null };
        String[] tableCandidates = new String[] { tableName, tableName.toUpperCase(Locale.ROOT), tableName.toLowerCase(Locale.ROOT) };
        String[] columnCandidates = new String[] { columnName, columnName.toUpperCase(Locale.ROOT), columnName.toLowerCase(Locale.ROOT) };
        for (String schema : schemaCandidates) {
            for (String table : tableCandidates) {
                for (String column : columnCandidates) {
                    try (ResultSet columns = databaseMetaData.getColumns(catalogName, schema, table, column)) {
                        if (columns.next()) {
                            String remarks = columns.getString("REMARKS");
                            if (remarks != null && !remarks.isBlank()) {
                                return remarks;
                            }
                        }
                    } catch (Exception ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private boolean shouldMask(String column, Set<String> sensitiveFields) {
        String normalized = column == null ? "" : column.toLowerCase(Locale.ROOT);
        return sensitiveFields.contains(normalized)
            || normalized.contains("password")
            || normalized.contains("token")
            || normalized.contains("secret")
            || normalized.contains("phone")
            || normalized.contains("mobile")
            || normalized.contains("id_card");
    }

    private Set<String> sensitiveFields(SqlDatasourceConfig datasource) {
        Set<String> fields = new LinkedHashSet<>();
        fields.add("password");
        fields.add("token");
        fields.add("secret");
        fields.add("mobile");
        fields.add("phone");
        fields.add("id_card");
        String json = datasource.getSensitiveFieldsJson();
        if (json == null || json.isBlank()) {
            return fields;
        }
        try {
            List<String> configured = objectMapper.readValue(json, new TypeReference<>() {});
            configured.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .forEach(fields::add);
        } catch (Exception ignored) {
            return fields;
        }
        return fields;
    }

    private boolean matchesSensitiveTable(SqlDatasourceConfig datasource, String sql) {
        String json = datasource.getSensitiveTablesJson();
        if (json == null || json.isBlank() || sql == null) {
            return false;
        }
        try {
            List<String> tables = objectMapper.readValue(json, new TypeReference<>() {});
            String normalizedSql = sql.toLowerCase(Locale.ROOT);
            return tables.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(table -> normalizedSql.matches("(?s).*\\b" + Pattern.quote(table) + "\\b.*"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void validateAllowedTables(SqlDatasourceConfig datasource, String sql) {
        String json = datasource.getAllowedTablesJson();
        if (json == null || json.isBlank() || sql == null) {
            return;
        }
        try {
            List<String> configured = objectMapper.readValue(json, new TypeReference<>() {});
            Set<String> allowed = configured.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
            if (allowed.isEmpty()) {
                return;
            }
            Pattern tablePattern = Pattern.compile("(?i)\\b(from|join)\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?)");
            Matcher matcher = tablePattern.matcher(sql);
            while (matcher.find()) {
                String table = matcher.group(2).toLowerCase(Locale.ROOT);
                if (!allowed.contains(table)) {
                    throw new IllegalArgumentException("SQL table is not allowed for this datasource: " + table);
                }
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("allowedTables config is invalid");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private int normalizeTimeout(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, Math.min(number.intValue(), 60));
        }
        return Math.max(1, Math.min(fallback, 60));
    }

    private int normalizeMaxRows(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, Math.min(number.intValue(), 5000));
        }
        return Math.max(1, Math.min(fallback, 5000));
    }

    private String text(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
