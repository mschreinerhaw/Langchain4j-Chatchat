package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunEventType;
import com.chatchat.agents.runtime.AgentObservation;
import com.chatchat.agents.runtime.InMemoryAgentRunStore;
import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.common.tool.ToolOutput;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterpretationPlanRuntimeTest {

    @Test
    void executesReadyToolStepsInParallelAndThenFinalAnswer() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.hasTool("web_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.updateAndGet(value -> Math.max(value, current));
            Thread.sleep(50);
            active.decrementAndGet();
            ToolRuntimeRequest request = invocation.getArgument(0);
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("tool", request.getToolName())),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of("tool", request.getToolName())
            );
        });

        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1, 2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            parallelPlan(),
            toolRegistry,
            List.of("document_search", "web_search"),
            "tenant-1",
            "req-plan-runtime",
            "conv-plan-runtime",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("done");
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2, 3);
        assertThat(maxActive.get()).isGreaterThan(1);
        verify(toolRuntimeService, times(2)).execute(any());
    }

    @Test
    void stopsDagWhenToolStepFails() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.failure("backend down"),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "failed",
            Map.of()
        ));
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            serialPlan(),
            toolRegistry,
            List.of("document_search"),
            "tenant-1",
            "req-plan-runtime-fail",
            "conv-plan-runtime-fail",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("STEP_FAILED");
        assertThat(result.errorMessage()).isEqualTo("backend down");
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1);
    }

    @Test
    void stopsDagWhenModelReviewRejectsToolResult() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("results", List.of())),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            new InterpretationPlanOptimizer(),
            null,
            request -> InterpretationPlanRuntime.StepReview.rejected("evidence is empty", Map.of("reviewed", true)),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            serialPlan(),
            toolRegistry,
            List.of("document_search"),
            "tenant-1",
            "req-review-reject",
            "conv-review-reject",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("STEP_FAILED");
        assertThat(result.errorMessage()).contains("Tool result rejected by model review");
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1);
        assertThat(result.steps().get(0).metadata())
            .containsEntry("toolResultReviewSatisfied", false)
            .containsEntry("reviewed", true);
    }

    @Test
    void preservesPartialSqlResultWhenModelReviewRejectsIt() {
        String toolName = "mcp_chatchat_mcp_server_sql_query_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(toolName)).thenReturn(true);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of(
                "schemaVersion", "tool_execution_result.v1",
                "kind", "sql_query",
                "success", true,
                "payload", Map.of(
                    "columns", List.of("STAT_DATE", "TOTAL_MARKET_VALUE", "AVG_CHANGE_PCT"),
                    "rows", List.of(Map.of(
                        "STAT_DATE", "2026-07-04",
                        "TOTAL_MARKET_VALUE", 1000,
                        "AVG_CHANGE_PCT", 0.0123
                    )),
                    "rowCount", 1
                )
            )),
            ToolMetadata.builder().id(toolName).build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            new InterpretationPlanOptimizer(),
            null,
            request -> InterpretationPlanRuntime.StepReview.rejected(
                "missing advancers and decliners",
                Map.of("reviewed", true)
            ),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            sqlQueryPlan(toolName),
            toolRegistry,
            List.of(toolName),
            "tenant-1",
            "req-sql-partial",
            "conv-sql-partial",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).as("status=%s error=%s steps=%s", result.status(), result.errorMessage(), result.steps()).isTrue();
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2);
        assertThat(result.steps().get(0).metadata())
            .containsEntry("toolResultReviewSatisfied", false)
            .containsEntry("toolResultReviewPartialAccepted", true)
            .containsEntry("partialEvidence", true);
    }

    @Test
    void factChecksTemplateDiscoveryWhenMcpResultIsJsonString() throws Exception {
        String templateQueryResult = """
            {
              "success": true,
              "returnedCount": 1,
              "templates": [
                {
                  "templateId": "MYSQL_INNODB_STATUS",
                  "name": "MySQL InnoDB engine status"
                }
              ]
            }
            """;
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_template_query",
            Map.of("filters", Map.of("intent", "InnoDB status"), "limit", 5),
            List.of(),
            null,
            null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_template_query",
            true,
            templateQueryResult,
            null,
            null,
            null,
            5
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNotNull();
        assertThat(review.satisfied()).isTrue();
        assertThat(review.metadata())
            .containsEntry("localFactCheckHasEvidence", true)
            .containsEntry("localFactCheckEvidenceType", "template_discovery")
            .containsEntry("templateDiscoveryReturnedCount", 1);
    }

    @Test
    void skipsModelReviewForNonEmptyTemplateDiscoveryResult() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_ssh_template_query")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of(
                "schemaVersion", "template_query_result.v1",
                "success", true,
                "returnedCount", 2,
                "templates", List.of(
                    Map.of(
                        "templateId", "CHECK_PROCESS",
                        "parameterSchema", Map.of("type", "object"),
                        "invocationExample", Map.of("template", "CHECK_PROCESS")
                    ),
                    Map.of(
                        "templateId", "CHECK_SERVICE_STATUS",
                        "parameterSchema", Map.of("type", "object"),
                        "invocationExample", Map.of("template", "CHECK_SERVICE_STATUS")
                    )
                )
            )),
            ToolMetadata.builder().id("mcp_chatchat_mcp_server_ssh_template_query").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("ops", "select ssh template", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1,
                    "mcp_tool",
                    "mcp_chatchat_mcp_server_ssh_template_query",
                    Map.of("filters", Map.of("intent", "分析MySQL服务器管理进程信息"), "limit", 10),
                    List.of(),
                    null,
                    null
                ),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of("mcp_chatchat_mcp_server_ssh_template_query"), List.of(), 30000),
            review()
        );
        AtomicInteger reviewerCalls = new AtomicInteger();
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            request -> {
                reviewerCalls.incrementAndGet();
                return InterpretationPlanRuntime.StepReview.rejected("should not review discovery results", Map.of());
            },
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_ssh_template_query"),
            "tenant-1",
            "req-template-discovery-skip-review",
            "conv-template-discovery-skip-review",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        assertThat(reviewerCalls).hasValue(0);
        assertThat(result.steps().get(0).metadata())
            .containsEntry("templateDiscoveryReturnedCount", 2)
            .containsEntry("toolResultReviewSkipped", true);
    }

    @Test
    void factChecksSqlMetadataSearchColumns() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            2,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_metadata_search",
            Map.of("query", "livebos.os_historystep", "includeColumns", true),
            List.of(1),
            null,
            null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            2,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_metadata_search",
            true,
            Map.of(
                "schemaVersion", "sql_metadata_search_result.v1",
                "success", true,
                "results", List.of(Map.of(
                    "location", Map.of("schema", "livebos", "table", "os_historystep"),
                    "columns", List.of(
                        Map.of("name", "ID", "dataType", "bigint", "columnType", "bigint(20)", "columnKey", "PRI"),
                        Map.of("name", "ENTRY_ID", "dataType", "varchar", "columnType", "varchar(64)", "columnKey", "MUL")
                    )
                ))
            ),
            null,
            null,
            null,
            6
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNotNull();
        assertThat(review.satisfied()).isTrue();
        assertThat(review.metadata())
            .containsEntry("localFactCheckHasEvidence", true)
            .containsEntry("localFactCheckEvidenceType", "sql_metadata_search_columns")
            .containsEntry("sqlMetadataFactChecked", true)
            .containsEntry("sqlMetadataColumnCount", 2)
            .containsEntry("sqlMetadataStepId", 2);
    }

    @Test
    void factChecksAssetDiscoveryWhenMcpResultIsTextEnvelope() throws Exception {
        String assetQueryResult = """
            {
              "success": true,
              "returnedCount": 1,
              "assets": [
                {
                  "asset": {
                    "name": "test_db_248",
                    "environment": "DEV"
                  }
                }
              ]
            }
            """;
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of("filters", Map.of("assetName", "test_db_248"), "limit", 5),
            List.of(),
            null,
            null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            true,
            Map.of("content", List.of(Map.of("type", "text", "text", assetQueryResult))),
            null,
            null,
            null,
            5
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNotNull();
        assertThat(review.satisfied()).isTrue();
        assertThat(review.metadata())
            .containsEntry("localFactCheckHasEvidence", true)
            .containsEntry("localFactCheckEvidenceType", "asset_discovery")
            .containsEntry("assetDiscoveryReturnedCount", 1);
    }

    @Test
    void factChecksAssetDiscoveryWhenAssetsListExistsDespiteOuterZeroCount() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of("filters", Map.of("assetName", "test_db_248"), "limit", 5),
            List.of(),
            null,
            null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            true,
            Map.of(
                "returnedCount", 0,
                "assets", List.of(Map.of("asset", Map.of("name", "test_db_248", "environment", "DEV")))
            ),
            null,
            null,
            null,
            5
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNotNull();
        assertThat(review.satisfied()).isTrue();
        assertThat(review.metadata())
            .containsEntry("localFactCheckHasEvidence", true)
            .containsEntry("localFactCheckEvidenceType", "asset_discovery")
            .containsEntry("assetDiscoveryReturnedCount", 1);
    }

    @Test
    void doesNotFactCheckAssetDiscoveryWhenResultContainsNoAssetEvidence() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of("filters", Map.of("assetName", "test_db_248"), "limit", 5),
            List.of(),
            null,
            null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            true,
            Map.of(
                "returnedCount", 0,
                "assets", List.of(),
                "emptyResultAdvice", Map.of("reason", "no match")
            ),
            null,
            null,
            null,
            5
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNull();
    }

    @Test
    void doesNotFactCheckAssetDiscoveryForArbitrarySelectedMap() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of("filters", Map.of("assetName", "test_db_248"), "limit", 5),
            List.of(),
            null,
            null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            true,
            Map.of(
                "returnedCount", 0,
                "assets", List.of(),
                "selected", Map.of("reason", "review required")
            ),
            null,
            null,
            null,
            5
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNull();
    }

    @Test
    void factChecksSqlColumnMetadataAsValidStructureEvidence() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of("templateId", "MYSQL_TABLE_METADATA"),
            List.of(2),
            null,
            null
        );
        Map<String, Object> output = Map.of(
            "kind", "sql_query",
            "data", Map.of(
                "columns", List.of("COLUMN_NAME", "COLUMN_TYPE", "IS_NULLABLE", "COLUMN_COMMENT"),
                "rowCount", 2,
                "rows", List.of(
                    Map.of("COLUMN_NAME", "DICT_ENTR_CODE", "COLUMN_TYPE", "varchar(8)", "IS_NULLABLE", "YES", "COLUMN_COMMENT", "瀛楀吀鏉＄洰浠ｇ爜"),
                    Map.of("COLUMN_NAME", "SSYS_CODE", "COLUMN_TYPE", "varchar(8)", "IS_NULLABLE", "YES", "COLUMN_COMMENT", "鏉ユ簮绯荤粺浠ｇ爜")
                )
            )
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            true,
            output,
            null,
            null,
            null,
            173
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNotNull();
        assertThat(review.satisfied()).isTrue();
        assertThat(review.metadata())
            .containsEntry("localFactCheckHasEvidence", true)
            .containsEntry("localFactCheckEvidenceType", "sql_column_metadata")
            .containsEntry("sqlMetadataFactChecked", true)
            .containsEntry("sqlMetadataColumnCount", 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsTableScopedGlobalTemplateWhenTemplateDiscoveryDidNotReturnTableMetadataTemplate() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of(
                "templateId", "MYSQL_SHOW_STATUS",
                "parameters", Map.of("table_name", "t_ad_dict_entr_supn"),
                "executionContext", Map.of("assetName", "test_db_248", "env", "DEV")
            ),
            List.of(2),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_metadata", "Analyze table t_ad_dict_entr_supn", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    Map.of("filters", Map.of("assetName", "test_db_248"), "limit", 10), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                    Map.of("filters", Map.of("intent", "table metadata"), "limit", 10), List.of(1), null, null),
                step,
                new InterpretationPlan.Step(4, "final_answer", "", Map.of("answer", "done"), List.of(3), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                4,
                false,
                List.of(
                    "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    "mcp_chatchat_mcp_server_sql_datasource_template_query",
                    "mcp_chatchat_mcp_server_sql_query_execute"
                ),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-sql-template-repair",
            "conv-sql-template-repair",
            "user-1",
            Map.of("executionTraceId", "trace-sql-template-repair")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(runtime, step, request, Map.of()))
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("SQL_TEMPLATE_TARGET_SCOPE_MISMATCH: template MYSQL_SHOW_STATUS is not table-scoped but tableName=t_ad_dict_entr_supn was provided; planner must select a dialect-specific *_TABLE_METADATA template.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsOracleInstanceTemplateWhenTemplateDiscoveryDidNotReturnTableMetadataTemplate() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of(
                "templateId", "ORACLE_INSTANCE_STATUS",
                "parameters", Map.of("tableName", "T_AD_DICT_ENTR_SUPN"),
                "executionContext", Map.of("databaseType", "oracle")
            ),
            List.of(2),
            null,
            null
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                mock(ToolRegistry.class),
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                "tenant-1",
                "req-oracle-template-repair",
                "conv-oracle-template-repair",
                "user-1",
                Map.of()
            ),
            Map.of()
        ))
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("SQL_TEMPLATE_TARGET_SCOPE_MISMATCH: template ORACLE_INSTANCE_STATUS is not table-scoped but tableName=T_AD_DICT_ENTR_SUPN was provided; planner must select a dialect-specific *_TABLE_METADATA template.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void hydratesTableMetadataSchemaFromSqlMetadataSearchResults() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of(
                "templateId", "MYSQL_TABLE_METADATA",
                "parameters", Map.of("tableName", "lbappdeploydetail"),
                "executionContext", Map.of("assetName", "test_db_248", "env", "DEV")
            ),
            List.of(1),
            null,
            null
        );
        Map<Integer, InterpretationPlanRuntime.StepExecution> completed = Map.of(
            1,
            new InterpretationPlanRuntime.StepExecution(
                1,
                "mcp_tool",
                "mcp_chatchat_mcp_server_sql_metadata_search",
                true,
                Map.of(
                    "results", List.of(Map.of(
                        "location", Map.of(
                            "database", "rdsm_ad",
                            "schema", "rdsm_ad",
                            "table", "lbappdeploydetail"
                        ),
                        "sqlExecutionBinding", Map.of(
                            "parameters", Map.of(
                                "databaseName", "rdsm_ad",
                                "schemaName", "rdsm_ad",
                                "tableName", "lbappdeploydetail"
                            )
                        ),
                        "score", 0.95
                    ))
                ),
                null,
                null,
                null,
                10
            )
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                mock(ToolRegistry.class),
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                "tenant-1",
                "req-metadata-search-table-location",
                "conv-metadata-search-table-location",
                "user-1",
                Map.of()
            ),
            completed
        );

        Map<String, Object> parameters = (Map<String, Object>) resolved.get("parameters");
        assertThat(parameters)
            .containsEntry("tableName", "lbappdeploydetail")
            .containsEntry("schemaName", "rdsm_ad")
            .containsEntry("databaseName", "rdsm_ad")
            .containsEntry("schema", "rdsm_ad")
            .containsEntry("database", "rdsm_ad");
        assertThat(resolved.get("runtimeTableResolution").toString())
            .contains("sql_metadata_search.results", "rdsm_ad", "lbappdeploydetail");
    }

    @Test
    void passesBusinessQueryTemplateNameToSqlQueryExecutor() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_database_query_template_query".equals(toolRequest.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "assetName", "market-dm",
                        "env", "DEV",
                        "templates", List.of(Map.of(
                            "templateId", "edayQuqtMoni",
                            "mcpToolName", "edayQuqtMoni",
                            "execution", Map.of("mode", "direct_mcp_tool", "callTool", "edayQuqtMoni"),
                            "parameterSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of())
                        ))
                    )),
                    ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("rows", List.of(Map.of("cnt", 1)))),
                ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            legacyBusinessQueryPlan(),
            toolRegistry,
            List.of(
                "mcp_chatchat_mcp_server_database_query_template_query",
                "mcp_chatchat_mcp_server_sql_datasource_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute"
            ),
            "tenant-1",
            "req-business-query",
            "conv-business-query",
            "user-1",
            Map.of()
        ));

        assertThat(result.success())
            .as("status=%s error=%s metadata=%s steps=%s", result.status(), result.errorMessage(), result.metadata(), result.steps())
            .isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues()).extracting(ToolRuntimeRequest::getToolName)
            .containsExactly("mcp_chatchat_mcp_server_database_query_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("templateId", "edayQuqtMoni");
        assertThat(captor.getAllValues().get(1).getAllowedTools())
            .doesNotContain("edayQuqtMoni");
    }

    @Test
    void hydratesSqlExecutionContextFromBusinessTemplateMetadata() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_business_query_template_search".equals(toolRequest.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "templates", List.of(Map.of(
                            "templateId", "edayQuqtMoni",
                            "sqlExecutionBinding", Map.of(
                                "toolName", "sql_query_execute",
                                "executionContext", Map.of(
                                    "assetName", "market-dm",
                                    "env", "DEV",
                                    "databaseType", "dm"
                                )
                            ),
                            "parameterSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of())
                        ))
                    )),
                    ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("rows", List.of(Map.of("cnt", 1)))),
                ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "分析行情波动提醒", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_business_query_template_search",
                    Map.of(
                        "finalDecision", "business_database_query",
                        "candidates", List.of(Map.of("targetKind", "business_database_query", "confidence", 0.95)),
                        "filters", Map.of("intent", "行情波动提醒")
                    ), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                    Map.of("templateId", "edayQuqtMoni", "parameters", Map.of()), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false,
                List.of("mcp_chatchat_mcp_server_business_query_template_search",
                    "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000),
            review()
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_business_query_template_search",
                "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-business-query-context",
            "conv-business-query-context",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues()).extracting(ToolRuntimeRequest::getToolName)
            .containsExactly("mcp_chatchat_mcp_server_business_query_template_search",
                "mcp_chatchat_mcp_server_sql_query_execute");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("executionContext", Map.of(
                "assetName", "market-dm",
                "env", "DEV",
                "databaseType", "dm"
            ));
    }

    @Test
    void hydratesSqlExecutionContextFromDatabaseQuerySearchIndexResults() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_business_query_template_search".equals(toolRequest.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "indexType", "database_query",
                        "results", List.of(Map.of(
                            "id", "ds-dm",
                            "sqlExecutionContext", Map.of(
                                "assetName", "达梦测试服务器",
                                "env", "DEV",
                                "environment", "DEV",
                                "databaseType", "dm",
                                "dbType", "dm"
                            ),
                            "associatedTemplates", List.of(Map.of(
                                "templateId", "query_edayQuqtMoni",
                                "mcpToolName", "query_edayQuqtMoni",
                                "sqlExecutionBinding", Map.of(
                                    "toolName", "sql_query_execute",
                                    "templateId", "query_edayQuqtMoni",
                                    "executionContext", Map.of(
                                        "assetName", "达梦测试服务器",
                                        "env", "DEV",
                                        "environment", "DEV",
                                        "databaseType", "dm",
                                        "dbType", "dm"
                                    )
                                ),
                                "parameterSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of())
                            ))
                        ))
                    )),
                    ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("rows", List.of(Map.of("cnt", 1)))),
                ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "行情提醒", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_business_query_template_search",
                    Map.of("filters", Map.of("intent", "行情提醒")), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                    Map.of("templateId", "query_edayQuqtMoni", "parameters", Map.of()), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false,
                List.of("mcp_chatchat_mcp_server_business_query_template_search",
                    "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000),
            review()
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_business_query_template_search",
                "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-business-query-search-index",
            "conv-business-query-search-index",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues()).extracting(ToolRuntimeRequest::getToolName)
            .containsExactly("mcp_chatchat_mcp_server_business_query_template_search",
                "mcp_chatchat_mcp_server_sql_query_execute");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("executionContext", Map.of(
                "assetName", "达梦测试服务器",
                "env", "DEV",
                "environment", "DEV",
                "databaseType", "dm",
                "dbType", "dm"
            ));
    }

    @Test
    void mapsDiscoveredTemplateToolStepToDeclaredExecutor() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenAnswer(invocation -> {
            String tool = invocation.getArgument(0);
            return !"query_edayQuqtMoni".equals(tool);
        });
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_business_query_template_search".equals(toolRequest.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "results", List.of(Map.of(
                            "id", "ds-dm",
                            "associatedTemplates", List.of(Map.of(
                                "templateId", "query_edayQuqtMoni",
                                "mcpToolName", "query_edayQuqtMoni",
                                "sqlExecutionBinding", Map.of(
                                    "toolName", "sql_query_execute",
                                    "templateId", "query_edayQuqtMoni",
                                    "executionContext", Map.of(
                                        "assetName", "达梦测试服务器",
                                        "env", "DEV",
                                        "databaseType", "dm"
                                    )
                                )
                            ))
                        ))
                    )),
                    ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("rows", List.of(Map.of("cnt", 1)))),
                ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_query", "行情波动提醒", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_business_query_template_search",
                    Map.of(
                        "finalDecision", "business_database_query",
                        "candidates", List.of(Map.of("targetKind", "business_database_query", "confidence", 0.95)),
                        "filters", Map.of("intent", "行情波动提醒")
                    ), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "query_edayQuqtMoni",
                    Map.of("parameters", Map.of()), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false,
                List.of("mcp_chatchat_mcp_server_business_query_template_search",
                    "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000),
            review()
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_business_query_template_search",
                "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-template-placeholder",
            "conv-template-placeholder",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues()).extracting(ToolRuntimeRequest::getToolName)
            .containsExactly("mcp_chatchat_mcp_server_business_query_template_search",
                "mcp_chatchat_mcp_server_sql_query_execute");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("templateId", "query_edayQuqtMoni")
            .containsEntry("executionContext", Map.of(
                "assetName", "达梦测试服务器",
                "env", "DEV",
                "databaseType", "dm"
            ));
    }

    @Test
    void mapsDiscoveredTemplateToolStepUsingDeclaredExecutionExecutorTool() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenAnswer(invocation -> {
            String tool = invocation.getArgument(0);
            return !"query_mktInfoEvtMoni".equals(tool);
        });
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_business_query_template_search".equals(toolRequest.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "results", List.of(Map.of(
                            "id", "ds-dm",
                            "associatedTemplates", List.of(Map.of(
                                "templateId", "query_mktInfoEvtMoni",
                                "mcpToolName", "query_mktInfoEvtMoni",
                                "execution", Map.of(
                                    "mode", "template_execution",
                                    "executorTool", "sql_query_execute",
                                    "template", "query_mktInfoEvtMoni",
                                    "callTool", "query_mktInfoEvtMoni",
                                    "executionContext", Map.of(
                                        "assetName", "dm-market",
                                        "env", "DEV",
                                        "databaseType", "dm"
                                    )
                                )
                            ))
                        ))
                    )),
                    ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("rows", List.of(Map.of("cnt", 1)))),
                ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_query", "market event monitor", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_business_query_template_search",
                    Map.of("filters", Map.of("intent", "market event monitor")), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "query_mktInfoEvtMoni",
                    Map.of("parameters", Map.of()), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false,
                List.of("mcp_chatchat_mcp_server_business_query_template_search",
                    "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000),
            review()
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_business_query_template_search",
                "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-template-executor",
            "conv-template-executor",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues()).extracting(ToolRuntimeRequest::getToolName)
            .containsExactly("mcp_chatchat_mcp_server_business_query_template_search",
                "mcp_chatchat_mcp_server_sql_query_execute");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("templateId", "query_mktInfoEvtMoni")
            .containsEntry("executionContext", Map.of(
                "assetName", "dm-market",
                "env", "DEV",
                "databaseType", "dm"
            ));
    }

    @Test
    void preservesPlannerRoutingDecisionBeforeMcpCall() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("results", List.of())),
                ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_query", "行情波动提醒", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_business_query_template_search",
                    Map.of(
                        "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.95)),
                        "finalDecision", "database",
                        "filters", Map.of("intent", "行情数据发生较大波动时异常提醒数据"),
                        "trace", Map.of("plannerVersion", "v1.1")
                    ), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false,
                List.of("mcp_chatchat_mcp_server_business_query_template_search"),
                List.of(),
                30000),
            review()
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_business_query_template_search"),
            "tenant-1",
            "req-normalize-business-template-target",
            "conv-normalize-business-template-target",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService).execute(captor.capture());
        Map<String, Object> parameters = captor.getValue().getToolInput().getParameters();
        assertThat(parameters).containsEntry("finalDecision", "database");
        assertThat(parameters.get("candidates").toString()).contains("targetKind=database");
        assertThat(parameters.get("trace").toString()).doesNotContain("routingForcedByTypedDiscoveryTool");
    }

    @Test
    void executesExactUserBoundDatabaseOpsTemplateToolWithoutBusinessSubstitution() {
        String requestedTool = "mcp_chatchat_mcp_server_database_ops_template_search";
        String otherTool = "mcp_chatchat_mcp_server_business_query_template_search";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("templates", List.of())),
                ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2)))
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("database_ops", "analyze mysql status", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", requestedTool,
                    Map.of("finalDecision", "database", "filters", Map.of("intent", "market volatility alert")),
                    List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of(requestedTool), List.of(), 30000),
            review()
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of(requestedTool, otherTool),
            "tenant-1",
            "req-bound-database-ops-template",
            "conv-bound-database-ops-template",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService).execute(captor.capture());
        assertThat(captor.getValue().getToolName()).isEqualTo(requestedTool);
        assertThat(captor.getValue().getToolInput().getParameters())
            .containsEntry("finalDecision", "database");
    }

    @Test
    @SuppressWarnings("unchecked")
    void repairsUsingExplicitTemplateSemanticMetadataBeforeNameInference() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of(
                "templateId", "GENERIC_INSTANCE_STATUS",
                "parameters", Map.of("tableName", "customer_label")
            ),
            List.of(2),
            null,
            null
        );
        Map<Integer, InterpretationPlanRuntime.StepExecution> completed = Map.of(
            2,
            new InterpretationPlanRuntime.StepExecution(
                2,
                "mcp_tool",
                "mcp_chatchat_mcp_server_sql_datasource_template_query",
                true,
                Map.of("templates", List.of(
                    Map.of(
                        "templateId", "GENERIC_INSTANCE_STATUS",
                        "databaseType", "postgresql",
                        "semantic", Map.of(
                            "operation", "INSTANCE_DIAGNOSTIC_QUERY",
                            "targetLevel", "INSTANCE",
                            "dialect", "postgresql"
                        )
                    ),
                    Map.of(
                        "templateId", "PG_CUSTOM_TABLE_METADATA",
                        "databaseType", "postgresql",
                        "category", "maintenance_metadata",
                        "parameterSchema", Map.of("required", List.of("tableName")),
                        "semantic", Map.of(
                            "operation", "TABLE_METADATA_QUERY",
                            "targetLevel", "TABLE",
                            "dialect", "postgresql"
                        )
                    )
                )),
                null,
                null,
                null,
                10
            )
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                mock(ToolRegistry.class),
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                "tenant-1",
                "req-semantic-template-repair",
                "conv-semantic-template-repair",
                "user-1",
                Map.of()
            ),
            completed
        );

        assertThat(resolved)
            .containsEntry("templateId", "PG_CUSTOM_TABLE_METADATA")
            .containsEntry("template", "PG_CUSTOM_TABLE_METADATA");
        Map<String, Object> repair = (Map<String, Object>) resolved.get("runtimeTemplateRepair");
        assertThat(repair)
            .containsEntry("fromTemplateId", "GENERIC_INSTANCE_STATUS")
            .containsEntry("toTemplateId", "PG_CUSTOM_TABLE_METADATA");
    }

    @Test
    void rejectsTableScopedGlobalTemplateWhenDialectCannotBeInferred() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of(
                "templateId", "INSTANCE_STATUS",
                "parameters", Map.of("tableName", "T_AD_DICT_ENTR_SUPN")
            ),
            List.of(2),
            null,
            null
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                mock(ToolRegistry.class),
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                "tenant-1",
                "req-template-scope-mismatch",
                "conv-template-scope-mismatch",
                "user-1",
                Map.of()
            ),
            Map.of()
        ))
            .hasRootCauseMessage("SQL_TEMPLATE_TARGET_SCOPE_MISMATCH: template INSTANCE_STATUS is not table-scoped but tableName=T_AD_DICT_ENTR_SUPN was provided; planner must select a dialect-specific *_TABLE_METADATA template.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsRoutingTraceForDiscoveryToolWhenPlannerOmittedIt() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of(
                "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.9)),
                "finalDecision", "database",
                "filters", Map.of("assetName", "test_mysql_database"),
                "limit", 10
            ),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_query", "Analyze InnoDB", "medium"),
            context(),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
            "tenant-1",
            "req-routing-trace",
            "conv-routing-trace",
            "user-1",
            Map.of("executionTraceId", "trace-runtime-1")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        assertThat(resolved).containsEntry("filtersSchemaVersion", "target_filters.v1");
        assertThat(resolved.get("trace")).isInstanceOf(Map.class);
        Map<String, Object> trace = (Map<String, Object>) resolved.get("trace");
        assertThat(trace)
            .containsEntry("schemaVersion", "routing_trace.v1")
            .containsEntry("source", "interpretation_plan_runtime")
            .containsEntry("executionTraceId", "trace-runtime-1")
            .containsEntry("stepId", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void repairsUnsupportedLogicalFiltersFromPublishedMcpContract() throws Exception {
        String toolName = "mcp_chatchat_mcp_server_database_asset_search";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .metadata(Map.of("mcpToolMeta", Map.of(
                "routingProtocol", Map.of("allowedFilterFields", List.of(
                    "assetname", "intent", "queryterms", "retrievalsignals"
                )),
                "forbiddenConcreteTargetFields", List.of("jdbcUrl", "datasourceId")
            )))
            .build());
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            toolName,
            Map.of("filters", Map.of(
                "business_line", "证券",
                "jdbcUrl", "jdbc:mysql://not-forwarded"
            )),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "分析证券持仓市值", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(step)),
            new InterpretationPlan.ExecutionPolicy(1, false, List.of(toolName), List.of(), 30000),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan, toolRegistry, List.of(toolName), "tenant-1", "req-filter-contract",
            "conv-filter-contract", "user-1", Map.of()
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());
        Map<String, Object> filters = (Map<String, Object>) resolved.get("filters");

        assertThat(filters).doesNotContainKeys("business_line", "jdbcUrl");
        assertThat((List<String>) filters.get("retrievalSignals"))
            .contains("business_line:证券", "证券")
            .noneMatch(value -> value.contains("jdbc:mysql"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsRetrievalIntentForDatabaseDiscoveryWhenPlannerOmittedFilter() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of(
                "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.85)),
                "finalDecision", "database",
                "filters", Map.of(),
                "limit", 10
            ),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "test_mysql\u6570\u636e\u5e93 connections", "low"),
            new InterpretationPlan.Context(
                List.of("User mentioned test_mysql\u6570\u636e\u5e93"),
                List.of(),
                List.of(),
                List.of()
            ),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
            "tenant-1",
            "req-routing-asset-name",
            "conv-routing-asset-name",
            "user-1",
            Map.of("executionTraceId", "trace-runtime-asset")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        Map<String, Object> filters = (Map<String, Object>) resolved.get("filters");
        assertThat(filters)
            .doesNotContainKey("assetName")
            .containsEntry("intent", "test_mysql\u6570\u636e\u5e93 connections")
            .containsEntry("goal", "test_mysql\u6570\u636e\u5e93 connections");
        assertThat((List<String>) filters.get("queryTerms")).containsExactly("test_mysql\u6570\u636e\u5e93 connections");
        assertThat(resolved.get("trace")).isInstanceOf(Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dropsPlannerEnvironmentExtractedFromAssetProperNameAndRestoresUserQuery() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_database_asset_search",
            Map.of(
                "finalDecision", "database",
                "filters", Map.of("env", "\u6d4b\u8bd5"),
                "limit", 20
            ),
            List.of(),
            null,
            null
        );
        String userQuery = "\u5206\u6790248\u6d4b\u8bd5\u6570\u636e\u5e93";
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", userQuery, "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_database_asset_search"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_database_asset_search"),
            "tenant-1",
            "req-env-proper-name",
            "conv-env-proper-name",
            "user-1",
            Map.of("originalUserQuery", userQuery)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        Map<String, Object> filters = (Map<String, Object>) resolved.get("filters");
        assertThat(filters)
            .doesNotContainKeys("env", "environment")
            .containsEntry("intent", userQuery)
            .containsEntry("goal", userQuery);
        assertThat((List<String>) filters.get("queryTerms")).containsExactly(userQuery);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsCanonicalPlannerEnvironmentWhenUserExplicitlySpecifiedIt() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_database_asset_search",
            Map.of("finalDecision", "database", "filters", Map.of("env", "test")),
            List.of(),
            null,
            null
        );
        String userQuery = "\u5728TEST\u73af\u5883\u5206\u6790248\u6570\u636e\u5e93";
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", userQuery, "low"),
            context(),
            new InterpretationPlan.Plan(List.of(step)),
            new InterpretationPlan.ExecutionPolicy(
                1,
                false,
                List.of("mcp_chatchat_mcp_server_database_asset_search"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_database_asset_search"),
            "tenant-1",
            "req-explicit-env",
            "conv-explicit-env",
            "user-1",
            Map.of("originalUserQuery", userQuery)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        Map<String, Object> filters = (Map<String, Object>) resolved.get("filters");
        assertThat(filters.get("env")).isEqualTo("TEST");
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuredAgentEnvironmentOverridesPlannerDiscoveryEnvironment() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_database_asset_search",
            Map.of("finalDecision", "database", "filters", Map.of("env", "TEST")),
            List.of(),
            null,
            null
        );
        String userQuery = "在TEST环境分析数据库";
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", userQuery, "low"),
            context(),
            new InterpretationPlan.Plan(List.of(step)),
            new InterpretationPlan.ExecutionPolicy(
                1,
                false,
                List.of("mcp_chatchat_mcp_server_database_asset_search"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_database_asset_search"),
            "tenant-1",
            "req-agent-env",
            "conv-agent-env",
            "user-1",
            Map.of(
                "originalUserQuery", userQuery,
                "agentRuntimeEnvironment", "DEV"
            )
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        assertThat((Map<String, Object>) resolved.get("filters")).containsEntry("env", "DEV");
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuredAgentEnvironmentOverridesSqlExecutionEnvironment() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of(
                "templateId", "MYSQL_STATUS",
                "executionContext", Map.of("assetName", "248-test-db", "env", "TEST")
            ),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "分析数据库", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(step)),
            new InterpretationPlan.ExecutionPolicy(
                1,
                false,
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-agent-sql-env",
            "conv-agent-sql-env",
            "user-1",
            Map.of("agentRuntimeEnvironment", "DEV")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        assertThat((Map<String, Object>) resolved.get("executionContext"))
            .containsEntry("assetName", "248-test-db")
            .containsEntry("env", "DEV");
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsRetrievalIntentForSshDiscoveryWhenPlannerOmittedFilter() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_ssh_asset_query",
            Map.of(
                "candidates", List.of(Map.of("targetKind", "host", "confidence", 0.85)),
                "finalDecision", "host",
                "filters", Map.of(),
                "limit", 10
            ),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("system_operation", "分析 MySQL服务器 管理进程信息", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_ssh_asset_query"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_ssh_asset_query"),
            "tenant-1",
            "req-routing-ssh-asset-name",
            "conv-routing-ssh-asset-name",
            "user-1",
            Map.of("executionTraceId", "trace-runtime-ssh-asset")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        Map<String, Object> filters = (Map<String, Object>) resolved.get("filters");
        assertThat(filters)
            .doesNotContainKey("assetName")
            .containsEntry("intent", "分析 MySQL服务器 管理进程信息")
            .containsEntry("goal", "分析 MySQL服务器 管理进程信息");
        assertThat((List<String>) filters.get("queryTerms")).containsExactly("分析 MySQL服务器 管理进程信息");
        assertThat(resolved.get("trace")).isInstanceOf(Map.class);
    }

    @SuppressWarnings("unchecked")
    void hydratesSqlExecuteContextAndRemovesAssetNameMisboundAsSchemaName() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            2,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of(
                "templateId", "MYSQL_TABLE_METADATA",
                "executionContext", Map.of(
                    "assetName", "$.assets[0].asset.name",
                    "env", "$.assets[0].asset.environment"
                ),
                "parameters", Map.of(
                    "tableName", "lbappdeploydetail",
                    "schemaName", "test_db_248"
                )
            ),
            List.of(1),
            null,
            null
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                mock(ToolRegistry.class),
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                "tenant-1",
                "req-sql-context-normalize",
                "conv-sql-context-normalize",
                "user-1",
                Map.of()
            ),
            Map.of(1, completedSqlAssetStep())
        );

        Map<String, Object> executionContext = (Map<String, Object>) resolved.get("executionContext");
        assertThat(executionContext)
            .containsEntry("assetName", "test_db_248")
            .containsEntry("env", "DEV");
        Map<String, Object> parameters = (Map<String, Object>) resolved.get("parameters");
        assertThat(parameters)
            .containsEntry("tableName", "lbappdeploydetail")
            .doesNotContainKey("schemaName");
    }

    @Test
    void rejectsMissingRoutingDecisionWithoutRuntimeInference() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of(
                "filters", Map.of("assetName", "Local MySQL Test Service"),
                "limit", 10
            ),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Analyze Local MySQL Test Service InnoDB status", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                    Map.of("filters", Map.of("intent", "InnoDB status"), "limit", 10), List.of(1), null, null),
                new InterpretationPlan.Step(3, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                    Map.of("templateId", "MYSQL_INNODB_STATUS", "parameters", Map.of()), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of(
                    "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    "mcp_chatchat_mcp_server_sql_datasource_template_query",
                    "mcp_chatchat_mcp_server_sql_query_execute"
                ),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
            "tenant-1",
            "req-routing-decision",
            "conv-routing-decision",
            "user-1",
            Map.of("executionTraceId", "trace-runtime-routing-decision")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());
        assertThat(resolved).doesNotContainKeys("finalDecision", "targetKind", "assetType");
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotInferRoutingFromDatabaseAndHostDownstreamTools() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of(
                "filters", Map.of("assetName", "demo-service"),
                "limit", 10
            ),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("ops_check", "Check service status", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_database_query",
                    Map.of("query", "status"), List.of(1), null, null),
                new InterpretationPlan.Step(3, "mcp_tool", "mcp_chatchat_mcp_server_linux_command_execute",
                    Map.of("command", "systemctl status demo"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of(
                    "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    "mcp_chatchat_mcp_server_database_query",
                    "mcp_chatchat_mcp_server_linux_command_execute"
                ),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
            "tenant-1",
            "req-routing-ambiguous",
            "conv-routing-ambiguous",
            "user-1",
            Map.of("executionTraceId", "trace-runtime-routing-ambiguous")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());
        assertThat(resolved).doesNotContainKeys("finalDecision", "targetKind", "assetType");
    }

    @Test
    void passesMissingRoutingDecisionThroughWithoutRuntimeInference() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_asset_query")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("asset_lookup", "Find a service asset", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    Map.of("filters", Map.of("assetName", "demo-service"), "limit", 10), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "",
                    Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
                List.of(),
                30000
            ),
            review()
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
            "tenant-1",
            "req-routing-target-required",
            "conv-routing-target-required",
            "user-1",
            Map.of("executionTraceId", "trace-runtime-routing-target-required")
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("STEP_FAILED");
        assertThat(result.errorMessage()).doesNotContain("ROUTING_TARGET_REQUIRED");
        assertThat(result.steps()).hasSize(1);
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService).execute(captor.capture());
        assertThat(captor.getValue().getToolInput().getParameters())
            .doesNotContainKeys("finalDecision", "targetKind", "assetType");
    }

    @Test
    void feedsReviewedWebSearchUrlIntoCrawlerStep() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("web_search")).thenReturn(true);
        when(toolRegistry.hasTool("crawl_url")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("web_search".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of("results", List.of(Map.of(
                        "title", "Example",
                        "url", "https://example.com/page",
                        "snippet", "candidate"
                    )))),
                    ToolMetadata.builder().id("web_search").build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("content", "full page")),
                ToolMetadata.builder().id("crawl_url").build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("web_search", "Collect full web evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "web_search", Map.of("query", "example"), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "crawl_url", Map.of(), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of("web_search", "crawl_url"), List.of(), 30000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            new InterpretationPlanOptimizer(),
            null,
            request -> {
                if ("web_search".equals(request.execution().toolName())) {
                    return InterpretationPlanRuntime.StepReview.accepted(
                        "candidate selected",
                        Map.of("selectedUrls", List.of("https://example.com/page"))
                    );
                }
                return InterpretationPlanRuntime.StepReview.accepted("content usable", Map.of());
            },
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("web_search", "crawl_url"),
            "tenant-1",
            "req-crawl-url",
            "conv-crawl-url",
            "user-1",
            Map.of()
        ));

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(result.success()).isTrue();
        assertThat(captor.getAllValues().get(1).getToolName()).isEqualTo("crawl_url");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("url", "https://example.com/page");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesMatchingBindingPlaceholderAtNestedInputPath() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step sourceStep = new InterpretationPlan.Step(
            1, "mcp_tool", "database_asset_search", Map.of(), List.of(), null, null);
        InterpretationPlan.Step targetStep = new InterpretationPlan.Step(
            2,
            "mcp_tool",
            "database_ops_template_search",
            Map.of("filters", Map.of("assetName", "{{bindings.assetName}}", "env", "DEV")),
            List.of(1),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("database_ops", "Inspect database status", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(sourceStep, targetStep),
                List.of(),
                List.of(new InterpretationPlan.Binding(
                    1, "$.assets[0].asset.name", 2, "assetName", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                2, false, List.of("database_asset_search", "database_ops_template_search"), List.of(), 30000),
            review()
        );
        Map<Integer, InterpretationPlanRuntime.StepExecution> completed = Map.of(
            1,
            new InterpretationPlanRuntime.StepExecution(
                1,
                "mcp_tool",
                "database_asset_search",
                true,
                Map.of("assets", List.of(Map.of("asset", Map.of("name", "test-database")))),
                null,
                null,
                null,
                10
            )
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(
            runtime,
            targetStep,
            new InterpretationPlanRuntime.ExecutionRequest(
                plan,
                mock(ToolRegistry.class),
                List.of("database_ops_template_search"),
                "tenant-1",
                "req-nested-binding",
                "conv-nested-binding",
                "user-1",
                Map.of("agentRuntimeEnvironment", "DEV")
            ),
            completed
        );

        assertThat((Map<String, Object>) resolved.get("filters"))
            .containsEntry("assetName", "test-database")
            .containsEntry("env", "DEV");
        assertThat(resolved).containsEntry("assetName", "test-database");
    }

    @Test
    void rejectsUnresolvedBindingPlaceholderBeforeToolExecution() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "assertNoUnresolvedBindingPlaceholders", Object.class);
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(
            runtime,
            Map.of("filters", Map.of("assetName", "{{bindings.assetName}}"))))
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("BINDING_FAILED: unresolved binding placeholder at $.filters.assetName");
    }

    @Test
    void resolvesPlanBindingIntoDownstreamToolInput() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("web_search")).thenReturn(true);
        when(toolRegistry.hasTool("crawl_url")).thenReturn(true);
        when(toolRegistry.getToolMetadata("web_search")).thenReturn(ToolMetadata.builder()
            .id("web_search")
            .riskLevel("low")
            .parameters(List.of(ToolParameter.builder()
                .name("query")
                .type("string")
                .required(true)
                .build()))
            .build());
        when(toolRegistry.getToolMetadata("crawl_url")).thenReturn(ToolMetadata.builder()
            .id("crawl_url")
            .riskLevel("low")
            .parameters(List.of(ToolParameter.builder()
                .name("url")
                .type("string")
                .required(true)
                .build()))
            .build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("web_search".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of("results", List.of(Map.of(
                        "title", "Market News",
                        "url", "https://example.com/market",
                        "snippet", "candidate"
                    )))),
                    ToolMetadata.builder().id("web_search").build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("content", "full page")),
                ToolMetadata.builder().id("crawl_url").build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("web_search", "Search then crawl", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "web_search", Map.of("query", "example page"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "crawl_url", Map.of(), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(1, "$.results[0].url", 2, "url", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of("web_search", "crawl_url"), List.of(), 30000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("web_search", "crawl_url"),
            "tenant-1",
            "req-binding",
            "conv-binding",
            "user-1",
            Map.of()
        ));

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(result.success()).isTrue();
        assertThat(captor.getAllValues().get(1).getToolName()).isEqualTo("crawl_url");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("url", "https://example.com/market");
    }

    @Test
    void resolvesLegacyTemplateBindingPathIntoSqlTemplateInput() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_template_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_sql_datasource_template_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of("templates", List.of(Map.of(
                        "templateId", "MYSQL_TABLE_METADATA",
                        "name", "MySQL table metadata"
                    )))),
                    ToolMetadata.builder().id(request.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("status", "ok", "parameters", request.getToolInput().getParameters())),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Query table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                        Map.of(
                            "finalDecision", "database",
                            "filters", Map.of("intent", "table metadata"),
                            "limit", 3
                        ), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of(
                            "executionContext", Map.of("assetName", "test_mysql_database", "env", "DEV"),
                            "parameters", Map.of("table", "user_info_file")
                        ), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(1, "$[0].id", 2, "template", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-template-binding",
            "conv-template-binding",
            "user-1",
            Map.of()
        ));

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(result.success()).isTrue();
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("template", "MYSQL_TABLE_METADATA");
    }

    @Test
    void hydratesSqlExecutionContextFromCompletedAssetDiscoveryWhenPlannerOmittedBinding() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_asset_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_template_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_sql_datasource_asset_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "schemaVersion", "asset_query_result.v1",
                        "assets", List.of(Map.of(
                            "asset", Map.of(
                                "name", "閺堫剙婀碝ySQL濞村鐦張宥呭",
                                "environment", "DEV",
                                "databaseRole", "primary"
                            )
                        ))
                    )),
                    ToolMetadata.builder().id(request.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            if ("mcp_chatchat_mcp_server_sql_datasource_template_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of("templates", List.of(Map.of(
                        "templateId", "MYSQL_TABLE_METADATA",
                        "id", "MYSQL_TABLE_METADATA"
                    )))),
                    ToolMetadata.builder().id(request.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("status", "ok", "parameters", request.getToolInput().getParameters())),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Query table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                        Map.of("filters", Map.of("assetName", "閺堫剙婀碝ySQL濞村鐦張宥呭"), "finalDecision", "database"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                        Map.of("filters", Map.of("intent", "table metadata"), "finalDecision", "database"), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of("parameters", Map.of("database", "test", "table", "user_info_file")), List.of(1, 2), null, null),
                    new InterpretationPlan.Step(4, "final_answer", "", Map.of("answer", "done"), List.of(3), null, null)
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(2, "$[0].id", 3, "templateId", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                4,
                false,
                List.of(
                    "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    "mcp_chatchat_mcp_server_sql_datasource_template_query",
                    "mcp_chatchat_mcp_server_sql_query_execute"
                ),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3), List.of(4)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of(
                "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                "mcp_chatchat_mcp_server_sql_datasource_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute"
            ),
            "tenant-1",
            "req-sql-context-from-asset",
            "conv-sql-context-from-asset",
            "user-1",
            Map.of()
        ));

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(3)).execute(captor.capture());
        assertThat(result.success()).isTrue();
        Map<?, ?> sqlInput = captor.getAllValues().get(2).getToolInput().getParameters();
        Map<?, ?> executionContext = (Map<?, ?>) sqlInput.get("executionContext");
        assertThat(sqlInput.get("templateId")).isEqualTo("MYSQL_TABLE_METADATA");
        assertThat(executionContext.get("assetName")).isEqualTo("閺堫剙婀碝ySQL濞村鐦張宥呭");
        assertThat(executionContext.get("env")).isEqualTo("DEV");
        assertThat(executionContext.get("databaseRole")).isEqualTo("primary");
    }

    @Test
    void acceptsTemplateIdEdgeContractFromTemplateDiscoveryEnvelope() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_template_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_sql_datasource_template_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "schemaVersion", "template_query_result.v1",
                        "templates", List.of(Map.of(
                            "schemaVersion", "command_template.v1",
                            "id", "MYSQL_TABLE_METADATA",
                            "templateId", "MYSQL_TABLE_METADATA"
                        ))
                    )),
                    ToolMetadata.builder().id(request.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("status", "ok", "parameters", request.getToolInput().getParameters())),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Query table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                        Map.of("filters", Map.of("intent", "table metadata"), "finalDecision", "database"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of(
                            "executionContext", Map.of("assetName", "test_mysql_database", "env", "DEV"),
                            "parameters", Map.of("database", "test", "table", "user_info_file")
                        ), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(new InterpretationPlan.EdgeContract(1, 2, "templateId", "string", true)),
                List.of(new InterpretationPlan.Binding(1, "$.templates[0].templateId", 2, "templateId", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-template-edge-contract",
            "conv-template-edge-contract",
            "user-1",
            Map.of()
        ));

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(result.success()).isTrue();
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("templateId", "MYSQL_TABLE_METADATA");
    }

    @Test
    void normalizesTemplateExecutionParameterAliasesFromTemplateSchema() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_template_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_sql_datasource_template_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "templates", List.of(Map.of(
                            "id", "MYSQL_TABLE_METADATA",
                            "templateId", "MYSQL_TABLE_METADATA",
                            "parameterSchema", Map.of(
                                "type", "object",
                                "properties", Map.of("tableName", Map.of("type", "string")),
                                "required", List.of("tableName")
                            )
                        ))
                    )),
                    ToolMetadata.builder().id(request.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("status", "ok", "parameters", request.getToolInput().getParameters())),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Query table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                        Map.of("filters", Map.of("intent", "table metadata"), "finalDecision", "database"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of(
                            "executionContext", Map.of("assetName", "test_mysql_database", "env", "DEV"),
                            "parameters", Map.of("table_name", "user_info_file")
                        ), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(1, "$.templates[0].templateId", 2, "templateId", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-template-param-alias",
            "conv-template-param-alias",
            "user-1",
            Map.of()
        ));

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        Map<?, ?> sqlInput = captor.getAllValues().get(1).getToolInput().getParameters();
        Map<?, ?> parameters = (Map<?, ?>) sqlInput.get("parameters");
        assertThat(sqlInput.get("templateId")).isEqualTo("MYSQL_TABLE_METADATA");
        assertThat(parameters.get("table_name")).isEqualTo("user_info_file");
        assertThat(parameters.get("tableName")).isEqualTo("user_info_file");
    }

    @Test
    void failsBeforeMcpExecutionWhenTemplateRequiredParameterIsMissing() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_template_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_sql_datasource_template_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "templates", List.of(Map.of(
                            "templateId", "MYSQL_TABLE_METADATA",
                            "parameterSchema", Map.of(
                                "type", "object",
                                "properties", Map.of("tableName", Map.of("type", "string")),
                                "required", List.of("tableName")
                            ),
                            "requiredParameters", List.of("tableName")
                        ))
                    )),
                    ToolMetadata.builder().id(request.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("status", "should-not-run")),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Query table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                        Map.of("filters", Map.of("intent", "table metadata"), "finalDecision", "database"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of(
                            "executionContext", Map.of("assetName", "test_mysql_database", "env", "DEV"),
                            "parameters", Map.of()
                        ), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(1, "$.templates[0].templateId", 2, "templateId", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-template-required-missing",
            "conv-template-required-missing",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("TEMPLATE_REQUIRED_PARAMETER_MISSING");
        verify(toolRuntimeService, times(1)).execute(any());
    }

    @Test
    void reviewsAssetDiscoveryAfterLocalFactCheckAndExecutesDependentLinuxCommand() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_ssh_asset_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_linux_command_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_ssh_asset_query"))
            .thenReturn(ToolMetadata.builder().id("mcp_chatchat_mcp_server_ssh_asset_query").riskLevel("low").build());
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_linux_command_execute"))
            .thenReturn(ToolMetadata.builder()
                .id("mcp_chatchat_mcp_server_linux_command_execute")
                .riskLevel("medium")
                .parameters(List.of(
                    ToolParameter.builder().name("template").type("string").required(true).build(),
                    ToolParameter.builder().name("executionContext").type("object").required(true).build()
                ))
                .build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_ssh_asset_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "schemaVersion", "asset_query_result.v1",
                        "success", true,
                        "returnedCount", 1,
                        "assets", List.of(Map.of(
                            "asset", Map.of(
                                "name", "docker_service",
                                "environment", "DEV",
                                "toolName", "ssh_docker_service"
                            ),
                            "capabilities", Map.of(
                                "allowedCommandTemplates", List.of("CHECK_SYSTEM_OVERVIEW")
                            )
                        ))
                    )),
                    ToolMetadata.builder().id(request.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("status", "ok")),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        AtomicInteger assetReviewCalls = new AtomicInteger();
        InterpretationPlanRuntime.StepResultReviewer reviewer = request -> {
            if ("mcp_chatchat_mcp_server_ssh_asset_query".equals(request.execution().toolName())) {
                assetReviewCalls.incrementAndGet();
                assertThat(request.execution().metadata())
                    .containsEntry("localFactCheckHasEvidence", true)
                    .containsEntry("assetDiscoveryReturnedCount", 1);
                return InterpretationPlanRuntime.StepReview.accepted("asset discovery contains a usable target", Map.of());
            }
            return InterpretationPlanRuntime.StepReview.accepted("command output usable", Map.of());
        };
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("system_operation", "Analyze docker_service load", "medium"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_ssh_asset_query",
                        Map.of("filters", Map.of("assetName", "docker_service"), "limit", 10), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_linux_command_execute",
                        Map.of("template", "CHECK_SYSTEM_OVERVIEW", "executionContext", Map.of()), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(),
                List.of(
                    new InterpretationPlan.Binding(1, "$.assets[0].asset.environment", 2, "executionContext.env", "jsonpath", true),
                    new InterpretationPlan.Binding(1, "$.assets[0].asset.name", 2, "executionContext.assetName", "jsonpath", true)
                ),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_ssh_asset_query", "mcp_chatchat_mcp_server_linux_command_execute"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            reviewer,
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_ssh_asset_query", "mcp_chatchat_mcp_server_linux_command_execute"),
            "tenant-1",
            "req-asset-linux",
            "conv-asset-linux",
            "user-1",
            Map.of()
        ));

        assertThat(result.success())
            .as(result.status() + ": " + result.errorMessage() + " steps=" + result.steps())
            .isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        Map<?, ?> linuxParameters = captor.getAllValues().get(1).getToolInput().getParameters();
        Map<?, ?> executionContext = (Map<?, ?>) linuxParameters.get("executionContext");
        assertThat(assetReviewCalls).hasValue(1);
        assertThat(captor.getAllValues().get(1).getToolName()).isEqualTo("mcp_chatchat_mcp_server_linux_command_execute");
        assertThat(linuxParameters.get("template")).isEqualTo("CHECK_SYSTEM_OVERVIEW");
        assertThat(executionContext.get("assetName")).isEqualTo("docker_service");
        assertThat(executionContext.get("env")).isEqualTo("DEV");
    }

    @Test
    void hydratesMissingLinuxTemplateFromUniqueTemplateDiscoveryResult() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_ssh_template_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_linux_command_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_ssh_template_query"))
            .thenReturn(ToolMetadata.builder().id("mcp_chatchat_mcp_server_ssh_template_query").riskLevel("low").build());
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_linux_command_execute"))
            .thenReturn(ToolMetadata.builder()
                .id("mcp_chatchat_mcp_server_linux_command_execute")
                .riskLevel("medium")
                .parameters(List.of(
                    ToolParameter.builder().name("template").type("string").required(true).build(),
                    ToolParameter.builder().name("executionContext").type("object").required(true).build()
                ))
                .build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_ssh_template_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "schemaVersion", "template_query_result.v1",
                        "success", true,
                        "templates", List.of(Map.of(
                            "templateId", "CHECK_SYSTEM_OVERVIEW",
                            "name", "System overview",
                            "parameterContract", Map.of(
                                "executionTool", "linux_command_execute",
                                "required", List.of()
                            ),
                            "invocationExample", Map.of(
                                "tool", "linux_command_execute",
                                "templateId", "CHECK_SYSTEM_OVERVIEW",
                                "parameters", Map.of()
                            )
                        ))
                    )),
                    ToolMetadata.builder().id(request.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("status", "ok")),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("system_operation", "Analyze host system status", "medium"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_ssh_template_query",
                    Map.of("query", "system overview", "limit", 1), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_linux_command_execute",
                    Map.of("executionContext", Map.of("assetName", "MySQL服务器", "env", "DEV")), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_ssh_template_query", "mcp_chatchat_mcp_server_linux_command_execute"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            null,
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_ssh_template_query", "mcp_chatchat_mcp_server_linux_command_execute"),
            "tenant-1",
            "req-linux-template-hydration",
            "conv-linux-template-hydration",
            "user-1",
            Map.of()
        ));

        assertThat(result.success())
            .as(result.status() + ": " + result.errorMessage() + " steps=" + result.steps())
            .isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        Map<?, ?> linuxParameters = captor.getAllValues().get(1).getToolInput().getParameters();
        assertThat(linuxParameters.get("template")).isEqualTo("CHECK_SYSTEM_OVERVIEW");
        assertThat(linuxParameters.get("templateId")).isEqualTo("CHECK_SYSTEM_OVERVIEW");
        assertThat(linuxParameters.get("runtimeTemplateBinding").toString())
            .contains("runtime_template_binding.v1", "CHECK_SYSTEM_OVERVIEW");
    }

    @Test
    void continuesWhenReviewerContradictsDeterministicAssetFacts() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_asset_query")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_sql_datasource_asset_query"))
            .thenReturn(ToolMetadata.builder().id("mcp_chatchat_mcp_server_sql_datasource_asset_query").riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of(
                "schemaVersion", "asset_query_result.v1",
                "success", true,
                "returnedCount", 1,
                "assets", List.of(Map.of(
                    "asset", Map.of(
                        "name", "248-test-db",
                        "environment", "DEV",
                        "toolName", "db_query_mysql_248_test_db"
                    )
                ))
            )),
            ToolMetadata.builder().id("mcp_chatchat_mcp_server_sql_datasource_asset_query").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlanRuntime.StepResultReviewer reviewer = request ->
            InterpretationPlanRuntime.StepReview.rejected(
                "Asset query returned zero results and no matching asset.",
                Map.of("reviewed", true)
            );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_metadata", "Analyze 248-test-db", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                        Map.of(
                            "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.9)),
                            "finalDecision", "database",
                            "filters", Map.of("assetName", "248-test-db"),
                            "limit", 5
                        ), List.of(), null, null),
                    new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
                ),
                List.of(),
                List.of(),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            reviewer,
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
            "tenant-1",
            "req-asset-contradiction",
            "conv-asset-contradiction",
            "user-1",
            Map.of()
        ));

        assertThat(result.success())
            .as(result.status() + ": " + result.errorMessage() + " steps=" + result.steps())
            .isTrue();
        assertThat(result.finalAnswer()).isEqualTo("done");
        assertThat(result.steps().get(0).metadata())
            .containsEntry("localFactCheckHasEvidence", true)
            .containsEntry("assetDiscoveryReturnedCount", 1)
            .containsEntry("toolResultReviewContradictedLocalFacts", true)
            .containsEntry("toolResultReviewSatisfied", true);
    }

    @Test
    void hydratesPriorObservationOutputForRewritePlanBindings() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_asset_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_template_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation -> {
            String toolName = invocation.getArgument(0);
            ToolMetadata.ToolMetadataBuilder builder = ToolMetadata.builder()
                .id(toolName)
                .riskLevel("low");
            if ("mcp_chatchat_mcp_server_sql_query_execute".equals(toolName)) {
                builder.parameters(List.of(
                    ToolParameter.builder().name("templateId").type("string").required(true).build(),
                    ToolParameter.builder().name("executionContext").type("object").required(true).build()
                ));
            }
            return builder.build();
        });
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("status", "ok", "parameters", request.getToolInput().getParameters())),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        String runId = "rewrite-hydrate-run";
        runStore.recordObservation(runId, AgentObservation.builder()
            .type("tool")
            .source("mcp_chatchat_mcp_server_sql_datasource_asset_query")
            .content("asset_query completed")
            .metadata(Map.of(
                "interpretationPlanStepId", 1,
                "interpretationPlanActionType", "mcp_tool",
                "toolName", "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                "success", true,
                "stepOutput", Map.of(
                    "assets", List.of(Map.of(
                        "asset", Map.of("name", "閺堫剙婀碝ySQL濞村鐦張宥呭", "environment", "DEV")
                    ))
                )
            ))
            .build());
        runStore.recordObservation(runId, AgentObservation.builder()
            .type("tool")
            .source("mcp_chatchat_mcp_server_sql_datasource_template_query")
            .content("template_query completed")
            .metadata(Map.of(
                "interpretationPlanStepId", 2,
                "interpretationPlanActionType", "mcp_tool",
                "toolName", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                "success", true,
                "stepOutput", Map.of(
                    "templates", List.of(Map.of("templateId", "MYSQL_INNODB_STATUS"))
                )
            ))
            .build());
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_query", "Analyze InnoDB status", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                        Map.of("filters", Map.of("assetName", "閺堫剙婀碝ySQL濞村鐦張宥呭"), "limit", 10), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                        Map.of("filters", Map.of("intent", "InnoDB status"), "limit", 10), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of("templateId", "", "parameters", Map.of()), List.of(1, 2), null, null),
                    new InterpretationPlan.Step(4, "final_answer", "", Map.of("answer", "done"), List.of(3), null, null)
                ),
                List.of(),
                List.of(
                    new InterpretationPlan.Binding(1, "$.assets[0].asset.name", 3, "executionContext.assetName", "jsonpath", true),
                    new InterpretationPlan.Binding(1, "$.assets[0].asset.environment", 3, "executionContext.env", "jsonpath", false),
                    new InterpretationPlan.Binding(2, "$.templates[0].templateId", 3, "templateId", "jsonpath", true)
                ),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                4,
                false,
                List.of(
                    "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    "mcp_chatchat_mcp_server_sql_datasource_template_query",
                    "mcp_chatchat_mcp_server_sql_query_execute"
                ),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            runStore,
            scriptedController(List.of(List.of(4)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of(
                "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                "mcp_chatchat_mcp_server_sql_datasource_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute"
            ),
            "tenant-1",
            "req-rewrite-hydrate",
            "conv-rewrite-hydrate",
            "user-1",
            Map.of("__agentRunId", runId)
        ));

        assertThat(result.success())
            .as(result.status() + ": " + result.errorMessage() + " steps=" + result.steps())
            .isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(1)).execute(captor.capture());
        Map<?, ?> parameters = captor.getValue().getToolInput().getParameters();
        Map<?, ?> executionContext = (Map<?, ?>) parameters.get("executionContext");
        assertThat(parameters.get("templateId")).isEqualTo("MYSQL_INNODB_STATUS");
        assertThat(executionContext.get("assetName")).isEqualTo("閺堫剙婀碝ySQL濞村鐦張宥呭");
        assertThat(executionContext.get("env")).isEqualTo("DEV");
    }

    @Test
    void failsWhenEdgeContractRequiredFieldIsMissing() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("items", List.of("x"))),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("document_retrieval", "Collect internal evidence", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "document_search", Map.of("query", "internal"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
                ),
                List.of(new InterpretationPlan.EdgeContract(1, 2, "data.results", "array", true))
            ),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of("document_search"), List.of(), 30000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("document_search"),
            "tenant-1",
            "req-edge-contract",
            "conv-edge-contract",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("EDGE_CONTRACT_FAILED");
        assertThat(result.errorMessage()).contains("missing required field data.results");
    }

    @Test
    void rejectsFinalAnswerDecisionWhenAnyStepsRemain() throws Exception {
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_metadata", "Analyze table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query", Map.of(), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null),
                new InterpretationPlan.Step(3, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute", Map.of(), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of(
                "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                "mcp_chatchat_mcp_server_sql_query_execute"
            ), List.of(), 30000),
            review()
        );
        Map<Integer, InterpretationPlan.Step> stepsById = new java.util.LinkedHashMap<>();
        plan.steps().forEach(step -> stepsById.put(step.id(), step));
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            scriptedController(List.of())
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "validateDecision",
            InterpretationPlanRuntime.DagDecision.class,
            InterpretationPlan.class,
            java.util.Set.class,
            Map.class,
            java.util.Set.class
        );
        method.setAccessible(true);

        Object validation = method.invoke(
            runtime,
            InterpretationPlanRuntime.DagDecision.finalAnswer(2, "done", "scripted final"),
            plan,
            new java.util.LinkedHashSet<>(List.of(2, 3)),
            stepsById,
            new java.util.LinkedHashSet<>(List.of(1))
        );
        Method validMethod = validation.getClass().getDeclaredMethod("valid");
        Method messageMethod = validation.getClass().getDeclaredMethod("message");
        validMethod.setAccessible(true);
        messageMethod.setAccessible(true);

        assertThat((Boolean) validMethod.invoke(validation)).isFalse();
        assertThat((String) messageMethod.invoke(validation))
            .contains("final_answer must be the last executed step")
            .contains("3");
    }

    @Test
    void rejectsExecuteStepDecisionWhenFinalAnswerStepWouldSkipRemainingSteps() throws Exception {
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_metadata", "Analyze table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query", Map.of(), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null),
                new InterpretationPlan.Step(3, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute", Map.of(), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of(
                "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                "mcp_chatchat_mcp_server_sql_query_execute"
            ), List.of(), 30000),
            review()
        );
        Map<Integer, InterpretationPlan.Step> stepsById = new java.util.LinkedHashMap<>();
        plan.steps().forEach(step -> stepsById.put(step.id(), step));
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            scriptedController(List.of())
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "validateDecision",
            InterpretationPlanRuntime.DagDecision.class,
            InterpretationPlan.class,
            java.util.Set.class,
            Map.class,
            java.util.Set.class
        );
        method.setAccessible(true);

        Object validation = method.invoke(
            runtime,
            InterpretationPlanRuntime.DagDecision.executeStep(2, "scripted final as execute_step"),
            plan,
            new java.util.LinkedHashSet<>(List.of(2, 3)),
            stepsById,
            new java.util.LinkedHashSet<>(List.of(1))
        );
        Method validMethod = validation.getClass().getDeclaredMethod("valid");
        Method messageMethod = validation.getClass().getDeclaredMethod("message");
        validMethod.setAccessible(true);
        messageMethod.setAccessible(true);

        assertThat((Boolean) validMethod.invoke(validation)).isFalse();
        assertThat((String) messageMethod.invoke(validation))
            .contains("final_answer must be the last executed step")
            .contains("3");
    }

    @Test
    void finalAnswerComesFromFinalStepNotControllerDecisionText() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("results", List.of("evidence"))),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("document_retrieval", "Collect evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "document_search", Map.of("query", "internal"), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "step-owned final answer"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of("document_search"), List.of(), 30000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            request -> {
                Integer stepId = request.remainingStepIds().stream().sorted().findFirst().orElse(null);
                if (Integer.valueOf(2).equals(stepId)) {
                    return InterpretationPlanRuntime.DagDecision.finalAnswer(
                        2,
                        "controller-authored answer must be ignored",
                        "select final"
                    );
                }
                return InterpretationPlanRuntime.DagDecision.executeStep(stepId, "select tool");
            }
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("document_search"),
            "tenant-1",
            "req-single-writer-final",
            "conv-single-writer-final",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("step-owned final answer");
        assertThat(result.finalAnswer()).isNotEqualTo("controller-authored answer must be ignored");
    }

    @Test
    void acceptsAssetTypeEdgeContractFromAssetEnvelopeWhenQueryScopeOmitted() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            scriptedController(List.of())
        );
        Map<String, Object> output = Map.of(
            "schemaVersion", "asset_query_result.v1",
            "success", true,
            "returnedCount", 1,
            "assets", List.of(Map.of(
                "schemaVersion", "asset_metadata.v1",
                "kind", "asset",
                "asset", Map.of(
                    "type", "ssh_host",
                    "name", "TDH scheduler server"
                ),
                "capabilities", Map.of(
                    "allowedCommandTemplateIds", List.of("CHECK_JAVA_PROCESS")
                )
            ))
        );
        var method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "checkContract",
            InterpretationPlan.EdgeContract.class,
            Object.class
        );
        method.setAccessible(true);
        Object check = method.invoke(
            runtime,
            new InterpretationPlan.EdgeContract(1, 2, "assetType", "string", true),
            output
        );
        var success = check.getClass().getDeclaredMethod("success");
        success.setAccessible(true);

        assertThat(success.invoke(check)).isEqualTo(true);
    }

    @Test
    void recordsStructuredEventsForDagStepsWhenRunIdIsAvailable() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("results", List.of("internal evidence"))),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "success",
            Map.of()
        ));
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            runStore,
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            serialPlan(),
            toolRegistry,
            List.of("document_search"),
            "tenant-1",
            "req-event-dag",
            "conv-event-dag",
            "user-1",
            Map.of("__agentRunId", "run-event-dag")
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("done");
        assertThat(result.metadata())
            .containsEntry("protocolVersion", InterpretationExecutionProtocol.VERSION)
            .containsEntry("executionTraceId", "run-event-dag::interpretation_plan");
        List<AgentRunEvent> events = runStore.events("run-event-dag");
        assertThat(events).extracting(AgentRunEvent::type)
            .contains(AgentRunEventType.STEP_RECORDED, AgentRunEventType.OBSERVATION_RECORDED);
        assertThat(events.stream()
            .filter(event -> event.type() == AgentRunEventType.OBSERVATION_RECORDED)
            .map(event -> event.payload().get("metadata"))
            .map(metadata -> (Map<?, ?>) metadata)
            .anyMatch(metadata -> Integer.valueOf(1).equals(metadata.get("interpretationPlanStepId"))
                && Boolean.TRUE.equals(metadata.get("success"))
                && "document_search".equals(metadata.get("toolName"))))
            .isTrue();
        assertThat(events.stream()
            .filter(event -> event.type() == AgentRunEventType.OBSERVATION_RECORDED)
            .map(event -> event.payload().get("metadata"))
            .map(metadata -> (Map<?, ?>) metadata)
            .anyMatch(metadata -> InterpretationExecutionProtocol.VERSION.equals(metadata.get("protocolVersion"))
                && "run-event-dag::interpretation_plan".equals(metadata.get("executionTraceId"))
                && "controller_decision".equals(metadata.get("lifecyclePhase"))
                && metadata.get("decision") instanceof Map<?, ?>
                && metadata.get("guardResult") instanceof Map<?, ?>))
            .isTrue();
    }

    @Test
    void doesNotReplayCompletedStepButExecutesNewWorkflowStepAfterRewrite() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("results", List.of("locked evidence"))),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "success",
            Map.of()
        ));
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        InterpretationPlanRuntime.StepResultReviewer reviewer = request -> InterpretationPlanRuntime.StepReview.accepted(
            "sufficient evidence",
            Map.of("evidenceEvaluation", Map.of(
                "relevance", 0.95,
                "answerability", 0.95,
                "usefulness", "HIGH"
            ))
        );
        InterpretationPlanRuntime firstRuntime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            runStore,
            reviewer,
            request -> InterpretationPlanRuntime.DagDecision.rewritePlan("rewrite after locked evidence")
        );

        InterpretationPlanRuntime.ExecutionResult rewriteRequested = firstRuntime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            serialPlan(),
            toolRegistry,
            List.of("document_search"),
            "tenant-1",
            "req-lock-rewrite",
            "conv-lock-rewrite",
            "user-1",
            Map.of("__agentRunId", "run-lock-rewrite")
        ));

        assertThat(rewriteRequested.success()).isFalse();
        assertThat(rewriteRequested.status()).isEqualTo("DAG_REWRITE_REQUESTED");
        assertThat(rewriteRequested.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1);
        Map<?, ?> executionLock = (Map<?, ?>) rewriteRequested.steps().get(0).metadata().get("executionLock");
        assertThat(executionLock.get("contractVersion")).isEqualTo("evidence_execution_lock_v1");
        assertThat(executionLock.get("lock")).isEqualTo(true);
        assertThat(executionLock.get("lockLevel")).isEqualTo("HARD");
        assertThat(executionLock.get("reason")).isEqualTo("sufficient_evidence");
        assertThat(executionLock.get("lockedSteps")).isEqualTo(List.of(1));
        assertThat(((Map<?, ?>) executionLock.get("executionConstraints")).get("blocked_tools"))
            .isEqualTo(List.of("document_search"));
        assertThat(((Map<?, ?>) executionLock.get("executionConstraints")).get("allow_only"))
            .isEqualTo(List.of("final_answer"));
        Map<?, ?> lockGraph = (Map<?, ?>) executionLock.get("lockGraph");
        assertThat(lockGraph.get("lockGraphVersion")).isEqualTo("evidence_execution_lock_v2");
        assertThat((List<?>) lockGraph.get("locks")).hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) lockGraph.get("locks")).get(0)).get("type")).isEqualTo("HARD");
        assertThat(((Map<?, ?>) lockGraph.get("dagFreeze")).get("status")).isEqualTo("FULLY_FROZEN");
        assertThat(((Map<?, ?>) lockGraph.get("propagation")).get("nodeWeights")).isInstanceOf(Map.class);

        InterpretationPlanRuntime secondRuntime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            runStore,
            reviewer,
            request -> {
                assertThat(request.completedStepIds()).contains(1);
                assertThat(request.remainingStepIds()).doesNotContain(1);
                if (request.remainingStepIds().contains(3)) {
                    return InterpretationPlanRuntime.DagDecision.executeStep(3, "execute new workflow step after rewrite");
                }
                assertThat(request.remainingStepIds()).containsExactly(2);
                return InterpretationPlanRuntime.DagDecision.finalAnswer(2, "done", "all workflow steps completed");
            }
        );

        InterpretationPlanRuntime.ExecutionResult completed = secondRuntime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            rewrittenPlanWithRepeatedSearch(),
            toolRegistry,
            List.of("document_search"),
            "tenant-1",
            "req-lock-rewrite-2",
            "conv-lock-rewrite",
            "user-1",
            Map.of("__agentRunId", "run-lock-rewrite")
        ));

        assertThat(completed.success()).isTrue();
        assertThat(completed.finalAnswer()).isEqualTo("done");
        assertThat(completed.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactlyInAnyOrder(2, 3);
        assertThat(completed.steps()).extracting(InterpretationPlanRuntime.StepExecution::actionType)
            .contains("mcp_tool", "final_answer");
        List<?> completedPlanStepIds = (List<?>) completed.metadata().get("completedPlanStepIds");
        assertThat(completedPlanStepIds.containsAll(List.of(1, 2, 3))).isTrue();
        verify(toolRuntimeService, times(2)).execute(any());
    }

    private InterpretationPlan rewrittenPlanWithRepeatedSearch() {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("document_retrieval", "Collect internal evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "document_search", Map.of("query", "internal"), List.of(), null, null),
                new InterpretationPlan.Step(3, "mcp_tool", "document_search", Map.of("query", "internal retry"), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of("document_search"), List.of(), 30000),
            review()
        );
    }

    private InterpretationPlan minimalPlan(InterpretationPlan.Step step) {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_metadata", "Analyze table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(4, "final_answer", "", Map.of("answer", "done"), List.of(step.id()), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of("mcp_chatchat_mcp_server_sql_query_execute"), List.of(), 30000),
            review()
        );
    }

    private InterpretationPlan legacyBusinessQueryPlan() {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_query", "分析行情数据发生较大波动时异常提醒数据", "medium"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1,
                        "mcp_tool",
                        "mcp_chatchat_mcp_server_database_query_template_query",
                        Map.of(
                            "finalDecision", "business_database_query",
                            "filters", Map.of(
                                "intent", "分析行情数据发生较大波动时异常提醒数据",
                                "bilingualIntent", List.of("行情波动", "异常提醒", "market data volatility", "alert")
                            ),
                            "limit", 10
                        ),
                        List.of(),
                        null,
                        null
                    ),
                    new InterpretationPlan.Step(
                        2,
                        "mcp_tool",
                        "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of("templateId", "BOUND_FROM_STEP1", "parameters", Map.of()),
                        List.of(1),
                        null,
                        null
                    ),
                    new InterpretationPlan.Step(
                        3,
                        "final_answer",
                        "",
                        Map.of("answer", "done"),
                        List.of(2),
                        null,
                        null
                    )
                ),
                List.of(
                    new InterpretationPlan.EdgeContract(1, 2, "templates[0].templateId", "string", true),
                    new InterpretationPlan.EdgeContract(1, 2, "assetName", "string", true),
                    new InterpretationPlan.EdgeContract(1, 2, "env", "string", true)
                ),
                List.of(
                    new InterpretationPlan.Binding(1, "$.templates[0].templateId", 2, "templateId", "jsonpath", true),
                    new InterpretationPlan.Binding(1, "$.assetName", 2, "executionContext.assetName", "jsonpath", true),
                    new InterpretationPlan.Binding(1, "$.env", 2, "executionContext.env", "jsonpath", true)
                ),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of(
                    "mcp_chatchat_mcp_server_database_query_template_query",
                    "mcp_chatchat_mcp_server_sql_query_execute"
                ),
                List.of(),
                30000
            ),
            review()
        );
    }

    private InterpretationPlanRuntime.StepExecution completedSqlAssetStep() {
        Map<String, Object> asset = Map.of(
            "asset", Map.of(
                "id", "datasource-248",
                "name", "test_db_248",
                "environment", "DEV",
                "toolName", "db_query_mysql_248_test_db",
                "databaseType", "mysql"
            ),
            "executionContext", Map.of(
                "assetName", "test_db_248",
                "env", "DEV",
                "databaseType", "mysql"
            )
        );
        return new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            true,
            Map.of("assets", List.of(asset), "selectedAsset", asset),
            null,
            null,
            null,
            10L
        );
    }

    private InterpretationPlan parallelPlan() {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("mixed", "Collect internal and web evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "document_search", Map.of("query", "internal"), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "web_search", Map.of("query", "public"), List.of(), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(1, 2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, true, List.of("document_search", "web_search"), List.of(), 30000),
            review()
        );
    }

    private InterpretationPlan serialPlan() {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("document_retrieval", "Collect internal evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "document_search", Map.of("query", "internal"), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of("document_search"), List.of(), 30000),
            review()
        );
    }

    private InterpretationPlan sqlQueryPlan(String toolName) {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_analysis", "Collect SQL analysis evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", toolName, Map.of(
                    "templateId", "market_kpi",
                    "executionContext", Map.of("assetName", "dm-test", "env", "DEV")
                ), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of(toolName), List.of(), 30000),
            review()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void removesProtocolFieldsFromDiscoveryFiltersBeforeToolCall() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            2,
            "mcp_tool",
            "mcp_chatchat_mcp_server_ssh_template_query",
            Map.of(
                "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.9)),
                "finalDecision", "database",
                "filters", Map.of(
                    "assetName", "TDH scheduler",
                    "env", "DEV",
                    "intent", "list java processes",
                    "trace", Map.of("plannerVersion", "v1.1"),
                    "finalDecision", "host",
                    "filtersSchemaVersion", "target_filters.v1"
                ),
                "trace", Map.of("plannerVersion", "v1.1")
            ),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("system_operation", "list java processes", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_ssh_template_query"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_ssh_template_query"),
            "tenant-1",
            "req-filter-sanitize",
            "conv-filter-sanitize",
            "user-1",
            Map.of("executionTraceId", "trace-runtime-filter-sanitize")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        Map<String, Object> filters = (Map<String, Object>) resolved.get("filters");
        assertThat(filters)
            .containsEntry("assetName", "TDH scheduler")
            .containsEntry("env", "DEV")
            .containsEntry("intent", "list java processes")
            .doesNotContainKeys("trace", "finalDecision", "filtersSchemaVersion");
        assertThat(resolved).containsEntry("finalDecision", "database");
        assertThat(resolved.get("trace").toString()).doesNotContain("routingForcedByTypedDiscoveryTool");
    }

    private InterpretationPlan.Context context() {
        return new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of());
    }

    private InterpretationPlan.Review review() {
        return new InterpretationPlan.Review(
            new InterpretationPlan.SelfCheck(0.8, 0.1, true, List.of()),
            List.of()
        );
    }

    private InterpretationPlanRuntime.DagExecutionController scriptedController(List<List<Integer>> waves) {
        AtomicInteger index = new AtomicInteger();
        return request -> {
            while (waves != null && index.get() < waves.size()) {
                List<Integer> stepIds = waves.get(index.getAndIncrement()).stream()
                    .filter(stepId -> request.remainingStepIds().contains(stepId))
                    .toList();
                if (stepIds.isEmpty()) {
                    continue;
                }
                if (stepIds.size() > 1) {
                    return InterpretationPlanRuntime.DagDecision.executeParallelSteps(stepIds, "scripted parallel decision");
                }
                Integer stepId = stepIds.get(0);
                InterpretationPlan.Step step = request.plan().steps().stream()
                    .filter(candidate -> stepId.equals(candidate.id()))
                    .findFirst()
                    .orElse(null);
                if (step != null && step.finalAnswerAction()) {
                    return InterpretationPlanRuntime.DagDecision.finalAnswer(stepId, String.valueOf(step.input().get("answer")), "scripted final answer");
                }
                return InterpretationPlanRuntime.DagDecision.executeStep(stepId, "scripted decision");
            }
            List<Integer> finalStepIds = request.remainingStepIds().stream()
                .filter(stepId -> request.plan().steps().stream()
                    .anyMatch(step -> stepId.equals(step.id()) && step.finalAnswerAction()))
                .toList();
            if (!finalStepIds.isEmpty()) {
                Integer stepId = finalStepIds.get(0);
                InterpretationPlan.Step step = request.plan().steps().stream()
                    .filter(candidate -> stepId.equals(candidate.id()))
                    .findFirst()
                    .orElse(null);
                Object answer = step == null || step.input() == null ? null : step.input().get("answer");
                return InterpretationPlanRuntime.DagDecision.finalAnswer(stepId, answer == null ? "" : String.valueOf(answer), "scripted final answer");
            }
            return InterpretationPlanRuntime.DagDecision.abort("No scripted DAG decision remains");
        };
    }
}



