package com.chatchat.runtime.temporal.activity.nativecoding;
import com.chatchat.runtime.temporal.contract.nativecoding.*;
import com.chatchat.runtime.temporal.workflow.nativecoding.AnalysisTaskWorkflow;
import io.temporal.activity.*;
import io.temporal.client.WorkflowClient;
import io.temporal.failure.ApplicationFailure;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** One Activity owns an entire native analysis. Model/tool calls remain inside
 * the official Cline loop. Heartbeats use only small execution references. */
public final class NativeAnalysisActivityImpl implements NativeAnalysisActivity,AutoCloseable {
    private final NativeAnalysisPort port;
    private final WorkflowClient client;
    private final ScheduledExecutorService heartbeats=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"native-activity-heartbeat");t.setDaemon(true);return t;});
    public NativeAnalysisActivityImpl(NativeAnalysisPort port,WorkflowClient client){this.port=port;this.client=client;}
    @Override public NativeAnalysisResult run(NativeAnalysisReference ref){
        var context=Activity.getExecutionContext();var info=context.getInfo();
        var last=new NativeAnalysisProgress[]{null};
        var current=new AtomicReference<NativeAnalysisProgress>(new NativeAnalysisProgress("DISPATCHED",null,null,null,info.getAttempt()));
        var lost=new AtomicReference<RuntimeException>();
        var timer=heartbeats.scheduleWithFixedDelay(()->{try{context.heartbeat(current.get());}catch(RuntimeException stopped){lost.compareAndSet(null,stopped);}},0,3,TimeUnit.SECONDS);
        try{return port.execute(ref,info.getWorkflowId(),info.getWorkflowRunId()+":"+info.getActivityId()+":"+info.getAttempt(),info.getAttempt(),progress->{
            if(lost.get()!=null)throw lost.get();current.set(progress);
            context.heartbeat(progress);
            if(!progress.equals(last[0])){
                client.newWorkflowStub(AnalysisTaskWorkflow.class,info.getWorkflowId()).progress(progress);
                last[0]=progress;
            }
        });}catch(SecurityException|IllegalArgumentException denied){throw ApplicationFailure.newNonRetryableFailure("Native task authorization or contract rejected",denied.getClass().getSimpleName());}
        finally{timer.cancel(false);}
    }
    @Override public NativeAnalysisResult stop(NativeAnalysisReference ref,String reason){
        var context=Activity.getExecutionContext();context.heartbeat("Reconciling actual Native stop");
        var timer=heartbeats.scheduleWithFixedDelay(()->{try{context.heartbeat("Reconciling actual Native stop");}catch(RuntimeException cancelled){/* Stop reconciliation must not abandon a real process. */}},3,3,TimeUnit.SECONDS);
        try{return port.stop(ref,reason);}finally{timer.cancel(false);}
    }
    @Override public void close(){heartbeats.shutdownNow();}
}
