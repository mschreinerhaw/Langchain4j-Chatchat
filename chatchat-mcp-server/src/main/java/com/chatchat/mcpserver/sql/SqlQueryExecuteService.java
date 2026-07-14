package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.tools.builtin.DatabaseToolProperties;
import com.chatchat.tools.builtin.DynamicJdbcDriverLoader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
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
    private final MetadataResolverService metadataResolverService;
    private final InvocationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final DynamicJdbcDriverLoader driverLoader;
    private final DynamicDateParamService dynamicDateParamService;
    private final DatabaseToolProperties databaseToolProperties;

    public SqlQueryResult execute(Map<String, Object> arguments) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> request = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        SqlDatasourceConfig datasource = null;
        String normalizedSql = null;
        SqlQueryResult result;
        int timeoutSeconds = 30;
        int maxRows = configuredDefaultMaxRows();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        try {
            datasource = datasourceConfigService.getEnabled(text(request, "datasourceId"));
            assertExecutionCapability(datasource);
            timeoutSeconds = normalizeTimeout(request.get("timeoutSeconds"), datasource.getDefaultTimeoutSeconds());
            maxRows = normalizeMaxRows(request.get("maxRows"), datasource.getDefaultMaxRows());
            diagnostics.putAll(baseDiagnostics(datasource, request));
            diagnostics.put("templateMetadata", templateService.executionMetadata(requestedTemplate(request)));
            String sql = resolveSql(request, datasource);
            diagnostics.put("templateParameters", new LinkedHashMap<>(mapValue(request.get("parameters"))));
            diagnostics.put("executionContext", contextFrom(request));
            diagnostics.put("tableResolution", request.get("tableResolution"));
            String executableSql = normalizeSingleStatementSql(sql);
            normalizedSql = safetyService.validateAndNormalizeScriptStatement(executableSql, maxRows);
            validateAllowedTables(datasource, normalizedSql);
            log.info("MCP execution detail: executionType=SQL_QUERY, datasourceId={}, datasourceName={}, env={}, tool={}, templateId={}, timeoutSeconds={}, maxRows={}, purpose={}, sourceTaskId={}, sql={}",
                datasource.getId(), datasource.getName(), datasource.getEnvironment(), datasource.getToolName(),
                requestedTemplate(request), timeoutSeconds, maxRows, text(request, "purpose"), text(request, "sourceTaskId"),
                truncateSql(normalizedSql));
            result = query(datasource, executableSql, normalizedSql, timeoutSeconds, maxRows,
                text(request, "purpose"), text(request, "sourceTaskId"), startedAt, diagnostics);
            result = attemptSelfHealingIfNeeded(datasource, request, result, timeoutSeconds, maxRows,
                text(request, "purpose"), text(request, "sourceTaskId"), startedAt);
        } catch (Exception ex) {
            long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
            diagnostics.put("templateParameters", new LinkedHashMap<>(mapValue(request.get("parameters"))));
            diagnostics.put("executionContext", contextFrom(request));
            diagnostics.put("tableResolution", request.get("tableResolution"));
            diagnostics.put("failureStage", normalizedSql == null ? "prepare" : "execute");
            diagnostics.put("errorType", ex.getClass().getSimpleName());
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
                ex.getMessage(),
                diagnostics
            );
            log.warn("MCP SQL query execution failed before/while running: datasourceId={}, durationMs={}, error={}, sql={}",
                result.datasourceId(), durationMs, ex.getMessage(), truncateSql(normalizedSql));
        }
        recordSuccessfulMetadataUsage(datasource, request, result);
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
            try (Connection connection = openConnection(datasource);
                 Statement statement = connection.createStatement()) {
                connection.setReadOnly(true);
                statement.setQueryTimeout(timeoutSeconds);
                ProbeQueryResult probe = executeProbeQuery(statement);
                List<String> availableDatabases = availableDatabases(connection);
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
                    List.of(diagnosticMap("probe", probe.value())),
                    1,
                    false,
                    durationMs,
                    "asset_connection_test",
                    "asset-center",
                    null,
                    diagnosticMap(
                        "connection", connectionDiagnostics(connection, datasource),
                        "probeSql", probe.sql(),
                        "availableDatabases", availableDatabases,
                        "metadataScopeOptions", availableDatabases
                    )
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
                ex.getMessage(),
                diagnosticMap("errorType", ex.getClass().getSimpleName())
            );
            logSqlResult(result);
            return result;
        }
    }

    String normalizeSingleStatementSql(String sql) {
        List<String> statements = SqlStatementExtractor.splitStatements(sql);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("SQL cannot be empty");
        }
        if (statements.size() > 1) {
            throw new IllegalArgumentException("Multiple SQL statements should be executed with sql_script_execute");
        }
        return statements.get(0);
    }

    private String resolveSql(Map<String, Object> request, SqlDatasourceConfig datasource) {
        String sql = text(request, "sql");
        String template = requestedTemplate(request);
        if (sql != null && template != null) {
            throw new IllegalArgumentException("Use either sql or SQL template, not both");
        }
        if (sql != null && !sql.isBlank()) {
            return dynamicDateParamService.resolveSqlPlaceholders(sql, datasource);
        }
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("Either sql or template is required");
        }
        Map<String, Object> parameters = resolveMetadataTableParameters(
            template,
            mapValue(request.get("parameters")),
            datasource,
            request
        );
        parameters = enrichTemplateParameters(parameters, datasource, request);
        request.put("parameters", parameters);
        return dynamicDateParamService.resolveSqlPlaceholders(
            templateService.render(template, parameters, datasource, request),
            datasource
        );
    }

    private String requestedTemplate(Map<String, Object> request) {
        return firstText(text(request, "template"), text(request, "templateId"), text(request, "template_id"));
    }

    private Map<String, Object> resolveMetadataTableParameters(String template, Map<String, Object> parameters,
                                                               SqlDatasourceConfig datasource,
                                                               Map<String, Object> request) {
        if (!isTableMetadataTemplate(template)) {
            return parameters;
        }
        String tableName = firstText(
            text(parameters, "tableName"),
            text(parameters, "table_name"),
            text(parameters, "table")
        );
        if (tableName == null) {
            return parameters;
        }
        String preferredSchema = firstText(
            text(parameters, "schemaName"),
            text(parameters, "databaseName"),
            text(parameters, "schema"),
            text(parameters, "database")
        );
        Map<String, Object> values = new LinkedHashMap<>(parameters);
        SqlTableNameParser.QualifiedTable qualifiedTable = SqlTableNameParser.parse(tableName, preferredSchema);
        String resolvedInputTable = firstText(qualifiedTable.table(), tableName);
        String resolvedInputDatabase = firstText(qualifiedTable.database(), preferredSchema);
        String resolvedInputSchema = firstText(qualifiedTable.schema(), resolvedInputDatabase);
        if (resolvedInputTable != null && !resolvedInputTable.equals(tableName)) {
            values.put("tableName", resolvedInputTable);
            values.put("table_name", resolvedInputTable);
        }
        if (resolvedInputSchema != null) {
            values.putIfAbsent("schemaName", resolvedInputSchema);
            values.putIfAbsent("schema", resolvedInputSchema);
        }
        if (resolvedInputDatabase != null) {
            values.putIfAbsent("databaseName", resolvedInputDatabase);
            values.putIfAbsent("database", resolvedInputDatabase);
        }
        TableResolution resolution = metadataResolverService.resolveTable(datasource, resolvedInputTable, firstText(resolvedInputSchema, resolvedInputDatabase));
        request.put("tableResolution", resolution.toDiagnostic());
        if (resolution.selectedSchema() != null) {
            values.put("schemaName", resolution.selectedSchema());
            values.put("schema", resolution.selectedSchema());
            values.put("databaseName", firstText(resolvedInputDatabase, resolution.selectedSchema()));
            values.put("database", firstText(resolvedInputDatabase, resolution.selectedSchema()));
            values.put("tableName", resolution.selectedTable());
        }
        return values;
    }

    private boolean isTableMetadataTemplate(String template) {
        return template != null && template.trim().toUpperCase(Locale.ROOT).endsWith("_TABLE_METADATA");
    }

    private SqlQueryResult attemptSelfHealingIfNeeded(SqlDatasourceConfig datasource, Map<String, Object> request,
                                                      SqlQueryResult result, int timeoutSeconds, int maxRows,
                                                      String purpose, String sourceTaskId, long startedAt)
        throws Exception {
        if (!shouldRepairMetadataEmptyResult(request, result)) {
            return result;
        }
        Map<String, Object> parameters = new LinkedHashMap<>(mapValue(request.get("parameters")));
        String usedSchema = firstText(
            text(parameters, "schemaName"),
            text(parameters, "databaseName"),
            text(parameters, "schema"),
            text(parameters, "database")
        );
        List<TableLocation> alternatives = alternativeSchemas(request, usedSchema);
        QueryFeedback feedback = new QueryFeedback(
            true,
            usedSchema,
            alternatives.stream().map(TableLocation::database).distinct().toList(),
            alternatives.isEmpty() ? "STOP" : "RETRY_ALTERNATE_SCHEMA",
            alternatives.isEmpty() ? "no alternate metadata candidate" : "information_schema.columns returned zero rows"
        );
        result.diagnostics().put("queryFeedback", feedback.toDiagnostic());
        if (alternatives.isEmpty()) {
            return result;
        }

        TableLocation alternative = alternatives.get(0);
        Map<String, Object> repairedParameters = new LinkedHashMap<>(parameters);
        repairedParameters.put("schemaName", alternative.database());
        repairedParameters.put("databaseName", alternative.database());
        repairedParameters.put("schema", alternative.database());
        repairedParameters.put("database", alternative.database());
        repairedParameters.put("tableName", alternative.table());
        Map<String, Object> repairedRequest = new LinkedHashMap<>(request);
        repairedRequest.put("parameters", repairedParameters);
        repairedRequest.put("selfHealingAttempted", true);

        Map<String, Object> repairDiagnostics = baseDiagnostics(datasource, repairedRequest);
        repairDiagnostics.put("templateMetadata", templateService.executionMetadata(requestedTemplate(repairedRequest)));
        repairDiagnostics.put("templateParameters", new LinkedHashMap<>(repairedParameters));
        repairDiagnostics.put("executionContext", contextFrom(repairedRequest));
        repairDiagnostics.put("tableResolution", repairedRequest.get("tableResolution"));
        repairDiagnostics.put("queryFeedback", feedback.toDiagnostic());
        repairDiagnostics.put("selfHealing", diagnosticMap(
            "schemaVersion", "sql_self_healing.v1",
            "strategy", "retry_alternate_schema",
            "previousSchema", usedSchema,
            "retrySchema", alternative.database(),
            "retryTable", alternative.table()
        ));

        String repairedSql = templateService.render(requestedTemplate(repairedRequest), repairedParameters, datasource, repairedRequest);
        String repairedNormalizedSql = safetyService.validateAndNormalize(repairedSql, maxRows);
        validateAllowedTables(datasource, repairedNormalizedSql);
        log.info("MCP SQL self-healing retry: datasourceId={}, table={}, previousSchema={}, retrySchema={}, sql={}",
            datasource.getId(), alternative.table(), usedSchema, alternative.database(), truncateSql(repairedNormalizedSql));
        SqlQueryResult repaired = query(datasource, repairedSql, repairedNormalizedSql, timeoutSeconds, maxRows,
            purpose, sourceTaskId, startedAt, repairDiagnostics);
        if (repaired.success() && repaired.rowCount() > 0) {
            request.put("parameters", repairedParameters);
            request.put("selfHealingResult", repairDiagnostics.get("selfHealing"));
            return repaired;
        }
        result.diagnostics().put("selfHealingResult", diagnosticMap(
            "attempted", true,
            "retrySchema", alternative.database(),
            "retryRowCount", repaired.rowCount(),
            "retryError", repaired.errorMessage()
        ));
        return result;
    }

    private boolean shouldRepairMetadataEmptyResult(Map<String, Object> request, SqlQueryResult result) {
        if (result == null || !result.success() || result.rowCount() != 0 || Boolean.TRUE.equals(request.get("selfHealingAttempted"))) {
            return false;
        }
        String template = requestedTemplate(request);
        String sql = result.normalizedSql() == null ? result.sql() : result.normalizedSql();
        return isTableMetadataTemplate(template)
            && sql != null
            && sql.toLowerCase(Locale.ROOT).contains("information_schema.columns");
    }

    private List<TableLocation> alternativeSchemas(Map<String, Object> request, String usedSchema) {
        Object resolution = request.get("tableResolution");
        if (!(resolution instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object candidatesValue = map.get("candidates");
        if (!(candidatesValue instanceof List<?> candidates)) {
            return List.of();
        }
        String normalizedUsedSchema = normalizeIdentifier(usedSchema);
        List<TableLocation> values = new ArrayList<>();
        for (Object item : candidates) {
            if (!(item instanceof Map<?, ?> candidate)) {
                continue;
            }
            String datasourceId = stringValue(candidate.get("datasourceId"));
            String database = stringValue(candidate.get("database"));
            String schema = stringValue(candidate.get("schema"));
            String table = stringValue(candidate.get("table"));
            String tableType = stringValue(candidate.get("tableType"));
            double score = doubleValue(candidate.get("score"));
            if (database != null && !database.equalsIgnoreCase(String.valueOf(usedSchema))) {
                values.add(new TableLocation(datasourceId, database, schema, table, tableType, null, score));
            }
        }
        return values.stream()
            .filter(location -> normalizedUsedSchema == null
                || !normalizedUsedSchema.equals(normalizeIdentifier(location.database())))
            .sorted(java.util.Comparator.comparingDouble(TableLocation::score).reversed())
            .toList();
    }

    private void recordSuccessfulMetadataUsage(SqlDatasourceConfig datasource, Map<String, Object> request,
                                               SqlQueryResult result) {
        if (datasource == null || result == null || !result.success() || result.rowCount() <= 0 || !isTableMetadataTemplate(requestedTemplate(request))) {
            return;
        }
        Map<String, Object> parameters = mapValue(request.get("parameters"));
        String table = firstText(text(parameters, "tableName"), text(parameters, "table_name"), text(parameters, "table"));
        String schema = firstText(text(parameters, "schemaName"), text(parameters, "databaseName"), text(parameters, "schema"), text(parameters, "database"));
        if (table == null || schema == null) {
            return;
        }
        metadataResolverService.recordUsage(new TableLocation(datasource.getId(), schema, schema, table, "BASE TABLE", null, 1.0));
    }

    private SqlQueryResult query(SqlDatasourceConfig datasource, String originalSql, String sql,
                                 int timeoutSeconds, int maxRows, String purpose,
                                 String sourceTaskId, long startedAt, Map<String, Object> diagnostics) throws Exception {
        try (Connection connection = openConnection(datasource);
             Statement statement = connection.createStatement()) {
            connection.setReadOnly(true);
            applyDefaultCatalog(connection, datasource, diagnostics);
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxRows);
            diagnostics.put("connection", connectionDiagnostics(connection, datasource));
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
                diagnostics.put("rowCount", rows.size());
                diagnostics.put("possiblyTruncated", rows.size() >= maxRows);
                addZeroRowDiagnostics(diagnostics, datasource, sql, rows.size());
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
                    null,
                    diagnostics
                );
            }
        }
    }

    void assertExecutionCapability(SqlDatasourceConfig datasource) {
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

    private List<String> availableDatabases(Connection connection) {
        if (connection == null) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        try {
            addIfText(values, connection.getCatalog());
        } catch (Exception ignored) {
            // Ignore driver-specific catalog access failures during lightweight connection tests.
        }
        try {
            addIfText(values, connection.getSchema());
        } catch (Exception ignored) {
            // Ignore driver-specific schema access failures during lightweight connection tests.
        }
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet catalogs = metaData.getCatalogs()) {
                while (catalogs.next() && values.size() < 200) {
                    addIfText(values, catalogs.getString(1));
                }
            }
            try (ResultSet schemas = metaData.getSchemas()) {
                while (schemas.next() && values.size() < 200) {
                    addIfText(values, schemas.getString("TABLE_SCHEM"));
                }
            }
        } catch (Exception ex) {
            log.debug("SQL datasource database/schema enumeration skipped: {}", ex.getMessage());
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private void addIfText(Set<String> values, String value) {
        if (values != null && value != null && !value.isBlank()) {
            values.add(value.trim());
        }
    }

    Connection openConnection(SqlDatasourceConfig datasource) throws Exception {
        DataSource dataSource = driverLoader.createDataSource(
            datasource.getJdbcUrl(),
            datasource.getUsername(),
            datasource.getPassword(),
            datasource.getDriverClass(),
            resolvedDatabaseType(datasource)
        );
        return dataSource.getConnection();
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
        String message = "MCP SQL query execution result: success={}, datasourceId={}, datasourceName={}, tool={}, env={}, timeoutSeconds={}, maxRows={}, rowCount={}, durationMs={}, sql={}, output={}, diagnostics={}, error={}";
        if (result.success()) {
            log.info(message,
                result.success(), result.datasourceId(), result.datasourceName(), result.toolName(), result.environment(),
                result.timeoutSeconds(), result.maxRows(), result.rowCount(), result.durationMs(),
                truncateSql(result.normalizedSql() == null ? result.sql() : result.normalizedSql()), rowPreview,
                result.diagnostics(), result.errorMessage());
        } else {
            log.warn(message,
                result.success(), result.datasourceId(), result.datasourceName(), result.toolName(), result.environment(),
                result.timeoutSeconds(), result.maxRows(), result.rowCount(), result.durationMs(),
                truncateSql(result.normalizedSql() == null ? result.sql() : result.normalizedSql()), rowPreview,
                result.diagnostics(), result.errorMessage());
        }
    }

    Map<String, Object> baseDiagnostics(SqlDatasourceConfig datasource, Map<String, Object> request) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("schemaVersion", "sql_query_diagnostics.v1");
        diagnostics.put("templateId", requestedTemplate(request));
        diagnostics.put("templateParameters", new LinkedHashMap<>(mapValue(request.get("parameters"))));
        diagnostics.put("executionContext", contextFrom(request));
        diagnostics.put("tableResolution", request == null ? null : request.get("tableResolution"));
        diagnostics.put("routedTarget", request == null ? null : request.get("routedTarget"));
        diagnostics.put("routingDecisionLog", request == null ? null : request.get("routingDecisionLog"));
        diagnostics.put("datasource", diagnosticMap(
            "id", datasource.getId(),
            "name", datasource.getName(),
            "toolName", datasource.getToolName(),
            "environment", datasource.getEnvironment(),
            "databaseType", resolvedDatabaseType(datasource),
            "jdbcDatabase", firstText(defaultSchemaName(datasource, request), "")
        ));
        return diagnostics;
    }

    Map<String, Object> contextFrom(Map<String, Object> request) {
        Map<String, Object> context = new LinkedHashMap<>();
        for (String key : List.of("executionContext", "mcpExecutionContext")) {
            Object value = request == null ? null : request.get(key);
            if (value instanceof Map<?, ?> map) {
                map.forEach((k, v) -> context.put(String.valueOf(k), v));
            }
        }
        Object parameters = request == null ? null : request.get("parameters");
        if (parameters instanceof Map<?, ?> map) {
            for (String key : List.of("database", "schema", "schemaName", "databaseName", "tableName", "table_name")) {
                Object value = map.get(key);
                if (value != null) {
                    context.putIfAbsent(key, value);
                }
            }
        }
        return context;
    }

    Map<String, Object> enrichTemplateParameters(Map<String, Object> parameters, SqlDatasourceConfig datasource,
                                                 Map<String, Object> request) {
        Map<String, Object> values = new LinkedHashMap<>(parameters == null ? Map.of() : parameters);
        String schemaName = firstText(
            text(values, "schemaName"),
            text(values, "schema"),
            text(values, "databaseName"),
            text(values, "database"),
            text(contextFrom(request), "schemaName"),
            text(contextFrom(request), "schema"),
            text(contextFrom(request), "databaseName"),
            text(contextFrom(request), "database"),
            defaultSchemaName(datasource, request)
        );
        if (schemaName != null) {
            values.putIfAbsent("schemaName", schemaName);
            values.putIfAbsent("databaseName", schemaName);
            values.putIfAbsent("schema", schemaName);
            values.putIfAbsent("database", schemaName);
        }
        return dynamicDateParamService.enrichParameters(values, datasource, null);
    }

    private String defaultSchemaName(SqlDatasourceConfig datasource, Map<String, Object> request) {
        String explicit = firstText(
            text(request, "schemaName"),
            text(request, "schema"),
            text(request, "databaseName"),
            text(request, "database")
        );
        if (explicit != null) {
            return explicit;
        }
        return databaseNameFromJdbcUrl(datasource == null ? null : datasource.getJdbcUrl());
    }

    private String databaseNameFromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        String trimmed = jdbcUrl.trim();
        Matcher parameterMatcher = Pattern.compile("(?i)[;?&](?:databaseName|database)=([^;?&]+)").matcher(trimmed);
        if (parameterMatcher.find()) {
            String value = parameterMatcher.group(1);
            return value == null || value.isBlank() ? null : value.trim();
        }
        Matcher matcher = Pattern.compile("(?i)^jdbc:[^:]+://[^/]+/([^?;]+)").matcher(trimmed);
        if (matcher.find()) {
            String value = matcher.group(1);
            return value == null || value.isBlank() ? null : value.trim();
        }
        return null;
    }

    void applyDefaultCatalog(Connection connection, SqlDatasourceConfig datasource,
                                     Map<String, Object> diagnostics) {
        String datasourceType = resolvedDatabaseType(datasource);
        String schemaName = defaultSchemaName(datasource, null);
        if (connection == null || schemaName == null || !"mysql".equals(datasourceType)) {
            return;
        }
        try {
            connection.setCatalog(schemaName);
            diagnostics.put("appliedCatalog", schemaName);
        } catch (Exception ex) {
            diagnostics.put("appliedCatalogError", ex.getMessage());
        }
    }

    Map<String, Object> connectionDiagnostics(Connection connection, SqlDatasourceConfig datasource) {
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            values.put("catalog", connection.getCatalog());
        } catch (Exception ignored) {
            values.put("catalog", null);
        }
        try {
            values.put("schema", connection.getSchema());
        } catch (Exception ignored) {
            values.put("schema", null);
        }
        values.put("jdbcDatabase", defaultSchemaName(datasource, null));
        values.put("databaseType", resolvedDatabaseType(datasource));
        values.put("jdbcUrl", redactJdbcUrl(datasource.getJdbcUrl()));
        return values;
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

    private void addZeroRowDiagnostics(Map<String, Object> diagnostics, SqlDatasourceConfig datasource,
                                       String sql, int rowCount) {
        if (rowCount != 0 || sql == null) {
            return;
        }
        String normalizedSql = sql.toLowerCase(Locale.ROOT);
        if (!normalizedSql.contains("information_schema.columns")) {
            return;
        }
        diagnostics.put("zeroRowReasonHint", "information_schema.columns returned no rows. Check schemaName/databaseName, tableName case, and table visibility permissions.");
        diagnostics.put("suggestedRepair", diagnosticMap(
            "useExplicitSchemaName", true,
            "schemaName", defaultSchemaName(datasource, null),
            "avoidDatabaseFunctionOnly", true
        ));
    }

    Map<String, Object> diagnosticMap(Object... entries) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            values.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return values;
    }

    String truncateSql(String value) {
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

    List<String> columns(ResultSetMetaData metaData) throws Exception {
        List<String> columns = new ArrayList<>();
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
            String label = metaData.getColumnLabel(index);
            columns.add(label == null || label.isBlank() ? metaData.getColumnName(index) : label);
        }
        return columns;
    }

    List<Map<String, Object>> columnMetadata(Connection connection, ResultSetMetaData metaData,
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

    boolean shouldMask(String column, Set<String> sensitiveFields) {
        String normalized = column == null ? "" : column.toLowerCase(Locale.ROOT);
        return sensitiveFields.contains(normalized)
            || normalized.contains("password")
            || normalized.contains("token")
            || normalized.contains("secret")
            || normalized.contains("phone")
            || normalized.contains("mobile")
            || normalized.contains("id_card");
    }

    Set<String> sensitiveFields(SqlDatasourceConfig datasource) {
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

    boolean matchesSensitiveTable(SqlDatasourceConfig datasource, String sql) {
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

    void validateAllowedTables(SqlDatasourceConfig datasource, String sql) {
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
    Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    int normalizeTimeout(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, Math.min(number.intValue(), 60));
        }
        return Math.max(1, Math.min(fallback, 60));
    }

    int normalizeMaxRows(Object value, int fallback) {
        int configuredMinimum = minRowsLimit();
        int configuredLimit = maxRowsLimit();
        int requested;
        if (value instanceof Number number) {
            requested = number.intValue();
        } else {
            requested = fallback > 0 ? fallback : configuredDefaultMaxRows();
        }
        return Math.max(configuredMinimum, Math.min(requested, configuredLimit));
    }

    private int configuredDefaultMaxRows() {
        int configuredMinimum = minRowsLimit();
        int configuredLimit = maxRowsLimit();
        return Math.max(configuredMinimum, Math.min(databaseToolProperties.getDefaultMaxRows(), configuredLimit));
    }

    int minRowsLimit() {
        return Math.max(1, Math.min(databaseToolProperties.getMinRows(), maxRowsLimit()));
    }

    int maxRowsLimit() {
        return Math.max(1, databaseToolProperties.getMaxRows());
    }

    private String text(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private String stringValue(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private String normalizeIdentifier(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
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
