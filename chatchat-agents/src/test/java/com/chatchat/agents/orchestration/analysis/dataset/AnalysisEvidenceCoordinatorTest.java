package com.chatchat.agents.orchestration.analysis.dataset;

import com.chatchat.agents.orchestration.analysis.contract.SemanticInsightContractProvider;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
import com.chatchat.agents.runtime.protocol.RuntimeAnalysisContextProtocol;
import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisProtocol;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisEvidenceCoordinatorTest {

    @Test void declaredEmptyMcpDataRemainsAReadableEmptyDatasetAcrossTransportCarriers() throws Exception {
        var payload=new com.chatchat.common.mcp.runtime.McpAnalysisPayload(null,"request","service","opaque",
            "EMPTY_RESULT",com.chatchat.common.mcp.service.McpResultKind.EMPTY,"published-schema",null,null,
            Map.of("complete",true),List.of(),Map.of("rows",List.of(Map.of("poison",99))),null,null,false,null,Map.of());
        var json=new ObjectMapper();
        for(Object carrier:List.of(payload,payload.toMap(),json.writeValueAsString(payload.toMap()),
                json.valueToTree(payload.toMap()),Map.of("structuredContent",payload.toMap()),
                com.chatchat.common.tool.ToolOutput.success(payload.toMap()))) {
            var projection=coordinator(mock(ToolRegistry.class),new com.chatchat.agents.runtime.analysis.McpResultAnalysisBridge())
                .project(result(step("opaque_template_execute",carrier)));
            assertThat(projection.excludedDatasets()).isEmpty();
            assertThat(projection.datasets()).singleElement().satisfies(dataset->{
                assertThat(dataset.recordCount()).isZero();
                assertThat(dataset.analysisContext()).containsEntry("sourceVerifiedEmpty",true);
                assertThat(dataset.records()).isEmpty();
            });
            assertThat(DataSupplyStatus.describe(result(step("opaque_template_execute",carrier)),projection.datasets(),projection.excludedDatasets()))
                .containsEntry("status","EMPTY_RESULT").containsEntry("deliveryComplete",true);
        }
    }

    @Test void failedOrIncompleteDeclaredEmptyResultCannotBecomeTransportTextOrAZeroRowSuccess() {
        for(String status:List.of("FAILED","PARTIAL")) {
            var payload=Map.of("schemaVersion",com.chatchat.common.mcp.runtime.McpAnalysisPayload.SCHEMA_VERSION,
                "status",status,"resultKind","EMPTY","completeness",Map.of("complete",false),
                "data",List.of(),"rawData",Map.of("rows",List.of(Map.of("poison",99))));
            var projection=coordinator(mock(ToolRegistry.class),new com.chatchat.agents.runtime.analysis.McpResultAnalysisBridge())
                .project(result(step("opaque_template_execute",payload)));
            assertThat(projection.datasets()).isEmpty();
            assertThat(DataSupplyStatus.describe(result(step("opaque_template_execute",payload)),projection.datasets(),projection.excludedDatasets()))
                .containsEntry("status","EXECUTION_FAILED");
        }
    }

    @Test void verifiedEmptySqlRowsRemainEmptyEvidenceRatherThanSerializedTransportPayload() throws Exception {
        var envelope=Map.of("dataSchema","sql_result.v1","data",Map.of("rows",List.of(),"rowCount",0,
            "complete",true,"possiblyTruncated",false,"diagnostics",Map.of("password","private-value")));
        for (Object payload : sqlCarriers(envelope)) {
            var projection=coordinator(mock(ToolRegistry.class),new com.chatchat.agents.runtime.analysis.McpResultAnalysisBridge())
                .project(result(step("empty_sql",payload)));
            assertThat(projection.datasets()).singleElement().satisfies(dataset->{
                assertThat(dataset.reference()).isEqualTo("empty_sql");
                assertThat(dataset.recordCount()).isZero();
                assertThat(dataset.analysisContext()).containsEntry("sourceVerifiedEmpty",true);
                assertThat(dataset.records()).isEmpty();
            });
        }
    }

    @Test
    void unsuccessfulOrIncompleteEmptySqlDoesNotBecomeTransportTextEvidence() throws Exception {
        for (boolean complete : List.of(false, true)) for (boolean success : List.of(false,true)) {
            if (complete && success) continue;
            var envelope=Map.of("dataSchema","sql_result.v1","success",success,"data",Map.of(
                "rows",List.of(),"rowCount",0,"complete",complete,"possiblyTruncated",!complete));
            for (Object payload : sqlCarriers(envelope)) {
                var projection=coordinator(mock(ToolRegistry.class),new com.chatchat.agents.runtime.analysis.McpResultAnalysisBridge())
                    .project(result(step("unavailable_sql",payload)));
                assertThat(projection.datasets()).isEmpty();
                assertThat(projection.excludedDatasets()).singleElement().satisfies(item ->
                    assertThat(item).containsEntry("reason","NO_PROJECTED_CONTENT"));
            }
        }
    }

    private List<Object> sqlCarriers(Map<String,?> envelope) throws Exception {
        var json=new ObjectMapper();
        var canonical=Map.of("schemaVersion",com.chatchat.common.mcp.runtime.McpAnalysisPayload.SCHEMA_VERSION,
            "resultKind","UNDECLARED","data",envelope,
            "rawData",Map.of("rows",List.of(Map.of("must_not_become_business_data",9999))));
        return List.of(envelope,json.writeValueAsString(envelope),json.valueToTree(envelope),
            canonical,json.writeValueAsString(canonical),json.valueToTree(canonical),
            json.convertValue(canonical,com.chatchat.common.mcp.runtime.McpAnalysisPayload.class),
            com.chatchat.common.tool.ToolOutput.success(canonical));
    }

    @Test void declaredDocumentContainingSqlJsonRemainsATextAnalysisSource() throws Exception {
        String text=new ObjectMapper().writeValueAsString(Map.of("dataSchema","sql_result.v1","data",Map.of(
            "rows",List.of(),"rowCount",0,"complete",true,"possiblyTruncated",false)));
        var document=Map.of("schemaVersion",com.chatchat.common.mcp.runtime.McpAnalysisPayload.SCHEMA_VERSION,
            "resultKind","DOCUMENT","data",text);
        var projection=coordinator(mock(ToolRegistry.class),new com.chatchat.agents.runtime.analysis.McpResultAnalysisBridge())
            .project(result(step("declared_document",document)));
        assertThat(projection.datasets()).singleElement().satisfies(dataset->{
            assertThat(dataset.recordCount()).isEqualTo(1);
            assertThat(dataset.records()).singleElement().satisfies(row->assertThat(row.get("content")).isEqualTo(text));
        });
    }

    @Test
    void failedParentBatchCannotEraseItsBlockedChildOutcome() {
        var blocked = new ToolCallResult("blocked", "tool", "template", "asset", "BLOCKED", 0,
            null, null, Map.of("code", "TEMPLATE_REQUIRED_PARAMETERS_MISSING"));
        var batch = new ToolCallBatchResult("batch", "SEQUENTIAL", "start", "end", "FAILED",
            new ToolCallBatchResult.Summary(1, 0, 0, 1, 0, 0), List.of(blocked));
        var failed = new InterpretationPlanRuntime.StepExecution(1, "mcp_tool", "tool", false,
            batch, "all children unavailable", null, null, 1);
        assertThat(coordinator().project(result(failed)).excludedDatasets()).singleElement().satisfies(item ->
            assertThat(item).containsEntry("executionStatus", "BLOCKED").containsEntry("invoked", false)
                .containsEntry("reason", "SOURCE_EXECUTION_BLOCKED").containsEntry("error", blocked.error()));
    }

    @Test
    void preservesBlockedAndUninvokedChildrenSeparatelyFromExecutionFailures() {
        var blocked = new ToolCallResult("blocked", "tool", "blocked-template", "asset", "BLOCKED", 0,
            null, null, Map.of("code", "TEMPLATE_REQUIRED_PARAMETERS_MISSING", "message", "container missing"));
        var failed = new ToolCallResult(null, "batch", "failed", "failed", "tool", "tool", "failed-template",
            "failed-template", "asset", null, null, 2, false, "FAILED", true, 10, null, null,
            Map.of("code", "TRANSPORT_ERROR", "message", "connection refused"));
        var skipped = new ToolCallResult("skipped", "tool", "skipped-template", "asset", "SKIPPED", 0,
            null, null, Map.of("code", "DEPENDENCY_BLOCKED"));
        var success = new ToolCallResult("success", "tool", "success-template", "asset", "SUCCESS", 1,
            "evidence", Map.of("records", List.of(Map.of("observed", 1))), Map.of());
        var batch = new ToolCallBatchResult("batch", "SEQUENTIAL", "start", "end", "PARTIAL_SUCCESS",
            new ToolCallBatchResult.Summary(4, 1, 1, 1, 1, 2), List.of(blocked, failed, skipped, success));
        var projection = coordinator().project(stepResult(batch));
        assertThat(projection.datasets()).hasSize(1);
        assertThat(projection.excludedDatasets()).hasSize(3).anySatisfy(item -> assertThat(item)
            .containsEntry("executionStatus", "BLOCKED").containsEntry("invoked", false)
            .containsEntry("accountingStatus", "EXCLUDED").containsEntry("reason", "SOURCE_EXECUTION_BLOCKED")
            .containsEntry("error", blocked.error()));
        assertThat(projection.excludedDatasets()).anySatisfy(item -> assertThat(item)
            .containsEntry("executionStatus", "FAILED").containsEntry("invoked", true)
            .containsEntry("accountingStatus", "FAILED").containsEntry("reason", "SOURCE_EXECUTION_FAILED"));
        assertThat(projection.excludedDatasets()).anySatisfy(item -> assertThat(item)
            .containsEntry("executionStatus", "SKIPPED").containsEntry("invoked", false)
            .containsEntry("accountingStatus", "EXCLUDED").containsEntry("reason", "SOURCE_NOT_EXECUTED"));
    }

    @Test
    void neverProjectsFinalAnswerBackIntoBusinessEvidence() {
        InterpretationPlanRuntime.StepExecution finalAnswer = new InterpretationPlanRuntime.StepExecution(
            2, "final_answer", "final_answer", true,
            Map.of("payload", Map.of("answer", "presentation only")), null, null, null, 1);

        AnalysisEvidenceCoordinator.Projection projection = coordinator().project(result(finalAnswer));

        assertThat(projection.datasets()).isEmpty();
        assertThat(projection.excludedDatasets()).isEmpty();
    }

    @Test
    void pinsRoleContextBeforeDatasetRelationshipAndWorkerPlanning() {
        AnalysisEvidenceCoordinator coordinator = coordinator();
        Map<String, Object> attributes = new java.util.LinkedHashMap<>(Map.of(
            AgentRoleAnalysisContext.RUNTIME_ATTRIBUTE,
            AgentRoleAnalysisContext.create("Quality analyst", "Find quality risks",
                List.of("Quality review"), List.of("quality"))));
        AgentRoleAnalysisContext.pinToRuntime(attributes, "run-quality", "agent-quality");

        AnalysisEvidenceCoordinator.Projection projection = coordinator.project(
            result(step("runtime_source", Map.of("records", List.of(Map.of("opaque", 1))))),
            attributes);

        assertThat(projection.datasets().get(0).analysisContext())
            .containsKey(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY);
        assertThat(projection.datasets().get(0).analysisContext().toString())
            .contains("run-quality", "Find quality risks");
    }

    @Test
    void projectsArbitraryRecordsWithoutBusinessFieldAssumptions() {
        AnalysisEvidenceCoordinator coordinator = coordinator();
        InterpretationPlanRuntime.ExecutionResult execution = result(step(
            "runtime_arbitrary_source", Map.of("records", List.of(
                Map.of("unknown_dimension", "A", "unknown_measure", 12)))));

        AnalysisEvidenceCoordinator.Projection projection = coordinator.project(execution);

        assertThat(projection.datasets()).hasSize(1);
        assertThat(projection.datasets().get(0).reference()).isEqualTo("runtime_arbitrary_source");
        assertThat(projection.datasets().get(0).records()).containsExactly(
            Map.of("unknown_dimension", "A", "unknown_measure", 12));
        assertThat(projection.excludedDatasets()).isEmpty();
    }

    @Test
    void annotatesSqlRowsWithCompletenessAndStableRowIdentity() {
        AnalysisEvidenceCoordinator coordinator = coordinator();
        InterpretationPlanRuntime.ExecutionResult execution = result(step("sql_tool", Map.of(
            "dataSchema", "sql_result.v1",
            "data", Map.of("possiblyTruncated", true,
                "rows", List.of(Map.of("opaque", 1), Map.of("opaque", 2))))));

        List<Map<String, Object>> rows = coordinator.project(execution).datasets().get(0).records();

        assertThat(rows).extracting(row -> row.get("_resultRowIndex"))
            .containsExactly(1, 2);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("sourceComplete")).isEqualTo(false));
    }

    @Test
    void excludesDiscoveryMetadataFromBusinessEvidence() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getWorkflowRole("mcp_chatchat_mcp_server_customer_service_template_query"))
            .thenReturn(com.chatchat.common.tool.ToolWorkflowRole.TEMPLATE_DISCOVERY);
        AnalysisEvidenceCoordinator coordinator = coordinator(registry);

        AnalysisEvidenceCoordinator.Projection projection = coordinator.project(result(step(
            "mcp_chatchat_mcp_server_customer_service_template_query",
            Map.of("records", List.of(Map.of("template", "metadata-only"))))));

        assertThat(projection.datasets()).isEmpty();
    }

    @Test
    void removesCheckpointReplayOfTheSameDatasetEvidence() {
        AnalysisEvidenceCoordinator coordinator = coordinator();
        Object output = Map.of("records", List.of(Map.of("trade", "buy", "quantity", 100)));
        InterpretationPlanRuntime.ExecutionResult execution = new InterpretationPlanRuntime.ExecutionResult(
            "completed", true, false, null, null,
            List.of(step("trade_source", output), step("trade_source", output)), Map.of(), 1);

        AnalysisEvidenceCoordinator.Projection projection = coordinator.project(execution);

        assertThat(projection.datasets()).singleElement().satisfies(dataset -> {
            assertThat(dataset.reference()).isEqualTo("trade_source");
            assertThat(dataset.recordCount()).isEqualTo(1);
        });
    }

    @Test
    void keepsRepeatedTemplateEntityBindingsInSeparateDatasetPartitions() {
        ToolCallResult first = new ToolCallResult(
            "binding-one", "api_template_execute", "shared-template", null,
            "SUCCESS", 1, "evidence-1", Map.of("records", List.of(Map.of("value", 1))), Map.of());
        ToolCallResult second = new ToolCallResult(
            "binding-two", "api_template_execute", "shared-template", null,
            "SUCCESS", 1, "evidence-2", Map.of("records", List.of(Map.of("value", 2))), Map.of());
        ToolCallBatchResult batch = new ToolCallBatchResult(
            "batch", "SEQUENTIAL", "start", "end", "SUCCESS",
            new ToolCallBatchResult.Summary(2, 2, 0, 0, 0, 2), List.of(first, second));

        AnalysisEvidenceCoordinator.Projection projection = coordinator().project(stepResult(batch));

        assertThat(projection.datasets()).extracting(AnalysisEvidenceCoordinator.Dataset::reference)
            .containsExactly("shared-template#binding-one", "shared-template#binding-two");
        assertThat(projection.datasets()).extracting(AnalysisEvidenceCoordinator.Dataset::recordCount)
            .containsExactly(1L, 1L);
    }

    @Test
    void accountsForEmptyNonBatchToolResults() {
        AnalysisEvidenceCoordinator.Projection projection = coordinator().project(
            result(step("runtime_empty_source", Map.of("records", List.of()))));

        assertThat(projection.datasets()).isEmpty();
        assertThat(projection.excludedDatasets()).singleElement().satisfies(excluded ->
            assertThat(excluded)
                .containsEntry("datasetReference", "runtime_empty_source")
                .containsEntry("accountingStatus", "EXCLUDED")
                .containsEntry("reason", "NO_PROJECTED_CONTENT"));
    }

    @Test
    void accountsForFailedNonBatchToolSteps() {
        InterpretationPlanRuntime.StepExecution failed = new InterpretationPlanRuntime.StepExecution(
            7, "mcp_tool", "runtime_failed_source", false, null,
            "upstream unavailable", null, null, 12);

        AnalysisEvidenceCoordinator.Projection projection = coordinator().project(result(failed));

        assertThat(projection.datasets()).isEmpty();
        assertThat(projection.excludedDatasets()).singleElement().satisfies(excluded ->
            assertThat(excluded)
                .containsEntry("datasetReference", "runtime_failed_source")
                .containsEntry("accountingStatus", "FAILED")
                .containsEntry("reason", "SOURCE_EXECUTION_FAILED")
                .containsEntry("error", "upstream unavailable"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = com.chatchat.common.tool.ToolWorkflowRole.class)
    void publishedRoleControlsDatasetAdmission(com.chatchat.common.tool.ToolWorkflowRole role) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getWorkflowRole("opaque_lookup")).thenReturn(role);
        var projection = coordinator(registry).project(result(step("opaque_lookup",
            Map.of("rows", List.of(Map.of("value", 42))))));
        if (role == com.chatchat.common.tool.ToolWorkflowRole.ASSET_DISCOVERY
            || role == com.chatchat.common.tool.ToolWorkflowRole.TEMPLATE_DISCOVERY)
            assertThat(projection.datasets()).isEmpty();
        else assertThat(projection.datasets()).hasSize(1);
    }

    private AnalysisEvidenceCoordinator coordinator() {
        return coordinator(mock(ToolRegistry.class));
    }

    private AnalysisEvidenceCoordinator coordinator(ToolRegistry registry) {
        return coordinator(registry, null);
    }

    private AnalysisEvidenceCoordinator coordinator(ToolRegistry registry, RuntimeResultAnalysisProtocol bridge) {
        RuntimeAnalysisContextProtocol context = mock(RuntimeAnalysisContextProtocol.class);
        when(context.adapt(anyString(), any(), any())).thenReturn(Map.of());
        when(context.adaptDataset(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        RuntimeResultAnalysisProtocol result = mock(RuntimeResultAnalysisProtocol.class);
        when(result.protocolAnalysisProjection(anyString(), any(), anyInt())).thenReturn(Map.of());
        when(result.analysisProjection(anyString(), any(), anyInt())).thenReturn(Map.of());
        ToolRuntimeService toolRuntime = mock(ToolRuntimeService.class);
        when(toolRuntime.resolveBatchOutputForEvidenceReview(any()))
            .thenAnswer(invocation -> invocation.getArgument(0));
        return new AnalysisEvidenceCoordinator(registry, toolRuntime,
            new StructuredDataProjector(), new AnalysisRecordChunkPlanner(new ObjectMapper()), 20_000,
            context, bridge == null ? result : bridge, SemanticInsightContractProvider.disabled());
    }

    @Test
    void admitsPlainTextBodyAndCommandStreamsThroughTheRealProtocolRegistry() {
        var coordinator = coordinator(mock(ToolRegistry.class),
            new com.chatchat.agents.runtime.analysis.McpResultAnalysisBridge());
        String text = "load average: 0.01\n内存观测值，不预判是否足够\n";
        for (Object payload : List.of(text, Map.of(
            "schemaVersion", com.chatchat.common.mcp.runtime.McpAnalysisPayload.SCHEMA_VERSION,
            "data", Map.of("body", text)), Map.of("dataSchema", "ssh_steps.v1",
                "data", Map.of("stdout", text)))) {
            var projection = coordinator.project(result(step("opaque_source", payload)));
            assertThat(projection.excludedDatasets()).isEmpty();
            assertThat(projection.datasets()).singleElement().satisfies(dataset -> {
                assertThat(dataset.reference()).startsWith("opaque_source#");
                assertThat(dataset.records()).extracting(record -> record.get("content")).containsExactly(text);
            });
        }
    }

    private InterpretationPlanRuntime.StepExecution step(String toolName, Object output) {
        return new InterpretationPlanRuntime.StepExecution(
            1, "mcp_tool", toolName, true, output, null, null, null, 1);
    }

    private InterpretationPlanRuntime.ExecutionResult result(
        InterpretationPlanRuntime.StepExecution step) {
        return new InterpretationPlanRuntime.ExecutionResult(
            "completed", true, false, null, null, List.of(step), Map.of(), 1);
    }

    private InterpretationPlanRuntime.ExecutionResult stepResult(Object output) {
        return result(step("api_template_execute", output));
    }
}
