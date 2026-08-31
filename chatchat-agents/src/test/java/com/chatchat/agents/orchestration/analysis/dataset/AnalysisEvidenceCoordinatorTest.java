package com.chatchat.agents.orchestration.analysis.dataset;

import com.chatchat.agents.orchestration.analysis.contract.SemanticInsightContractProvider;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
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
        AnalysisEvidenceCoordinator coordinator = coordinator();

        AnalysisEvidenceCoordinator.Projection projection = coordinator.project(result(step(
            "mcp_chatchat_mcp_server_customer_service_template_query",
            Map.of("records", List.of(Map.of("template", "metadata-only"))))));

        assertThat(projection.datasets()).isEmpty();
    }

    private AnalysisEvidenceCoordinator coordinator() {
        RuntimeAnalysisContextProtocol context = mock(RuntimeAnalysisContextProtocol.class);
        when(context.adapt(anyString(), any(), any())).thenReturn(Map.of());
        when(context.adaptDataset(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        RuntimeResultAnalysisProtocol result = mock(RuntimeResultAnalysisProtocol.class);
        when(result.protocolAnalysisProjection(anyString(), any(), anyInt())).thenReturn(Map.of());
        when(result.analysisProjection(anyString(), any(), anyInt())).thenReturn(Map.of());
        return new AnalysisEvidenceCoordinator(mock(ToolRegistry.class), mock(ToolRuntimeService.class),
            new StructuredDataProjector(), new AnalysisRecordChunkPlanner(new ObjectMapper()), 20_000,
            context, result, SemanticInsightContractProvider.disabled());
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
}
