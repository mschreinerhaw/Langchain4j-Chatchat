package com.chatchat.mcpserver.tool;

import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.ops.HttpRequestToolResult;
import com.chatchat.mcpserver.ops.LinuxCommandResult;
import com.chatchat.mcpserver.ops.LinuxCommandStepResult;
import com.chatchat.mcpserver.sql.SqlQueryResult;
import com.chatchat.mcpserver.sql.SqlScriptResult;
import com.chatchat.mcpserver.sql.SqlScriptStatementResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class StandardToolExecutionResultFactory {

    public static final String SCHEMA_VERSION = "tool_execution_result.v1";
    public static final int SQL_RESULT_ROW_LIMIT = 50;
    static final int LINUX_AGGREGATE_STREAM_LIMIT = 24_000;
    static final int LINUX_STEP_STREAM_TOTAL_BUDGET = 100_000;

    public Map<String, Object> fromSql(SqlQueryResult result) {
        Map<String, Object> payload = base(
            "sql_query",
            "sql_result.v1",
            "structured",
            result.success(),
            result.durationMs(),
            result.errorMessage()
        );
        payload.put("target", mapOf(
            "type", "database",
            "id", result.datasourceId(),
            "name", result.datasourceName(),
            "toolName", result.toolName(),
            "environment", result.environment()
        ));
        payload.put("operation", mapOf(
            "type", "sql.query",
            "statement", result.normalizedSql() == null ? result.sql() : result.normalizedSql(),
            "timeoutSeconds", result.timeoutSeconds(),
            "purpose", result.purpose(),
            "sourceTaskId", result.sourceTaskId(),
            "diagnostics", result.diagnostics()
        ));
        List<Map<String, Object>> rows = result.rows() == null
            ? List.of()
            : result.rows().stream().limit(SQL_RESULT_ROW_LIMIT).toList();
        Map<String, Object> limits = mapOf(
            "maxRowsRequested", result.maxRows(),
            "maxRowsReturnedToModel", SQL_RESULT_ROW_LIMIT,
            "truncationStrategy", "LIMIT_50"
        );
        payload.put("limits", limits);
        boolean possiblyTruncated = result.possiblyTruncated() || result.rowCount() > rows.size();
        Map<String, Object> data = mapOf(
            "rowCount", result.rowCount(),
            "returnedRowCount", rows.size(),
            "complete", !possiblyTruncated,
            "possiblyTruncated", possiblyTruncated,
            "truncationStrategy", "LIMIT_50",
            "columns", result.columns(),
            "columnMetadata", result.columnMetadata(),
            "governance", sqlOutputGovernance(result, rows.size()),
            "diagnostics", result.diagnostics(),
            "rows", rows
        );
        payload.put("data", data);
        payload.put("execution", execution(
            result.toolName(),
            result.durationMs(),
            List.of(step(
                1,
                "sql",
                mapOf(
                    "statement", result.normalizedSql() == null ? result.sql() : result.normalizedSql(),
                    "timeoutSeconds", result.timeoutSeconds(),
                    "purpose", result.purpose(),
                    "sourceTaskId", result.sourceTaskId()
                ),
                mapOf(
                    "columns", result.columns(),
                    "columnMetadata", result.columnMetadata(),
                    "rows", rows,
                    "rowCount", result.rowCount(),
                    "returnedRowCount", rows.size(),
                    "possiblyTruncated", data.get("possiblyTruncated"),
                    "governance", data.get("governance"),
                    "meta", limits,
                    "diagnostics", result.diagnostics()
                ),
                result.success(),
                result.durationMs(),
                result.errorMessage(),
                mapOf("rowCount", result.rowCount())
            ))
        ));
        payload.put("executionGraph", graph(
            List.of(graphNode("sql_query", "sql.query", result.success(), result.durationMs())),
            List.of()
        ));
        return payload;
    }

    public Map<String, Object> fromSqlScript(SqlScriptResult result) {
        Map<String, Object> payload = base(
            "sql_script",
            "sql_script_result.v1",
            "structured",
            result.success(),
            result.durationMs(),
            result.errorMessage()
        );
        payload.put("target", mapOf(
            "type", "database",
            "id", result.datasourceId(),
            "name", result.datasourceName(),
            "toolName", result.toolName(),
            "environment", result.environment()
        ));
        payload.put("operation", mapOf(
            "type", "sql.script",
            "statementCount", result.statementCount(),
            "timeoutSeconds", result.timeoutSeconds(),
            "maxRowsPerStatement", result.maxRowsPerStatement(),
            "purpose", result.purpose(),
            "sourceTaskId", result.sourceTaskId(),
            "diagnostics", result.diagnostics()
        ));
        List<Map<String, Object>> resultSets = result.results().stream()
            .map(this::scriptResultSet)
            .toList();
        payload.put("limits", mapOf(
            "maxRowsPerStatementRequested", result.maxRowsPerStatement(),
            "maxRowsReturnedToModelPerStatement", SQL_RESULT_ROW_LIMIT,
            "truncationStrategy", "LIMIT_50_PER_STATEMENT"
        ));
        payload.put("data", mapOf(
            "statementCount", result.statementCount(),
            "resultSetCount", resultSets.size(),
            "results", resultSets,
            "diagnostics", result.diagnostics()
        ));
        payload.put("execution", execution(
            "sql_script_execute",
            result.durationMs(),
            result.results().stream()
                .map(statement -> step(
                    statement.statementIndex(),
                    firstText(statement.stepType(), "sql").toLowerCase(java.util.Locale.ROOT),
                    mapOf(
                        "statement", statement.sql(),
                        "stepCode", statement.stepCode(),
                        "stepName", statement.stepName(),
                        "stepType", statement.stepType(),
                        "required", statement.required(),
                        "analysisHint", statement.analysisHint()
                    ),
                    scriptResultSet(statement),
                    statement.success(),
                    statement.durationMs(),
                    statement.errorMessage(),
                    mapOf(
                        "rowCount", statement.rowCount(),
                        "stepCode", statement.stepCode(),
                        "stepName", statement.stepName(),
                        "stepType", statement.stepType(),
                        "required", statement.required(),
                        "analysisHint", statement.analysisHint()
                    )
                ))
                .toList()
        ));
        payload.put("executionGraph", graph(
            result.results().stream()
                .map(statement -> graphNode(
                    "sql_statement_" + statement.statementIndex(),
                    "sql.query",
                    statement.success(),
                    statement.durationMs(),
                    mapOf("rowCount", statement.rowCount())
                ))
                .toList(),
            List.of()
        ));
        return payload;
    }

    private Map<String, Object> scriptResultSet(SqlScriptStatementResult result) {
        List<Map<String, Object>> rows = result.rows() == null
            ? List.of()
            : result.rows().stream().limit(SQL_RESULT_ROW_LIMIT).toList();
        return mapOf(
            "statementIndex", result.statementIndex(),
            "stepCode", result.stepCode(),
            "stepName", result.stepName(),
            "stepType", result.stepType(),
            "required", result.required(),
            "analysisHint", result.analysisHint(),
            "statement", result.sql(),
            "success", result.success(),
            "columns", result.columns(),
            "columnMetadata", result.columnMetadata(),
            "rows", rows,
            "rowCount", result.rowCount(),
            "returnedRowCount", rows.size(),
            "possiblyTruncated", result.possiblyTruncated() || result.rowCount() > rows.size(),
            "truncationStrategy", "LIMIT_50",
            "errorMessage", result.errorMessage(),
            "diagnostics", result.diagnostics()
        );
    }

    public Map<String, Object> fromLinuxCommand(LinuxCommandResult result) {
        Map<String, Object> diagnostics = linuxCommandDiagnostics(result);
        List<LinuxCommandStepResult> rawSteps = result.steps() == null ? List.of() : result.steps();
        int perStepStreamLimit = Math.min(
            LINUX_AGGREGATE_STREAM_LIMIT,
            Math.max(4_000, LINUX_STEP_STREAM_TOTAL_BUDGET / Math.max(2, rawSteps.size() * 2))
        );
        List<Map<String, Object>> boundedSteps = stepResults(rawSteps, perStepStreamLimit);
        BoundedText stdout = boundedText(result.stdout(), LINUX_AGGREGATE_STREAM_LIMIT);
        BoundedText stderr = boundedText(result.stderr(), LINUX_AGGREGATE_STREAM_LIMIT);
        Map<String, Object> payload = base(
            "ssh_command",
            "ssh_steps.v1",
            "structured",
            result.success(),
            result.durationMs(),
            result.errorMessage()
        );
        payload.put("target", mapOf(
            "type", "server",
            "id", result.hostId(),
            "name", result.host(),
            "address", result.host(),
            "addressType", serverAddressType(result.host()),
            "ipAddress", serverIpAddress(result.host()),
            "toolName", result.toolName(),
            "environment", result.environment()
        ));
        payload.put("operation", mapOf(
            "type", "ssh.command_steps",
            "template", result.template(),
            "commandHash", result.commandHash(),
            "sourceTaskId", result.request() == null ? null : result.request().get("sourceTaskId"),
            "reason", result.request() == null ? null : result.request().get("reason"),
            "diagnostics", diagnostics
        ));
        payload.put("data", mapOf(
            "exitCode", result.exitCode(),
            "transportSuccess", result.success(),
            "commandSuccess", rawSteps.stream().allMatch(LinuxCommandStepResult::success),
            "nonZeroStepIndexes", rawSteps.stream()
                .filter(step -> !step.success())
                .map(LinuxCommandStepResult::stepIndex)
                .toList(),
            "failedStepIndex", result.failedStepIndex(),
            "failedCommand", result.failedCommand(),
            "outputMode", "separated",
            "diagnostics", diagnostics,
            "outputLimits", mapOf(
                "strategy", "HEAD_TAIL_PER_STREAM",
                "aggregateStreamLimit", LINUX_AGGREGATE_STREAM_LIMIT,
                "perStepStreamLimit", perStepStreamLimit,
                "stdoutOriginalLength", stdout.originalLength(),
                "stdoutReturnedLength", stdout.value().length(),
                "stdoutTruncated", stdout.truncated(),
                "stderrOriginalLength", stderr.originalLength(),
                "stderrReturnedLength", stderr.value().length(),
                "stderrTruncated", stderr.truncated()
            ),
            "steps", boundedSteps,
            "stdout", stdout.value(),
            "stderr", stderr.value()
        ));
        payload.put("execution", linuxExecution(result, rawSteps, perStepStreamLimit));
        payload.put("executionGraph", graph(stepGraphNodes(rawSteps), stepGraphEdges(rawSteps)));
        return payload;
    }

    public Map<String, Object> fromHttp(HttpRequestToolResult result) {
        Map<String, Object> payload = base(
            "http_request",
            "http_response.v1",
            result.body() instanceof Map<?, ?> || result.body() instanceof List<?> ? "structured" : "semi_raw",
            result.success(),
            result.durationMs(),
            result.errorMessage()
        );
        payload.put("target", mapOf(
            "type", "http_endpoint",
            "id", result.url(),
            "name", result.url(),
            "toolName", "http_request",
            "environment", null
        ));
        payload.put("operation", mapOf(
            "type", "http.request",
            "method", result.method(),
            "url", result.url()
        ));
        Map<String, Object> data = mapOf(
            "statusCode", result.statusCode(),
            "headers", result.headers(),
            "body", result.body(),
            "rawBody", result.rawBody()
        );
        payload.put("data", data);
        payload.put("execution", execution(
            "http_request",
            result.durationMs(),
            List.of(step(
                1,
                "http",
                mapOf(
                    "method", result.method(),
                    "url", result.url()
                ),
                data,
                result.success(),
                result.durationMs(),
                result.errorMessage(),
                mapOf("statusCode", result.statusCode())
            ))
        ));
        payload.put("executionGraph", graph(
            List.of(graphNode("http_request", "http.request", result.success(), result.durationMs())),
            List.of()
        ));
        return payload;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fromDatabaseQuery(DatabaseQueryConfig config, Map<String, Object> arguments,
                                                ToolOutput output) {
        boolean success = output != null && output.isSuccess();
        long durationMs = output == null || output.getExecutionTimeMs() == null
            ? 0L
            : Math.max(0L, output.getExecutionTimeMs());
        Object rawData = output == null ? null : output.getData();
        Map<String, Object> resultData = rawData instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : mapOf("value", rawData);
        boolean multiSql = resultData.get("resultSets") instanceof List<?>
            || resultData.get("results") instanceof List<?>;
        resultData.putIfAbsent("columnMetadata", databaseQueryColumnMetadata(resultData));
        String statement = firstText(
            stringValue(resultData.get("sql")),
            config == null ? null : config.getSqlTemplate()
        );
        String toolName = firstText(config == null ? null : config.getToolName(), "database_query");
        String errorMessage = output == null ? "database_query returned no output" : output.getErrorMessage();
        Map<String, Object> analysisContext = databaseQueryAnalysisContext(config);
        Map<String, Object> payload = base(
            multiSql ? "sql_result_sets" : "sql_query",
            multiSql ? "database_query_multi_sql_result.v1" : "sql_result.v1",
            "structured",
            success,
            durationMs,
            errorMessage
        );
        payload.put("target", mapOf(
            "type", "database",
            "id", config == null ? null : firstText(config.getDatasourceId(), config.getId()),
            "name", config == null ? null : config.getTitle(),
            "toolName", toolName,
            "environment", null,
                "template", config == null ? null : mapOf(
                "templateId", config.getToolName(),
                "templateName", config.getTitle(),
                "description", config.getDescription(),
                "implementationSteps", config.getImplementationSteps(),
                "intent", firstText(config.getTemplateIntent(), "general_query"),
                "businessGroup", mapOf(
                    "code", firstText(config.getBusinessGroup(), "default"),
                    "name", firstText(config.getBusinessGroupName(), firstText(config.getBusinessGroup(), "default")),
                    "description", firstText(config.getBusinessGroupDescription(), "")
                ),
                "databaseType", firstText(config.getDatabaseType(), "generic"),
                "riskLevel", firstText(config.getRiskLevel(), "read_only"),
                "owner", firstText(config.getOwner(), "admin")
            )
        ));
        payload.put("analysisContext", analysisContext);
        payload.put("operation", mapOf(
            "type", "sql.query",
            "executionMode", multiSql ? "SEQUENTIAL_MULTI_SQL" : "SINGLE_SQL",
            "resultSetCount", resultData.get("resultSetCount"),
            "statement", statement,
            "timeoutSeconds", config == null ? null : config.getTimeoutSeconds(),
            "purpose", arguments == null ? null : arguments.get("purpose"),
            "sourceTaskId", arguments == null ? null : arguments.get("sourceTaskId")
        ));
        payload.put("limits", mapOf(
            "maxRowsRequested", resultData.get("maxRows"),
            "maxRowsReturnedToModel", resultData.get("maxRows"),
            "truncationStrategy", "DATABASE_QUERY_MAX_ROWS"
        ));
        payload.put("data", resultData);
        payload.put("execution", execution(
            toolName,
            durationMs,
            multiSql ? databaseQueryMultiSqlSteps(resultData, arguments, durationMs, errorMessage) : List.of(step(
                1,
                "sql",
                mapOf(
                    "statement", statement,
                    "parameters", arguments == null ? Map.of() : arguments,
                    "timeoutSeconds", config == null ? null : config.getTimeoutSeconds(),
                    "sourceTaskId", arguments == null ? null : arguments.get("sourceTaskId")
                ),
                mapOf(
                    "analysisContext", analysisContext,
                    "columns", resultData.get("columns"),
                    "columnMetadata", resultData.get("columnMetadata"),
                    "rows", resultData.get("rows"),
                    "rowCount", resultData.get("rowCount"),
                    "returnedRowCount", resultData.get("rowCount"),
                    "possiblyTruncated", resultData.get("possiblyTruncated"),
                    "readOnly", resultData.get("readOnly"),
                    "governance", sqlOutputGovernance(resultData),
                    "meta", mapOf(
                        "maxRows", resultData.get("maxRows"),
                        "dataSource", resultData.get("dataSource")
                    )
                ),
                success,
                durationMs,
                errorMessage,
                mapOf("rowCount", resultData.get("rowCount"))
            ))
        ));
        payload.put("executionGraph", graph(
            multiSql
                ? databaseQueryMultiSqlGraphNodes(resultData)
                : List.of(graphNode("sql_query", "sql.query", success, durationMs)),
            List.of()
        ));
        return payload;
    }

    private List<Map<String, Object>> databaseQueryMultiSqlSteps(Map<String, Object> resultData,
                                                                 Map<String, Object> arguments,
                                                                 long durationMs,
                                                                 String errorMessage) {
        List<Map<String, Object>> resultSets = listOfMaps(firstPresent(resultData.get("resultSets"), resultData.get("results")));
        if (resultSets.isEmpty()) {
            return List.of(step(1, "sql", Map.of(), resultData, false, durationMs, errorMessage, Map.of()));
        }
        return resultSets.stream()
            .map(resultSet -> {
                int index = intValue(resultSet.get("executionOrder"), resultSets.indexOf(resultSet) + 1);
                return step(
                    index,
                    "sql_result_set",
                    mapOf(
                        "sqlCode", resultSet.get("sqlCode"),
                        "sqlName", resultSet.get("sqlName"),
                        "description", resultSet.get("description"),
                        "statement", resultSet.get("sql"),
                        "parameters", arguments == null ? Map.of() : arguments
                    ),
                    resultSet,
                    Boolean.TRUE.equals(resultSet.get("success")),
                    longValue(resultSet.get("durationMs"), 0L),
                    stringValue(resultSet.get("errorMessage")),
                    mapOf(
                        "sqlCode", resultSet.get("sqlCode"),
                        "sqlName", resultSet.get("sqlName"),
                        "resultSetDescription", resultSet.get("description"),
                        "rowCount", resultSet.get("rowCount")
                    )
                );
            })
            .toList();
    }

    private List<Map<String, Object>> databaseQueryMultiSqlGraphNodes(Map<String, Object> resultData) {
        List<Map<String, Object>> resultSets = listOfMaps(firstPresent(resultData.get("resultSets"), resultData.get("results")));
        return resultSets.stream()
            .map(resultSet -> graphNode(
                "sql_result_set_" + intValue(resultSet.get("executionOrder"), resultSets.indexOf(resultSet) + 1),
                "sql.query",
                Boolean.TRUE.equals(resultSet.get("success")),
                longValue(resultSet.get("durationMs"), 0L),
                mapOf(
                    "sqlCode", resultSet.get("sqlCode"),
                    "sqlName", resultSet.get("sqlName"),
                    "description", resultSet.get("description"),
                    "rowCount", resultSet.get("rowCount")
                )
            ))
            .toList();
    }

    private Map<String, Object> databaseQueryAnalysisContext(DatabaseQueryConfig config) {
        if (config == null) {
            return Map.of();
        }
        String groupCode = firstText(config.getBusinessGroup(), "default");
        String groupName = firstText(config.getBusinessGroupName(), groupCode);
        String groupDescription = firstText(config.getBusinessGroupDescription(), "");
        return mapOf(
            "schemaVersion", "database_query_analysis_context.v1",
            "templateId", config.getToolName(),
            "templateTitle", config.getTitle(),
            "templateDescription", config.getDescription(),
            "implementationSteps", config.getImplementationSteps(),
            "templateIntent", firstText(config.getTemplateIntent(), "general_query"),
            "businessGroupCode", groupCode,
            "businessGroupName", groupName,
            "businessGroupDescription", groupDescription,
            "modelAnalysisHint", "Use businessGroupName and businessGroupDescription as semantic context when interpreting returned SQL rows."
        );
    }

    private Map<String, Object> base(String kind, String dataSchema, String payloadType,
                                     boolean success, long durationMs, String errorMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("kind", kind);
        payload.put("dataSchema", dataSchema);
        payload.put("payloadType", payloadType);
        payload.put("success", success);
        payload.put("status", success ? "success" : "failed");
        payload.put("durationMs", durationMs);
        payload.put("generatedAt", Instant.now().toString());
        payload.put("error", errorMessage == null || errorMessage.isBlank()
            ? null
            : mapOf("message", errorMessage));
        return payload;
    }

    private List<Map<String, Object>> stepResults(List<LinuxCommandStepResult> steps, int streamLimit) {
        if (steps == null) {
            return List.of();
        }
        return steps.stream()
            .map(step -> boundedStepResult(step, streamLimit))
            .toList();
    }

    private Map<String, Object> boundedStepResult(LinuxCommandStepResult step, int streamLimit) {
        BoundedText stdout = boundedText(step.stdout(), streamLimit);
        BoundedText stderr = boundedText(step.stderr(), streamLimit);
        return mapOf(
            "stepIndex", step.stepIndex(),
            "stepCode", step.stepCode(),
            "stepName", step.stepName(),
            "stepType", step.stepType(),
            "required", step.required(),
            "analysisHint", step.analysisHint(),
            "command", step.command(),
            "commandHash", step.commandHash(),
            "exitCode", step.exitCode(),
            "success", step.success(),
            "durationMs", step.durationMs(),
            "stdoutOriginalLength", stdout.originalLength(),
            "stdoutReturnedLength", stdout.value().length(),
            "stdoutTruncated", stdout.truncated(),
            "stderrOriginalLength", stderr.originalLength(),
            "stderrReturnedLength", stderr.value().length(),
            "stderrTruncated", stderr.truncated(),
            "stdout", stdout.value(),
            "stderr", stderr.value()
        );
    }

    private Map<String, Object> linuxExecution(LinuxCommandResult result,
                                               List<LinuxCommandStepResult> steps,
                                               int streamLimit) {
        return execution(
            result.toolName(),
            result.durationMs(),
            steps.stream().map(step -> {
                Map<String, Object> bounded = boundedStepResult(step, streamLimit);
                return step(
                    step.stepIndex(),
                    "command",
                    mapOf(
                        "stepCode", step.stepCode(),
                        "stepName", step.stepName(),
                        "stepType", step.stepType(),
                        "required", step.required(),
                        "analysisHint", step.analysisHint(),
                        "command", step.command(),
                        "commandHash", step.commandHash()
                    ),
                    bounded,
                    step.success(),
                    step.durationMs(),
                    step.success() ? null : bounded.get("stderr").toString(),
                    mapOf(
                        "exitCode", step.exitCode(),
                        "stdoutTruncated", bounded.get("stdoutTruncated"),
                        "stderrTruncated", bounded.get("stderrTruncated")
                    )
                );
            }).toList()
        );
    }

    private BoundedText boundedText(String value, int limit) {
        String text = value == null ? "" : value;
        int max = Math.max(256, limit);
        if (text.length() <= max) {
            return new BoundedText(text, text.length(), false);
        }
        String markerTemplate = "\n...[truncated %d chars; preserving tail]...\n";
        String marker = markerTemplate.formatted(Math.max(0, text.length() - max));
        for (int attempt = 0; attempt < 3; attempt++) {
            int retained = Math.max(2, max - marker.length());
            marker = markerTemplate.formatted(Math.max(0, text.length() - retained));
        }
        int available = Math.max(2, max - marker.length());
        int headLength = available / 2;
        int tailLength = available - headLength;
        String bounded = text.substring(0, headLength) + marker + text.substring(text.length() - tailLength);
        return new BoundedText(bounded, text.length(), true);
    }

    private Map<String, Object> linuxCommandDiagnostics(LinuxCommandResult result) {
        List<LinuxCommandStepResult> steps = result.steps() == null ? List.of() : result.steps();
        return mapOf(
            "schemaVersion", "linux_command_diagnostics.v1",
            "hostId", result.hostId(),
            "host", result.host(),
            "toolName", result.toolName(),
            "environment", result.environment(),
            "template", result.template(),
            "sourceTaskId", result.request() == null ? null : result.request().get("sourceTaskId"),
            "reason", result.request() == null ? null : result.request().get("reason"),
            "parameters", result.request() == null ? Map.of() : result.request().getOrDefault("parameters", Map.of()),
            "commandHash", result.commandHash(),
            "stepCount", steps.size(),
            "failedStepIndex", result.failedStepIndex(),
            "failedCommandHash", sha256(result.failedCommand()),
            "exitCode", result.exitCode(),
            "transportSuccess", result.success(),
            "commandSuccess", steps.stream().allMatch(LinuxCommandStepResult::success),
            "nonZeroStepIndexes", steps.stream()
                .filter(step -> !step.success())
                .map(LinuxCommandStepResult::stepIndex)
                .toList(),
            "durationMs", result.durationMs(),
            "stdoutLength", result.stdout() == null ? 0 : result.stdout().length(),
            "stderrLength", result.stderr() == null ? 0 : result.stderr().length(),
            "steps", steps.stream()
                .map(step -> mapOf(
                    "stepIndex", step.stepIndex(),
                    "commandHash", step.commandHash(),
                    "exitCode", step.exitCode(),
                    "success", step.success(),
                    "durationMs", step.durationMs(),
                    "stdoutLength", step.stdout() == null ? 0 : step.stdout().length(),
                    "stderrLength", step.stderr() == null ? 0 : step.stderr().length()
                ))
                .toList()
        );
    }

    private String sha256(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return null;
        }
    }

    private List<Map<String, Object>> stepGraphNodes(List<LinuxCommandStepResult> steps) {
        if (steps == null) {
            return List.of();
        }
        return steps.stream()
            .map(step -> graphNode(
                "ssh_step_" + step.stepIndex(),
                "ssh.command",
                step.success(),
                step.durationMs(),
                mapOf(
                    "stepIndex", step.stepIndex(),
                    "commandHash", step.commandHash(),
                    "exitCode", step.exitCode()
                )
            ))
            .toList();
    }

    private List<Map<String, Object>> stepGraphEdges(List<LinuxCommandStepResult> steps) {
        if (steps == null || steps.size() < 2) {
            return List.of();
        }
        List<Map<String, Object>> edges = new java.util.ArrayList<>();
        for (int index = 1; index < steps.size(); index++) {
            edges.add(mapOf(
                "from", "ssh_step_" + steps.get(index - 1).stepIndex(),
                "to", "ssh_step_" + steps.get(index).stepIndex(),
                "type", "sequential_after_success"
            ));
        }
        return edges;
    }

    private Map<String, Object> graph(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        return mapOf(
            "schemaVersion", "execution_graph.v1",
            "nodes", nodes == null ? List.of() : nodes,
            "edges", edges == null ? List.of() : edges
        );
    }

    private Map<String, Object> sqlOutputGovernance(SqlQueryResult result, int returnedRowCount) {
        return mapOf(
            "schemaVersion", "sql_output_governance.v1",
            "readOnly", true,
            "rowCount", result.rowCount(),
            "returnedRowCount", returnedRowCount,
            "possiblyTruncated", result.possiblyTruncated() || result.rowCount() > returnedRowCount,
            "truncationStrategy", "LIMIT_50",
            "maskedColumns", maskedColumns(result.columnMetadata()),
            "columnCommentsIncluded", hasColumnComments(result.columnMetadata())
        );
    }

    private Map<String, Object> sqlOutputGovernance(Map<String, Object> resultData) {
        List<Map<String, Object>> columnMetadata = listOfMaps(resultData.get("columnMetadata"));
        return mapOf(
            "schemaVersion", "sql_output_governance.v1",
            "readOnly", resultData.get("readOnly"),
            "rowCount", resultData.get("rowCount"),
            "returnedRowCount", resultData.get("rowCount"),
            "possiblyTruncated", resultData.get("possiblyTruncated"),
            "truncationStrategy", "DATABASE_QUERY_MAX_ROWS",
            "maskedColumns", maskedColumns(columnMetadata),
            "columnCommentsIncluded", hasColumnComments(columnMetadata)
        );
    }

    private List<Map<String, Object>> databaseQueryColumnMetadata(Map<String, Object> resultData) {
        List<Map<String, Object>> existing = listOfMaps(resultData.get("columnMetadata"));
        if (!existing.isEmpty()) {
            return existing;
        }
        Map<String, Object> comments = new LinkedHashMap<>();
        if (resultData.get("columnComments") instanceof Map<?, ?> map) {
            map.forEach((key, value) -> comments.put(String.valueOf(key), value));
        }
        Object columns = resultData.get("columns");
        if (!(columns instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .map(String::valueOf)
            .map(column -> {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("name", column);
                metadata.put("label", column);
                metadata.put("comment", comments.get(column));
                metadata.put("masked", false);
                return metadata;
            })
            .toList();
    }

    private Map<String, Object> execution(String toolName, long durationMs, List<Map<String, Object>> steps) {
        Instant finishedAt = Instant.now();
        Instant startedAt = finishedAt.minusMillis(Math.max(0L, durationMs));
        List<Map<String, Object>> safeSteps = steps == null ? List.of() : steps;
        return mapOf(
            "schemaVersion", "execution_unit.v1",
            "executionId", UUID.randomUUID().toString(),
            "toolName", toolName,
            "startedAt", startedAt.toString(),
            "finishedAt", finishedAt.toString(),
            "durationMs", durationMs,
            "stepCount", safeSteps.size(),
            "steps", safeSteps
        );
    }

    private Map<String, Object> step(int stepIndex, String stepType, Map<String, Object> input,
                                     Map<String, Object> output, boolean success, long durationMs,
                                     String errorMessage, Map<String, Object> attributes) {
        Map<String, Object> value = mapOf(
            "stepIndex", stepIndex,
            "stepId", stepType + "_" + stepIndex,
            "stepType", stepType,
            "input", input == null ? Map.of() : input,
            "output", output == null ? Map.of() : output,
            "success", success,
            "status", success ? "success" : "failed",
            "durationMs", durationMs,
            "error", errorMessage == null || errorMessage.isBlank() ? null : mapOf("message", errorMessage)
        );
        if (attributes != null) {
            value.putAll(attributes);
        }
        return value;
    }

    private Map<String, Object> graphNode(String id, String type, boolean success, long durationMs) {
        return graphNode(id, type, success, durationMs, Map.of());
    }

    private Map<String, Object> graphNode(String id, String type, boolean success,
                                          long durationMs, Map<String, Object> attributes) {
        return mapOf(
            "id", id,
            "type", type,
            "status", success ? "success" : "failed",
            "durationMs", durationMs,
            "attributes", attributes == null ? Map.of() : attributes
        );
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record BoundedText(String value, int originalLength, boolean truncated) {
    }

    private Object firstPresent(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
        }
        return List.of();
    }

    private List<String> maskedColumns(List<Map<String, Object>> columnMetadata) {
        if (columnMetadata == null) {
            return List.of();
        }
        return columnMetadata.stream()
            .filter(column -> Boolean.TRUE.equals(column.get("masked")))
            .map(column -> firstText(stringValue(column.get("label")), stringValue(column.get("name"))))
            .filter(value -> value != null && !value.isBlank())
            .toList();
    }

    private boolean hasColumnComments(List<Map<String, Object>> columnMetadata) {
        if (columnMetadata == null) {
            return false;
        }
        return columnMetadata.stream()
            .map(column -> stringValue(column.get("comment")))
            .anyMatch(value -> value != null && !value.isBlank());
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

    private String serverAddressType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return "ipv4";
        }
        if (text.contains(":") && text.matches("[0-9a-fA-F:]+")) {
            return "ipv6";
        }
        return "hostname";
    }

    private String serverIpAddress(String value) {
        String type = serverAddressType(value);
        return "ipv4".equals(type) || "ipv6".equals(type) ? value.trim() : null;
    }
}
