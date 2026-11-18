package com.chatchat.api.runtime.coding;

import com.chatchat.agents.runtime.coding.CodingJournal;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.runtime.coding.cline.ClineModelReceiptStore;
import com.chatchat.api.runtime.coding.cline.LegacyCodingStopReconciler;
import com.chatchat.api.runtime.coding.execution.CodingPythonExecutionBridge;
import com.chatchat.api.runtime.coding.workspace.CodingWorkspaceProofVerifier;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyCodingStopReconcilerTest {
    private final String task="sql-coding-retired";
    private final KernelDataScope scope=new KernelDataScope("tenant","user",null,null,"run",null,Map.of());
    private final DurableCodingTaskService tasks=mock(DurableCodingTaskService.class);
    private final ClineModelReceiptStore models=mock(ClineModelReceiptStore.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final CodingInvocationContext identity=new CodingInvocationContext();
    private final CodingJournal.Lease lease=new CodingJournal.Lease("new",Instant.now().plusSeconds(60),2);
    private final LegacyCodingStopReconciler reconciler=new LegacyCodingStopReconciler(tasks,identity,models,
        mock(CodingWorkspaceProofVerifier.class),mock(CodingPythonExecutionBridge.class),jdbc);

    @BeforeEach void journal(){
        when(jdbc.queryForList(anyString(),eq(""))).thenReturn(List.of(Map.of("id","a","tenant_id","tenant",
            "user_id","user","run_id","run","task_id",task)));
        var outcome=mock(CodingTaskOutcome.class);
        when(outcome.status()).thenReturn(CodingTaskOutcome.Status.RUNNING);
        when(outcome.revision()).thenReturn(7L);
        when(outcome.executionControl()).thenReturn(new CodingTaskOutcome.ExecutionControl(
            CodingErrorCode.CANCEL_REQUESTED,Instant.EPOCH,false,true));
        when(tasks.get(scope,task)).thenReturn(outcome);
        when(tasks.contract(scope,task)).thenReturn(mock(CodingTaskContract.class));
        when(tasks.ownership(scope,task)).thenReturn(new CodingJournal.Lease("old",Instant.EPOCH,1));
        when(tasks.claim(eq(scope),eq(task),anyString(),eq(Duration.ofMinutes(2)),eq(7L))).thenReturn(lease);
    }

    @Test void closesAnExpiredTaskWhenAllStartedOperationsAreAlreadyReconciled(){
        when(tasks.unresolvedOperations(scope,task)).thenReturn(List.of());
        reconciler.scan();
        verify(tasks).finishStoppedAfterReconciliation(scope,task,lease,7L);
        verifyNoInteractions(models);
    }

    @Test void verifiesACommittedModelReceiptBeforeClosingTheTask(){
        when(tasks.unresolvedOperations(scope,task)).thenReturn(List.of("model-1"),List.of());
        when(models.proofRef(scope,task,"model-1")).thenReturn("runtime-evidence:old-receipt");
        when(models.confirmsStopped(scope,task,"model-1","runtime-model:model-1","runtime-evidence:old-receipt")).thenReturn(true);
        reconciler.scan();
        var order=inOrder(models,tasks);
        order.verify(models).confirmsStopped(scope,task,"model-1","runtime-model:model-1","runtime-evidence:old-receipt");
        order.verify(tasks).reconcileStopped(scope,task,lease,7L,"model-1","runtime-evidence:old-receipt");
        order.verify(tasks).finishStoppedAfterReconciliation(scope,task,lease,7L);
    }

    @Test void leavesUnprovedOperationsPendingWithoutTakingALease(){
        when(tasks.unresolvedOperations(scope,task)).thenReturn(List.of("model-1"));
        reconciler.scan();
        verify(tasks,never()).claim(any(),anyString(),anyString(),any(),anyLong());
        verify(tasks,never()).finishStoppedAfterReconciliation(any(),anyString(),any(),anyLong());
    }
}
