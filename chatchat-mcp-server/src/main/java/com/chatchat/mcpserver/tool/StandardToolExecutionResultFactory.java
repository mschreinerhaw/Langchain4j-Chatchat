package com.chatchat.mcpserver.tool;

import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.api.ApiInvokeResult;
import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.notification.NotificationSendResult;
import com.chatchat.mcpserver.ops.HttpRequestToolResult;
import com.chatchat.mcpserver.ops.LinuxCommandResult;
import com.chatchat.mcpserver.ops.LinuxCommandStepResult;
import com.chatchat.mcpserver.ops.JmxMonitorResult;
import com.chatchat.mcpserver.sql.SqlQueryResult;
import com.chatchat.mcpserver.sql.SqlScriptResult;
import com.chatchat.mcpserver.sql.SqlScriptStatementResult;
import com.chatchat.tools.builtin.DatabaseToolProperties;
import com.chatchat.common.tool.DataAnalysisContextProtocol;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
@RequiredArgsConstructor
public class StandardToolExecutionResultFactory {

    public static final String SCHEMA_VERSION = "tool_execution_result.v1";
    /** Maximum requested batch size; this is input governance, never output truncation. */
    public static final int MODEL_SAFE_COLLECTION_LIMIT = 200;
    static final int STRUCTURED_JSON_TEXT_LIMIT = 1_000_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DatabaseToolProperties databaseToolProperties;

    public Map<String, Object> fromJmx(JmxMonitorResult result) {
        Map<String, Object> payload = base(
            "jmx_monitor", "jmx_monitor_result.v1", "structured",
            result.success(), result.durationMs(), result.errorMessage());
        payload.put("target", mapOf(
            "type", "jmx_endpoint",
            "templateId", result.template()
        ));
        payload.put("sourceMetadata", sourceMetadata(
            "JMX_MONITOR", "jmx_template", "jmx_monitor_execute",
            mapOf("type", "jmx_endpoint", "templateId", result.template()),
            mapOf("template", result.template()),
            firstText(result.template(), "JMX monitoring"),
            "Read-only metrics collected from an administrator-maintained JMX template",
            "jmx_monitoring"
        ));
        List<Map<String, Object>> metrics = result.metrics() == null ? List.of() : result.metrics().stream()
            .map(this::runtimeSafeEvidenceMap)
            .toList();
        List<Map<String, Object>> errors = result.errors() == null ? List.of() : result.errors().stream()
            .map(this::runtimeSafeEvidenceMap)
            .toList();
        Map<String, Object> data = mapOf(
            "resultSetMode", "ONE_PER_TEMPLATE_EXECUTION",
            "resultSetId", firstText(result.template(), "jmx-monitor-result"),
            "commandContext", commandContext(
                result.template(), result.template(), "Read-only metrics collected by the configured JMX template",
                "SINGLE", List.of(commandDescriptor("jmx_monitor", 1, result.template(),
                    "Collect the metrics declared by this JMX template", null, "$.data")), List.of()),
            "metrics", metrics,
            "metricCount", result.metrics() == null ? 0 : result.metrics().size(),
            "errors", errors,
            "errorCount", result.errors() == null ? 0 : result.errors().size(),
            "possiblyTruncated", false
        );
        payload.put("data", data);
        payload.put("execution", execution(
            "jmx_monitor_execute", result.durationMs(),
            List.of(step(1, "jmx", mapOf("template", result.template()), mapOf(
                    "resultSetReference", "$.data",
                    "metricCount", data.get("metricCount"),
                    "errorCount", data.get("errorCount"),
                    "possiblyTruncated", data.get("possiblyTruncated")
                ),
                result.success(), result.durationMs(), result.errorMessage(),
                mapOf("metricCount", data.get("metricCount"), "errorCount", data.get("errorCount"))))
        ));
        payload.put("executionGraph", graph(
            List.of(graphNode("jmx_monitor", "jmx.read_metrics", result.success(), result.durationMs())),
            List.of()
        ));
        return payload;
    }

    public Map<String, Object> fromSql(SqlQueryResult result) {
        Map<String, Object> safeDiagnostics = modelSafeMap(result.diagnostics());
        boolean effectiveSuccess = result.success()
            && (result.errorMessage() == null || result.errorMessage().isBlank());
        Map<String, Object> payload = base(
            "sql_query",
            "sql_result.v1",
            "structured",
            effectiveSuccess,
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
        payload.put("sourceMetadata", sqlSourceMetadata(result));
        payload.put("operation", mapOf(
            "type", "sql.query",
            "statement", result.normalizedSql() == null ? result.sql() : result.normalizedSql(),
            "timeoutSeconds", result.timeoutSeconds(),
            "purpose", result.purpose(),
            "sourceTaskId", result.sourceTaskId(),
            "diagnostics", safeDiagnostics
        ));
        List<Map<String, Object>> rows = result.rows() == null
            ? List.of()
            : result.rows().stream()
                .map(row -> runtimeSafeSqlRow(row, result.columnMetadata()))
                .toList();
        int effectiveRowCount = Math.max(rows.size(), Math.max(0, result.rowCount()));
        Map<String, Object> limits = mapOf(
            "maxRowsRequested", result.maxRows(),
            "configuredDatabaseMaxRows", databaseToolProperties.getMaxRows(),
            "rowsFetchedFromDatabase", rows.size(),
            "fullFetchedRowsAvailable", true,
            "analysisStrategy", "RUNTIME_EXTERNALIZE_AND_CHUNK",
            "truncationStrategy", "DATABASE_MAX_ROWS_" + result.maxRows()
        );
        payload.put("limits", limits);
        boolean possiblyTruncated = result.possiblyTruncated();
        Map<String, Object> data = mapOf(
            "resultSetMode", "ONE_PER_TEMPLATE_EXECUTION",
            "resultSetId", firstText(sha256(firstText(result.normalizedSql(), result.sql())),
                result.sourceTaskId(), "sql-query-result"),
            "commandContext", commandContext(
                result.toolName(), result.toolName(), result.purpose(), "SINGLE_SQL",
                List.of(commandDescriptor("sql_query", 1, result.toolName(), result.purpose(), null, "$.data")),
                List.of()),
            "rowCount", effectiveRowCount,
            "returnedRowCount", rows.size(),
            "complete", !possiblyTruncated,
            "possiblyTruncated", possiblyTruncated,
            "truncationStrategy", "DATABASE_MAX_ROWS_" + result.maxRows(),
            "columns", result.columns(),
            "columnMetadata", result.columnMetadata(),
            "governance", sqlOutputGovernance(result, effectiveRowCount, rows.size()),
            "diagnostics", safeDiagnostics,
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
                    "resultSetReference", "$.data",
                    "rowCount", effectiveRowCount,
                    "returnedRowCount", rows.size(),
                    "possiblyTruncated", data.get("possiblyTruncated"),
                    "columnCount", result.columns() == null ? 0 : result.columns().size()
                ),
                effectiveSuccess,
                result.durationMs(),
                result.errorMessage(),
                mapOf("rowCount", effectiveRowCount)
            ))
        ));
        payload.put("executionGraph", graph(
            List.of(graphNode("sql_query", "sql.query", effectiveSuccess, result.durationMs())),
            List.of()
        ));
        return payload;
    }

    public Map<String, Object> fromSqlScript(SqlScriptResult result) {
        Map<String, Object> safeDiagnostics = modelSafeMap(result.diagnostics());
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
        payload.put("sourceMetadata", sqlScriptSourceMetadata(result));
        payload.put("operation", mapOf(
            "type", "sql.script",
            "statementCount", result.statementCount(),
            "timeoutSeconds", result.timeoutSeconds(),
            "maxRowsPerStatement", result.maxRowsPerStatement(),
            "purpose", result.purpose(),
            "sourceTaskId", result.sourceTaskId(),
            "diagnostics", safeDiagnostics
        ));
        List<Map<String, Object>> resultSets = result.results().stream()
            .map(this::scriptResultSet)
            .toList();
        payload.put("limits", mapOf(
            "maxRowsPerStatementRequested", result.maxRowsPerStatement(),
            "configuredDatabaseMaxRows", databaseToolProperties.getMaxRows(),
            "fullFetchedRowsAvailable", true,
            "analysisStrategy", "RUNTIME_EXTERNALIZE_AND_CHUNK",
            "truncationStrategy", "DATABASE_MAX_ROWS_PER_STATEMENT_" + result.maxRowsPerStatement()
        ));
        payload.put("data", mapOf(
            "resultSetMode", "ONE_PER_TEMPLATE_EXECUTION",
            "resultSetId", firstText(result.sourceTaskId(), "sql-script-result"),
            "commandContext", sqlScriptCommandContext(result),
            "statementCount", result.statementCount(),
            "resultSetCount", resultSets.size(),
            "results", resultSets,
            "diagnostics", safeDiagnostics
        ));
        payload.put("execution", execution(
            "sql_query_execute",
            result.durationMs(),
            IntStream.range(0, result.results().size())
                .mapToObj(position -> {
                    SqlScriptStatementResult statement = result.results().get(position);
                    return step(
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
                    mapOf(
                        "resultSetReference", "$.data.results[" + position + "]",
                        "rowCount", statement.rowCount(),
                        "returnedRowCount", statement.rows() == null ? 0 : statement.rows().size(),
                        "possiblyTruncated", statement.possiblyTruncated()
                    ),
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
                );
                })
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
            : result.rows().stream()
                .map(row -> runtimeSafeSqlRow(row, result.columnMetadata()))
                .toList();
        return mapOf(
            "statementIndex", result.statementIndex(),
            "stepCode", result.stepCode(),
            "stepName", result.stepName(),
            "stepType", result.stepType(),
            "required", result.required(),
            "analysisHint", result.analysisHint(),
            "statement", result.sql(),
            "sourceMetadata", sqlStatementSourceMetadata(result),
            "success", result.success(),
            "columns", result.columns(),
            "columnMetadata", result.columnMetadata(),
            "rows", rows,
            "rowCount", result.rowCount(),
            "returnedRowCount", rows.size(),
            "possiblyTruncated", result.possiblyTruncated() || result.rowCount() > rows.size(),
            "truncationStrategy", "DATABASE_MAX_ROWS_PER_STATEMENT",
            "errorMessage", result.errorMessage(),
            "diagnostics", modelSafeMap(result.diagnostics())
        );
    }

    public Map<String, Object> fromLinuxCommand(LinuxCommandResult result) {
        Map<String, Object> diagnostics = linuxCommandDiagnostics(result);
        List<LinuxCommandStepResult> rawSteps = result.steps() == null ? List.of() : result.steps();
        List<Map<String, Object>> stepSummaries = commandStepSummaries(rawSteps);
        BoundedText stdout = completeCapturedText(result.stdout());
        BoundedText stderr = completeCapturedText(result.stderr());
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
        payload.put("sourceMetadata", linuxSourceMetadata(result));
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
            "resultSetMode", "ONE_PER_TEMPLATE_EXECUTION",
            "resultSetId", firstText(result.template(), result.commandHash(), "linux-command-result"),
            "commandContext", linuxCommandContext(result, rawSteps),
            "diagnostics", diagnostics,
            "outputLimits", mapOf(
                "strategy", "SINGLE_TEMPLATE_RESULT_WITH_STEP_METADATA",
                "aggregateStreamLimit", -1,
                "fullAggregateAvailable", !stdout.truncated() && !stderr.truncated(),
                "commandStreamsStoredOnce", true,
                "stepStreamsInline", false,
                "stdoutOriginalLength", stdout.originalLength(),
                "stdoutReturnedLength", stdout.value().length(),
                "stdoutTruncated", stdout.truncated(),
                "stderrOriginalLength", stderr.originalLength(),
                "stderrReturnedLength", stderr.value().length(),
                "stderrTruncated", stderr.truncated()
            ),
            "steps", stepSummaries,
            "stdout", stdout.value(),
            "stderr", stderr.value()
        ));
        payload.put("execution", linuxExecution(result, rawSteps));
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
        payload.put("sourceMetadata", httpSourceMetadata(result));
        payload.put("operation", mapOf(
            "type", "http.request",
            "method", result.method(),
            "url", result.url()
        ));
        Map<String, Object> data = mapOf(
            "resultSetMode", "ONE_PER_TEMPLATE_EXECUTION",
            "resultSetId", firstText(sha256(result.method() + ":" + result.url()), "http-request-result"),
            "commandContext", commandContext(
                "http_request", "HTTP request", result.method() + " request to the configured endpoint",
                "SINGLE", List.of(commandDescriptor("http_request", 1, "HTTP request",
                    result.method() + " request", null, "$.data")), List.of()),
            "statusCode", result.statusCode(),
            "headers", result.headers(),
            "body", result.body(),
            "rawBody", result.rawBody(),
            "bodyCompleteness", httpBodyCompleteness(result.body(), result.rawBody())
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
                mapOf(
                    "resultSetReference", "$.data",
                    "statusCode", result.statusCode(),
                    "bodyCompleteness", data.get("bodyCompleteness")
                ),
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

    /**
     * Normalizes governed API-template execution into the same runtime contract as
     * the other execution gateways. HTTP success is intentionally kept separate
     * from source-level pagination or completeness, which the gateway cannot infer.
     */
    public Map<String, Object> fromApi(ApiServiceConfig config, ApiInvokeResult result) {
        Map<String, Object> outputSchema = apiOutputSchema(config);
        List<Map<String, Object>> fieldMetadata = apiFieldMetadata(outputSchema);
        Map<String, Object> analysisContext = config == null ? Map.of() : DataAnalysisContextProtocol.create(
            mapOf(
                "type", "api_service",
                "id", config.getId(),
                "displayName", firstText(config.getTitle(), config.getToolName()),
                "toolName", config.getToolName(),
                "description", config.getDescription()
            ),
            apiJsonObject(config.getCapabilitySpecJson()),
            mapOf(
                "id", config.getCategoryId(),
                "code", config.getBusinessGroup(),
                "name", firstText(config.getBusinessGroupName(), config.getBusinessGroup()),
                "description", config.getBusinessGroupDescription()
            ),
            mapOf("definition", outputSchema, "fields", fieldMetadata),
            apiJsonObject(config.getDependencySpecJson())
        );
        Map<String, Object> payload = base(
            "api_request", "api_response.v1",
            result.body() instanceof Map<?, ?> || result.body() instanceof List<?> ? "structured" : "semi_raw",
            result.success(), 0L, result.errorMessage());
        payload.put("target", mapOf(
            "type", "api_service",
            "id", config == null ? null : config.getId(),
            "name", config == null ? null : config.getTitle(),
            "toolName", config == null ? null : config.getToolName(),
            "environment", null
        ));
        payload.put("sourceMetadata", sourceMetadata(
            "HTTP_REQUEST", "external_api", config == null ? null : config.getToolName(),
            mapOf("type", "api_service", "id", config == null ? null : config.getId(),
                "name", config == null ? null : config.getTitle()),
            mapOf("method", config == null ? null : config.getMethod(),
                "gatewayId", config == null ? null : config.getGatewayId()),
            config == null ? null : firstText(config.getBusinessGroupName(), config.getTitle()),
            config == null ? null : firstText(config.getBusinessGroupDescription(), config.getDescription()),
            config == null ? null : config.getBusinessGroup()
        ));
        payload.put("operation", mapOf(
            "type", "api.template_request",
            "method", config == null ? null : config.getMethod(),
            "cacheHit", result.cacheHit()
        ));
        payload.put("analysisContext", analysisContext);
        String apiCommandId = config == null ? "api_request" : firstText(config.getId(), config.getToolName(), "api_request");
        String apiCommandName = config == null ? "API request" : firstText(config.getTitle(), config.getToolName(), "API request");
        String apiDescription = config == null ? "Execute the configured API request"
            : firstText(config.getDescription(), config.getBusinessGroupDescription(), "Execute the configured API request");
        Map<String, Object> apiData = mapOf(
            "resultSetMode", "ONE_PER_TEMPLATE_EXECUTION",
            "resultSetId", apiCommandId,
            "commandContext", commandContext(
                config == null ? null : config.getId(),
                apiCommandName,
                apiDescription,
                "SINGLE",
                List.of(commandDescriptor(apiCommandId, 1, apiCommandName, apiDescription,
                    config == null ? null : firstText(config.getBusinessGroupDescription(), config.getDescription()),
                    "$.data.body")),
                List.of()),
            "statusCode", result.statusCode(),
            "headers", result.headers(),
            "body", result.body(),
            "rawBody", result.rawBody(),
            "cacheHit", result.cacheHit(),
            "bodyCompleteness", httpBodyCompleteness(result.body(), result.rawBody())
        );
        payload.put("data", apiData);
        payload.put("execution", execution(
            config == null ? "api_template_execute" : firstText(config.getToolName(), "api_template_execute"),
            0L,
            List.of(step(1, apiCommandId,
                mapOf("method", config == null ? null : config.getMethod()),
                mapOf(
                    "resultSetReference", "$.data",
                    "bodyReference", "$.data.body",
                    "statusCode", result.statusCode(),
                    "bodyCompleteness", apiData.get("bodyCompleteness")
                ),
                result.success(), 0L, result.errorMessage(),
                mapOf("statusCode", result.statusCode(), "cacheHit", result.cacheHit()))))
        );
        payload.put("executionGraph", graph(
            List.of(graphNode(apiCommandId, "api.template_request", result.success(), 0L)),
            List.of()
        ));
        return payload;
    }

    private Map<String, Object> apiOutputSchema(ApiServiceConfig config) {
        if (config == null || config.getOutputSchemaJson() == null
            || config.getOutputSchemaJson().isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(config.getOutputSchemaJson(), new TypeReference<>() { });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> apiJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> apiFieldMetadata(Map<String, Object> outputSchema) {
        if (outputSchema == null || !(outputSchema.get("properties") instanceof Map<?, ?> properties)) {
            return List.of();
        }
        Set<String> required = outputSchema.get("required") instanceof List<?> values
            ? values.stream().map(String::valueOf).collect(Collectors.toSet())
            : Set.of();
        return properties.entrySet().stream()
            .filter(entry -> entry.getKey() != null)
            .map(entry -> {
                String name = String.valueOf(entry.getKey());
                Map<?, ?> definition = entry.getValue() instanceof Map<?, ?> map ? map : Map.of();
                String comment = firstText(
                    stringValue(definition.get("description")),
                    stringValue(definition.get("title")),
                    stringValue(definition.get("label"))
                );
                return mapOf(
                    "name", name,
                    "technicalName", name,
                    "description", comment,
                    "comment", comment,
                    "type", definition.get("type"),
                    "required", required.contains(name),
                    "source", "api_output_schema"
                );
            })
            .toList();
    }

    /** Creates an operation receipt; notification response text is not business evidence. */
    public Map<String, Object> fromNotification(NotificationSendResult result) {
        Map<String, Object> payload = base(
            "notification_receipt", "notification_receipt.v1", "structured",
            result.success(), 0L, result.errorMessage());
        payload.put("target", mapOf(
            "type", "notification_channel",
            "name", result.channel() == null ? null : result.channel().name(),
            "toolName", result.toolName()
        ));
        payload.put("operation", mapOf(
            "type", "notification.send",
            "attempts", result.attempts()
        ));
        payload.put("data", mapOf(
            "statusCode", result.statusCode(),
            "attempts", result.attempts(),
            "notification", result.notification(),
            "responseBody", result.responseBody(),
            "rawResponse", result.rawResponse(),
            "evidenceRole", "OPERATION_RECEIPT",
            "businessEvidence", false
        ));
        return payload;
    }

    private Map<String, Object> httpBodyCompleteness(Object body, String rawBody) {
        Integer recordCount = body instanceof List<?> list ? list.size() : null;
        return mapOf(
            "gatewayTruncated", false,
            "upstreamCompleteness", "UNKNOWN",
            "paginationAssessed", false,
            "rawBodyChars", rawBody == null ? null : rawBody.length(),
            "topLevelRecordCount", recordCount,
            "rule", "gatewayTruncated describes this gateway only; upstream pagination/completeness requires explicit source fields"
        );
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fromDatabaseQuery(DatabaseQueryConfig config, Map<String, Object> arguments,
                                                ToolOutput output) {
        return fromDatabaseQuery(config, arguments, output, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fromDatabaseQuery(DatabaseQueryConfig config, Map<String, Object> arguments,
                                                ToolOutput output, String publishedToolName) {
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
        boolean workflow = "database_query_workflow".equals(resultData.get("mode"));
        String multiExecutionMode = workflow ? "DEPENDENCY_GRAPH" : "SEQUENTIAL_MULTI_SQL";
        resultData.putIfAbsent("columnMetadata", databaseQueryColumnMetadata(resultData));
        String statement = firstText(
            stringValue(resultData.get("sql")),
            config == null ? null : config.getSqlTemplate()
        );
        String toolName = firstText(publishedToolName,
            config == null ? null : config.getToolName(), "database_query");
        String errorMessage = output == null ? "database_query returned no output" : output.getErrorMessage();
        Map<String, Object> analysisContext = databaseQueryAnalysisContext(config, resultData, toolName);
        Map<String, Object> payload = base(
            workflow ? "sql_workflow_result_sets" : multiSql ? "sql_result_sets" : "sql_query",
            workflow ? "database_query_workflow_result.v1" : multiSql ? "database_query_multi_sql_result.v1" : "sql_result.v1",
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
        payload.put("sourceMetadata", sourceMetadata(
            multiSql ? "SQL_QUERY_STEPS" : "SQL_QUERY",
            multiSql ? "database_query_template" : "sql_statement",
            toolName,
            mapOf(
                "type", "database",
                "id", config == null ? null : firstText(config.getDatasourceId(), config.getId()),
                "name", config == null ? null : config.getTitle(),
                "environment", null
            ),
            mapOf(
                "statementHash", sha256(statement),
                "executionMode", multiSql ? multiExecutionMode : "SINGLE_SQL"
            ),
            config == null ? "Database query" : firstText(config.getTitle(), config.getToolName()),
            config == null
                ? stringValue(arguments == null ? null : arguments.get("purpose"))
                : firstText(config.getDescription(), config.getBusinessGroupDescription(),
                    stringValue(arguments == null ? null : arguments.get("purpose"))),
            config == null ? "database_query" : firstText(config.getBusinessGroup(), "database_query")
        ));
        payload.put("analysisContext", analysisContext);
        payload.put("operation", mapOf(
            "type", "sql.query",
            "executionMode", multiSql ? multiExecutionMode : "SINGLE_SQL",
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
        Map<String, Object> canonicalData = new LinkedHashMap<>(runtimeSafeEvidenceMap(resultData));
        synchronizeResultColumns(canonicalData);
        canonicalData.put("resultSetMode", "ONE_PER_TEMPLATE_EXECUTION");
        canonicalData.put("resultSetId", firstText(
            sha256(statement),
            stringValue(arguments == null ? null : arguments.get("sourceTaskId")),
            "database-query-result"
        ));
        canonicalData.put("commandContext", databaseQueryCommandContext(
            config, resultData, toolName, multiSql, workflow));
        payload.put("data", canonicalData);
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
                    "resultSetReference", "$.data",
                    "rowCount", resultData.get("rowCount"),
                    "returnedRowCount", resultData.get("rowCount"),
                    "possiblyTruncated", resultData.get("possiblyTruncated"),
                    "readOnly", resultData.get("readOnly")
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
            workflow ? databaseQueryWorkflowGraphEdges(resultData) : List.of()
        ));
        return payload;
    }

    /**
     * Keeps the tabular schema aligned with rows after generic JSON-cell promotion.
     * Consumers intentionally use {@code columns} as their evidence boundary, so a
     * field discovered dynamically in a row must also be declared there.  The same
     * rule is applied to nested result sets and workflow nodes without knowing any
     * tool, table, or business field names.
     */
    private void synchronizeResultColumns(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return;
        }
        Object rowsValue = result.get("rows");
        if (rowsValue instanceof Iterable<?> rows) {
            java.util.LinkedHashSet<String> columns = new java.util.LinkedHashSet<>();
            if (result.get("columns") instanceof Iterable<?> declared) {
                declared.forEach(column -> {
                    if (column != null) {
                        columns.add(String.valueOf(column));
                    }
                });
            }
            for (Object rowValue : rows) {
                if (rowValue instanceof Map<?, ?> row) {
                    row.keySet().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(String::valueOf)
                        .forEach(columns::add);
                }
            }
            result.put("columns", List.copyOf(columns));
            synchronizeColumnMetadata(result, columns);
        }
        for (String childKey : List.of("results", "resultSets", "nodeExecutions")) {
            Object children = result.get(childKey);
            if (children instanceof Iterable<?> iterable) {
                for (Object child : iterable) {
                    if (child instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> nested = (Map<String, Object>) map;
                        synchronizeResultColumns(nested);
                    }
                }
            }
        }
    }

    private void synchronizeColumnMetadata(Map<String, Object> result, Set<String> columns) {
        List<Map<String, Object>> existing = listOfMaps(result.get("columnMetadata"));
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> metadata : existing) {
            String name = firstText(stringValue(metadata.get("name")), stringValue(metadata.get("label")));
            if (name != null) {
                byName.putIfAbsent(name, metadata);
            }
        }
        for (String column : columns) {
            byName.computeIfAbsent(column, key -> mapOf(
                "name", key,
                "label", key,
                "comment", null,
                "masked", false,
                "dynamic", true
            ));
        }
        result.put("columnMetadata", List.copyOf(byName.values()));
    }

    private List<Map<String, Object>> databaseQueryMultiSqlSteps(Map<String, Object> resultData,
                                                                 Map<String, Object> arguments,
                                                                 long durationMs,
                                                                 String errorMessage) {
        List<Map<String, Object>> resultSets = listOfMaps(firstPresent(resultData.get("resultSets"), resultData.get("results")));
        if (resultSets.isEmpty()) {
            return List.of(step(1, "sql", Map.of(), mapOf(
                "resultSetReference", "$.data",
                "rowCount", resultData.get("rowCount")
            ), false, durationMs, errorMessage, Map.of()));
        }
        String resultSetField = resultData.get("resultSets") instanceof List<?> ? "resultSets" : "results";
        return IntStream.range(0, resultSets.size())
            .mapToObj(position -> {
                Map<String, Object> resultSet = resultSets.get(position);
                int index = intValue(resultSet.get("executionOrder"), position + 1);
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
                    mapOf(
                        "resultSetReference", "$.data." + resultSetField + "[" + position + "]",
                        "rowCount", resultSet.get("rowCount"),
                        "returnedRowCount", resultSet.get("rows") instanceof List<?> rows
                            ? rows.size() : resultSet.get("returnedRowCount"),
                        "possiblyTruncated", resultSet.get("possiblyTruncated")
                    ),
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
        List<Map<String, Object>> resultSets = "database_query_workflow".equals(resultData.get("mode"))
            ? listOfMaps(firstPresent(resultData.get("nodeExecutions"), resultData.get("resultSets")))
            : listOfMaps(firstPresent(resultData.get("resultSets"), resultData.get("results")));
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

    private List<Map<String, Object>> databaseQueryWorkflowGraphEdges(Map<String, Object> resultData) {
        List<Map<String, Object>> resultSets = listOfMaps(firstPresent(resultData.get("nodeExecutions"), resultData.get("resultSets")));
        List<Map<String, Object>> edges = new java.util.ArrayList<>();
        for (Map<String, Object> resultSet : resultSets) {
            String targetCode = firstText(stringValue(resultSet.get("nodeCode")), stringValue(resultSet.get("sqlCode")));
            Object dependencies = resultSet.get("dependencies");
            if (targetCode == null || !(dependencies instanceof List<?> list)) continue;
            for (Object dependency : list) {
                if (dependency == null) continue;
                edges.add(mapOf(
                    "from", databaseQueryGraphNodeId(String.valueOf(dependency), resultSets),
                    "to", databaseQueryGraphNodeId(targetCode, resultSets),
                    "type", "depends_on_success"
                ));
            }
        }
        return edges;
    }

    private String databaseQueryGraphNodeId(String code, List<Map<String, Object>> resultSets) {
        for (int index = 0; index < resultSets.size(); index++) {
            Map<String, Object> resultSet = resultSets.get(index);
            String current = firstText(stringValue(resultSet.get("nodeCode")), stringValue(resultSet.get("sqlCode")));
            if (code != null && code.equals(current)) {
                return "sql_result_set_" + intValue(resultSet.get("executionOrder"), index + 1);
            }
        }
        return "sql_node_" + code;
    }

    private Map<String, Object> databaseQueryAnalysisContext(DatabaseQueryConfig config,
                                                             Map<String, Object> resultData,
                                                             String toolName) {
        if (config == null) {
            return Map.of();
        }
        String groupCode = firstText(config.getBusinessGroup(), "default");
        String groupName = firstText(config.getBusinessGroupName(), groupCode);
        String groupDescription = firstText(config.getBusinessGroupDescription(), "");
        return DataAnalysisContextProtocol.create(
            mapOf(
                "type", "database_query_template",
                "id", config.getId(),
                "displayName", firstText(config.getTitle(), config.getToolName()),
                "toolName", toolName,
                "description", config.getDescription()
            ),
            mapOf(
                "intent", firstText(config.getTemplateIntent(), "general_query"),
                "implementationSteps", config.getImplementationSteps()
            ),
            mapOf(
                "code", groupCode,
                "name", groupName,
                "description", groupDescription
            ),
            mapOf(
                "fields", resultData == null ? List.of() : resultData.get("columnMetadata")
            ),
            Map.of()
        );
    }

    private Map<String, Object> linuxCommandContext(LinuxCommandResult result,
                                                    List<LinuxCommandStepResult> steps) {
        Map<String, Object> request = result.request() == null ? Map.of() : result.request();
        List<Map<String, Object>> commands = IntStream.range(0, steps.size())
            .mapToObj(position -> {
                LinuxCommandStepResult step = steps.get(position);
                return commandDescriptor(
                    firstText(step.stepCode(), "STEP_" + step.stepIndex()),
                    step.stepIndex(),
                    firstText(step.stepName(), step.stepCode(), "Step " + step.stepIndex()),
                    firstText(step.analysisHint(), step.stepName()),
                    step.analysisHint(),
                    "$.data"
                );
            })
            .toList();
        return commandContext(
            result.template(),
            firstText(stringValue(request.get("templateTitle")), result.template()),
            firstText(stringValue(request.get("templateDescription")), stringValue(request.get("reason"))),
            firstText(contextExecutionMode(request), "SEQUENTIAL"),
            commands,
            sequentialCommandReferences(commands)
        );
    }

    private String contextExecutionMode(Map<String, Object> request) {
        if (request == null || !(request.get("templateDsl") instanceof Map<?, ?> templateDsl)) {
            return null;
        }
        return stringValue(templateDsl.get("executionMode"));
    }

    private Map<String, Object> sqlScriptCommandContext(SqlScriptResult result) {
        List<Map<String, Object>> commands = IntStream.range(0, result.results().size())
            .mapToObj(position -> {
                SqlScriptStatementResult statement = result.results().get(position);
                return commandDescriptor(
                    firstText(statement.stepCode(), "STEP_" + statement.statementIndex()),
                    statement.statementIndex(),
                    firstText(statement.stepName(), statement.stepCode(), "Step " + statement.statementIndex()),
                    firstText(statement.analysisHint(), statement.stepName()),
                    statement.analysisHint(),
                    "$.data.results[" + position + "]"
                );
            })
            .toList();
        return commandContext(
            result.toolName(), result.toolName(), result.purpose(), "SEQUENTIAL",
            commands, sequentialCommandReferences(commands));
    }

    private Map<String, Object> databaseQueryCommandContext(DatabaseQueryConfig config,
                                                             Map<String, Object> resultData,
                                                             String toolName,
                                                             boolean multiSql,
                                                             boolean workflow) {
        List<Map<String, Object>> resultSets = listOfMaps(firstPresent(
            resultData.get("nodeExecutions"), resultData.get("resultSets"), resultData.get("results")));
        String resultSetField = resultData.get("nodeExecutions") instanceof List<?> ? "nodeExecutions"
            : resultData.get("resultSets") instanceof List<?> ? "resultSets" : "results";
        List<Map<String, Object>> commands;
        if (resultSets.isEmpty()) {
            commands = List.of(commandDescriptor(
                "sql_query", 1, config == null ? toolName : firstText(config.getTitle(), toolName),
                config == null ? null : firstText(config.getDescription(), config.getImplementationSteps()),
                null, "$.data"));
        } else {
            commands = IntStream.range(0, resultSets.size())
                .mapToObj(position -> {
                    Map<String, Object> resultSet = resultSets.get(position);
                    String commandId = firstText(
                        stringValue(resultSet.get("nodeCode")),
                        stringValue(resultSet.get("sqlCode")),
                        stringValue(resultSet.get("stepCode")),
                        "sql_result_" + (position + 1));
                    return commandDescriptor(
                        commandId,
                        intValue(resultSet.get("executionOrder"), position + 1),
                        firstText(stringValue(resultSet.get("sqlName")), stringValue(resultSet.get("stepName")), commandId),
                        firstText(stringValue(resultSet.get("description")), stringValue(resultSet.get("analysisHint"))),
                        stringValue(resultSet.get("analysisHint")),
                        "$.data." + resultSetField + "[" + position + "]"
                    );
                })
                .toList();
        }
        List<Map<String, Object>> references = workflow
            ? databaseQueryCommandReferences(resultSets)
            : multiSql ? sequentialCommandReferences(commands) : List.of();
        return commandContext(
            config == null ? toolName : firstText(config.getToolName(), config.getId()),
            config == null ? toolName : firstText(config.getTitle(), config.getToolName()),
            config == null ? null : firstText(config.getDescription(), config.getImplementationSteps()),
            workflow ? "DEPENDENCY_GRAPH" : multiSql ? "SEQUENTIAL_MULTI_SQL" : "SINGLE_SQL",
            commands,
            references
        );
    }

    private List<Map<String, Object>> databaseQueryCommandReferences(List<Map<String, Object>> resultSets) {
        List<Map<String, Object>> references = new java.util.ArrayList<>();
        for (Map<String, Object> resultSet : resultSets) {
            String target = firstText(stringValue(resultSet.get("nodeCode")), stringValue(resultSet.get("sqlCode")),
                stringValue(resultSet.get("stepCode")));
            if (target == null || !(resultSet.get("dependencies") instanceof Iterable<?> dependencies)) {
                continue;
            }
            for (Object dependency : dependencies) {
                if (dependency != null) {
                    references.add(commandReference(String.valueOf(dependency), target, "DEPENDS_ON_SUCCESS"));
                }
            }
        }
        return List.copyOf(references);
    }

    private Map<String, Object> commandContext(String templateId,
                                               String templateName,
                                               String description,
                                               String executionMode,
                                               List<Map<String, Object>> commands,
                                               List<Map<String, Object>> references) {
        return mapOf(
            "schemaVersion", "template_result_context.v1",
            "templateId", templateId,
            "templateName", templateName,
            "description", description,
            "executionMode", executionMode,
            "commands", commands == null ? List.of() : commands,
            "references", references == null ? List.of() : references
        );
    }

    private Map<String, Object> commandDescriptor(String commandId,
                                                  int order,
                                                  String name,
                                                  String description,
                                                  String analysisHint,
                                                  String resultReference) {
        return mapOf(
            "commandId", commandId,
            "order", order,
            "name", name,
            "description", description,
            "analysisHint", analysisHint,
            "resultReference", resultReference
        );
    }

    private List<Map<String, Object>> sequentialCommandReferences(List<Map<String, Object>> commands) {
        if (commands == null || commands.size() < 2) {
            return List.of();
        }
        List<Map<String, Object>> references = new java.util.ArrayList<>();
        for (int index = 1; index < commands.size(); index++) {
            references.add(commandReference(
                stringValue(commands.get(index - 1).get("commandId")),
                stringValue(commands.get(index).get("commandId")),
                "EXECUTION_ORDER"
            ));
        }
        return List.copyOf(references);
    }

    private Map<String, Object> commandReference(String fromCommandId,
                                                 String toCommandId,
                                                 String relation) {
        return mapOf(
            "fromCommandId", fromCommandId,
            "toCommandId", toCommandId,
            "relation", relation
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
        payload.put("dataCompleteness", mapOf(
            "contractVersion", "mcp_complete_result.v1",
            "complete", true,
            "gatewayTruncated", false,
            "projectionRequiredByConsumer", true
        ));
        payload.put("error", errorMessage == null || errorMessage.isBlank()
            ? null
            : mapOf("message", errorMessage));
        return payload;
    }

    private Map<String, Object> sqlSourceMetadata(SqlQueryResult result) {
        Map<String, Object> template = nestedMap(result.diagnostics(), "templateMetadata");
        String statement = result.normalizedSql() == null ? result.sql() : result.normalizedSql();
        return sourceMetadata(
            "SQL_QUERY",
            "sql_statement",
            result.toolName(),
            mapOf("type", "database", "id", result.datasourceId(), "name", result.datasourceName(),
                "environment", result.environment()),
            mapOf("statementHash", sha256(statement), "templateId", template.get("templateId")),
            firstText(stringValue(template.get("businessName")), result.purpose(), "SQL query"),
            firstText(stringValue(template.get("businessDescription")), result.purpose()),
            firstText(stringValue(template.get("category")), "database_query")
        );
    }

    private Map<String, Object> sqlScriptSourceMetadata(SqlScriptResult result) {
        Map<String, Object> template = nestedMap(result.diagnostics(), "templateMetadata");
        return sourceMetadata(
            "SQL_SCRIPT",
            "sql_script",
            result.toolName(),
            mapOf("type", "database", "id", result.datasourceId(), "name", result.datasourceName(),
                "environment", result.environment()),
            mapOf("scriptHash", sha256(result.script()), "templateId", template.get("templateId"),
                "statementCount", result.statementCount()),
            firstText(stringValue(template.get("businessName")), result.purpose(), "SQL script"),
            firstText(stringValue(template.get("businessDescription")), result.purpose()),
            firstText(stringValue(template.get("category")), "database_query")
        );
    }

    private Map<String, Object> sqlStatementSourceMetadata(SqlScriptStatementResult result) {
        return sourceMetadata(
            "SQL_SCRIPT_STEP",
            "sql_statement",
            "sql_query_execute",
            Map.of(),
            mapOf("statementIndex", result.statementIndex(), "stepCode", result.stepCode(),
                "statementHash", sha256(result.sql())),
            firstText(result.stepName(), result.stepCode(), "SQL step " + result.statementIndex()),
            firstText(result.analysisHint(), result.stepName()),
            "database_query_step"
        );
    }

    private Map<String, Object> linuxSourceMetadata(LinuxCommandResult result) {
        Map<String, Object> request = result.request() == null ? Map.of() : result.request();
        return sourceMetadata(
            "LINUX_COMMAND",
            "shell_command",
            result.toolName(),
            mapOf("type", "server", "id", result.hostId(), "name", result.host(),
                "environment", result.environment()),
            mapOf("template", result.template(), "commandHash", result.commandHash()),
            firstText(stringValue(request.get("templateTitle")), result.template(), "Linux command"),
            firstText(stringValue(request.get("templateDescription")), stringValue(request.get("reason"))),
            firstText(stringValue(request.get("templateCategory")), "system_operation")
        );
    }

    private Map<String, Object> httpSourceMetadata(HttpRequestToolResult result) {
        Map<String, Object> metadata = result.sourceMetadata() == null ? Map.of() : result.sourceMetadata();
        return sourceMetadata(
            "HTTP_REQUEST",
            "http_request",
            firstText(stringValue(metadata.get("toolName")), "http_request"),
            mapOf("type", "http_endpoint", "id", metadata.get("endpointId"),
                "name", firstText(stringValue(metadata.get("endpointName")), "HTTP endpoint"),
                "environment", metadata.get("environment")),
            mapOf("method", result.method(), "endpointId", metadata.get("endpointId")),
            firstText(stringValue(metadata.get("businessName")), stringValue(metadata.get("endpointName")), "HTTP request"),
            stringValue(metadata.get("businessDescription")),
            firstText(stringValue(metadata.get("category")), "http_api")
        );
    }

    private Map<String, Object> sourceMetadata(String executionType, String sourceType, String toolName,
                                               Map<String, Object> asset, Map<String, Object> operation,
                                               String businessName, String businessDescription, String category) {
        return mapOf(
            "schemaVersion", "execution_source.v1",
            "executionType", executionType,
            "sourceType", sourceType,
            "toolName", toolName,
            "asset", asset == null ? Map.of() : asset,
            "operation", operation == null ? Map.of() : operation,
            "business", mapOf(
                "name", businessName,
                "description", businessDescription,
                "category", category
            )
        );
    }

    private Map<String, Object> runtimeSafeSqlRow(Map<String, Object> row,
                                                  List<Map<String, Object>> columnMetadata) {
        if (row == null || row.isEmpty()) {
            return Map.of();
        }
        Set<String> masked = maskedColumnNames(columnMetadata);
        Map<String, Object> safe = new LinkedHashMap<>();
        row.forEach((key, value) -> safe.put(
            key,
            masked.contains(normalizedColumnName(key)) ? "***" : runtimeSafeEvidenceValue(value)
        ));
        return safe;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runtimeSafeEvidenceMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return (Map<String, Object>) runtimeSafeEvidenceValue(value);
    }

    /** Preserves complete template evidence for Runtime externalization while removing connection coordinates. */
    private Object runtimeSafeEvidenceValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                if (!isDatabaseConnectionUrlKey(name)) {
                    Object normalized = runtimeSafeEvidenceValue(item);
                    safe.put(name, normalized);
                    if (item instanceof CharSequence && normalized instanceof Map<?, ?> structured) {
                        structured.forEach((structuredKey, structuredValue) -> {
                            if (structuredKey != null) {
                                safe.putIfAbsent(String.valueOf(structuredKey), structuredValue);
                            }
                        });
                    }
                }
            });
            return safe;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> safe = new java.util.ArrayList<>();
            iterable.forEach(item -> safe.add(runtimeSafeEvidenceValue(item)));
            return safe;
        }
        if (value instanceof CharSequence text) {
            String raw = text.toString();
            Object structured = structuredJsonValue(raw);
            return structured == null ? raw : runtimeSafeEvidenceValue(structured);
        }
        return value;
    }

    /**
     * Promotes JSON object/array strings returned by any integration into native
     * structures before the evidence crosses the model boundary. This is deliberately
     * schema- and tool-agnostic: published tools remain free to add fields without a
     * matching Runtime code change.
     */
    private Object structuredJsonValue(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.length() < 2 || candidate.length() > STRUCTURED_JSON_TEXT_LIMIT) {
            return null;
        }
        boolean object = candidate.charAt(0) == '{' && candidate.charAt(candidate.length() - 1) == '}';
        boolean array = candidate.charAt(0) == '[' && candidate.charAt(candidate.length() - 1) == ']';
        if (!object && !array) {
            return null;
        }
        try {
            Object decoded = JSON.readValue(candidate, Object.class);
            return decoded instanceof Map<?, ?> || decoded instanceof List<?> ? decoded : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Set<String> maskedColumnNames(List<Map<String, Object>> columnMetadata) {
        if (columnMetadata == null) {
            return Set.of();
        }
        return columnMetadata.stream()
            .filter(column -> column != null && Boolean.TRUE.equals(column.get("masked")))
            .flatMap(column -> java.util.stream.Stream.of(
                column.get("name"), column.get("label"), column.get("technicalName")))
            .filter(java.util.Objects::nonNull)
            .map(String::valueOf)
            .map(this::normalizedColumnName)
            .collect(Collectors.toSet());
    }

    private String normalizedColumnName(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> modelSafeMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        // MCP is the lossless fact boundary. It may redact connection secrets, but it
        // must never shorten strings or collections for a model context window.
        // Context projection belongs to Runtime/Agent after this complete value has
        // been persisted as evidence.
        return (Map<String, Object>) runtimeSafeEvidenceValue(value);
    }

    private boolean isDatabaseConnectionUrlKey(String key) {
        String normalized = key == null ? "" : key.replace("_", "").replace("-", "")
            .toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("jdbcurl")
            || normalized.equals("databaseurl")
            || normalized.equals("connectionurl");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        if (source == null || !(source.get(key) instanceof Map<?, ?> value)) {
            return Map.of();
        }
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    /**
     * Command output has one canonical home in data.stdout/data.stderr for each
     * template invocation. Step entries are control-plane metadata only. Keeping
     * another copy of every stream in both data.steps and execution.steps made a
     * perfectly ordinary command result exceed the transport budget.
     */
    private List<Map<String, Object>> commandStepSummaries(List<LinuxCommandStepResult> steps) {
        if (steps == null) {
            return List.of();
        }
        return steps.stream()
            .map(this::commandStepSummary)
            .toList();
    }

    private Map<String, Object> commandStepSummary(LinuxCommandStepResult step) {
        return mapOf(
            "stepIndex", step.stepIndex(),
            "stepCode", step.stepCode(),
            "stepName", step.stepName(),
            "stepType", step.stepType(),
            "required", step.required(),
            "analysisHint", step.analysisHint(),
            "command", step.command(),
            "commandHash", step.commandHash(),
            "sourceMetadata", sourceMetadata(
                "LINUX_COMMAND_STEP",
                "shell_command",
                "linux_command_execute",
                Map.of(),
                mapOf("stepIndex", step.stepIndex(), "stepCode", step.stepCode(),
                    "commandHash", step.commandHash()),
                firstText(step.stepName(), step.stepCode(), "Linux command step " + step.stepIndex()),
                firstText(step.analysisHint(), step.stepName()),
                "system_operation_step"
            ),
            "exitCode", step.exitCode(),
            "success", step.success(),
            "durationMs", step.durationMs(),
            "stdoutLength", textLength(step.stdout()),
            "stderrLength", textLength(step.stderr()),
            "outputReference", "$.data.stdout",
            "errorReference", "$.data.stderr"
        );
    }

    private Map<String, Object> linuxExecution(LinuxCommandResult result,
                                               List<LinuxCommandStepResult> steps) {
        return execution(
            result.toolName(),
            result.durationMs(),
            steps.stream().map(step -> {
                return step(
                    step.stepIndex(),
                    "command",
                    mapOf(
                        "stepCode", step.stepCode(),
                        "commandHash", step.commandHash()
                    ),
                    mapOf(
                        "exitCode", step.exitCode(),
                        "stdoutLength", textLength(step.stdout()),
                        "stderrLength", textLength(step.stderr()),
                        "resultSetReference", "$.data"
                    ),
                    step.success(),
                    step.durationMs(),
                    step.success() ? null : "Command step exited with code " + step.exitCode(),
                    mapOf(
                        "exitCode", step.exitCode(),
                        "resultSetId", firstText(result.template(), result.commandHash(), "linux-command-result")
                    )
                );
            }).toList()
        );
    }

    private int textLength(String value) {
        return value == null ? 0 : value.length();
    }

    private BoundedText completeCapturedText(String value) {
        String text = value == null ? "" : value;
        boolean captureTruncated = text.contains("...[capture truncated ")
            || text.contains("...[aggregate output truncated; preserving tail]...");
        return new BoundedText(text, text.length(), captureTruncated);
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

    private Map<String, Object> sqlOutputGovernance(SqlQueryResult result,
                                                    int effectiveRowCount,
                                                    int returnedRowCount) {
        return mapOf(
            "schemaVersion", "sql_output_governance.v1",
            "readOnly", true,
            "rowCount", effectiveRowCount,
            "returnedRowCount", returnedRowCount,
            "possiblyTruncated", result.possiblyTruncated() || effectiveRowCount > returnedRowCount,
            "truncationStrategy", "DATABASE_MAX_ROWS_" + result.maxRows(),
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
