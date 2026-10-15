package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClinePythonCallbackControllerTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final KernelDataScope scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
    final CodingInvocationContext identity=new CodingInvocationContext();
    final DurableCodingTaskService tasks=mock(DurableCodingTaskService.class);
    final CodingInputSnapshotService inputs=mock(CodingInputSnapshotService.class);
    final CodingValidationPlanService plans=mock(CodingValidationPlanService.class);
    final CodingPythonExecutionBridge python=mock(CodingPythonExecutionBridge.class);
    final CodingEvidenceCollector collector=mock(CodingEvidenceCollector.class);
    final String operation="cline-"+"a".repeat(64);
    CodingTaskContract contract;
    ClinePythonCallbackController controller;
    @BeforeEach void setup(){
        Instant until=Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        contract=new CodingTaskContract(CodingProtocol.VERSION,"task",scope,
            new CodingTaskContract.Objective("Analyze authorized input",List.of("rows.jsonl"),List.of("plan")),
            "runtime-evidence:input",List.of(),"workspace",new CodingTaskContract.Authorization("decision",List.of("python_execute"),
                List.of("input"),List.of(),List.of(),CodingTaskContract.NetworkPolicy.NONE,List.of(),List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
            new CodingTaskContract.Budget("budget",until,2,4096,4,4,"1","USD",1000,128*1024*1024,10000),
            "environment","plan",new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,"schema","validation",
                List.of("rows.jsonl"),List.of("application/x-ndjson"),100,10000,List.of(CodingTaskContract.RequiredEvidence.values())));
        var outcome=mock(CodingTaskOutcome.class);
        when(outcome.status()).thenReturn(CodingTaskOutcome.Status.RUNNING);
        when(outcome.executionControl()).thenReturn(new CodingTaskOutcome.ExecutionControl(null,null,false,false));
        when(outcome.contractSha256()).thenReturn(CodingCanonicalHash.sha256(contract));
        when(tasks.get(scope,"task")).thenReturn(outcome);
        when(tasks.ownership(scope,"task")).thenReturn(new CodingJournal.Lease("cline-adapter:test",until.plusSeconds(120),1));
        when(inputs.requireAuthorized(any())).thenReturn(new CodingInputSnapshotService.Snapshot("runtime-evidence:input","input","input.jsonl","a".repeat(64),1,20,"source"));
        when(plans.validator(any())).thenReturn(rows->new CodingEvidenceCollector.Validation(false,Map.of()));
        when(python.get(scope,"task",operation)).thenReturn(new McpPythonControlPlanePort.OperationView(operation,"SUCCEEDED","container",1,
            false,true,0,"b".repeat(64),"sha256:image","a".repeat(64),null));
        controller=new ClinePythonCallbackController(identity,tasks,inputs,plans,python,collector,json);
    }
    Map<String,Object> body(){return new LinkedHashMap<>(Map.of("scope",scope,"taskId","task","contract",contract,
        "contractHash",CodingCanonicalHash.sha256(contract),"fencingToken",1,"operationId",operation));}

    @Test void successfulExplorationWithoutResultReturnsActionableOutputStatus(){
        when(collector.collect(any(),any(),anyString(),anyString(),anyString(),anyList(),any()))
            .thenThrow(new CodingEvidenceCollector.RequiredOutputMissingException("rows.jsonl"));
        var result=controller.get(json.valueToTree(body()));
        assertThat(result).containsEntry("status","SUCCEEDED").containsEntry("executorStopped",true)
            .containsEntry("outputStatus","REQUIRED_OUTPUT_MISSING").containsEntry("resultAvailable",false)
            .containsEntry("requiredOutput","rows.jsonl").doesNotContainKeys("resultDatasetRef","evidenceBundleRef","stdout","rows");
        assertThat(result.get("diagnostic").toString()).contains("fresh sandbox","JSONL");
    }
    @Test void committedResultKeepsTheExistingSixEvidenceResponse(){
        when(collector.collect(any(),any(),anyString(),anyString(),anyString(),anyList(),any()))
            .thenReturn(new CodingEvidenceCollector.Collected("input","code","receipt","result","validation","bundle",false,
                CodingTaskOutcome.ValidationStatus.INCONCLUSIVE));
        assertThat(controller.get(json.valueToTree(body()))).containsEntry("resultDatasetRef","result")
            .containsEntry("evidenceBundleRef","bundle").containsEntry("validationStatus","INCONCLUSIVE")
            .doesNotContainKey("outputStatus");
    }
    @Test void corruptedArchiveIsNotDowngradedToAnExplorationReceipt(){
        when(collector.collect(any(),any(),anyString(),anyString(),anyString(),anyList(),any()))
            .thenThrow(new IllegalStateException("Manifest hash differs"));
        assertThatThrownBy(()->controller.get(json.valueToTree(body()))).isInstanceOf(ResponseStatusException.class)
            .hasCauseInstanceOf(IllegalStateException.class);
    }
    @Test void changedTaskBindingCannotReadPythonOrCollectEvidence(){
        var body=body();body.put("contractHash","different");
        assertThatThrownBy(()->controller.get(json.valueToTree(body))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(python,collector);
    }
}
