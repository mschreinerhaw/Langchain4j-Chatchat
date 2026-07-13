package com.chatchat.mcpserver.tool;

import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
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
            List.of(Map.of(
                "name", "id",
                "label", "id",
                "comment", "customer id",
                "typeName", "INTEGER",
                "masked", true
            )),
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
        Map<?, ?> execution = (Map<?, ?>) envelope.get("execution");
        Map<?, ?> step = (Map<?, ?>) ((List<?>) execution.get("steps")).get(0);
        Map<?, ?> input = (Map<?, ?>) step.get("input");
        Map<?, ?> output = (Map<?, ?>) step.get("output");
        Map<?, ?> governance = (Map<?, ?>) output.get("governance");
        Map<?, ?> firstColumn = (Map<?, ?>) ((List<?>) output.get("columnMetadata")).get(0);
        Map<?, ?> graph = (Map<?, ?>) envelope.get("executionGraph");

        assertThat(envelope).containsEntry("schemaVersion", StandardToolExecutionResultFactory.SCHEMA_VERSION);
        assertThat(envelope).containsEntry("kind", "sql_query");
        assertThat(envelope).containsEntry("dataSchema", "sql_result.v1");
        assertThat(envelope).containsEntry("payloadType", "structured");
        assertThat(execution.get("schemaVersion")).isEqualTo("execution_unit.v1");
        assertThat(execution.get("toolName")).isEqualTo("sql_main");
        assertThat(step.get("stepType")).isEqualTo("sql");
        assertThat(input.get("statement")).isEqualTo("SELECT * FROM t");
        assertThat(output.get("rowCount")).isEqualTo(60);
        assertThat(firstColumn.get("comment")).isEqualTo("customer id");
        assertThat(governance.get("schemaVersion")).isEqualTo("sql_output_governance.v1");
        assertThat(((List<?>) governance.get("maskedColumns")).stream().map(String::valueOf).toList()).contains("id");
        assertThat(governance.get("columnCommentsIncluded")).isEqualTo(true);
        assertThat((List<?>) data.get("rows")).hasSize(50);
        assertThat(data.get("rowCount")).isEqualTo(60);
        assertThat(data.get("returnedRowCount")).isEqualTo(50);
        assertThat(data.get("complete")).isEqualTo(false);
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
        Map<?, ?> target = (Map<?, ?>) envelope.get("target");
        Map<?, ?> operation = (Map<?, ?>) envelope.get("operation");
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        Map<?, ?> execution = (Map<?, ?>) envelope.get("execution");
        Map<?, ?> secondStep = (Map<?, ?>) ((List<?>) execution.get("steps")).get(1);
        Map<?, ?> secondInput = (Map<?, ?>) secondStep.get("input");
        Map<?, ?> secondOutput = (Map<?, ?>) secondStep.get("output");
        Map<?, ?> diagnostics = (Map<?, ?>) data.get("diagnostics");
        Map<?, ?> graph = (Map<?, ?>) envelope.get("executionGraph");

        assertThat(envelope).containsEntry("schemaVersion", StandardToolExecutionResultFactory.SCHEMA_VERSION);
        assertThat(envelope).containsEntry("kind", "ssh_command");
        assertThat(envelope).containsEntry("dataSchema", "ssh_steps.v1");
        assertThat(target.get("address")).isEqualTo("10.0.0.1");
        assertThat(target.get("addressType")).isEqualTo("ipv4");
        assertThat(target.get("ipAddress")).isEqualTo("10.0.0.1");
        assertThat(execution.get("schemaVersion")).isEqualTo("execution_unit.v1");
        assertThat(execution.get("toolName")).isEqualTo("ssh_host");
        assertThat(secondStep.get("stepType")).isEqualTo("command");
        assertThat(secondStep.get("exitCode")).isEqualTo(1);
        assertThat(secondInput.get("command")).isEqualTo("false");
        assertThat(secondOutput.get("stderr")).isEqualTo("failed");
        assertThat(data.get("failedStepIndex")).isEqualTo(2);
        assertThat(data.get("outputMode")).isEqualTo("separated");
        assertThat(operation.get("diagnostics")).isEqualTo(diagnostics);
        assertThat(diagnostics.get("schemaVersion")).isEqualTo("linux_command_diagnostics.v1");
        assertThat(diagnostics.get("stepCount")).isEqualTo(2);
        assertThat(diagnostics.get("failedStepIndex")).isEqualTo(2);
        assertThat(diagnostics.get("stderrLength")).isEqualTo(6);
        assertThat((List<?>) data.get("steps")).hasSize(2);
        assertThat((List<?>) graph.get("nodes")).hasSize(2);
        assertThat((List<?>) graph.get("edges")).hasSize(1);
    }

    @Test
    void linuxResultSeparatesTransportSuccessFromCommandExitStatus() {
        LinuxCommandResult result = new LinuxCommandResult(
            true,
            "host-1",
            "10.0.0.1",
            "ssh_host",
            "PROD",
            "CHECK_JAVA_PROCESS",
            "ps -eo pid,args | awk 'NR==1 || /[j]ava/'",
            "hash",
            List.of(new LinuxCommandStepResult(1, "grep java", "hash1", 1, "", "", 5, false)),
            1,
            "grep java",
            1,
            "",
            "",
            5,
            null,
            Map.of("sourceTaskId", "task-1")
        );

        Map<String, Object> envelope = factory.fromLinuxCommand(result);
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        Map<?, ?> diagnostics = (Map<?, ?>) data.get("diagnostics");

        assertThat(envelope).containsEntry("success", true);
        assertThat(envelope).containsEntry("status", "success");
        assertThat(data.get("transportSuccess")).isEqualTo(true);
        assertThat(data.get("commandSuccess")).isEqualTo(false);
        assertThat(data.get("exitCode")).isEqualTo(1);
        assertThat(diagnostics.get("transportSuccess")).isEqualTo(true);
        assertThat(diagnostics.get("commandSuccess")).isEqualTo(false);
        List<?> nonZeroStepIndexes = (List<?>) diagnostics.get("nonZeroStepIndexes");
        assertThat(nonZeroStepIndexes).hasSize(1);
        assertThat(nonZeroStepIndexes.get(0)).isEqualTo(1);
    }

    @Test
    void linuxResultPreservesHeadTailAndFailureFactsForLongStreams() {
        String stdout = "STDOUT_HEAD\n" + "x".repeat(70_000) + "\nSTDOUT_TAIL";
        String stderr = "STDERR_HEAD\n" + "y".repeat(70_000) + "\nFATAL_ERROR_AT_TAIL";
        LinuxCommandStepResult step = new LinuxCommandStepResult(
            1,
            "LONG_CHECK",
            "Long check",
            "SHELL",
            true,
            "inspect the tail error",
            "long-check",
            "hash-long",
            2,
            stdout,
            stderr,
            42,
            false
        );
        LinuxCommandResult result = new LinuxCommandResult(
            true,
            "host-1",
            "10.0.0.1",
            "ssh_host",
            "PROD",
            "LONG_CHECK",
            "long-check",
            "hash",
            List.of(step),
            1,
            "long-check",
            2,
            stdout,
            stderr,
            42,
            null,
            Map.of()
        );

        Map<String, Object> envelope = factory.fromLinuxCommand(result);
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        Map<?, ?> limits = (Map<?, ?>) data.get("outputLimits");
        Map<?, ?> returnedStep = (Map<?, ?>) ((List<?>) data.get("steps")).get(0);

        assertThat(data.get("exitCode")).isEqualTo(2);
        assertThat(data.get("commandSuccess")).isEqualTo(false);
        assertThat(limits.get("stdoutTruncated")).isEqualTo(true);
        assertThat(limits.get("stderrTruncated")).isEqualTo(true);
        assertThat(String.valueOf(data.get("stdout")))
            .contains("STDOUT_HEAD")
            .contains("[truncated")
            .contains("STDOUT_TAIL");
        assertThat(String.valueOf(data.get("stderr")))
            .contains("STDERR_HEAD")
            .contains("FATAL_ERROR_AT_TAIL");
        assertThat(returnedStep.get("stderrTruncated")).isEqualTo(true);
        assertThat(String.valueOf(returnedStep.get("stderr"))).contains("FATAL_ERROR_AT_TAIL");
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
        Map<?, ?> execution = (Map<?, ?>) envelope.get("execution");
        Map<?, ?> step = (Map<?, ?>) ((List<?>) execution.get("steps")).get(0);
        Map<?, ?> input = (Map<?, ?>) step.get("input");
        Map<?, ?> output = (Map<?, ?>) step.get("output");

        assertThat(envelope).containsEntry("schemaVersion", StandardToolExecutionResultFactory.SCHEMA_VERSION);
        assertThat(envelope).containsEntry("kind", "http_request");
        assertThat(envelope).containsEntry("dataSchema", "http_response.v1");
        assertThat(envelope).containsEntry("payloadType", "structured");
        assertThat(execution.get("schemaVersion")).isEqualTo("execution_unit.v1");
        assertThat(step.get("stepType")).isEqualTo("http");
        assertThat(input.get("method")).isEqualTo("GET");
        assertThat(output.get("statusCode")).isEqualTo(200);
        assertThat(data.get("statusCode")).isEqualTo(200);
        assertThat(envelope).containsKey("executionGraph");
    }

    @Test
    void databaseQueryOutputIsWrappedInStandardSqlExecutionUnit() {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("query-1");
        config.setToolName("db_query_customer");
        config.setTitle("Customer query");
        config.setBusinessGroup("fund_nav");
        config.setBusinessGroupName("基金净值核对");
        config.setBusinessGroupDescription("用于跨渠道基金净值一致性分析");
        config.setTemplateIntent("nav_reconciliation");
        config.setSqlTemplate("select * from customer where id = :id");
        config.setMaxRows(20);
        ToolOutput output = ToolOutput.success(Map.of(
            "sql", "select * from customer where id = :id",
            "dataSource", "external",
            "rowCount", 1,
            "maxRows", 20,
            "columns", List.of("id", "name"),
            "columnComments", Map.of("name", "Customer name"),
            "rows", List.of(Map.of("id", "c-1", "name", "Alice")),
            "readOnly", true,
            "possiblyTruncated", false
        ));
        output.setExecutionTimeMs(15L);

        Map<String, Object> envelope = factory.fromDatabaseQuery(config, Map.of("id", "c-1"), output);
        Map<?, ?> execution = (Map<?, ?>) envelope.get("execution");
        Map<?, ?> analysisContext = (Map<?, ?>) envelope.get("analysisContext");
        Map<?, ?> target = (Map<?, ?>) envelope.get("target");
        Map<?, ?> template = (Map<?, ?>) target.get("template");
        Map<?, ?> businessGroup = (Map<?, ?>) template.get("businessGroup");
        Map<?, ?> step = (Map<?, ?>) ((List<?>) execution.get("steps")).get(0);
        Map<?, ?> input = (Map<?, ?>) step.get("input");
        Map<?, ?> stepOutput = (Map<?, ?>) step.get("output");
        Map<?, ?> nameColumn = (Map<?, ?>) ((List<?>) stepOutput.get("columnMetadata")).get(1);

        assertThat(envelope).containsEntry("schemaVersion", StandardToolExecutionResultFactory.SCHEMA_VERSION);
        assertThat(envelope).containsEntry("kind", "sql_query");
        assertThat(envelope).containsEntry("dataSchema", "sql_result.v1");
        assertThat(execution.get("toolName")).isEqualTo("db_query_customer");
        assertThat(step.get("stepType")).isEqualTo("sql");
        assertThat(input.get("statement")).isEqualTo("select * from customer where id = :id");
        assertThat(stepOutput.get("rowCount")).isEqualTo(1);
        assertThat(nameColumn.get("comment")).isEqualTo("Customer name");
        assertThat(analysisContext.get("businessGroupName")).isEqualTo("基金净值核对");
        assertThat(analysisContext.get("businessGroupDescription")).isEqualTo("用于跨渠道基金净值一致性分析");
        assertThat(analysisContext.get("templateIntent")).isEqualTo("nav_reconciliation");
        assertThat(String.valueOf(analysisContext.get("modelAnalysisHint"))).contains("businessGroupName");
        assertThat(businessGroup.get("name")).isEqualTo("基金净值核对");
    }
}
