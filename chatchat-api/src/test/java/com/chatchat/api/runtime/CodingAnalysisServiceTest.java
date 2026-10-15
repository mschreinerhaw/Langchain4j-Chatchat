package com.chatchat.api.runtime;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.RuntimeExecutionCheckpointPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodingAnalysisServiceTest {
    final CodingAnalysisProperties policy=new CodingAnalysisProperties();
    final CodingInvocationContext identity=new CodingInvocationContext();
    final CodingInputSnapshotService inputs=mock(CodingInputSnapshotService.class);
    final CodingValidationPlanService plans=mock(CodingValidationPlanService.class);
    final CodingAgentSPI agent=mock(CodingAgentSPI.class);
    final CodingEvidenceCollector collector=mock(CodingEvidenceCollector.class);
    final McpPythonControlPlanePort python=mock(McpPythonControlPlanePort.class);
    final CodingParentModels models=new CodingParentModels();
    final Map<String,String> saved=new ConcurrentHashMap<>();
    final RuntimeExecutionCheckpointPort checkpoints=new RuntimeExecutionCheckpointPort(){
        String key(KernelDataScope scope,String key){return scope.tenantId()+":"+scope.userId()+":"+scope.runId()+":"+key;}
        public Optional<String> readExecutionCheckpoint(KernelDataScope scope,String key){return Optional.ofNullable(saved.get(key(scope,key)));}
        public boolean compareAndSetExecutionCheckpoint(KernelDataScope scope,String key,String old,String next){
            String name=key(scope,key);return old==null?saved.putIfAbsent(name,next)==null:saved.replace(name,old,next);
        }
    };
    CodingAnalysisService service(){return new CodingAnalysisService(policy,identity,inputs,plans,agent,
        mock(DurableCodingTaskService.class),collector,checkpoints,models,python,new ObjectMapper().findAndRegisterModules());}
    Map<String,Object> request(){return Map.of("operation","RUN_CODING","requestId","calculation-1",
        "datasetReference","source","instruction","Transform the authorized data",
        "outputSchema",Map.of("type","object","properties",Map.of("value",Map.of("type","string")))) ;}
    Map<String,Dataset> sources(){var result=new LinkedHashMap<String,Dataset>();result.put("source",new Dataset("source",Map.of(),List.of(Map.of("text","example"))));return result;}
    AnalysisCoverageCoordinator.Request context(String tenant){return new AnalysisCoverageCoordinator.Request(mock(ChatModel.class),"Question",null,Map.of(),
        new LinkedHashMap<>(),()->false,()->{},GovernanceIsolationScope.runtime(tenant,"user","run","request","conversation"),null);}

    @Test void reusesPersistedContractAndBudgetAndAdmitsOnlyCommittedScopedResults() {
        policy.setEnvironmentId("environment");
        when(python.environment("environment")).thenReturn(new McpPythonControlPlanePort.EnvironmentView("environment","Python",null,"image","3", "1","512m","8m","8m","1000","NONE",null,"[]",300,false,1,"PUBLISHED"));
        when(inputs.capture(any(),anyString(),anyList(),eq("source"),anyString(),anyString(),anyLong(),anyLong()))
            .thenAnswer(call->new CodingInputSnapshotService.Snapshot("runtime-evidence:input","c"+"a".repeat(63),"input.jsonl","a".repeat(64),1,30,"source"));
        when(inputs.describe(any(),anyString(),any())).thenReturn(Map.of("rowCount",1,"schemaJson","{}"));
        when(plans.register(any(),anyString(),any(),any())).thenReturn("runtime-evidence:plan");
        var completed=mock(CodingTaskOutcome.class);
        when(completed.status()).thenReturn(CodingTaskOutcome.Status.COMPLETED);
        when(completed.validationStatus()).thenReturn(CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
        when(completed.executionControl()).thenReturn(new CodingTaskOutcome.ExecutionControl(null,null,true,false));
        when(completed.usage()).thenReturn(mock(CodingTaskOutcome.Usage.class));
        when(completed.inputSnapshotRef()).thenReturn("runtime-evidence:input");when(completed.codeArtifactRef()).thenReturn("runtime-evidence:code");
        when(completed.executionReceiptRef()).thenReturn("runtime-evidence:receipt");when(completed.resultDatasetRef()).thenReturn("runtime-evidence:result");
        when(completed.validationRecordRef()).thenReturn("runtime-evidence:validation");when(completed.evidenceBundleRef()).thenReturn("runtime-evidence:bundle");
        when(agent.submit(any())).thenReturn(completed);
        when(collector.registeredResult(any(),anyString(),eq("runtime-evidence:result")))
            .thenReturn(new Dataset("runtime-evidence:result",Map.of(),List.of(Map.of("value","computed"))));
        var source=sources();var parent=context("tenant");
        var first=service().execute(parent,request(),source);
        assertThat(first).containsEntry("status","COMPLETED").containsEntry("businessValidation","NOT_EVALUATED")
            .containsEntry("validationStatus","INCONCLUSIVE").containsEntry("resultAvailable",true);
        assertThat(source).containsKey("runtime-evidence:result");assertThat((List<?>)first.get("evidenceRefs")).hasSize(6);
        var provenance=(Map<?,?>)source.get("runtime-evidence:result").analysisContext().get("coding");
        assertThat(provenance.get("inputDatasetReference")).isEqualTo("source");
        assertThat(provenance.get("validationStatus")).isEqualTo("INCONCLUSIVE");
        assertThat(provenance.get("businessValidation")).isEqualTo("NOT_EVALUATED");
        assertThat((List<?>)provenance.get("evidenceRefs")).hasSize(6);
        var second=service().execute(context("tenant"),new TreeMap<>(request()),sources());
        assertThat(second.get("taskId")).isEqualTo(first.get("taskId"));
        var contracts=org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);
        verify(agent,times(2)).submit(contracts.capture());
        assertThat(contracts.getAllValues().get(0)).isEqualTo(contracts.getAllValues().get(1));
        assertThat(models.find(contracts.getValue().scope(),contracts.getValue().taskId())).isNull();
        try(var active=models.bind(contracts.getValue().scope(),contracts.getValue().taskId(),parent.model())) {
            assertThatThrownBy(()->service().execute(parent,request(),sources())).isInstanceOf(IllegalStateException.class);
            verify(agent,never()).cancel(any(),anyString(),anyString());
        } catch(Exception failure) { throw new AssertionError(failure); }
        verify(inputs,times(1)).capture(any(),anyString(),anyList(),anyString(),anyString(),anyString(),anyLong(),anyLong());
        var conflicting=new LinkedHashMap<>(request());conflicting.put("instruction","Changed target under the same identity");
        assertThatThrownBy(()->service().execute(parent,conflicting,sources())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity conflict");
        verify(agent,times(2)).submit(any());
    }
    @Test void unknownSourceAndAnonymousIdentityCannotAcquireExecutionAuthority() {
        var request=new LinkedHashMap<>(request());request.put("datasetReference","another-run");
        assertThatThrownBy(()->service().execute(context("tenant"),request,sources())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service().execute(context("default"),request(),sources())).isInstanceOf(SecurityException.class);
        verifyNoInteractions(agent,python,inputs,plans);
    }

    @Test void parentCancellationIsCheckedBeforeCapturingOrDispatchingData() {
        var parent=context("tenant");
        var cancelled=new AnalysisCoverageCoordinator.Request(parent.model(),parent.query(),null,Map.of(),parent.metadata(),
            ()->true,()->{},parent.isolationScope(),null);
        assertThatThrownBy(()->service().execute(cancelled,request(),sources())).isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(agent,python,inputs,plans,collector);
    }

    @Test void expiredParentDeadlineIsCheckedBeforeCapturingData() {
        var parent=context("tenant");
        var expired=new AnalysisCoverageCoordinator.Request(parent.model(),parent.query(),null,
            Map.of("__agentDeadlineAt",System.currentTimeMillis()-1),parent.metadata(),()->false,()->{},parent.isolationScope(),null);
        assertThatThrownBy(()->service().execute(expired,request(),sources()))
            .isInstanceOf(com.chatchat.agents.orchestration.model.AgentDeadlineExceededException.class);
        verifyNoInteractions(agent,python,inputs,plans,collector);
    }

    @Test void cancellationDuringCodingStopsTheOwnedTaskAndReleasesTheModelBinding() {
        var running=outcome(CodingTaskOutcome.Status.RUNNING,CodingTaskOutcome.ValidationStatus.NOT_RUN);
        prepare(running);
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();var parent=context("tenant");
        var context=new AnalysisCoverageCoordinator.Request(parent.model(),parent.query(),null,Map.of(),parent.metadata(),
            cancelled::get,()->{},parent.isolationScope(),null);
        when(agent.submit(any())).thenAnswer(call->{cancelled.set(true);return running;});
        assertThatThrownBy(()->service().execute(context,request(),sources())).isInstanceOf(java.util.concurrent.CancellationException.class);
        var contract=org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);
        verify(agent).submit(contract.capture());
        verify(agent).cancel(eq(contract.getValue().scope()),eq(contract.getValue().taskId()),anyString());
        assertThat(models.find(contract.getValue().scope(),contract.getValue().taskId())).isNull();
        verifyNoInteractions(collector);
    }

    @Test void failedOrValidationFailedTasksReturnReasonsWithoutAdmittingADataset() {
        for(var state:List.of(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.Status.COMPLETED)){
            var result=outcome(state,CodingTaskOutcome.ValidationStatus.FAILED);
            when(result.reasonCode()).thenReturn(state==CodingTaskOutcome.Status.FAILED?CodingErrorCode.EXECUTION_FAILED:null);
            when(result.summary()).thenReturn("Worker reported execution/validation failure");
            when(result.limitations()).thenReturn(List.of("No trusted analysis result is available"));
            prepare(result);
            var sources=sources();
            var receipt=service().execute(context("tenant"),request(),sources);
            assertThat(receipt).containsEntry("resultAvailable",false).containsEntry("summary","Worker reported execution/validation failure")
                .containsEntry("limitations",List.of("No trusted analysis result is available"));
            if(state==CodingTaskOutcome.Status.FAILED)assertThat(receipt).containsEntry("reasonCode","EXECUTION_FAILED");
            assertThat(receipt).doesNotContainKeys("datasetReference","evidenceRefs");
            assertThat(sources).containsOnlyKeys("source");
        }
        verifyNoInteractions(collector);
        verify(agent,never()).cancel(any(),anyString(),anyString());
    }

    @Test void cancellationWhileCapturingInputPreventsWorkerDispatch() {
        prepare(outcome(CodingTaskOutcome.Status.RUNNING,CodingTaskOutcome.ValidationStatus.NOT_RUN));
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();var parent=context("tenant");
        when(inputs.describe(any(),anyString(),any())).thenAnswer(call->{cancelled.set(true);return Map.of("rowCount",1);});
        var context=new AnalysisCoverageCoordinator.Request(parent.model(),parent.query(),null,Map.of(),parent.metadata(),
            cancelled::get,()->{},parent.isolationScope(),null);
        assertThatThrownBy(()->service().execute(context,request(),sources())).isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(agent,collector);
    }

    @Test void expiredPersistedBudgetIsReconciledBeforeSendingCancellation() throws Exception {
        var completed=outcome(CodingTaskOutcome.Status.COMPLETED,CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
        prepare(completed);var service=service();var parent=context("tenant");
        service.execute(parent,request(),sources());
        var json=new ObjectMapper().findAndRegisterModules();
        var entry=saved.entrySet().stream().filter(e->e.getKey().contains("coding:analysis-request:")).findFirst().orElseThrow();
        var checkpoint=json.readTree(entry.getValue());
        ((com.fasterxml.jackson.databind.node.ObjectNode)checkpoint.path("contract").path("budget"))
            .put("deadlineAt",java.time.Instant.ofEpochMilli(System.currentTimeMillis()-1).toString());
        saved.put(entry.getKey(),json.writeValueAsString(checkpoint));
        var running=outcome(CodingTaskOutcome.Status.RUNNING,CodingTaskOutcome.ValidationStatus.NOT_RUN);
        var stopped=outcome(CodingTaskOutcome.Status.BUDGET_EXCEEDED,CodingTaskOutcome.ValidationStatus.NOT_RUN);
        when(stopped.reasonCode()).thenReturn(CodingErrorCode.DEADLINE_EXCEEDED);
        when(stopped.executionControl()).thenReturn(new CodingTaskOutcome.ExecutionControl(CodingErrorCode.DEADLINE_EXCEEDED,
            java.time.Instant.ofEpochMilli(System.currentTimeMillis()),true,false));
        when(agent.submit(any())).thenReturn(running);when(agent.resume(any(),anyString())).thenReturn(stopped);
        var receipt=service.execute(parent,request(),sources());
        assertThat(receipt).containsEntry("status","BUDGET_EXCEEDED").containsEntry("stopReason","DEADLINE_EXCEEDED")
            .containsEntry("resultAvailable",false);
        verify(agent).resume(any(),anyString());verify(agent,never()).cancel(any(),anyString(),anyString());
        verify(collector,times(1)).registeredResult(any(),anyString(),anyString());
    }

    @Test void unknownExecutionRemainsUnknownAndCannotSupplyAnAnalysisResult() {
        var unknown=outcome(CodingTaskOutcome.Status.RUNNING,CodingTaskOutcome.ValidationStatus.NOT_RUN);
        when(unknown.executionControl()).thenReturn(new CodingTaskOutcome.ExecutionControl(null,null,false,true));
        when(unknown.reasonCode()).thenReturn(CodingErrorCode.EXECUTION_OUTCOME_UNKNOWN);
        when(unknown.limitations()).thenReturn(List.of("Provider execution requires reconciliation"));
        prepare(unknown);when(agent.resume(any(),anyString())).thenReturn(unknown);
        var sources=sources();var receipt=service().execute(context("tenant"),request(),sources);
        assertThat(receipt).containsEntry("status","RUNNING").containsEntry("reconciliationRequired",true)
            .containsEntry("resultAvailable",false).containsEntry("reasonCode","EXECUTION_OUTCOME_UNKNOWN")
            .doesNotContainKeys("datasetReference","evidenceRefs");
        assertThat(sources).containsOnlyKeys("source");
        verifyNoInteractions(collector);
        verify(agent).submit(any());verify(agent).resume(any(),anyString());
    }

    @Test void resultReadFailureDoesNotCancelAnAlreadyCompletedTask() {
        prepare(outcome(CodingTaskOutcome.Status.COMPLETED,CodingTaskOutcome.ValidationStatus.INCONCLUSIVE));
        when(collector.registeredResult(any(),anyString(),anyString())).thenThrow(new IllegalStateException("Archive temporarily unavailable"));
        assertThatThrownBy(()->service().execute(context("tenant"),request(),sources())).isInstanceOf(IllegalStateException.class)
            .hasMessage("Archive temporarily unavailable");
        verify(agent,never()).cancel(any(),anyString(),anyString());
        var contract=org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);verify(agent).submit(contract.capture());
        assertThat(models.find(contract.getValue().scope(),contract.getValue().taskId())).isNull();
    }

    @Test void differentModelChosenCalculationsShareThePersistedParentBudget() {
        var completed=outcome(CodingTaskOutcome.Status.COMPLETED,CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
        prepare(completed);
        var service=service();var parent=context("tenant");
        service.execute(parent,request(),sources());
        var another=new LinkedHashMap<>(request());another.put("requestId","calculation-2");another.put("instruction","Another model-selected transformation");
        service.execute(parent,another,sources());
        var contracts=org.mockito.ArgumentCaptor.forClass(CodingTaskContract.class);verify(agent,times(2)).submit(contracts.capture());
        assertThat(contracts.getAllValues().get(0).taskId()).isNotEqualTo(contracts.getAllValues().get(1).taskId());
        assertThat(contracts.getAllValues().get(0).budget()).isEqualTo(contracts.getAllValues().get(1).budget());
    }

    private CodingTaskOutcome outcome(CodingTaskOutcome.Status status,CodingTaskOutcome.ValidationStatus validation){
        var result=mock(CodingTaskOutcome.class);
        when(result.status()).thenReturn(status);when(result.validationStatus()).thenReturn(validation);
        when(result.executionControl()).thenReturn(new CodingTaskOutcome.ExecutionControl(null,null,status.terminal(),false));
        when(result.usage()).thenReturn(mock(CodingTaskOutcome.Usage.class));
        when(result.inputSnapshotRef()).thenReturn("runtime-evidence:input");when(result.codeArtifactRef()).thenReturn("runtime-evidence:code");
        when(result.executionReceiptRef()).thenReturn("runtime-evidence:receipt");when(result.resultDatasetRef()).thenReturn("runtime-evidence:result");
        when(result.validationRecordRef()).thenReturn("runtime-evidence:validation");when(result.evidenceBundleRef()).thenReturn("runtime-evidence:bundle");
        return result;
    }
    private void prepare(CodingTaskOutcome result){
        policy.setEnvironmentId("environment");
        when(python.environment("environment")).thenReturn(new McpPythonControlPlanePort.EnvironmentView("environment","Python",null,"image","3", "1","512m","8m","8m","1000","NONE",null,"[]",300,false,1,"PUBLISHED"));
        when(inputs.capture(any(),anyString(),anyList(),eq("source"),anyString(),anyString(),anyLong(),anyLong()))
            .thenReturn(new CodingInputSnapshotService.Snapshot("runtime-evidence:input","c"+"a".repeat(63),"input.jsonl","a".repeat(64),1,30,"source"));
        when(inputs.describe(any(),anyString(),any())).thenReturn(Map.of("rowCount",1,"schemaJson","{}"));
        when(plans.register(any(),anyString(),any(),any())).thenReturn("runtime-evidence:plan");
        when(agent.submit(any())).thenReturn(result);
        when(collector.registeredResult(any(),anyString(),eq("runtime-evidence:result")))
            .thenReturn(new Dataset("runtime-evidence:result",Map.of("parentEvidenceIds",List.of("input")),List.of(Map.of("value","computed"))));
    }
}
