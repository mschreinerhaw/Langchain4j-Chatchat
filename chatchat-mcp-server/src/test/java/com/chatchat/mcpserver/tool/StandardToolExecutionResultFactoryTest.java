package com.chatchat.mcpserver.tool;

import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.api.invocation.ApiInvokeResult;
import com.chatchat.mcpserver.api.registry.ApiServiceConfig;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfig;
import com.chatchat.mcpserver.notification.NotificationChannel;
import com.chatchat.mcpserver.notification.NotificationSendResult;
import com.chatchat.mcpserver.ops.http.HttpRequestToolResult;
import com.chatchat.mcpserver.ops.jmx.JmxMonitorResult;
import com.chatchat.mcpserver.ops.ssh.LinuxCommandResult;
import com.chatchat.mcpserver.ops.ssh.LinuxCommandStepResult;
import com.chatchat.mcpserver.sql.execution.SqlQueryResult;
import com.chatchat.mcpserver.sql.execution.SqlScriptResult;
import com.chatchat.mcpserver.sql.execution.SqlScriptStatementResult;
import com.chatchat.tools.builtin.DatabaseToolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StandardToolExecutionResultFactoryTest {

    private final DatabaseToolProperties databaseToolProperties = databaseToolProperties();
    private final StandardToolExecutionResultFactory factory = new StandardToolExecutionResultFactory(databaseToolProperties);

    @Test
    @SuppressWarnings("unchecked")
    void apiProducerPublishesItsGenericSemanticDeclarationInAnalysisContext() {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setId("generic-api");
        config.setToolName("generic_api_execute");
        config.setTitle("Generic metrics");
        config.setMethod("GET");
        config.setOutputSchemaJson("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"number\"}}}");
        config.setCapabilitySpecJson("""
            {"producerSemanticDeclaration":{
              "schemaVersion":"producer_semantic_declaration.v1",
              "capabilityId":"generic.observation",
              "allowedOperations":["OBSERVE"],
              "fields":[{"name":"value","meaning":"producer supplied value"}],
              "evidenceScope":{"grain":"","timeScope":"","populationScope":"","completeness":"UNKNOWN"},
              "rules":[]
            }}
            """);

        Map<String, Object> envelope = factory.fromApi(config, new ApiInvokeResult(
            true, 200, Map.of(), List.of(Map.of("value", 7)), "[{\"value\":7}]", null, false));
        Map<String, Object> context = (Map<String, Object>) envelope.get("analysisContext");

        assertThat(context.get("capability").toString())
            .contains("generic.observation", "OBSERVE");
        assertThat(context.get("producerSemanticDeclaration").toString())
            .contains("producer_semantic_declaration.v1");
        assertThat(context.toString()).doesNotContain("ETF", "customer", "finance");
    }

    @Test
    @SuppressWarnings("unchecked")
    void apiExecutionUsesStandardEnvelopeAndKeepsUpstreamCompletenessUnknown() {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setId("api-1");
        config.setToolName("api_template_execute");
        config.setTitle("Governed API");
        config.setDescription("Returns governed shareholder positions");
        config.setCategoryId("category-1");
        config.setBusinessGroup("securities");
        config.setBusinessGroupName("Securities business");
        config.setBusinessGroupDescription("Governed securities datasets");
        config.setMethod("GET");
        config.setCapabilitySpecJson("{\"summary\":\"Query shareholder positions\"}");
        config.setDependencySpecJson("{\"joinKeys\":[\"GDH\"]}");
        config.setOutputSchemaJson("""
            {
              "type": "object",
              "properties": {
                "GDH": {"type": "string", "description": "股东号"},
                "JYS": {"type": "string", "description": "交易所"}
              },
              "required": ["GDH"]
            }
            """);
        Map<String, Object> envelope = factory.fromApi(config, new ApiInvokeResult(
            true, 200, Map.of(), List.of(Map.of("GDH", "A000046604", "JYS", "SH")),
            "[{\"GDH\":\"A000046604\",\"JYS\":\"SH\"}]", null, false));

        assertThat(envelope)
            .containsEntry("schemaVersion", StandardToolExecutionResultFactory.SCHEMA_VERSION)
            .containsEntry("kind", "api_request")
            .containsEntry("success", true);
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        Map<String, Object> completeness = (Map<String, Object>) data.get("bodyCompleteness");
        assertThat(completeness)
            .containsEntry("gatewayTruncated", false)
            .containsEntry("upstreamCompleteness", "UNKNOWN")
            .containsEntry("topLevelRecordCount", 1);
        assertThat(data)
            .containsEntry("resultSetMode", "ONE_PER_TEMPLATE_EXECUTION")
            .containsEntry("resultSetId", "api-1");
        Map<String, Object> commandContext = (Map<String, Object>) data.get("commandContext");
        assertThat(commandContext)
            .containsEntry("templateId", "api-1")
            .containsEntry("templateName", "Governed API")
            .containsEntry("description", "Returns governed shareholder positions");
        Map<String, Object> apiCommand = (Map<String, Object>) ((List<?>) commandContext.get("commands")).get(0);
        assertThat(apiCommand)
            .containsEntry("commandId", "api-1")
            .containsEntry("resultReference", "$.data.body");
        assertThat(commandContext.get("references")).isEqualTo(List.of());
        Map<String, Object> execution = (Map<String, Object>) envelope.get("execution");
        Map<String, Object> executionStep = (Map<String, Object>) ((List<?>) execution.get("steps")).get(0);
        assertThat((Map<String, Object>) executionStep.get("output"))
            .containsEntry("resultSetReference", "$.data")
            .containsEntry("bodyReference", "$.data.body")
            .doesNotContainKeys("body", "rawBody");
        assertThat(data).doesNotContainKeys("outputSchema", "fieldMetadata", "columnMetadata");
        Map<String, Object> analysisContext = (Map<String, Object>) envelope.get("analysisContext");
        Map<String, Object> schema = (Map<String, Object>) analysisContext.get("schema");
        assertThat((Map<String, Object>) schema.get("definition"))
            .containsKey("properties");
        List<Map<String, Object>> fieldMetadata =
            (List<Map<String, Object>>) schema.get("fields");
        assertThat(fieldMetadata)
            .anySatisfy(field -> assertThat(field)
                .containsEntry("name", "GDH")
                .containsEntry("technicalName", "GDH")
                .containsEntry("description", "股东号")
                .containsEntry("comment", "股东号")
                .containsEntry("required", true))
            .anySatisfy(field -> assertThat(field)
                .containsEntry("name", "JYS")
                .containsEntry("comment", "交易所")
                .containsEntry("required", false));
        assertThat(analysisContext)
            .containsEntry("schemaVersion", "data_analysis_context.v1");
        assertThat((Map<String, Object>) analysisContext.get("source"))
            .containsEntry("type", "api_service")
            .containsEntry("displayName", "Governed API")
            .containsEntry("toolName", "api_template_execute")
            .containsEntry("description", "Returns governed shareholder positions");
        assertThat((Map<String, Object>) analysisContext.get("capability"))
            .containsEntry("summary", "Query shareholder positions");
        assertThat((Map<String, Object>) analysisContext.get("business"))
            .containsEntry("id", "category-1")
            .containsEntry("code", "securities")
            .containsEntry("name", "Securities business")
            .containsEntry("description", "Governed securities datasets");
        assertThat((Map<String, Object>) analysisContext.get("relationships"))
            .containsEntry("joinKeys", List.of("GDH"));
        assertThat(schema.get("fields")).isEqualTo(fieldMetadata);
    }

    @Test
    @SuppressWarnings("unchecked")
    void notificationIsAnOperationReceiptNotBusinessEvidence() {
        Map<String, Object> envelope = factory.fromNotification(new NotificationSendResult(
            true, NotificationChannel.EMAIL, "send_alert", 202, 1,
            Map.of("accepted", true), "accepted", null, Map.of("subject", "alert")));

        assertThat(envelope).containsEntry("kind", "notification_receipt");
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        assertThat(data)
            .containsEntry("evidenceRole", "OPERATION_RECEIPT")
            .containsEntry("businessEvidence", false);
    }

    @Test
    void jmxResultUsesStandardEnvelopeWithoutExposingConfiguredServiceUrl() {
        JmxMonitorResult result = new JmxMonitorResult(
            true,
            "JMX_KAFKA_BROKER_OVERVIEW",
            "service:jmx:rmi:///jndi/rmi://10.20.30.40:9999/jmxrmi",
            List.of(Map.of(
                "name", "heap",
                "objectName", "java.lang:type=Memory",
                "attributes", Map.of("HeapMemoryUsage", Map.of("used", 42L))
            )),
            List.of(),
            15L,
            null
        );

        Map<String, Object> envelope = factory.fromJmx(result);

        assertThat(envelope.get("schemaVersion")).isEqualTo(StandardToolExecutionResultFactory.SCHEMA_VERSION);
        assertThat(envelope.get("kind")).isEqualTo("jmx_monitor");
        assertThat(String.valueOf(envelope)).doesNotContain("10.20.30.40").doesNotContain("service:jmx");
        assertThat(((Map<?, ?>) envelope.get("target")).get("templateId"))
            .isEqualTo("JMX_KAFKA_BROKER_OVERVIEW");
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        Map<?, ?> execution = (Map<?, ?>) envelope.get("execution");
        Map<?, ?> step = (Map<?, ?>) ((List<?>) execution.get("steps")).get(0);
        Map<?, ?> output = (Map<?, ?>) step.get("output");
        assertThat(data.get("resultSetMode")).isEqualTo("ONE_PER_TEMPLATE_EXECUTION");
        assertThat(output.get("resultSetReference")).isEqualTo("$.data");
        assertThat(output.containsKey("metrics")).isFalse();
        Map<?, ?> commandContext = (Map<?, ?>) data.get("commandContext");
        assertThat(commandContext.get("description"))
            .isEqualTo("Read-only metrics collected by the configured JMX template");
    }

    @Test
    void jmxResultPreservesCompleteMetricSetOnlyInCanonicalData() {
        List<Map<String, Object>> metrics = IntStream.range(0, 250)
            .mapToObj(index -> Map.<String, Object>of(
                "name", "metric-" + index,
                "value", "UNIQUE_JMX_VALUE_" + index
            ))
            .toList();
        JmxMonitorResult result = new JmxMonitorResult(
            true, "GENERIC_MONITOR", "service:jmx:hidden", metrics, List.of(), 10L, null
        );

        Map<String, Object> envelope = factory.fromJmx(result);
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        Map<?, ?> execution = (Map<?, ?>) envelope.get("execution");
        Map<?, ?> step = (Map<?, ?>) ((List<?>) execution.get("steps")).get(0);
        Map<?, ?> output = (Map<?, ?>) step.get("output");

        assertThat((List<?>) data.get("metrics")).hasSize(250);
        assertThat(data.get("possiblyTruncated")).isEqualTo(false);
        assertThat(output.containsKey("metrics")).isFalse();
        assertThat(countOccurrences(envelope.toString(), "UNIQUE_JMX_VALUE_249")).isEqualTo(1);
    }

    @Test
    void sqlResultPreservesAllFetchedRowsForRuntimeChunkAnalysis() throws Exception {
        String fullCell = "SQL_CELL_HEAD\n" + "x".repeat(10_000) + "\nSQL_CELL_TAIL";
        Map<String, Object> completeDiagnostics = new LinkedHashMap<>();
        completeDiagnostics.put("connection", Map.of(
            "jdbcUrl", "jdbc:mysql://db.internal:3306/customer",
            "catalog", "customer"
        ));
        IntStream.range(0, 250).forEach(index ->
            completeDiagnostics.put("diagnostic-" + index, "value-" + index));
        completeDiagnostics.put("longDiagnostic", "DIAGNOSTIC_HEAD" + "z".repeat(5_000) + "DIAGNOSTIC_TAIL");
        List<Map<String, Object>> rows = IntStream.rangeClosed(1, 60)
            .mapToObj(index -> Map.<String, Object>of("id", index, "payload", fullCell + index))
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
            List.of("id", "payload"),
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
            null,
            completeDiagnostics
        );

        Map<String, Object> envelope = factory.fromSql(result);
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        Map<?, ?> limits = (Map<?, ?>) envelope.get("limits");
        Map<?, ?> execution = (Map<?, ?>) envelope.get("execution");
        Map<?, ?> step = (Map<?, ?>) ((List<?>) execution.get("steps")).get(0);
        Map<?, ?> input = (Map<?, ?>) step.get("input");
        Map<?, ?> output = (Map<?, ?>) step.get("output");
        Map<?, ?> governance = (Map<?, ?>) data.get("governance");
        Map<?, ?> firstColumn = (Map<?, ?>) ((List<?>) data.get("columnMetadata")).get(0);
        Map<?, ?> graph = (Map<?, ?>) envelope.get("executionGraph");
        Map<?, ?> sourceMetadata = (Map<?, ?>) envelope.get("sourceMetadata");
        Map<?, ?> sourceOperation = (Map<?, ?>) sourceMetadata.get("operation");
        Map<?, ?> sourceBusiness = (Map<?, ?>) sourceMetadata.get("business");
        Map<?, ?> diagnostics = (Map<?, ?>) data.get("diagnostics");
        Map<?, ?> completeness = (Map<?, ?>) envelope.get("dataCompleteness");

        assertThat(envelope).containsEntry("schemaVersion", StandardToolExecutionResultFactory.SCHEMA_VERSION);
        assertThat(envelope).containsEntry("kind", "sql_query");
        assertThat(envelope).containsEntry("dataSchema", "sql_result.v1");
        assertThat(envelope).containsEntry("payloadType", "structured");
        assertThat(completeness.get("contractVersion")).isEqualTo("mcp_complete_result.v1");
        assertThat(completeness.get("complete")).isEqualTo(true);
        assertThat(completeness.get("gatewayTruncated")).isEqualTo(false);
        assertThat(diagnostics.get("diagnostic-249")).isEqualTo("value-249");
        assertThat(String.valueOf(diagnostics.get("longDiagnostic")))
            .startsWith("DIAGNOSTIC_HEAD")
            .endsWith("DIAGNOSTIC_TAIL")
            .hasSize("DIAGNOSTIC_HEAD".length() + 5_000 + "DIAGNOSTIC_TAIL".length());
        assertThat(String.valueOf(diagnostics)).doesNotContain("truncated");
        assertThat(execution.get("schemaVersion")).isEqualTo("execution_unit.v1");
        assertThat(execution.get("toolName")).isEqualTo("sql_main");
        assertThat(step.get("stepType")).isEqualTo("sql");
        assertThat(input.get("statement")).isEqualTo("SELECT * FROM t");
        assertThat(output.get("rowCount")).isEqualTo(60);
        assertThat(output.get("resultSetReference")).isEqualTo("$.data");
        assertThat(output.containsKey("rows")).isFalse();
        assertThat(firstColumn.get("comment")).isEqualTo("customer id");
        assertThat(governance.get("schemaVersion")).isEqualTo("sql_output_governance.v1");
        assertThat(((List<?>) governance.get("maskedColumns")).stream().map(String::valueOf).toList()).contains("id");
        assertThat(governance.get("columnCommentsIncluded")).isEqualTo(true);
        assertThat((List<?>) data.get("rows")).hasSize(60);
        assertThat(data.get("rowCount")).isEqualTo(60);
        assertThat(data.get("returnedRowCount")).isEqualTo(60);
        assertThat(data.get("complete")).isEqualTo(true);
        assertThat(data.get("possiblyTruncated")).isEqualTo(false);
        assertThat(data.get("truncationStrategy")).isEqualTo("DATABASE_MAX_ROWS_1000");
        assertThat(limits.get("fullFetchedRowsAvailable")).isEqualTo(true);
        assertThat(limits.get("analysisStrategy")).isEqualTo("RUNTIME_EXTERNALIZE_AND_CHUNK");
        assertThat(((Map<?, ?>) ((List<?>) data.get("rows")).get(0)).get("payload"))
            .isEqualTo(fullCell + 1);
        assertThat(graph.get("schemaVersion")).isEqualTo("execution_graph.v1");
        assertThat(sourceMetadata.get("schemaVersion")).isEqualTo("execution_source.v1");
        assertThat(sourceMetadata.get("executionType")).isEqualTo("SQL_QUERY");
        assertThat(sourceOperation.containsKey("statement")).isFalse();
        assertThat(sourceOperation.get("statementHash")).isNotNull();
        assertThat(sourceBusiness.get("description")).isEqualTo("debug");
        assertThat(envelope.toString()).doesNotContain("jdbc:mysql://", "db.internal", "jdbcUrl");
        String json = new ObjectMapper().writeValueAsString(envelope);
        assertThat(countOccurrences(json, "SQL_CELL_HEAD")).isEqualTo(60);
    }

    @Test
    void sqlScriptExecutionReferencesCanonicalStatementResultSets() {
        SqlScriptStatementResult statement = new SqlScriptStatementResult(
            7, "LOAD_INVENTORY", "Load inventory", "SQL", true,
            "Use this result as the inventory evidence", true,
            "select payload from inventory", List.of("payload"), List.of(),
            List.of(Map.of("payload", "UNIQUE_SCRIPT_VALUE")), 1, false, 5L, null, Map.of()
        );
        SqlScriptResult result = new SqlScriptResult(
            true, "ds-1", "db", "sql_script_execute", "DEV", "script", 30, 100,
            1, List.of(statement), 5L, "inventory", "task-1", null, Map.of()
        );

        Map<String, Object> envelope = factory.fromSqlScript(result);
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        Map<?, ?> execution = (Map<?, ?>) envelope.get("execution");
        Map<?, ?> step = (Map<?, ?>) ((List<?>) execution.get("steps")).get(0);
        Map<?, ?> output = (Map<?, ?>) step.get("output");

        assertThat(data.get("resultSetMode")).isEqualTo("ONE_PER_TEMPLATE_EXECUTION");
        assertThat(execution.get("toolName")).isEqualTo("sql_query_execute");
        assertThat(output.get("resultSetReference")).isEqualTo("$.data.results[0]");
        assertThat(output.containsKey("rows")).isFalse();
        assertThat(countOccurrences(envelope.toString(), "UNIQUE_SCRIPT_VALUE")).isEqualTo(1);
        Map<?, ?> commandContext = (Map<?, ?>) data.get("commandContext");
        Map<?, ?> command = (Map<?, ?>) ((List<?>) commandContext.get("commands")).get(0);
        assertThat(command.get("description")).isEqualTo("Use this result as the inventory evidence");
        assertThat(command.get("resultReference")).isEqualTo("$.data.results[0]");
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
            Map.of(
                "sourceTaskId", "task-1",
                "templateTitle", "System state check",
                "templateDescription", "Collect system state before evaluating the dependent check",
                "templateDsl", Map.of("executionMode", "SEQUENTIAL")
            )
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
        Map<?, ?> sourceMetadata = (Map<?, ?>) envelope.get("sourceMetadata");
        Map<?, ?> sourceOperation = (Map<?, ?>) sourceMetadata.get("operation");

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
        assertThat(secondInput.get("commandHash")).isEqualTo("hash2");
        assertThat(secondInput.containsKey("command")).isFalse();
        assertThat(secondOutput.get("resultSetReference")).isEqualTo("$.data");
        assertThat(data.get("stderr")).isEqualTo("failed");
        assertThat(data.get("failedStepIndex")).isEqualTo(2);
        assertThat(data.get("outputMode")).isEqualTo("separated");
        assertThat(data.get("resultSetMode")).isEqualTo("ONE_PER_TEMPLATE_EXECUTION");
        Map<?, ?> commandContext = (Map<?, ?>) data.get("commandContext");
        assertThat(commandContext.get("description"))
            .isEqualTo("Collect system state before evaluating the dependent check");
        assertThat((List<?>) commandContext.get("references")).hasSize(1);
        assertThat(operation.get("diagnostics")).isEqualTo(diagnostics);
        assertThat(diagnostics.get("schemaVersion")).isEqualTo("linux_command_diagnostics.v1");
        assertThat(diagnostics.get("stepCount")).isEqualTo(2);
        assertThat(diagnostics.get("failedStepIndex")).isEqualTo(2);
        assertThat(diagnostics.get("stderrLength")).isEqualTo(6);
        assertThat((List<?>) data.get("steps")).hasSize(2);
        assertThat((List<?>) graph.get("nodes")).hasSize(2);
        assertThat((List<?>) graph.get("edges")).hasSize(1);
        assertThat(sourceMetadata.get("executionType")).isEqualTo("LINUX_COMMAND");
        assertThat(sourceOperation.containsKey("command")).isFalse();
        assertThat(sourceOperation.get("commandHash")).isEqualTo("hash");
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

    private static DatabaseToolProperties databaseToolProperties() {
        DatabaseToolProperties properties = new DatabaseToolProperties();
        properties.setMaxRows(50);
        return properties;
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
        assertThat(limits.get("strategy"))
            .isEqualTo("SINGLE_TEMPLATE_RESULT_WITH_STEP_METADATA");
        assertThat(limits.get("fullAggregateAvailable")).isEqualTo(true);
        assertThat(limits.get("commandStreamsStoredOnce")).isEqualTo(true);
        assertThat(limits.get("stepStreamsInline")).isEqualTo(false);
        assertThat(limits.get("stdoutTruncated")).isEqualTo(false);
        assertThat(limits.get("stderrTruncated")).isEqualTo(false);
        assertThat(String.valueOf(data.get("stdout")))
            .contains("STDOUT_HEAD")
            .contains("STDOUT_TAIL");
        assertThat(String.valueOf(data.get("stderr")))
            .contains("STDERR_HEAD")
            .contains("FATAL_ERROR_AT_TAIL");
        assertThat(returnedStep.containsKey("stdout")).isFalse();
        assertThat(returnedStep.containsKey("stderr")).isFalse();
        assertThat(returnedStep.get("errorReference")).isEqualTo("$.data.stderr");
    }

    @Test
    void linuxResultStoresCommandStreamsOnlyOncePerTemplateResultSet() throws Exception {
        String stdout = "UNIQUE_COMMAND_ROW\n".repeat(8_000);
        LinuxCommandResult result = new LinuxCommandResult(
            true, "host-1", "10.0.0.1", "ssh_host", "DEV", "LARGE_COMMAND_OUTPUT",
            "inventory-command", "hash", List.of(
                new LinuxCommandStepResult(1, "inventory-command", "step-hash", 0, stdout, "", 10, true)
            ), null, null, 0, stdout, "", 10, null, Map.of()
        );

        Map<String, Object> envelope = factory.fromLinuxCommand(result);
        String json = new ObjectMapper().writeValueAsString(envelope);

        assertThat(countOccurrences(json, "UNIQUE_COMMAND_ROW")).isEqualTo(8_000);
        assertThat(json.length()).isLessThan(stdout.length() + 20_000);
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
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
            null,
            Map.of(
                "toolName", "service_health",
                "endpointId", "endpoint-1",
                "endpointName", "Health API",
                "businessName", "Service health",
                "businessDescription", "Checks whether the business service is available",
                "category", "monitoring"
            )
        );

        Map<String, Object> envelope = factory.fromHttp(result);
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        Map<?, ?> execution = (Map<?, ?>) envelope.get("execution");
        Map<?, ?> step = (Map<?, ?>) ((List<?>) execution.get("steps")).get(0);
        Map<?, ?> input = (Map<?, ?>) step.get("input");
        Map<?, ?> output = (Map<?, ?>) step.get("output");
        Map<?, ?> sourceMetadata = (Map<?, ?>) envelope.get("sourceMetadata");
        Map<?, ?> sourceOperation = (Map<?, ?>) sourceMetadata.get("operation");
        Map<?, ?> sourceBusiness = (Map<?, ?>) sourceMetadata.get("business");

        assertThat(envelope).containsEntry("schemaVersion", StandardToolExecutionResultFactory.SCHEMA_VERSION);
        assertThat(envelope).containsEntry("kind", "http_request");
        assertThat(envelope).containsEntry("dataSchema", "http_response.v1");
        assertThat(envelope).containsEntry("payloadType", "structured");
        assertThat(execution.get("schemaVersion")).isEqualTo("execution_unit.v1");
        assertThat(step.get("stepType")).isEqualTo("http");
        assertThat(input.get("method")).isEqualTo("GET");
        assertThat(output.get("statusCode")).isEqualTo(200);
        assertThat(output.get("resultSetReference")).isEqualTo("$.data");
        assertThat(output.containsKey("body")).isFalse();
        assertThat(data.get("statusCode")).isEqualTo(200);
        assertThat(envelope).containsKey("executionGraph");
        assertThat(sourceMetadata.get("executionType")).isEqualTo("HTTP_REQUEST");
        assertThat(sourceMetadata.get("toolName")).isEqualTo("service_health");
        assertThat(sourceOperation.get("method")).isEqualTo("GET");
        assertThat(sourceOperation.containsKey("url")).isFalse();
        assertThat(sourceBusiness.get("description")).isEqualTo("Checks whether the business service is available");
    }

    @Test
    void databaseWorkflowResultCarriesDescriptionsAndExplicitCommandReferences() {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("workflow-1");
        config.setToolName("database_query");
        config.setTitle("Inventory workflow");
        config.setDescription("Collect inventory and then evaluate its state");
        Map<String, Object> resultData = new LinkedHashMap<>();
        resultData.put("mode", "database_query_workflow");
        resultData.put("nodeExecutions", List.of(
            Map.of(
                "nodeCode", "COLLECT", "sqlName", "Collect inventory",
                "description", "Provides the inventory used by the evaluation command",
                "executionOrder", 1, "dependencies", List.of(), "rowCount", 1,
                "rows", List.of(Map.of("value", 1)), "success", true
            ),
            Map.of(
                "nodeCode", "EVALUATE", "sqlName", "Evaluate inventory",
                "description", "Evaluates the collected inventory",
                "executionOrder", 2, "dependencies", List.of("COLLECT"), "rowCount", 1,
                "rows", List.of(Map.of("state", "ok")), "success", true
            )
        ));
        ToolOutput output = ToolOutput.success(resultData);

        Map<String, Object> envelope = factory.fromDatabaseQuery(config, Map.of(), output);
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        Map<?, ?> commandContext = (Map<?, ?>) data.get("commandContext");
        List<?> commands = (List<?>) commandContext.get("commands");
        Map<?, ?> firstCommand = (Map<?, ?>) commands.get(0);
        Map<?, ?> reference = (Map<?, ?>) ((List<?>) commandContext.get("references")).get(0);

        assertThat(firstCommand.get("description"))
            .isEqualTo("Provides the inventory used by the evaluation command");
        assertThat(firstCommand.get("resultReference")).isEqualTo("$.data.nodeExecutions[0]");
        assertThat(reference.get("fromCommandId")).isEqualTo("COLLECT");
        assertThat(reference.get("toCommandId")).isEqualTo("EVALUATE");
        assertThat(reference.get("relation")).isEqualTo("DEPENDS_ON_SUCCESS");
    }

    @Test
    void databaseQueryPromotesJsonTextCellsWithoutToolOrBusinessFieldRules() {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("query-json");
        config.setToolName("dynamic_payload_query");
        config.setTitle("Dynamic payload query");
        config.setSqlTemplate("select payload from observations");
        ToolOutput output = ToolOutput.success(Map.of(
            "rowCount", 1,
            "columns", List.of("payload"),
            "rows", List.of(Map.of(
                "payload", "{\"newMetric\":12.5,\"labels\":[\"alpha\",\"beta\"]}",
                "plainText", "not-json"
            ))
        ));

        Map<String, Object> envelope = factory.fromDatabaseQuery(config, Map.of(), output);
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        Map<?, ?> row = (Map<?, ?>) ((List<?>) data.get("rows")).get(0);
        Map<?, ?> payload = (Map<?, ?>) row.get("payload");

        assertThat(payload.get("newMetric")).isEqualTo(12.5);
        assertThat(payload.get("labels")).isEqualTo(List.of("alpha", "beta"));
        assertThat(row.get("newMetric")).isEqualTo(12.5);
        assertThat(row.get("labels")).isEqualTo(List.of("alpha", "beta"));
        assertThat(row.get("plainText")).isEqualTo("not-json");
        assertThat(((List<?>) data.get("columns")).stream().map(String::valueOf).toList())
            .containsExactlyInAnyOrder("payload", "newMetric", "labels", "plainText");
        assertThat((List<?>) data.get("columnMetadata")).anySatisfy(item -> {
            Map<?, ?> metadata = (Map<?, ?>) item;
            assertThat(metadata.get("name")).isEqualTo("newMetric");
            assertThat(metadata.get("dynamic")).isEqualTo(true);
        });
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
        Map<?, ?> contextSource = (Map<?, ?>) analysisContext.get("source");
        Map<?, ?> contextCapability = (Map<?, ?>) analysisContext.get("capability");
        Map<?, ?> contextBusiness = (Map<?, ?>) analysisContext.get("business");
        Map<?, ?> contextSchema = (Map<?, ?>) analysisContext.get("schema");
        Map<?, ?> target = (Map<?, ?>) envelope.get("target");
        Map<?, ?> template = (Map<?, ?>) target.get("template");
        Map<?, ?> businessGroup = (Map<?, ?>) template.get("businessGroup");
        Map<?, ?> step = (Map<?, ?>) ((List<?>) execution.get("steps")).get(0);
        Map<?, ?> input = (Map<?, ?>) step.get("input");
        Map<?, ?> stepOutput = (Map<?, ?>) step.get("output");
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        Map<?, ?> nameColumn = (Map<?, ?>) ((List<?>) data.get("columnMetadata")).get(1);

        assertThat(envelope).containsEntry("schemaVersion", StandardToolExecutionResultFactory.SCHEMA_VERSION);
        assertThat(envelope).containsEntry("kind", "sql_query");
        assertThat(envelope).containsEntry("dataSchema", "sql_result.v1");
        assertThat(execution.get("toolName")).isEqualTo("db_query_customer");
        assertThat(step.get("stepType")).isEqualTo("sql");
        assertThat(input.get("statement")).isEqualTo("select * from customer where id = :id");
        assertThat(stepOutput.get("rowCount")).isEqualTo(1);
        assertThat(stepOutput.get("resultSetReference")).isEqualTo("$.data");
        assertThat(stepOutput.containsKey("rows")).isFalse();
        assertThat(nameColumn.get("comment")).isEqualTo("Customer name");
        assertThat(analysisContext.get("schemaVersion")).isEqualTo("data_analysis_context.v1");
        assertThat(contextSource.get("type")).isEqualTo("database_query_template");
        assertThat(contextSource.get("displayName")).isEqualTo("Customer query");
        assertThat(contextCapability.get("intent")).isEqualTo("nav_reconciliation");
        assertThat(contextBusiness.get("name")).isEqualTo("基金净值核对");
        assertThat(contextBusiness.get("description")).isEqualTo("用于跨渠道基金净值一致性分析");
        assertThat((List<?>) contextSchema.get("fields")).hasSize(2);
        assertThat(businessGroup.get("name")).isEqualTo("基金净值核对");
    }
}
