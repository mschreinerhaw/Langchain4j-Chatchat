package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.template.AgentRuntimeTemplateDsl;
import com.chatchat.mcpserver.template.AgentRuntimeTemplateDsl.TemplatePlan;
import com.chatchat.mcpserver.template.AgentRuntimeTemplateDsl.TemplateStep;
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
    private final SqlTemplateService templateService;
    private final InvocationAuditService auditService;
    private final DynamicDateParamService dynamicDateParamService;

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
            diagnostics.put("templateMetadata", templateService.executionMetadata(requestedTemplate(request)));
            String script = resolveScript(request, datasource);
            TemplatePlan plan = scriptPlan(requestedTemplate(request), script, maxRowsPerStatement);
            diagnostics.put("statementCount", plan.steps().size());
            diagnostics.put("templateDsl", AgentRuntimeTemplateDsl.metadata(plan));
            diagnostics.put("analysisPolicy", plan.analysisPolicy());
            diagnostics.put("executionMode", plan.executionMode());
            diagnostics.put("continueOnError", plan.continueOnError());
            SqlDatasourceConfig currentDatasource = datasource;
            plan.steps().forEach(step -> queryExecuteService.validateAllowedTables(currentDatasource, step.command()));

            SqlScriptResult result = executeStatements(
                datasource,
                script,
                plan,
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

    private SqlScriptResult executeStatements(SqlDatasourceConfig datasource, String originalScript, TemplatePlan plan,
                                              int timeoutSeconds, int maxRowsPerStatement, String purpose,
                                              String sourceTaskId, long startedAt, Map<String, Object> diagnostics)
        throws Exception {
        List<SqlScriptStatementResult> results = new ArrayList<>();
        List<TemplateStep> statements = plan.steps();
        try (Connection connection = queryExecuteService.openConnection(datasource);
             Statement statement = connection.createStatement()) {
            connection.setReadOnly(true);
            queryExecuteService.applyDefaultCatalog(connection, datasource, diagnostics);
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxRowsPerStatement);
            diagnostics.put("connection", queryExecuteService.connectionDiagnostics(connection, datasource));
            Set<String> sensitiveFields = queryExecuteService.sensitiveFields(datasource);
            for (int index = 0; index < statements.size(); index++) {
                TemplateStep step = statements.get(index);
                String sql = step.command();
                long stepStartedAt = System.currentTimeMillis();
                log.info("MCP execution detail: executionType=SQL_SCRIPT_STEP, datasourceId={}, datasourceName={}, statementIndex={}, stepCode={}, stepName={}, timeoutSeconds={}, maxRows={}, sql={}",
                    datasource.getId(), datasource.getName(), index + 1, step.stepCode(), step.stepName(),
                    timeoutSeconds, maxRowsPerStatement, sql);
                try {
                    results.add(executeSingle(connection, statement, datasource, step, index + 1, maxRowsPerStatement, sensitiveFields, stepStartedAt));
                } catch (Exception ex) {
                    long stepDurationMs = Math.max(0, System.currentTimeMillis() - stepStartedAt);
                    results.add(new SqlScriptStatementResult(
                        index + 1,
                        step.stepCode(),
                        step.stepName(),
                        step.stepType(),
                        step.required(),
                        step.analysisHint(),
                        false,
                        sql,
                        List.of(),
                        List.of(),
                        List.of(),
                        0,
                        false,
                        stepDurationMs,
                        ex.getMessage(),
                        stepDiagnostics(step, Map.of("errorType", ex.getClass().getSimpleName()))
                    ));
                    if (!plan.continueOnError()) {
                        break;
                    }
                }
            }
        }
        long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
        boolean requiredFailure = results.stream().anyMatch(result -> result.required() && !result.success());
        boolean success = results.size() == statements.size() && !requiredFailure
            && (plan.continueOnError() || results.stream().allMatch(SqlScriptStatementResult::success));
        String error = results.stream()
            .filter(result -> result.required() && !result.success())
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
                                                   TemplateStep step, int index, int maxRowsPerStatement,
                                                   Set<String> sensitiveFields, long startedAt) throws Exception {
        String sql = step.command();
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
            stepDiagnostics.putAll(stepDiagnostics(step, Map.of()));
            return new SqlScriptStatementResult(
                index,
                step.stepCode(),
                step.stepName(),
                step.stepType(),
                step.required(),
                step.analysisHint(),
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

    private TemplatePlan scriptPlan(String templateCode, String script, int maxRowsPerStatement) {
        TemplatePlan dsl = AgentRuntimeTemplateDsl.parse(script, templateCode, "DB_SQL", "SQL");
        if (dsl != null) {
            List<TemplateStep> normalized = dsl.steps().stream()
                .map(step -> new TemplateStep(
                    step.stepCode(),
                    step.stepName(),
                    step.stepType(),
                    step.order(),
                    safetyService.validateAndNormalizeScriptStatement(step.command(), maxRowsPerStatement),
                    step.required(),
                    step.timeoutSeconds(),
                    step.analysisHint()
                ))
                .toList();
            assertStatementLimit(normalized.size());
            return new TemplatePlan(
                dsl.templateCode(),
                dsl.templateName(),
                dsl.templateType(),
                dsl.targetType(),
                dsl.executionMode(),
                dsl.continueOnError(),
                dsl.riskLevel(),
                dsl.timeoutSeconds(),
                dsl.analysisPolicy(),
                normalized,
                true
            );
        }
        List<String> statements = splitStatements(script).stream()
            .map(value -> safetyService.validateAndNormalizeScriptStatement(value, maxRowsPerStatement))
            .toList();
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("SQL script cannot be empty");
        }
        assertStatementLimit(statements.size());
        List<TemplateStep> steps = new ArrayList<>();
        for (int index = 0; index < statements.size(); index++) {
            steps.add(AgentRuntimeTemplateDsl.singleStep(index + 1, "SQL", statements.get(index)));
        }
        return new TemplatePlan(
            templateCode,
            templateCode,
            "DB_SQL",
            null,
            "SEQUENTIAL",
            false,
            null,
            null,
            Map.of(),
            steps,
            false
        );
    }

    private void assertStatementLimit(int statementCount) {
        if (statementCount > MAX_STATEMENTS) {
            throw new IllegalArgumentException("SQL script can contain at most " + MAX_STATEMENTS + " statements");
        }
    }

    private String resolveScript(Map<String, Object> request, SqlDatasourceConfig datasource) {
        String script = firstText(text(request, "script"), text(request, "sql"));
        String template = requestedTemplate(request);
        if (script != null && template != null) {
            throw new IllegalArgumentException("Use either script/sql or SQL template, not both");
        }
        if (script != null && !script.isBlank()) {
            return dynamicDateParamService.resolveSqlPlaceholders(script, datasource);
        }
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("Either script/sql or template is required");
        }
        Map<String, Object> parameters = queryExecuteService.enrichTemplateParameters(
            mapValue(request.get("parameters")),
            datasource,
            request
        );
        request.put("parameters", parameters);
        return dynamicDateParamService.resolveSqlPlaceholders(
            templateService.render(template, parameters, datasource, request),
            datasource
        );
    }

    private String requestedTemplate(Map<String, Object> request) {
        return firstText(text(request, "template"), text(request, "templateId"), text(request, "template_id"));
    }

    private Map<String, Object> stepDiagnostics(TemplateStep step, Map<String, Object> values) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("stepCode", step.stepCode());
        diagnostics.put("stepName", step.stepName());
        diagnostics.put("stepType", step.stepType());
        diagnostics.put("required", step.required());
        diagnostics.put("analysisHint", step.analysisHint());
        diagnostics.putAll(values);
        return diagnostics;
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
                item.put("stepCode", statement.stepCode());
                item.put("stepName", statement.stepName());
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
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
}
