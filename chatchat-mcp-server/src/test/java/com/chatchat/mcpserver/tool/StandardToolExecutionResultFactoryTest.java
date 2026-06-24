package com.chatchat.mcpserver.tool;

import com.chatchat.mcpserver.ops.HttpRequestToolResult;
import com.chatchat.mcpserver.ops.LinuxCommandResult;
import com.chatchat.mcpserver.ops.LinuxCommandStepResult;
import com.chatchat.mcpserver.sql.SqlQueryResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StandardToolExecutionResultFactoryTest {

    private final StandardToolExecutionResultFactory factory = new StandardToolExecutionResultFactory();

    @Test
    void sqlResultUsesStandardEnvelopeAndLimitsRowsForModel() {
        List<Map<String, Object>> rows = IntStream.rangeClosed(1, 60)
            .mapToObj(index -> Map.<String, Object>of("id", index))
            .toList();
        SqlQueryResult result = new SqlQueryResult(
            true,
            "ds-1",
            "main-db",
            "sql_main",
            "PROD",
            "select * from t",
            "SELECT * FROM t",
            30,
            1000,
            List.of("id"),
            rows,
            rows.size(),
            false,
            12,
            "debug",
            "task-1",
            null
        );

        Map<String, Object> envelope = factory.fromSql(result);
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        Map<?, ?> limits = (Map<?, ?>) envelope.get("limits");
        Map<?, ?> graph = (Map<?, ?>) envelope.get("executionGraph");

        assertThat(envelope).containsEntry("schemaVersion", StandardToolExecutionResultFactory.SCHEMA_VERSION);
        assertThat(envelope).containsEntry("kind", "sql_query");
        assertThat(envelope).containsEntry("dataSchema", "sql_result.v1");
        assertThat(envelope).containsEntry("payloadType", "structured");
        assertThat((List<?>) data.get("rows")).hasSize(50);
        assertThat(data.get("rowCount")).isEqualTo(60);
        assertThat(data.get("returnedRowCount")).isEqualTo(50);
        assertThat(data.get("possiblyTruncated")).isEqualTo(true);
        assertThat(data.get("truncationStrategy")).isEqualTo("LIMIT_50");
        assertThat(limits.get("truncationStrategy")).isEqualTo("LIMIT_50");
        assertThat(graph.get("schemaVersion")).isEqualTo("execution_graph.v1");
    }

    @Test
    void linuxResultUsesStandardEnvelopeWithStepContext() {
        LinuxCommandResult result = new LinuxCommandResult(
            false,
            "host-1",
            "10.0.0.1",
            "ssh_host",
            "PROD",
            "CHECK",
            "uptime\nfalse",
            "hash",
            List.of(
                new LinuxCommandStepResult(1, "uptime", "hash1", 0, "ok", "", 5, true),
                new LinuxCommandStepResult(2, "false", "hash2", 1, "", "failed", 6, false)
            ),
            2,
            "false",
            1,
            "ok",
            "failed",
            11,
            "step failed",
            Map.of("sourceTaskId", "task-1")
        );

        Map<String, Object> envelope = factory.fromLinuxCommand(result);
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        Map<?, ?> graph = (Map<?, ?>) envelope.get("executionGraph");

        assertThat(envelope).containsEntry("schemaVersion", StandardToolExecutionResultFactory.SCHEMA_VERSION);
        assertThat(envelope).containsEntry("kind", "ssh_command");
        assertThat(envelope).containsEntry("dataSchema", "ssh_steps.v1");
        assertThat(data.get("failedStepIndex")).isEqualTo(2);
        assertThat(data.get("outputMode")).isEqualTo("separated");
        assertThat((List<?>) data.get("steps")).hasSize(2);
        assertThat((List<?>) graph.get("nodes")).hasSize(2);
        assertThat((List<?>) graph.get("edges")).hasSize(1);
    }

    @Test
    void httpResultUsesStandardEnvelope() {
        HttpRequestToolResult result = new HttpRequestToolResult(
            true,
            "GET",
            "https://example.test/status",
            200,
            Map.of("status", "ok"),
            "{\"status\":\"ok\"}",
            Map.of("content-type", "application/json"),
            20,
            null
        );

        Map<String, Object> envelope = factory.fromHttp(result);
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");

        assertThat(envelope).containsEntry("schemaVersion", StandardToolExecutionResultFactory.SCHEMA_VERSION);
        assertThat(envelope).containsEntry("kind", "http_request");
        assertThat(envelope).containsEntry("dataSchema", "http_response.v1");
        assertThat(envelope).containsEntry("payloadType", "structured");
        assertThat(data.get("statusCode")).isEqualTo(200);
        assertThat(envelope).containsKey("executionGraph");
    }
}
