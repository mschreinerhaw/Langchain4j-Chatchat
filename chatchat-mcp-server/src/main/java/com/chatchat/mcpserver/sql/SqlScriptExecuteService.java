package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqlScriptExecuteService {

    private static final int MAX_STATEMENTS = 10;
    private static final int LOG_ROWS_LIMIT = 10;

    private final SqlDatasourceConfigService datasourceConfigService;
    private final SqlSafetyService safetyService;
    private final SqlQueryExecuteService queryExecuteService;
    private final InvocationAuditService auditService;

    public SqlScriptResult execute(Map<String, Object> arguments) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> request = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        SqlDatasourceConfig datasource = null;
        int timeoutSeconds = 30;
        int maxRowsPerStatement = 1000;
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        try {
            datasource = datasourceConfigService.getEnabled(text(request, "datasourceId"));
            queryExecuteService.assertExecutionCapability(datasource);
            timeoutSeconds = normalizeScriptTimeout(request.get("timeoutSeconds"), datasource.getDefaultTimeoutSeconds());
            maxRowsPerStatement = queryExecuteService.normalizeMaxRows(firstValue(request, "maxRowsPerStatement", "maxRows"), datasource.getDefaultMaxRows());
            diagnostics.putAll(queryExecuteService.baseDiagnostics(datasource, request));
            diagnostics.put("scriptMode", "read_only_multi_result");
            String script = text(request, "script");
            List<String> statements = normalizeStatements(script, maxRowsPerStatement);
            diagnostics.put("statementCount", statements.size());
            SqlDatasourceConfig currentDatasource = datasource;
            statements.forEach(sql -> queryExecuteService.validateAllowedTables(currentDatasource, sql));

            SqlScriptResult result = executeStatements(
                datasource,
                script,
                statements,
                timeoutSeconds,
                maxRowsPerStatement,
                text(request, "purpose"),
                text(request, "sourceTaskId"),
                startedAt,
                diagnostics
            );
            logScriptResult(result);
            auditService.recordSqlScriptCall(datasource, request, result);
            return result;
        } catch (Exception ex) {
            long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
            diagnostics.put("failureStage", "prepare");
            diagnostics.put("errorType", ex.getClass().getSimpleName());
            SqlScriptResult result = new SqlScriptResult(
                false,
                datasource == null ? text(request, "datasourceId") : datasource.getId(),
                datasource == null ? null : datasource.getName(),
                datasource == null ? null : datasource.getToolName(),
                datasource == null ? null : datasource.getEnvironment(),
                text(request, "script"),
                timeoutSeconds,
                maxRowsPerStatement,
                0,
                List.of(),
                durationMs,
                text(request, "purpose"),
                text(request, "sourceTaskId"),
                ex.getMessage(),
                diagnostics
            );
            logScriptResult(result);
            auditService.recordSqlScriptCall(datasource, request, result);
            return result;
        }
    }

    public SqlScriptResult execute(SqlDatasourceConfig datasource, Map<String, Object> arguments) {
        Map<String, Object> request = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        request.put("datasourceId", datasource.getId());
        return execute(request);
    }

    public List<String> extractStatements(String script) {
        return splitStatements(script);
    }

    private SqlScriptResult executeStatements(SqlDatasourceConfig datasource, String originalScript, List<String> statements,
                                              int timeoutSeconds, int maxRowsPerStatement, String purpose,
                                              String sourceTaskId, long startedAt, Map<String, Object> diagnostics)
        throws Exception {
        List<SqlScriptStatementResult> results = new ArrayList<>();
        try (Connection connection = queryExecuteService.openConnection(datasource);
             Statement statement = connection.createStatement()) {
            connection.setReadOnly(true);
            queryExecuteService.applyDefaultCatalog(connection, datasource, diagnostics);
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxRowsPerStatement);
            diagnostics.put("connection", queryExecuteService.connectionDiagnostics(connection, datasource));
            Set<String> sensitiveFields = queryExecuteService.sensitiveFields(datasource);
            for (int index = 0; index < statements.size(); index++) {
                String sql = statements.get(index);
                long stepStartedAt = System.currentTimeMillis();
                try {
                    results.add(executeSingle(connection, statement, datasource, sql, index + 1, maxRowsPerStatement, sensitiveFields, stepStartedAt));
                } catch (Exception ex) {
                    long stepDurationMs = Math.max(0, System.currentTimeMillis() - stepStartedAt);
                    results.add(new SqlScriptStatementResult(
                        index + 1,
                        false,
                        sql,
                        List.of(),
                        List.of(),
                        List.of(),
                        0,
                        false,
                        stepDurationMs,
                        ex.getMessage(),
                        Map.of("errorType", ex.getClass().getSimpleName())
                    ));
                    break;
                }
            }
        }
        long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
        boolean success = results.size() == statements.size() && results.stream().allMatch(SqlScriptStatementResult::success);
        String error = results.stream()
            .filter(result -> !result.success())
            .map(SqlScriptStatementResult::errorMessage)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
        diagnostics.put("successfulStatements", results.stream().filter(SqlScriptStatementResult::success).count());
        diagnostics.put("failedStatementIndex", results.stream()
            .filter(result -> !result.success())
            .map(SqlScriptStatementResult::statementIndex)
            .findFirst()
            .orElse(null));
        return new SqlScriptResult(
            success,
            datasource.getId(),
            datasource.getName(),
            datasource.getToolName(),
            datasource.getEnvironment(),
            originalScript,
            timeoutSeconds,
            maxRowsPerStatement,
            statements.size(),
            results,
            durationMs,
            purpose,
            sourceTaskId,
            error,
            diagnostics
        );
    }

    private SqlScriptStatementResult executeSingle(Connection connection, Statement statement, SqlDatasourceConfig datasource,
                                                   String sql, int index, int maxRowsPerStatement,
                                                   Set<String> sensitiveFields, long startedAt) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            List<String> columns = queryExecuteService.columns(metaData);
            boolean maskAll = queryExecuteService.matchesSensitiveTable(datasource, sql);
            List<Map<String, Object>> columnMetadata = queryExecuteService.columnMetadata(connection, metaData, columns, sensitiveFields, maskAll);
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next() && rows.size() < maxRowsPerStatement) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int columnIndex = 1; columnIndex <= metaData.getColumnCount(); columnIndex++) {
                    String column = columns.get(columnIndex - 1);
                    Object value = resultSet.getObject(columnIndex);
                    row.put(column, maskAll || queryExecuteService.shouldMask(column, sensitiveFields) ? "***" : value);
                }
                rows.add(row);
            }
            Map<String, Object> stepDiagnostics = new LinkedHashMap<>();
            stepDiagnostics.put("rowCount", rows.size());
            stepDiagnostics.put("possiblyTruncated", rows.size() >= maxRowsPerStatement);
            return new SqlScriptStatementResult(
                index,
                true,
                sql,
                columns,
                columnMetadata,
                rows,
                rows.size(),
                rows.size() >= maxRowsPerStatement,
                Math.max(0, System.currentTimeMillis() - startedAt),
                null,
                stepDiagnostics
            );
        }
    }

    private List<String> normalizeStatements(String script, int maxRowsPerStatement) {
        List<String> statements = splitStatements(script).stream()
            .map(value -> safetyService.validateAndNormalizeScriptStatement(value, maxRowsPerStatement))
            .toList();
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("SQL script cannot be empty");
        }
        if (statements.size() > MAX_STATEMENTS) {
            throw new IllegalArgumentException("SQL script can contain at most " + MAX_STATEMENTS + " statements");
        }
        return statements;
    }

    private int normalizeScriptTimeout(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, Math.min(number.intValue(), 300));
        }
        return Math.max(1, Math.min(fallback, 300));
    }

    List<String> splitStatements(String script) {
        return SqlStatementExtractor.splitStatements(script);
    }

    private void logScriptResult(SqlScriptResult result) {
        if (result == null) {
            return;
        }
        List<Map<String, Object>> preview = result.results().stream()
            .map(statement -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("statementIndex", statement.statementIndex());
                item.put("success", statement.success());
                item.put("rowCount", statement.rowCount());
                item.put("rows", statement.rows().stream().limit(LOG_ROWS_LIMIT).toList());
                item.put("error", statement.errorMessage());
                return item;
            })
            .toList();
        log.info("MCP SQL script execution result: success={}, datasourceId={}, datasourceName={}, tool={}, env={}, statementCount={}, durationMs={}, output={}, error={}",
            result.success(), result.datasourceId(), result.datasourceName(), result.toolName(), result.environment(),
            result.statementCount(), result.durationMs(), preview, result.errorMessage());
    }

    private Object firstValue(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String text(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }
}
