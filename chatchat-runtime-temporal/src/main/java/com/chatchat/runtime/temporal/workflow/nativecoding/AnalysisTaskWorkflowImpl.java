package com.chatchat.runtime.temporal.workflow.nativecoding;
import com.chatchat.runtime.temporal.activity.nativecoding.NativeAnalysisActivity;
import com.chatchat.runtime.temporal.contract.nativecoding.*;
import io.temporal.activity.*;
import io.temporal.common.RetryOptions;
import io.temporal.failure.*;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/** v1 histories are deterministic and contain no external IO or analysis plan. */
public final class AnalysisTaskWorkflowImpl implements AnalysisTaskWorkflow {
    private NativeAnalysisProgress progress=new NativeAnalysisProgress("QUEUED",null,null,null,0);
    private boolean acceptingProgress=true;
    @Override public NativeAnalysisResult execute(NativeAnalysisReference ref){
        long remaining=Math.max(1,ref.deadlineEpochMillis()-Workflow.currentTimeMillis());
        var activity=Workflow.newActivityStub(NativeAnalysisActivity.class,ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMillis(remaining)).setScheduleToCloseTimeout(Duration.ofMillis(remaining))
            .setHeartbeatTimeout(Duration.ofSeconds(30))
            .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
            .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofSeconds(2)).setMaximumInterval(Duration.ofSeconds(20)).build()).build());
        try{
            var result=activity.run(ref);progress=new NativeAnalysisProgress(result.status(),result.nodeId(),result.sessionId(),result.executionEpoch(),progress.attempt());
            if("CANCELLED".equals(result.status()))throw new CanceledFailure("Native task cancellation reconciled");
            if(!"COMPLETED".equals(result.status())){acceptingProgress=false;throw ApplicationFailure.newNonRetryableFailure("Native task ended with "+result.status(),"NATIVE_ANALYSIS_"+result.status());}
            acceptingProgress=false;return result;
        }catch(CanceledFailure cancelled){
            acceptingProgress=false;progress=new NativeAnalysisProgress("CANCELLING",progress.nodeId(),progress.sessionId(),progress.executionEpoch(),progress.attempt());cleanup(ref,"Temporal cancellation requested");progress=new NativeAnalysisProgress("CANCELLED",progress.nodeId(),progress.sessionId(),progress.executionEpoch(),progress.attempt());throw cancelled;
        }catch(ActivityFailure failed){
            acceptingProgress=false;progress=new NativeAnalysisProgress("STOP_RECONCILIATION",progress.nodeId(),progress.sessionId(),progress.executionEpoch(),progress.attempt());cleanup(ref,"Temporal execution failed or timed out");progress=new NativeAnalysisProgress(Workflow.currentTimeMillis()>=ref.deadlineEpochMillis()?"TIMED_OUT":"FAILED",progress.nodeId(),progress.sessionId(),progress.executionEpoch(),progress.attempt());throw failed;
        }
    }
    private void cleanup(NativeAnalysisReference ref,String reason){
        // Cancellation is not considered complete until another available
        // Worker has reconciled the real Executor stop, even after a crash.
        Workflow.newDetachedCancellationScope(()->Workflow.newActivityStub(NativeAnalysisActivity.class,ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(2)).setHeartbeatTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofSeconds(2)).setMaximumInterval(Duration.ofSeconds(30)).build()).build()).stop(ref,reason)).run();
    }
    @Override public void progress(NativeAnalysisProgress next){if(acceptingProgress&&next.attempt()>=progress.attempt())progress=next;}
    @Override public NativeAnalysisProgress status(){return progress;}
}
