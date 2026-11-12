package com.chatchat.api.assets;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.coding.*;
import com.chatchat.api.runtime.coding.nativecline.*;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.mcp.license.McpLicenseEntitlementPort;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.*;

/** Real Runtime SQL/Silo registry transactions over an explicit fake Node and
 * versioned in-memory blob transport. This is not model/business acceptance. */
class NativeS3CodingAdapterTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final CodingInvocationContext identity=new CodingInvocationContext();
    SiloCodingWorkspaceServiceTest fixture;SiloCodingWorkspaceService workspace;
    DurableCodingTaskService tasks;NativeSiloTaskService capture;NativeS3CodingAdapter adapter;
    NativeS3Evidence nativeEvidence;
    ThreadPoolTaskScheduler scheduler;HttpServer http;
    JsonNode grant,manifest;boolean created,messaged,sealed;final AtomicInteger messageCalls=new AtomicInteger();
    volatile boolean blockStatus;
    final java.util.concurrent.CountDownLatch blockedStatus=new java.util.concurrent.CountDownLatch(1),releaseStatus=new java.util.concurrent.CountDownLatch(1);
    volatile boolean asynchronousStop,missingStoppedTelemetry,rejectAllocation;volatile long stopReadyAt;
    final byte[] output="{\"value\":42}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @BeforeEach void setup()throws Exception{
        fixture=new SiloCodingWorkspaceServiceTest();fixture.setup();
        var tx=new DataSourceTransactionManager(fixture.jdbc.getDataSource());var store=new JdbcRuntimeEvidenceStore(fixture.jdbc,json,tx);
        var policy=new AssetStorageProperties();policy.setEndpoint("http://silo.invalid:9000");workspace=new SiloCodingWorkspaceService(fixture.assets,fixture.blobs,policy,store,json);
        scheduler=new ThreadPoolTaskScheduler();scheduler.initialize();
        tasks=new DurableCodingTaskService(store,json,Clock.systemUTC(),identity,(scope,ref)->store.find(scope,NativeS3Evidence.id(ref)).isPresent()||store.snapshot(scope,NativeS3Evidence.id(ref)).isPresent(),new NativeS3ExecutionProofVerifier(store),Runnable::run,scheduler);
        capture=new NativeSiloTaskService(identity,workspace,store,json);
        http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/",this::node);http.start();
        Path config=directory.resolve("connection.json");Files.writeString(config,json.writeValueAsString(Map.of("endpoint","http://127.0.0.1:"+http.getAddress().getPort(),"issuerKey","k".repeat(64),"defaultModel","published-model","modelProfiles",Map.of("published-model","fixed"))));
        McpLicenseEntitlementPort license=new McpLicenseEntitlementPort(){public CodingDispatchEntitlement codingDispatchEntitlement(){return new CodingDispatchEntitlement(true,"VALID");}};
        nativeEvidence=spy(new NativeS3Evidence(fixture.assets,store,json));
        adapter=new NativeS3CodingAdapter(tasks,capture,workspace,new NativeS3Settings(config.toString(),json),nativeEvidence,identity,json,fixture.jdbc,license,tx);
    }
    @AfterEach void stop(){if(adapter!=null)adapter.close();if(http!=null)http.stop(0);if(scheduler!=null)scheduler.shutdown();}
    <T>T authorized(java.util.function.Supplier<T> action){return identity.as(fixture.scope,Set.of(READ,SUBMIT,WORKER,EVIDENCE,STOP,RECOVER),action);}
    @Test void actualProtocolDeliveryRegistersAllSixReferencesAndCannotSilentlyBecomeBusinessValidation()throws Exception{
        String file=fixture.assets.upload(fixture.owner,"input.csv","value\n42\n".getBytes()).id();
        var contract=authorized(()->capture.capture(fixture.scope,"native-test",List.of(file),"Analyze the supplied input","published-model",new CodingTaskContract.Budget("b",Instant.now().plusSeconds(90).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),-1,-1,-1,-1,"unlimited","USD",-1,1024*1024,1024*1024),Map.of("profile","artifact-integrity-v1"),List.of("RESULT")));
        authorized(()->adapter.submit(contract));CodingTaskOutcome result=null;
        for(int i=0;i<100;i++){result=authorized(()->tasks.get(fixture.scope,"native-test"));if(result.status().terminal())break;Thread.sleep(100);}
        assertThat(result.status()).as(result.summary()).isEqualTo(CodingTaskOutcome.Status.COMPLETED);
        assertThat(result.validationStatus()).isEqualTo(CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
        assertThat(result.codeArtifactRef()).isNotBlank();assertThat(result.executionReceiptRef()).isNotBlank();assertThat(result.resultDatasetRef()).isNotBlank();assertThat(result.validationRecordRef()).isNotBlank();assertThat(result.evidenceBundleRef()).isNotBlank();
        assertThat(workspace.get(fixture.scope,"native-test").status()).isEqualTo("COMPLETED");
        assertThat(messageCalls).hasValue(1);authorized(()->adapter.submit(contract));assertThat(messageCalls).hasValue(1);
    }
    @Test void unknownFrozenModelAndUnauthenticatedCaptureFailBeforeAllocatingAnyNode(){
        assertThatThrownBy(()->capture.capture(fixture.scope,"native-test",List.of(),"Analyze","published-model",null)).isInstanceOf(CodingContractException.class);
        assertThat(created).isFalse();
    }
    @Test void cancellationWaitsForExecutorProofAndCanSettleWhenHistoricalTelemetryIsMissing()throws Exception{
        asynchronousStop=true;missingStoppedTelemetry=true;
        String file=fixture.assets.upload(fixture.owner,"input.csv","value\n42\n".getBytes()).id();
        var contract=authorized(()->capture.capture(fixture.scope,"native-cancel",List.of(file),"Long running task","published-model",new CodingTaskContract.Budget("cancel-budget",Instant.now().plusSeconds(90).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),-1,-1,-1,-1,"unlimited","USD",-1,1024*1024,1024*1024),Map.of("profile","artifact-integrity-v1"),List.of("RESULT")));
        authorized(()->adapter.submit(contract));for(int i=0;i<100&&messageCalls.get()==0;i++)Thread.sleep(50);
        assertThat(messageCalls).hasValue(1);authorized(()->adapter.cancel(fixture.scope,"native-cancel","User requested cancel"));
        CodingTaskOutcome result=null;for(int i=0;i<100;i++){result=authorized(()->tasks.get(fixture.scope,"native-cancel"));if(result.status().terminal())break;Thread.sleep(100);}
        assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.CANCELLED);assertThat(result.executionControl().executorStopped()).isTrue();
        assertThat(result.resultDatasetRef()).isNull();assertThat(System.nanoTime()).isGreaterThanOrEqualTo(stopReadyAt);
        var facade=new NativeCodingTasks(capture,adapter,new NativeS3Settings(directory.resolve("connection.json").toString(),json),identity,fixture.assets,null,tasks);
        var traces=new ArrayList<Map<String,Object>>();var receipt=authorized(()->facade.await(fixture.scope,"native-cancel",()->false,traces::add));
        var committed=authorized(()->tasks.events(fixture.scope,"native-cancel",0,128));
        assertThat(traces).hasSize(committed.size());assertThat(traces.stream().map(t->t.get("eventId"))).containsExactlyElementsOf(committed.stream().map(CodingExecutionEvent::eventId).toList());
        assertThat(receipt.get("analysisEvents")).asList().isNotEmpty();assertThat(receipt.get("status")).isEqualTo("CANCELLED");
    }
    @Test void shutdownDuringARealHttpWaitDoesNotInvalidateTheRecoverableWorkspace()throws Exception{
        asynchronousStop=true;
        String file=fixture.assets.upload(fixture.owner,"input.csv","value\n42\n".getBytes()).id();
        var contract=authorized(()->capture.capture(fixture.scope,"native-restart",List.of(file),"Restart-safe local analysis","published-model",new CodingTaskContract.Budget("restart-budget",Instant.now().plusSeconds(90).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),-1,-1,-1,-1,"unlimited","USD",-1,1024*1024,1024*1024),Map.of("profile","artifact-integrity-v1"),List.of("RESULT")));
        authorized(()->adapter.submit(contract));
        for(int i=0;i<100&&messageCalls.get()==0;i++)Thread.sleep(50);
        assertThat(messageCalls).hasValue(1);blockStatus=true;
        try{
            assertThat(blockedStatus.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            adapter.close();Thread.sleep(300);
            assertThat(authorized(()->tasks.get(fixture.scope,"native-restart")).status().terminal()).isFalse();
            assertThat(workspace.get(fixture.scope,"native-restart").status()).isEqualTo("RUNNING");
            assertThat(authorized(()->tasks.privateState(fixture.scope,"native-restart","native-s3:binding"))).isNotNull();
        }finally{releaseStatus.countDown();}
    }
    @Test void capacityRejectedTaskCancelsWithoutInventingAWorkerStopReceipt()throws Exception{
        rejectAllocation=true;
        String file=fixture.assets.upload(fixture.owner,"input.csv","value\n42\n".getBytes()).id();
        var contract=authorized(()->capture.capture(fixture.scope,"native-capacity",List.of(file),"Queued task","published-model",new CodingTaskContract.Budget("capacity-budget",Instant.now().plusSeconds(90).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),-1,-1,-1,-1,"unlimited","USD",-1,1024*1024,1024*1024),Map.of("profile","artifact-integrity-v1"),List.of("RESULT")));
        authorized(()->adapter.submit(contract));
        for(int i=0;i<100&&authorized(()->tasks.privateState(fixture.scope,"native-capacity","native-s3:binding"))==null;i++)Thread.sleep(30);
        authorized(()->adapter.cancel(fixture.scope,"native-capacity","Owner stopped queued task"));
        CodingTaskOutcome result=null;for(int i=0;i<100;i++){result=authorized(()->tasks.get(fixture.scope,"native-capacity"));if(result.status().terminal())break;Thread.sleep(50);}
        assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.CANCELLED);
        assertThat(result.summary()).contains("before any external dispatch");assertThat(result.executionReceiptRef()).isNull();assertThat(messageCalls).hasValue(0);assertThat(created).isFalse();
    }
    @Test void cancellationDuringControllerExitRetainsItsWakeup()throws Exception{
        rejectAllocation=true;
        var atExit=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(call->{atExit.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Controller exit not released");return call.callRealMethod();})
            .when(nativeEvidence).archive(any(),eq("control-error"),anyString(),any());
        String file=fixture.assets.upload(fixture.owner,"race.csv","value\n42\n".getBytes()).id();
        var contract=authorized(()->capture.capture(fixture.scope,"native-exit-cancel",List.of(file),"Queued task","published-model",new CodingTaskContract.Budget("race-budget",Instant.now().plusSeconds(90).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),-1,-1,-1,-1,"unlimited","USD",-1,1024*1024,1024*1024),Map.of("profile","artifact-integrity-v1"),List.of("RESULT")));
        try{
            authorized(()->adapter.submit(contract));assertThat(atExit.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            authorized(()->adapter.cancel(fixture.scope,contract.taskId(),"Stop during exit"));release.countDown();
            CodingTaskOutcome result=null;for(int i=0;i<100;i++){result=authorized(()->tasks.get(fixture.scope,contract.taskId()));if(result.status().terminal())break;Thread.sleep(50);}
            assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.CANCELLED);assertThat(result.executionReceiptRef()).isNull();assertThat(messageCalls).hasValue(0);assertThat(created).isFalse();
        }finally{release.countDown();}
    }
    private void node(HttpExchange exchange)throws java.io.IOException{
        try{
            String path=exchange.getRequestURI().getPath();byte[] body=exchange.getRequestBody().readAllBytes();JsonNode request=body.length==0?json.createObjectNode():json.readTree(body);
            if(path.equals("/health/live")){reply(exchange,200,Map.of("engine","cline-standalone","version","4.1.26","legacySdkWorker",false));return;}
            String token=exchange.getRequestHeaders().getFirst("Authorization").substring(7);var identity=json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
            if(path.equals("/v1/sessions")){if(rejectAllocation){reply(exchange,429,Map.of("code","CAPACITY_EXHAUSTED"));return;}created=true;grant=identity;reply(exchange,201,state());return;}
            if(!created){reply(exchange,404,Map.of("code","NOT_FOUND"));return;}
            if(path.endsWith("/status")){if(blockStatus&&messaged){blockedStatus.countDown();releaseStatus.await(5,java.util.concurrent.TimeUnit.SECONDS);}reply(exchange,200,state());return;}
            if(path.endsWith("/observations")||path.endsWith("/receipts")){
                if(sealed&&missingStoppedTelemetry){reply(exchange,410,Map.of("code","HISTORICAL_TELEMETRY_UNAVAILABLE"));return;}
                reply(exchange,200,Map.of("events",List.of(),"nextEventId",0,"hasMore",false));return;
            }
            if(path.endsWith("/messages")){messageCalls.incrementAndGet();messaged=true;reply(exchange,202,Map.of("accepted",true));return;}
            if(path.endsWith("/delivery-preview")||path.endsWith("/delivery")){
                manifest=json.valueToTree(new SiloCodingWorkspaceService.Delivery("coding_node_delivery.v1",grant.path("taskId").asText(),grant.path("deliveryBinding").path("runId").asText(),grant.path("tenantId").asText(),grant.path("deliveryBinding").path("inputSnapshotVersion").asText(),"v1","native-session","native-epoch","NATIVE_TURN_COMPLETED","PENDING_RUNTIME_VALIDATION",List.of(new SiloCodingWorkspaceService.Artifact("output/answer.json","RESULT",output.length,UserAssetService.hash(output))),List.of(new SiloCodingWorkspaceService.Receipt("1","turn.completed","native-session","native-epoch"))));
                if(path.endsWith("/delivery"))sealed=true;reply(exchange,200,manifest);return;
            }
            if(path.endsWith("/executions")){reply(exchange,200,Map.of("executions",List.of()));return;}
            if(path.endsWith("/upload")){
                byte[] journal=json.writeValueAsBytes(Map.of("events",List.of(Map.of("eventId","1","eventType","turn.completed","sessionId","native-session","epoch","native-epoch"),Map.of("eventId","2","eventType","delivery.sealed","sessionId","native-session","epoch","native-epoch","payload",Map.of("localStopConfirmed",true)))));
                var artifact=request.path("artifacts").path(0);var part=put(artifact.path("parts").path(0).path("url").asText(),output);
                reply(exchange,200,Map.of("artifacts",List.of(Map.of("path","output/answer.json","parts",List.of(Map.of("index",0,"size",output.length,"sha256",UserAssetService.hash(output),"objectVersion",part.get("objectVersion"))),"size",output.length,"sha256",UserAssetService.hash(output))),"manifest",put(request.path("manifestUrl").asText(),json.writeValueAsBytes(manifest)),"evidence",put(request.path("evidenceUrl").asText(),journal)));return;
            }
            if(path.endsWith("/acceptance")){reply(exchange,200,Map.of("accepted",true));return;}
            if(path.endsWith("/cancel")){if(asynchronousStop){if(stopReadyAt==0)stopReadyAt=System.nanoTime()+500_000_000;reply(exchange,202,Map.of("workerState","STOPPING"));}else{sealed=true;reply(exchange,200,state());}return;}
            reply(exchange,404,Map.of("path",path));
        }catch(Exception failed){reply(exchange,500,Map.of("error",failed.toString()));}
    }
    private Map<String,Object> state(){if(stopReadyAt>0&&System.nanoTime()>=stopReadyAt)sealed=true;var state=new LinkedHashMap<String,Object>();state.put("workerState",sealed?"STOPPED":messaged?asynchronousStop?"RUNNING":"COMPLETED":"READY");state.put("native",Map.of("status",messaged?asynchronousStop?"running":"turn_completed":"idle"));if(sealed){if(manifest!=null)state.put("delivery",manifest);state.put("stopProof",Map.of("containerId","a".repeat(64),"running",false));}return state;}
    private Map<String,Object> put(String url,byte[] bytes){String key=url.substring("put:".length());var stored=fixture.blobs.put(key,bytes,"application/octet-stream",UserAssetService.hash(bytes));return Map.of("size",bytes.length,"sha256",UserAssetService.hash(bytes),"objectVersion",stored.versionId());}
    private void reply(HttpExchange e,int code,Object value)throws java.io.IOException{byte[] bytes=json.writeValueAsBytes(value);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(code,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}}
}
