package com.chatchat.mcpserver.tool;

import com.chatchat.mcpserver.ops.HttpRequestToolResult;
import com.chatchat.mcpserver.ops.LinuxCommandResult;
import com.chatchat.mcpserver.ops.LinuxCommandStepResult;
import com.chatchat.mcpserver.sql.SqlQueryResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class StandardToolExecutionResultFactory {

    public static final String SCHEMA_VERSION = "tool_execution_result.v1";
    public static final int SQL_RESULT_ROW_LIMIT = 50;

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
            "sourceTaskId", result.sourceTaskId()
        ));
        List<Map<String, Object>> rows = result.rows() == null
            ? List.of()
            : result.rows().stream().limit(SQL_RESULT_ROW_LIMIT).toList();
        payload.put("limits", mapOf(
            "maxRowsRequested", result.maxRows(),
            "maxRowsReturnedToModel", SQL_RESULT_ROW_LIMIT,
            "truncationStrategy", "LIMIT_50"
        ));
        payload.put("data", mapOf(
            "columns", result.columns(),
            "rows", rows,
            "rowCount", result.rowCount(),
            "returnedRowCount", rows.size(),
            "possiblyTruncated", result.possiblyTruncated() || result.rowCount() > rows.size(),
            "truncationStrategy", "LIMIT_50"
        ));
        payload.put("executionGraph", graph(
            List.of(graphNode("sql_query", "sql.query", result.success(), result.durationMs())),
            List.of()
        ));
        return payload;
    }

    public Map<String, Object> fromLinuxCommand(LinuxCommandResult result) {
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
            "toolName", result.toolName(),
            "environment", result.environment()
        ));
        payload.put("operation", mapOf(
            "type", "ssh.command_steps",
            "template", result.template(),
            "commandHash", result.commandHash(),
            "sourceTaskId", result.request() == null ? null : result.request().get("sourceTaskId"),
            "reason", result.request() == null ? null : result.request().get("reason")
        ));
        payload.put("data", mapOf(
            "exitCode", result.exitCode(),
            "steps", stepResults(result.steps()),
            "failedStepIndex", result.failedStepIndex(),
            "failedCommand", result.failedCommand(),
            "outputMode", "separated",
            "stdout", result.stdout(),
            "stderr", result.stderr()
        ));
        payload.put("executionGraph", graph(stepGraphNodes(result.steps()), stepGraphEdges(result.steps())));
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
        payload.put("data", mapOf(
            "statusCode", result.statusCode(),
            "headers", result.headers(),
            "body", result.body(),
            "rawBody", result.rawBody()
        ));
        payload.put("executionGraph", graph(
            List.of(graphNode("http_request", "http.request", result.success(), result.durationMs())),
            List.of()
        ));
        return payload;
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

    private List<Map<String, Object>> stepResults(List<LinuxCommandStepResult> steps) {
        if (steps == null) {
            return List.of();
        }
        return steps.stream()
            .map(step -> mapOf(
                "stepIndex", step.stepIndex(),
                "command", step.command(),
                "commandHash", step.commandHash(),
                "exitCode", step.exitCode(),
                "success", step.success(),
                "durationMs", step.durationMs(),
                "stdout", step.stdout(),
                "stderr", step.stderr()
            ))
            .toList();
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
}
