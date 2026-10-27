package com.chatchat.agents.orchestration.analysis.dataset;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DataSupplyStatusTest {
    @Test void actualMcpSuccessSurvivesMissingProjectionFlags() {
        var tool=org.mockito.Mockito.mock(com.chatchat.agents.runtime.tool.ToolRuntimeExecution.class);
        org.mockito.Mockito.when(tool.output()).thenReturn(com.chatchat.common.tool.ToolOutput.success(Map.of("assets",List.of())));
        var step=new InterpretationPlanRuntime.StepExecution(1,"mcp_tool","discovery",false,null,
            "selection incomplete",tool,null,1L,Map.of("candidateSelectionErrorCode","MODEL_SELECTION_INCOMPLETE"));
        var execution=new InterpretationPlanRuntime.ExecutionResult("run",false,false,null,"failed",List.of(step),Map.of(),1);
        assertThat(DataSupplyStatus.describe(execution,List.of(),List.of()).get("failures").toString())
            .contains("mcpDiscoverySucceeded=true","stage=SELECTION");
    }
    @Test void selectionFailureIsNotAnMcpExecutionFailureOrRetryPermission() {
        var status=DataSupplyStatus.describe(failed("MODEL_UNAVAILABLE"),List.of(),List.of());
        assertThat(status).containsEntry("status","SELECTION_FAILED")
            .containsEntry("automaticRetryAuthorized",false).containsEntry("businessDataAvailable",false);
        assertThat(status.get("failures").toString()).contains("mcpDiscoverySucceeded=true","stage=SELECTION");
    }

    @Test void invalidParametersRemainSeparateFromSelectionAndExecutionFailures() {
        assertThat(DataSupplyStatus.describe(failed("PARAMETERS_REQUIRED"),List.of(),List.of()))
            .containsEntry("status","PARAMETER_INVALID");
        assertThat(DataSupplyStatus.describe(failed("INVALID_TEMPLATE_ID"),List.of(),List.of()))
            .containsEntry("status","SELECTION_FAILED");
        assertThat(DataSupplyStatus.describe(failed(null),List.of(),List.of()))
            .containsEntry("status","EXECUTION_FAILED");
    }

    @Test void onlyVerifiedZeroRowsAreACompletedEmptyDelivery() {
        var verified=new AnalysisEvidenceCoordinator.Dataset("empty",Map.of("sourceVerifiedEmpty",true),List.of());
        assertThat(DataSupplyStatus.describe(null,List.of(verified),List.of()))
            .containsEntry("status","EMPTY_RESULT").containsEntry("rowCount",0L)
            .containsEntry("deliveryComplete",true).containsEntry("businessDataAvailable",false);
        var unknown=new AnalysisEvidenceCoordinator.Dataset("unknown",Map.of(),List.of());
        assertThat(DataSupplyStatus.describe(null,List.of(unknown),List.of()))
            .doesNotContainEntry("status","EMPTY_RESULT").containsEntry("deliveryComplete",false);
    }

    @Test void partialDeliveryRetainsUsableDataAndTheFailureWithoutClaimingCompleteness() {
        var dataset=new AnalysisEvidenceCoordinator.Dataset("real",Map.of(),List.of(Map.of("value",1)));
        assertThat(DataSupplyStatus.describe(failed("MODEL_UNAVAILABLE"),List.of(dataset),List.of()))
            .containsEntry("status","DATASET_READY").containsEntry("deliveryComplete",false)
            .containsEntry("businessDataAvailable",true).containsEntry("datasetReferences",List.of("real"));
    }

    private InterpretationPlanRuntime.ExecutionResult failed(String code) {
        var metadata=code==null?Map.<String,Object>of():Map.<String,Object>of(
            "candidateSelectionErrorCode",code,"mcpDiscoverySucceeded",true);
        var step=new InterpretationPlanRuntime.StepExecution(1,"mcp_tool","opaque",false,null,
            "failed",null,null,1L,metadata);
        return new InterpretationPlanRuntime.ExecutionResult("run",false,false,null,"failed",List.of(step),Map.of(),1);
    }
}
