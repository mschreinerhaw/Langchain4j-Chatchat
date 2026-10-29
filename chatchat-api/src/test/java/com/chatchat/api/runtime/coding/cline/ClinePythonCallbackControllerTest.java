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
        when(tasks.scriptFile(any(),anyString(),anyString())).thenReturn(CodingScript.LEGACY_FILE);
        controller=new ClinePythonCallbackController(identity,tasks,inputs,plans,python,collector,json);
    }
    @Test void monitorReadsStoppedTaskWithoutWorkerLeaseOrExecutionSideEffects() {
        var feedback=mock(com.chatchat.api.runtime.coding.execution.CodingExecutionFeedbackService.class);controller.setFeedback(feedback);
        var historical=tasks.get(scope,"task");when(historical.status()).thenReturn(CodingTaskOutcome.Status.COMPLETED);
        when(tasks.get(scope,"task")).thenAnswer(call->{identity.require(scope,CodingTaskAccess.Action.READ);
            assertThatThrownBy(()->identity.require(scope,CodingTaskAccess.Action.WORKER)).isInstanceOf(CodingContractException.class);return historical;});
        when(tasks.contract(scope,"task")).thenReturn(contract);when(feedback.timeline(scope,"task")).thenReturn(List.of());
        var response=controller.monitorPython(json.valueToTree(Map.of("scope",scope,"taskId","task")));
        assertThat(response).containsEntry("source","DURABLE_CODING_JOURNAL").containsEntry("rawLogsAvailable",false);
        verify(tasks,never()).ownership(any(),anyString());verifyNoInteractions(python,collector);
    }
    @Test void monitorCodeBindsTheArchiveAndAnyExistingExecutionHash() {
        var feedback=mock(com.chatchat.api.runtime.coding.execution.CodingExecutionFeedbackService.class);controller.setFeedback(feedback);
        var artifacts=mock(CodingCodeArtifactStore.class);controller.setCodeArtifacts(artifacts);when(tasks.contract(scope,"task")).thenReturn(contract);
        var execution=mock(ExecutionFeedback.class);when(execution.executionId()).thenReturn(operation);when(execution.codeHash()).thenReturn("f".repeat(64));when(feedback.timeline(scope,"task")).thenReturn(List.of(execution));
        when(artifacts.find(scope,"task",operation)).thenReturn(Optional.of(Map.of("content","print(7)","sha256","f".repeat(64),"fileName","main.py")));
        assertThatThrownBy(()->controller.monitorPython(json.valueToTree(Map.of("scope",scope,"taskId","task","operationId",operation)))).hasMessageContaining("409");
        assertThatThrownBy(()->controller.monitorPython(json.valueToTree(Map.of("scope",scope,"taskId","task","operationId","cline-"+"b".repeat(64))))).hasMessageContaining("404");
        verifyNoInteractions(python,collector);
    }
    @Test void monitorCanReadSubmittedCodeWithoutPretendingAnExecutionReceiptExists() {
        var feedback=mock(com.chatchat.api.runtime.coding.execution.CodingExecutionFeedbackService.class);controller.setFeedback(feedback);
        var artifacts=mock(CodingCodeArtifactStore.class);controller.setCodeArtifacts(artifacts);when(tasks.contract(scope,"task")).thenReturn(contract);when(feedback.timeline(scope,"task")).thenReturn(List.of());
        String source="print(7)",hash=PythonCodeProject.hash(source);
        when(artifacts.find(scope,"task",operation)).thenReturn(Optional.of(Map.of("content",source,"sha256",hash,"fileName","main.py")));
        var request=Map.of("scope",scope,"taskId","task","operationId",operation);
        assertThat(controller.monitorPython(json.valueToTree(request))).containsEntry("sha256",hash).containsEntry("executionReceiptAvailable",false);
        assertThatThrownBy(()->controller.monitorPython(json.valueToTree(Map.of("scope",scope,"taskId","task","operationId",operation,"kind","timing")))).hasMessageContaining("404");
        verifyNoInteractions(python,collector);
    }
    @Test void monitorTimingUsesOnlyHashBoundArchivedReceiptAndNeverQueriesSandbox() throws Exception {
        var feedback=mock(com.chatchat.api.runtime.coding.execution.CodingExecutionFeedbackService.class);controller.setFeedback(feedback);
        var archive=mock(com.chatchat.api.assets.RuntimePythonAssetArchiver.class);controller.setMonitorAssets(archive);when(tasks.contract(scope,"task")).thenReturn(contract);
        var execution=mock(ExecutionFeedback.class);when(execution.executionId()).thenReturn(operation);when(execution.codeHash()).thenReturn("a".repeat(64));when(feedback.timeline(scope,"task")).thenReturn(List.of(execution));
        when(archive.log(scope,"task",operation,"receipt.json")).thenReturn(Optional.of(json.writeValueAsBytes(Map.of("operationId",operation,"codeHash","a".repeat(64),"startedAt","2026-10-29T00:00:00Z","finishedAt","2026-10-29T00:00:01Z"))));
        var request=json.valueToTree(Map.of("scope",scope,"taskId","task","operationId",operation,"kind","timing"));
        assertThat(controller.monitorPython(request)).containsEntry("source","MCP_ARCHIVED_RECEIPT").containsEntry("startedAt","2026-10-29T00:00:00Z");
        when(archive.log(scope,"task",operation,"receipt.json")).thenReturn(Optional.of(json.writeValueAsBytes(Map.of("operationId",operation,"codeHash","b".repeat(64)))));
        assertThatThrownBy(()->controller.monitorPython(request)).hasMessageContaining("409");verifyNoInteractions(python,collector);
    }
    Map<String,Object> body(){return new LinkedHashMap<>(Map.of("scope",scope,"taskId","task","contract",contract,
        "contractHash",CodingCanonicalHash.sha256(contract),"fencingToken",1,"operationId",operation));}

    @Test void temporarilyUnverifiableStopProofKeepsTheSameExecutionPendingWithoutPublishing() {
        when(python.reconcile(eq(scope),eq("task"),any(),eq(operation))).thenThrow(
            new CodingContractException(CodingErrorCode.EXECUTION_OUTCOME_UNKNOWN,"Stop proof is not yet verifiable"));
        var response=controller.get(json.valueToTree(body()));
        assertThat(response).containsEntry("status","RECONCILING").containsEntry("executorStopped",false)
            .containsEntry("resultAvailable",false).containsEntry("reconciliationRequired",true)
            .containsEntry("operationId",operation);
        verifyNoInteractions(collector);
        verify(python).get(scope,"task",operation);
        verify(python).reconcile(eq(scope),eq("task"),any(),eq(operation));
        verifyNoMoreInteractions(python);
    }

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
    @Test void visualArtifactContractFailureRequestsRepairButUnresolvedBusinessChecksDoNot(){
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(contract);
        node.set("runtimeContext",json.valueToTree(Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT","reportMode","visual")));
        contract=json.convertValue(node,CodingTaskContract.class);
        when(tasks.get(scope,"task").contractSha256()).thenReturn(CodingCanonicalHash.sha256(contract));
        when(collector.collect(any(),any(),anyString(),anyString(),anyString(),anyList(),any()))
            .thenReturn(new CodingEvidenceCollector.Collected("input","code","receipt","result","validation","bundle",false,CodingTaskOutcome.ValidationStatus.INCONCLUSIVE));
        when(collector.visualReport(scope,"task","result")).thenReturn(Optional.of(Map.of("status","FAILED","artifactValidationStatus","FAILED")));
        assertThat(controller.get(json.valueToTree(body()))).containsEntry("resultAvailable",false).containsEntry("outputStatus","VISUAL_REPORT_INVALID");
        when(collector.visualReport(scope,"task","result")).thenReturn(Optional.of(Map.of("status","PARTIAL","artifactValidationStatus","PASSED")));
        assertThat(controller.get(json.valueToTree(body()))).containsEntry("resultAvailable",true).doesNotContainKey("outputStatus");
        when(collector.visualReport(scope,"task","result")).thenReturn(Optional.of(Map.of("status","FAILED","artifactValidationStatus","PASSED")));
        assertThat(controller.get(json.valueToTree(body()))).containsEntry("resultAvailable",true).doesNotContainKey("outputStatus");
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
