package com.chatchat.api.runtime.coding.nativecline;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingCanonicalHash;
import com.chatchat.runtime.temporal.activity.nativecoding.*;
import com.chatchat.runtime.temporal.contract.nativecoding.*;
import com.chatchat.runtime.temporal.workflow.nativecoding.AnalysisTaskWorkflowImpl;
import io.temporal.client.*;
import io.temporal.serviceclient.*;
import io.temporal.worker.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableConfigurationProperties(NativeTemporalProperties.class)
@ConditionalOnProperty(prefix="chatchat.runtime.coding",name="scheduler",havingValue="temporal")
public class NativeTemporalConfiguration {
    @Bean(destroyMethod="shutdown") WorkflowServiceStubs nativeAnalysisStubs(NativeTemporalProperties p)throws Exception{
        var options=WorkflowServiceStubsOptions.newBuilder().setTarget(p.getTarget()).setEnableHttps(p.isTlsEnabled());
        if(p.getTlsCertificateFile().isBlank()!=p.getTlsKeyFile().isBlank())throw new IllegalArgumentException("Both Temporal client certificate and private key required");
        if(!p.getTlsCertificateFile().isBlank()||!p.getTlsCaFile().isBlank()){
            if(!p.isTlsEnabled())throw new IllegalArgumentException("Temporal TLS material requires TLS enabled");
            var ssl=io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forClient();
            if(!p.getTlsCertificateFile().isBlank())ssl.keyManager(new java.io.File(p.getTlsCertificateFile()),new java.io.File(p.getTlsKeyFile()));
            if(!p.getTlsCaFile().isBlank())ssl.trustManager(new java.io.File(p.getTlsCaFile()));options.setSslContext(ssl.build());
        }
        if(!p.getApiKeyFile().isBlank()){
            if(!p.isTlsEnabled())throw new IllegalArgumentException("Temporal API key requires TLS");
            var path=java.nio.file.Path.of(p.getApiKeyFile());if(!path.isAbsolute()||!java.nio.file.Files.isRegularFile(path))throw new IllegalArgumentException("Private Temporal API key file required");
            options.addApiKey(()->{try{return java.nio.file.Files.readString(path).trim();}catch(java.io.IOException unavailable){throw new IllegalStateException("Temporal credential unavailable");}});
        }
        return WorkflowServiceStubs.newServiceStubs(options.build());
    }
    @Bean WorkflowClient nativeAnalysisClient(@Qualifier("nativeAnalysisStubs")WorkflowServiceStubs stubs,NativeTemporalProperties p){return WorkflowClient.newInstance(stubs,WorkflowClientOptions.newBuilder().setNamespace(p.getNamespace()).setIdentity(p.getNodeId().isBlank()?"runtime-native-api":p.getNodeId()).build());}
    @Bean NativeTaskDispatch nativeAnalysisDispatch(@Qualifier("nativeAnalysisClient")WorkflowClient client,NativeTemporalProperties p,NativeSiloTaskService inputs,NativeS3Settings settings){return new NativeTemporalDispatcher(client,p,inputs,settings);}
    @Bean NativeAnalysisPort nativeAnalysisPort(NativeS3CodingAdapter adapter,NativeSiloTaskService inputs,com.chatchat.api.runtime.coding.CodingInvocationContext identity,com.chatchat.agents.runtime.coding.DurableCodingTaskService tasks,NativeS3Settings settings){
        return new NativeAnalysisPort(){
            private KernelDataScope scope(NativeAnalysisReference r){return new KernelDataScope(r.tenantId(),r.userId(),null,null,r.runId(),null,Map.of());}
            private void verify(NativeAnalysisReference r){identity.as(scope(r),EnumSet.allOf(com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.class),()->{if(!CodingCanonicalHash.sha256(inputs.prepared(scope(r),r.taskId())).equals(r.contractSha256()))throw new SecurityException("Temporal frozen task binding changed");return null;});}
            private NativeAnalysisResult result(NativeAnalysisReference r,com.chatchat.common.runtime.coding.CodingTaskOutcome outcome){return identity.as(scope(r),EnumSet.allOf(com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.class),()->{var b=tasks.privateState(scope(r),r.taskId(),"native-s3:binding");return new NativeAnalysisResult(r.taskId(),outcome.status().name(),b==null?null:b.path("nodeId").asText(),b==null?null:b.path("sessionId").asText(),b==null?null:b.path("executionEpoch").asText());});}
            public NativeAnalysisResult execute(NativeAnalysisReference r,String workflowId,String attemptId,int attempt,Checkpoint heartbeat){
                verify(r);
                if(!NativeTemporalDispatcher.workflowId(scope(r),r.taskId()).equals(workflowId))throw new SecurityException("Unexpected analysis Workflow identity");
                String owner="temporal-"+com.chatchat.api.assets.UserAssetService.hash(attemptId.getBytes(StandardCharsets.UTF_8));
                try{return result(r,adapter.runManaged(scope(r),r.taskId(),new NativeExecutionControl(){
                    public String owner(){return owner;}public int attempt(){return attempt;}public String workflowId(){return workflowId;}
                    public void heartbeat(String phase,String node,String session,String epoch){heartbeat.pulse(new NativeAnalysisProgress(phase,node,session,epoch,attempt));}
                }));}catch(NativeS3NodeClient.NodeException rejected){if(rejected.status()==401||rejected.status()==403)throw new SecurityException("Native task authorization rejected");throw rejected;}
            }
            public NativeAnalysisResult stop(NativeAnalysisReference r,String reason){verify(r);return result(r,adapter.stopManaged(scope(r),r.taskId(),reason));}
        };
    }
    @Bean @ConditionalOnProperty(prefix="chatchat.runtime.coding.temporal",name="worker-enabled",havingValue="true")
    NativeWorkerLifecycle nativeAnalysisWorker(@Qualifier("nativeAnalysisClient")WorkflowClient client,NativeAnalysisPort port,NativeTemporalProperties p,NativeS3Settings settings){return new NativeWorkerLifecycle(client,port,p,settings);}
    static final class NativeWorkerLifecycle implements SmartLifecycle {
        private final WorkerFactory factory;private final Worker worker;private final NativeS3Settings settings;private volatile boolean running;
        NativeWorkerLifecycle(WorkflowClient client,NativeAnalysisPort port,NativeTemporalProperties p,NativeS3Settings settings){
            this.settings=settings;factory=WorkerFactory.newInstance(client);worker=factory.newWorker(p.getTaskQueue(),WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(p.getMaxConcurrentTasks()).setMaxConcurrentWorkflowTaskExecutionSize(8).build());
            worker.registerWorkflowImplementationTypes(AnalysisTaskWorkflowImpl.class);worker.registerActivitiesImplementations(new NativeAnalysisActivityImpl(port,client));
        }
        public void start(){factory.start();running=true;}public void stop(){running=false;factory.suspendPolling();factory.shutdown();}public boolean isRunning(){return running;}
        @Scheduled(initialDelay=5000,fixedDelay=5000)public void localNodeHealth(){if(!running)return;try{settings.client(settings.nodeId()).requireNativeIdentity();worker.resumePolling();}catch(RuntimeException offline){worker.suspendPolling();}}
    }
}
