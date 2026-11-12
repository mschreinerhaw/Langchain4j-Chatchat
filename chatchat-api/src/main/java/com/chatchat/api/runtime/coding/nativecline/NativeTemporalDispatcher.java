package com.chatchat.api.runtime.coding.nativecline;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.runtime.temporal.contract.nativecoding.NativeAnalysisReference;
import com.chatchat.runtime.temporal.workflow.nativecoding.AnalysisTaskWorkflow;
import io.temporal.client.*;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
public final class NativeTemporalDispatcher implements NativeTaskDispatch {
    private final WorkflowClient client;private final NativeTemporalProperties properties;private final NativeSiloTaskService inputs;
    private final NativeS3Settings settings;
    public NativeTemporalDispatcher(WorkflowClient client,NativeTemporalProperties properties,NativeSiloTaskService inputs,NativeS3Settings settings){this.client=client;this.properties=properties;this.inputs=inputs;this.settings=settings;}
    public static String workflowId(KernelDataScope scope,String task){return "native-analysis-v1-"+UserAssetService.hash((scope.tenantId()+"\0"+scope.userId()+"\0"+scope.runId()+"\0"+task).getBytes(StandardCharsets.UTF_8));}
    @Override public void start(CodingTaskContract c){
        String hash=CodingCanonicalHash.sha256(c);
        if(!hash.equals(CodingCanonicalHash.sha256(inputs.prepared(c.scope(),c.taskId()))))throw new SecurityException("Temporal reference differs from the authorized frozen contract");
        var ref=new NativeAnalysisReference(c.scope().tenantId(),c.scope().userId(),c.scope().runId(),c.taskId(),hash,c.budget().deadlineAt().toEpochMilli());
        var stub=client.newWorkflowStub(AnalysisTaskWorkflow.class,WorkflowOptions.newBuilder().setWorkflowId(workflowId(c.scope(),c.taskId())).setTaskQueue(properties.getTaskQueue())
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE).build());
        try{WorkflowClient.start(stub::execute,ref);}catch(WorkflowExecutionAlreadyStarted existing){/* Stable identity and immutable contract: no second Session. */}
    }
    @Override public void cancel(KernelDataScope scope,String task){client.newUntypedWorkflowStub(workflowId(scope,task)).cancel();}
    @Override public Map<String,Object> status(KernelDataScope scope,String task){
        var result=new LinkedHashMap<String,Object>();result.put("workflowId",workflowId(scope,task));result.put("taskQueue",properties.getTaskQueue());
        try{
            var description=client.getWorkflowServiceStubs().blockingStub().withDeadlineAfter(3,TimeUnit.SECONDS).describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder().setNamespace(properties.getNamespace()).setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId(scope,task))).build());
            result.put("availability","AVAILABLE");result.put("workflowStatus",description.getWorkflowExecutionInfo().getStatus().name());result.put("workflowRunId",description.getWorkflowExecutionInfo().getExecution().getRunId());
            result.put("historyEvents",description.getWorkflowExecutionInfo().getHistoryLength());
            var pending=new ArrayList<Map<String,Object>>();for(var activity:description.getPendingActivitiesList())pending.add(Map.of("activityId",activity.getActivityId(),"state",activity.getState().name(),"attempt",activity.getAttempt(),"workerIdentity",activity.getLastWorkerIdentity()));
            result.put("pendingActivities",pending);
        }catch(RuntimeException unavailable){result.put("availability","UNAVAILABLE");}
        return result;
    }
    @Override public List<Map<String,Object>> nodes(){
        var result=new ArrayList<Map<String,Object>>();for(String node:settings.nodeIds()){
            var row=new LinkedHashMap<String,Object>();row.put("nodeId",node);
            try{var health=settings.client(node).health();row.put("online",true);for(String field:List.of("workerMemoryBytes","workerCpuTimeNanos"))row.put(field,health.path(field).isNumber()?health.path(field).longValue():null);row.put("activeWorkers",health.path("capacity").path("activeWorkers").isNumber()?health.path("capacity").path("activeWorkers").intValue():null);row.put("maxWorkers",health.path("capacity").path("maxWorkers").isNumber()?health.path("capacity").path("maxWorkers").intValue():null);row.put("gatewayMemoryBytes",health.path("gatewayMemoryBytes").isNumber()?health.path("gatewayMemoryBytes").longValue():null);}
            catch(RuntimeException offline){row.put("online",false);}result.add(row);
        }return result;
    }
}
