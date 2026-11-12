package com.chatchat.runtime.temporal.workflow.nativecoding;
import com.chatchat.runtime.temporal.activity.nativecoding.*;
import com.chatchat.runtime.temporal.contract.nativecoding.*;
import io.temporal.activity.Activity;
import io.temporal.client.*;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerOptions;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class AnalysisTaskWorkflowTest {
    private NativeAnalysisReference reference(){return new NativeAnalysisReference("tenant","user","run","task","0".repeat(64),System.currentTimeMillis()+600000);}
    @Test void sdkRetriesWholeAnalysisAndPreservesStableWorkflowIdentity(){
        try(var env=TestWorkflowEnvironment.newInstance()){
            var worker=env.newWorker("native-test");worker.registerWorkflowImplementationTypes(AnalysisTaskWorkflowImpl.class);
            var calls=new AtomicInteger();
            worker.registerActivitiesImplementations(new NativeAnalysisActivity(){
                public NativeAnalysisResult run(NativeAnalysisReference r){if(calls.incrementAndGet()==1)throw ApplicationFailure.newFailure("Node unavailable","NODE_UNAVAILABLE");return new NativeAnalysisResult(r.taskId(),"COMPLETED","node-2","session-2","epoch-2");}
                public NativeAnalysisResult stop(NativeAnalysisReference r,String reason){throw new AssertionError("Successful retry must not invoke cleanup");}
            });env.start();
            var workflow=env.getWorkflowClient().newWorkflowStub(AnalysisTaskWorkflow.class,WorkflowOptions.newBuilder().setWorkflowId("stable-analysis").setTaskQueue("native-test").build());
            assertThat(workflow.execute(reference()).status()).isEqualTo("COMPLETED");assertThat(calls).hasValue(2);
            assertThat(env.getWorkflowClient().fetchHistory("stable-analysis").getHistory().getEventsCount()).isLessThan(20);
        }
    }
    @Test void cancellationWaitsForStopReconciliationInsteadOfInventingAResult(){
        try(var env=TestWorkflowEnvironment.newInstance()){
            var worker=env.newWorker("native-cancel",WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(1).build());worker.registerWorkflowImplementationTypes(AnalysisTaskWorkflowImpl.class);
            var stopped=new AtomicInteger();
            var activities=new NativeAnalysisActivity(){
                public NativeAnalysisResult run(NativeAnalysisReference r){for(;;){Activity.getExecutionContext().heartbeat("real activity pending");try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}}
                public NativeAnalysisResult stop(NativeAnalysisReference r,String reason){stopped.incrementAndGet();return new NativeAnalysisResult(r.taskId(),"CANCELLED","node",null,null);}
            };worker.registerActivitiesImplementations(activities);
            env.newWorker("native-cancel.control").registerActivitiesImplementations(activities);env.start();
            var workflow=env.getWorkflowClient().newWorkflowStub(AnalysisTaskWorkflow.class,WorkflowOptions.newBuilder().setWorkflowId("cancel-analysis").setTaskQueue("native-cancel").build());
            WorkflowClient.start(workflow::execute,reference());env.sleep(Duration.ofSeconds(1));WorkflowStub.fromTyped(workflow).cancel();
            assertThatThrownBy(()->WorkflowStub.fromTyped(workflow).getResult(NativeAnalysisResult.class)).isInstanceOf(WorkflowFailedException.class).hasCauseInstanceOf(io.temporal.failure.CanceledFailure.class);
            assertThat(stopped).hasValue(1);
        }
    }
    @Test void queuedCancellationHasControlCapacityWhileTheAnalysisSlotIsOccupied()throws Exception{
        try(var env=TestWorkflowEnvironment.newInstance()){
            var worker=env.newWorker("native-saturated",WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(1).build());worker.registerWorkflowImplementationTypes(AnalysisTaskWorkflowImpl.class);
            var occupied=new java.util.concurrent.CountDownLatch(1);var stopped=new AtomicInteger();
            var activities=new NativeAnalysisActivity(){
                public NativeAnalysisResult run(NativeAnalysisReference r){
                    assertThat(r.taskId()).isEqualTo("busy");occupied.countDown();
                    for(;;){Activity.getExecutionContext().heartbeat("analysis slot occupied");try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
                }
                public NativeAnalysisResult stop(NativeAnalysisReference r,String reason){stopped.incrementAndGet();return new NativeAnalysisResult(r.taskId(),"CANCELLED","node",null,null);}
            };
            worker.registerActivitiesImplementations(activities);env.newWorker("native-saturated.control").registerActivitiesImplementations(activities);env.start();
            var busy=env.getWorkflowClient().newWorkflowStub(AnalysisTaskWorkflow.class,WorkflowOptions.newBuilder().setWorkflowId("busy-analysis").setTaskQueue("native-saturated").build());
            var queued=env.getWorkflowClient().newWorkflowStub(AnalysisTaskWorkflow.class,WorkflowOptions.newBuilder().setWorkflowId("queued-analysis").setTaskQueue("native-saturated").build());
            WorkflowClient.start(busy::execute,new NativeAnalysisReference("tenant","user","run","busy","0".repeat(64),System.currentTimeMillis()+600000));
            assertThat(occupied.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try{
                WorkflowClient.start(queued::execute,reference());env.sleep(Duration.ofSeconds(1));WorkflowStub.fromTyped(queued).cancel();
                assertThatThrownBy(()->WorkflowStub.fromTyped(queued).getResult(15,java.util.concurrent.TimeUnit.SECONDS,NativeAnalysisResult.class)).isInstanceOf(WorkflowFailedException.class).hasCauseInstanceOf(io.temporal.failure.CanceledFailure.class);
                assertThat(stopped).hasValue(1);
            }finally{
                WorkflowStub.fromTyped(busy).cancel();
                assertThatThrownBy(()->WorkflowStub.fromTyped(busy).getResult(NativeAnalysisResult.class)).isInstanceOf(WorkflowFailedException.class);
            }
        }
    }
    @Test void failedBusinessOutcomeCannotCompleteTheTemporalWorkflowSuccessfully(){
        try(var env=TestWorkflowEnvironment.newInstance()){
            var worker=env.newWorker("native-failed");worker.registerWorkflowImplementationTypes(AnalysisTaskWorkflowImpl.class);
            worker.registerActivitiesImplementations(new NativeAnalysisActivity(){
                public NativeAnalysisResult run(NativeAnalysisReference r){return new NativeAnalysisResult(r.taskId(),"FAILED","node","session","epoch");}
                public NativeAnalysisResult stop(NativeAnalysisReference r,String reason){throw new AssertionError("An already reconciled terminal task is immutable");}
            });env.start();
            var workflow=env.getWorkflowClient().newWorkflowStub(AnalysisTaskWorkflow.class,WorkflowOptions.newBuilder().setWorkflowId("failed-analysis").setTaskQueue("native-failed").build());
            assertThatThrownBy(()->workflow.execute(reference())).isInstanceOf(WorkflowFailedException.class).hasCauseInstanceOf(ApplicationFailure.class);
            var events=env.getWorkflowClient().fetchHistory("failed-analysis").getHistory().getEventsList();
            assertThat(events.get(events.size()-1).getEventType()).isEqualTo(io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED);
        }
    }
    @Test void historyReferenceCannotCarryUnboundedAnalysisMaterials(){
        assertThatThrownBy(()->new NativeAnalysisReference("tenant","user","run","x".repeat(1024),"0".repeat(64),1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new NativeAnalysisReference("tenant","user","run","task","not-a-hash",1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void recordedPreControlQueueHistoriesRemainReplayCompatible()throws Exception{
        var root=java.nio.file.Path.of("").toAbsolutePath();
        while(root!=null&&!java.nio.file.Files.isDirectory(root.resolve("docs/testing/evidence/native-temporal-20261113")))root=root.getParent();
        assertThat(root).as("Recorded real candidate histories").isNotNull();
        for(String file:java.util.List.of("history-temporal-cancel-live.json","history-temporal-storage-live.json"))
            io.temporal.testing.WorkflowReplayer.replayWorkflowExecution(root.resolve("docs/testing/evidence/native-temporal-20261113").resolve(file).toFile(),AnalysisTaskWorkflowImpl.class);
    }
}
