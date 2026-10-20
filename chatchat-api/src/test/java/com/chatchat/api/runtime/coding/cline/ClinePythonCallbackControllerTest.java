package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector;
import com.chatchat.api.runtime.coding.evidence.CodingValidationPlanService;
import com.chatchat.api.runtime.coding.execution.CodingPythonExecutionBridge;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    @Test void failedTableReaderReceivesStructuralRepairGuidanceWithoutSourceValues() {
        var old=contract; String file="c"+"a".repeat(63);
        contract=new CodingTaskContract(CodingProtocol.ANALYSIS_VERSION,old.taskId(),old.scope(),old.objective(),
            old.inputSnapshotRef(),old.metadataRefs(),old.workspaceRef(),
            new CodingTaskContract.Authorization("decision",List.of("python_execute"),List.of(file),List.of(),List.of(),
                CodingTaskContract.NetworkPolicy.NONE,List.of(),List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
            old.budget(),old.environmentRef(),old.validationPlanRef(),old.outputContract(),Map.of(
                "schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT","inputFiles",Map.of(
                    "schemaVersion",CodingInputManifest.VERSION,"delivery","SHARED_DIRECTORY","format","PARQUET",
                    "manifestFileId",file,"manifestSha256","d".repeat(64))));
        when(tasks.get(scope,"task").contractSha256()).thenReturn(CodingCanonicalHash.sha256(contract));
        when(python.get(scope,"task",operation)).thenReturn(new McpPythonControlPlanePort.OperationView(operation,"FAILED","container",1,
            false,true,1,"b".repeat(64),"sha256:image","a".repeat(64),"KeyError: PRIVATE_SOURCE_VALUE"));
        when(python.stderr(scope,"task",operation)).thenReturn("KeyError: PRIVATE_SOURCE_VALUE");
        var result=controller.get(json.valueToTree(body()));
        assertThat(result).containsEntry("status","FAILED").containsKey("inputLayout");
        assertThat(json.valueToTree(result).path("inputLayout").path("expectedManifestSha256").asText())
            .isEqualTo("d".repeat(64));
        assertThat(json.valueToTree(result).toString()).doesNotContain("PRIVATE_SOURCE_VALUE");
        verifyNoInteractions(collector);
    }

    @Test void successfulExplorationWithoutResultReturnsActionableOutputStatus(){
        when(collector.collect(any(),any(),anyString(),anyString(),anyString(),anyList(),any()))
            .thenThrow(new CodingEvidenceCollector.RequiredOutputMissingException("rows.jsonl"));
        var result=controller.get(json.valueToTree(body()));
        assertThat(result).containsEntry("status","SUCCEEDED").containsEntry("executorStopped",true)
            .containsEntry("outputStatus","REQUIRED_OUTPUT_MISSING").containsEntry("resultAvailable",false)
            .containsEntry("requiredOutput","rows.jsonl").doesNotContainKeys("resultDatasetRef","evidenceBundleRef","stdout","rows");
        assertThat(result.get("diagnostic").toString()).contains("fresh sandbox","JSONL","repeating print/file probes","same Python program");
    }
    @Test void archivedDiagnosticsDoNotTurnExplorationIntoAnAdmittedDataset(){
        when(python.stdout(scope,"task",operation)).thenReturn("columns: assets, date; rows: 3");
        when(python.stderr(scope,"task",operation)).thenReturn("");
        when(collector.collect(any(),any(),anyString(),anyString(),anyString(),anyList(),any()))
            .thenThrow(new CodingEvidenceCollector.RequiredOutputMissingException("rows.jsonl"));
        assertThat(controller.get(json.valueToTree(body()))).containsEntry("diagnosticPolicy","METADATA_ONLY")
            .containsEntry("resultAvailable",false).doesNotContainKeys("resultDatasetRef","evidenceBundleRef","stdout","stderr");
    }
    @Test void sourceValuesInExecutionOutputNeverEnterRepairDiagnostics(){
        when(python.stdout(scope,"task",operation)).thenReturn("PRIVATE_FIXTURE_ROW_A");
        when(python.stderr(scope,"task",operation)).thenReturn("Traceback (most recent call last):\n  File \"/workspace/main.py\", line 7, in <module>\n    raise ValueError(row)\nValueError: PRIVATE_FIXTURE_ROW_B\n");
        when(collector.collect(any(),any(),anyString(),anyString(),anyString(),anyList(),any()))
            .thenThrow(new CodingEvidenceCollector.RequiredOutputMissingException("rows.jsonl"));
        var result=controller.get(json.valueToTree(body()));
        assertThat(json.valueToTree(result).toString()).doesNotContain("PRIVATE_FIXTURE_ROW_");
        assertThat(((Map<?,?>)result.get("diagnostics")).get("exceptionType")).isEqualTo("ValueError");
        assertThat(((Map<?,?>)result.get("diagnostics")).get("line")).isEqualTo(7);
        assertThat(ClinePythonCallbackController.diagnosticMetadata("ValueError: PRIVATE_FIXTURE_ROW_C").toString())
            .contains("ValueError").doesNotContain("PRIVATE_FIXTURE_ROW_");
    }
    @Test void invalidStructuredOutputReturnsRepairDiagnosticsWithoutCancellingTheTask(){
        when(collector.collect(any(),any(),anyString(),anyString(),anyString(),anyList(),any()))
            .thenThrow(new CodingEvidenceCollector.InvalidStructuredResultException("Column types changed: PRIVATE_FIXTURE_ROW_D"));
        var result=controller.get(json.valueToTree(body()));
        assertThat(result).containsEntry("outputStatus","INVALID_RESULT_SCHEMA")
            .containsEntry("resultAvailable",false);
        assertThat(result.toString()).doesNotContain("PRIVATE_FIXTURE_ROW_");
        verify(tasks,never()).cancel(any(),anyString(),anyString());
    }
    @Test void rejectedResultDoesNotExposeAnAdmittedDatasetAndRequestsRepair(){
        when(collector.collect(any(),any(),anyString(),anyString(),anyString(),anyList(),any()))
            .thenReturn(new CodingEvidenceCollector.Collected("input","code","receipt","rejected-result","validation","bundle",false,
                CodingTaskOutcome.ValidationStatus.FAILED));
        var result=controller.get(json.valueToTree(body()));
        assertThat(result).containsEntry("outputStatus","RESULT_VALIDATION_FAILED").containsEntry("resultAvailable",false)
            .doesNotContainKeys("resultDatasetRef","evidenceBundleRef");
        assertThat(result.get("diagnostic").toString()).contains("calculated in Python","execute again");
        verify(tasks,never()).cancel(any(),anyString(),anyString());
    }
    @Test void committedResultKeepsTheExistingSixEvidenceResponse(){
        when(collector.collect(any(),any(),anyString(),anyString(),anyString(),anyList(),any()))
            .thenReturn(new CodingEvidenceCollector.Collected("input","code","receipt","result","validation","bundle",false,
                CodingTaskOutcome.ValidationStatus.INCONCLUSIVE));
        assertThat(controller.get(json.valueToTree(body()))).containsEntry("resultDatasetRef","result")
            .containsEntry("evidenceBundleRef","bundle").containsEntry("validationStatus","INCONCLUSIVE")
            .containsEntry("resultAvailable",true)
            .doesNotContainKey("outputStatus");
    }

    @Test void trustedSourceCopyFailureEndsOnlyThisDelegationForParentReplanning(){
        var current=running(false);
        when(tasks.get(scope,"task")).thenReturn(current);
        copiedResult();
        var execution=python.get(scope,"task",operation);
        when(python.get(scope,"task",operation)).thenAnswer(invocation->{
            identity.require(scope,com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.WORKER);
            assertThatThrownBy(()->identity.require(scope,com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.EVIDENCE))
                .isInstanceOf(CodingContractException.class);
            return execution;
        });
        when(tasks.commitOutcome(eq(scope),eq("task"),any(),eq(current.revision()),any()))
            .thenAnswer(invocation->{
                identity.require(scope,com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.EVIDENCE);
                CodingTaskOutcome proposal=invocation.getArgument(4);
                current.validateSuccessor(proposal);
                proposal.validateAgainst(contract);
                return proposal;
            });

        var response=controller.get(json.valueToTree(body()));

        assertThat(response).containsEntry("outputStatus","SOURCE_COPY_REJECTED")
            .containsEntry("replanningRequired",true).containsEntry("resultAvailable",false)
            .doesNotContainKeys("resultDatasetRef","evidenceBundleRef","stdout","stderr");
        var outcome=org.mockito.ArgumentCaptor.forClass(CodingTaskOutcome.class);
        verify(tasks).commitOutcome(eq(scope),eq("task"),any(),eq(current.revision()),outcome.capture());
        assertThat(outcome.getValue().status()).isEqualTo(CodingTaskOutcome.Status.FAILED);
        assertThat(outcome.getValue().reasonCode()).isEqualTo(CodingErrorCode.OUTPUT_CONTRACT_VIOLATION);
        assertThat(outcome.getValue().validationStatus()).isEqualTo(CodingTaskOutcome.ValidationStatus.FAILED);
        assertThat(outcome.getValue().resultDatasetRef()).isNull();
        assertThat(outcome.getValue().cancellationRequested()).isFalse();
        assertThatThrownBy(()->identity.require(scope,com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.EVIDENCE))
            .isInstanceOf(CodingContractException.class);
        verify(tasks,never()).cancel(any(),anyString(),anyString());
    }

    @Test void sourceCopyCannotDeclareUnknownExecutionsStopped(){
        when(tasks.get(scope,"task")).thenReturn(running(false));
        copiedResult();
        when(tasks.unresolvedOperations(scope,"task")).thenReturn(List.of("unresolved-operation"));

        assertThatThrownBy(()->controller.get(json.valueToTree(body())))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("reconciliation");
        verify(tasks,never()).commitOutcome(any(),anyString(),any(),anyLong(),any());
    }

    @Test void sourceCopyDoesNotOverrideAnAcceptedCancellation(){
        when(tasks.get(scope,"task")).thenReturn(running(true));
        copiedResult();

        assertThat(controller.get(json.valueToTree(body()))).doesNotContainKey("replanningRequired");
        verify(tasks,never()).commitOutcome(any(),anyString(),any(),anyLong(),any());
        verify(tasks,never()).cancel(any(),anyString(),anyString());
    }

    @Test void cancellationAcceptedDuringCommitDoesNotReportACompletedPolicyFailure(){
        var current=running(false);
        when(tasks.get(scope,"task")).thenReturn(current);
        copiedResult();
        when(tasks.commitOutcome(eq(scope),eq("task"),any(),eq(current.revision()),any()))
            .thenReturn(running(true));

        assertThat(controller.get(json.valueToTree(body()))).doesNotContainKey("replanningRequired");
    }

    private void copiedResult(){
        when(collector.collect(any(),any(),anyString(),anyString(),anyString(),anyList(),any()))
            .thenReturn(new CodingEvidenceCollector.Collected(contract.inputSnapshotRef(),"code","receipt","rejected-result","validation","bundle",false,
                CodingTaskOutcome.ValidationStatus.FAILED));
        when(collector.copiedSourceResult(scope,"task","validation")).thenReturn(true);
    }

    private CodingTaskOutcome running(boolean cancelled){
        Instant at=Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        var zero=new CodingTaskOutcome.Counters(0,0,0,0,0,0,0,"0");
        return new CodingTaskOutcome(contract.schemaVersion(),"task",scope,CodingCanonicalHash.sha256(contract),
            CodingTaskOutcome.Status.RUNNING,3,4,at,at,at,null,cancelled,null,"Running",List.of(),
            contract.inputSnapshotRef(),null,null,null,null,null,CodingTaskOutcome.ValidationStatus.NOT_RUN,
            new CodingTaskOutcome.Reproducibility(CodingTaskOutcome.ReproducibilityLevel.UNAVAILABLE,List.of("fixture"),List.of()),
            new CodingTaskOutcome.Usage("budget","USD",zero,zero,true),
            new CodingTaskOutcome.ExecutionControl(cancelled?CodingErrorCode.CANCEL_REQUESTED:null,cancelled?at:null,false,false),
            null,null,Map.of());
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
