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
    JdbcRuntimeEvidenceStore store;
    NativeS3Settings settings;
    String outputPath="output/answer.json";
    ThreadPoolTaskScheduler scheduler;HttpServer http;
    JsonNode grant,manifest;boolean created,messaged,sealed;final AtomicInteger messageCalls=new AtomicInteger();
    volatile boolean blockStatus;
    boolean dropBeforeAccept,dropAfterAccept;
    int previewRejections;
    boolean repairSchema;
    final AtomicInteger recoveryStops=new AtomicInteger();
    final Map<String,Map<String,Object>> messageReceipts=new java.util.concurrent.ConcurrentHashMap<>();
    volatile boolean wrongReceiptSession;
    final java.util.concurrent.CountDownLatch blockedStatus=new java.util.concurrent.CountDownLatch(1),releaseStatus=new java.util.concurrent.CountDownLatch(1);
    volatile boolean asynchronousStop,missingStoppedTelemetry,rejectAllocation;volatile long stopReadyAt;
    byte[] output="{\"value\":42}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @BeforeEach void setup()throws Exception{
        fixture=new SiloCodingWorkspaceServiceTest();fixture.setup();
        var tx=new DataSourceTransactionManager(fixture.jdbc.getDataSource());store=new JdbcRuntimeEvidenceStore(fixture.jdbc,json,tx);
        var policy=new AssetStorageProperties();policy.setEndpoint("http://silo.invalid:9000");workspace=new SiloCodingWorkspaceService(fixture.assets,fixture.blobs,policy,store,json);
        scheduler=new ThreadPoolTaskScheduler();scheduler.initialize();
        tasks=new DurableCodingTaskService(store,json,Clock.systemUTC(),identity,(scope,ref)->store.find(scope,NativeS3Evidence.id(ref)).isPresent()||store.snapshot(scope,NativeS3Evidence.id(ref)).isPresent(),new NativeS3ExecutionProofVerifier(store),Runnable::run,scheduler);
        capture=new NativeSiloTaskService(identity,workspace,store,json);
        http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/",this::node);http.start();
        Path config=directory.resolve("connection.json");Files.writeString(config,json.writeValueAsString(Map.of("endpoint","http://127.0.0.1:"+http.getAddress().getPort(),"issuerKey","k".repeat(64),"defaultModel","published-model","modelProfiles",Map.of("published-model","fixed"))));
        McpLicenseEntitlementPort license=new McpLicenseEntitlementPort(){public CodingDispatchEntitlement codingDispatchEntitlement(){return new CodingDispatchEntitlement(true,"VALID");}};
        nativeEvidence=spy(new NativeS3Evidence(fixture.assets,store,json));
        settings=new NativeS3Settings(config.toString(),json);
        adapter=new NativeS3CodingAdapter(tasks,capture,workspace,settings,nativeEvidence,identity,json,fixture.jdbc,license,tx);
    }
    @AfterEach void stop(){if(adapter!=null)adapter.close();if(http!=null)http.stop(0);if(scheduler!=null)scheduler.shutdown();}
    <T>T authorized(java.util.function.Supplier<T> action){return identity.as(fixture.scope,Set.of(READ,SUBMIT,WORKER,EVIDENCE,STOP,RECOVER),action);}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"postgresql","python"})
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="coding.sqlglotPython",matches=".+")
    void studioUsesTheSameNativeBrokerAndReturnsReviewedSourceViaItsOriginalId(String dialect) throws Exception {
        boolean python="python".equals(dialect);
        outputPath="output/studio-proposal.json";
        String filename=python?"total.py":"total.sql";
        output=json.writeValueAsBytes(Map.of("schemaVersion",(python?"python":"sql")+"-studio.output.v1",
            "files",List.of(Map.of("path",filename,"content",python?"def total():\n    return 44\n":"SELECT 44 AS total;")),"notes","For review"));
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var workspace=new com.chatchat.api.runtime.coding.workspace.CodingWorkspaceService(tasks,identity,store,
            beans.getBeanProvider(com.chatchat.common.runtime.coding.CodingAgentSPI.class),
            new com.chatchat.api.datascience.sql.SqlGlotToolchain(System.getProperty("coding.sqlglotPython"),json),json,120);
        beans.registerSingleton("adapter",new NativeAwareCodingAdapter(mock(CodingAgentSPI.class),adapter,tasks));
        beans.registerSingleton("studio",workspace);
        org.springframework.test.util.ReflectionTestUtils.setField(workspace,"nativeTasks",
            new NativeCodingTasks(capture,adapter,settings,identity,fixture.assets,null,tasks));
        org.springframework.test.util.ReflectionTestUtils.setField(workspace,"nativeInputs",capture);
        org.springframework.test.util.ReflectionTestUtils.setField(adapter,"studioWorkspace",
            beans.getBeanProvider(com.chatchat.api.runtime.coding.workspace.CodingWorkspaceService.class));
        workspace.setSqlCumulativeLimitsEnabled(true);
        workspace.setPythonCumulativeLimitsEnabled(true);
        String logical="studio-test-"+dialect;
        var accepted=workspace.submit(fixture.scope,logical,"Propose "+filename,dialect,"published-model");
        assertThat(accepted.taskId()).startsWith("native-");
        CodingTaskOutcome result=accepted;
        for(int i=0;i<100&&!result.status().terminal();i++){Thread.sleep(100);result=workspace.get(fixture.scope,logical);}
        assertThat(result.status()).as(result.summary()).isEqualTo(CodingTaskOutcome.Status.COMPLETED);
        assertThat(workspace.output(fixture.scope,logical,result).get("files").toString()).contains(filename,"44");
        var stored=authorized(()->tasks.contract(fixture.scope,accepted.taskId()));
        assertThat(stored.authorization().allowedTools()).doesNotContain("run_commands");
        assertThat(stored.budget().maxCodeExecutions()).isZero();
        assertThat(grant.path("studioBudget").path("modelCalls").asLong()).isEqualTo(stored.budget().maxModelCalls());
        assertThat(result.usage().consumed().modelCalls()).isEqualTo(1);
        var receipts=authorized(()->tasks.nativeTelemetry(fixture.scope,accepted.taskId(),"model"));
        assertThat(receipts).hasSize(1);
        assertThat(receipts.get(0).path("reasoningEffort").asText()).isEqualTo("medium");
        assertThat(workspace.submit(fixture.scope,logical,"Propose "+filename,dialect,"published-model").taskId()).isEqualTo(accepted.taskId());
        assertThat(messageCalls).hasValue(1);
    }

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
    @Test void repairEvidenceFindsLatestEditAcrossSparseEventSequences(){
        String task="native-sparse-edit";
        String file=fixture.assets.upload(fixture.owner,"input.csv","value\n42\n".getBytes()).id();
        authorized(()->{
            var budget=new CodingTaskContract.Budget("sparse",Instant.now().plusSeconds(90).truncatedTo(
                java.time.temporal.ChronoUnit.MILLIS),-1,-1,-1,-1,"unlimited","USD",-1,1024*1024,1024*1024);
            var contract=capture.capture(fixture.scope,task,List.of(file),"Analyze the supplied input",
                "published-model",budget,Map.of("profile","artifact-integrity-v1"),List.of("RESULT"));
            var submitted=tasks.submit(contract);
            var lease=tasks.claim(fixture.scope,task,"sparse-test",Duration.ofMinutes(3),submitted.revision());
            for(long sequence:List.of(7L,1_000_000L,2_000_000L)){
                tasks.privateState(fixture.scope,task,lease,"native-s3:edit:"+sequence,null,
                    json.valueToTree(Map.of("nativeEventId",Long.toString(sequence),"detailAssetId","asset")));
            }
            tasks.privateState(fixture.scope,task,lease,"native-s3:binding",null,
                json.valueToTree(Map.of("nativeEventId",1_400_000)));
            assertThat(tasks.latestNativeEditBefore(fixture.scope,task,1_500_000)).isEqualTo(1_000_000);
            assertThat(tasks.latestNativeEditBefore(fixture.scope,task,1_000_000)).isEqualTo(7);
            assertThat(tasks.latestNativeEditBefore(fixture.scope,task,7)).isZero();
            return null;
        });
    }

    @Test void missingDispatchAndLostAcceptanceResponseConvergeThroughTheSameKey()throws Exception{
        dropBeforeAccept=true;
        dropAfterAccept=true;
        var result=runDelivery("native-receipt");
        assertThat(result.status()).as(result.summary()).isEqualTo(CodingTaskOutcome.Status.COMPLETED);
        assertThat(messageCalls).hasValue(2);
        assertThat(messageReceipts).hasSize(1);
    }
    @Test void deliveryRepairAdvancesOnlyAfterAcknowledgement()throws Exception{
        previewRejections=1;
        dropAfterAccept=true;
        var result=runDelivery("native-repair");
        assertThat(result.status()).as(result.summary()).isEqualTo(CodingTaskOutcome.Status.COMPLETED);
        assertThat(messageReceipts).containsKey("delivery-rejection-v1").hasSize(2);
        assertThat(manifest.path("deliveryVersion").asText()).isEqualTo("v2");
    }
    @Test void schemaMismatchRepairsTheArtifactBeforeFinalization()throws Exception{
        repairSchema=true;
        output="{\"value\":\"not an integer\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var schema=Map.<String,Object>of("type","object","required",List.of("value"),
            "properties",Map.of("value",Map.of("type","integer")));
        var result=runDelivery("native-schema-repair",schema);
        assertThat(result.status()).as(result.summary()).isEqualTo(CodingTaskOutcome.Status.COMPLETED);
        assertThat(manifest.path("deliveryVersion").asText()).isEqualTo("v2");
        assertThat(messageReceipts).containsKey("delivery-rejection-v1").hasSize(2);
    }
    @Test void unchangedDeliveryRejectionStopsAfterTwoRepairTurns()throws Exception{
        previewRejections=10;
        var result=runDelivery("native-no-progress");
        assertThat(result.status()).as(result.summary()).isEqualTo(CodingTaskOutcome.Status.FAILED);
        assertThat(result.reasonCode()).isEqualTo(CodingErrorCode.OUTPUT_CONTRACT_VIOLATION);
        assertThat(result.executionControl().executorStopped()).isTrue();
        assertThat(messageCalls).hasValue(3);
    }
    private CodingTaskOutcome runDelivery(String task)throws Exception{return runDelivery(task,Map.of());}
    private CodingTaskOutcome runDelivery(String task,Map<String,Object> schema)throws Exception{
        String file=fixture.assets.upload(fixture.owner,"input.csv","value\n42\n".getBytes()).id();
        var budget=new CodingTaskContract.Budget("b",Instant.now().plusSeconds(90).truncatedTo(
            java.time.temporal.ChronoUnit.MILLIS),-1,-1,-1,-1,"unlimited","USD",-1,1024*1024,1024*1024);
        var contract=authorized(()->capture.capture(fixture.scope,task,List.of(file),"Analyze the supplied input",
            "published-model",budget,Map.of("profile","artifact-integrity-v1"),List.of("RESULT"),schema));
        authorized(()->adapter.submit(contract));
        for(int i=0;i<100;i++){
            var result=authorized(()->tasks.get(fixture.scope,task));
            if(result.status().terminal())return result;
            Thread.sleep(100);
        }
        return authorized(()->tasks.get(fixture.scope,task));
    }
    @Test void unknownFrozenModelAndUnauthenticatedCaptureFailBeforeAllocatingAnyNode(){
        assertThatThrownBy(()->capture.capture(fixture.scope,"native-test",List.of(),"Analyze","published-model",null)).isInstanceOf(CodingContractException.class);
        assertThat(created).isFalse();
    }
    @Test void cancelledHistoricalTemporalTaskReconcilesThroughItsRetiredExecutorWithoutDispatch()throws Exception{
        String task="temporal-native-retired-node";
        var config=directory.resolve("connection.json");
        var connection=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readString(config));
        connection.putObject("recoveryNodes").put("retired",connection.path("endpoint").asText());
        Files.writeString(config,connection.toString());
        adapter.close();
        var license=new McpLicenseEntitlementPort(){public CodingDispatchEntitlement codingDispatchEntitlement(){return new CodingDispatchEntitlement(true,"VALID");}};
        adapter=new NativeS3CodingAdapter(tasks,capture,workspace,new NativeS3Settings(config.toString(),json),nativeEvidence,
            identity,json,fixture.jdbc,license,new DataSourceTransactionManager(fixture.jdbc.getDataSource()));
        var projection=new com.chatchat.api.runtime.coding.monitor.CodingMonitorProjection(fixture.jdbc,json,tasks,
            new JdbcRuntimeEvidenceStore(fixture.jdbc,json,new DataSourceTransactionManager(fixture.jdbc.getDataSource())));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(projection,"schema");
        String file=fixture.assets.upload(fixture.owner,"input.csv","value\n42\n".getBytes()).id();
        var budget=new CodingTaskContract.Budget("retired",Instant.now().plusSeconds(90).truncatedTo(
            java.time.temporal.ChronoUnit.MILLIS),-1,-1,-1,-1,"unlimited","USD",-1,1024*1024,1024*1024);
        authorized(()->{
            var contract=capture.capture(fixture.scope,task,List.of(file),"Analyze","published-model",budget,
                Map.of("profile","artifact-integrity-v1"),List.of("RESULT"));
            tasks.submit(contract);
            var lease=tasks.claim(fixture.scope,task,"old-temporal-owner",Duration.ofMinutes(2),tasks.get(fixture.scope,task).revision());
            var grant=new NativeS3NodeClient(URI.create(connection.path("endpoint").asText()),json)
                .signGrant(Map.of("sessionId","session"),"k".repeat(64));
            tasks.privateState(fixture.scope,task,lease,"native-s3:binding",null,
                json.valueToTree(Map.of("nodeId","retired","sessionId","session","grant",grant)));
            tasks.privateState(fixture.scope,task,lease,"native-s3:message-intent",null,json.valueToTree(Map.of("messageId","objective-session")));
            nativeEvidence.register(contract,NativeS3ExecutionProofVerifier.STOP_TYPE,"stop-session",
                Map.of("taskId",task,"sessionId","session","workerStopped",true,"containerId","a".repeat(64),
                    "stopProof",Map.of("containerId","a".repeat(64),"running",false)),List.of(contract.inputSnapshotRef()));
            tasks.cancel(fixture.scope,task,"original cancellation");
            return null;
        });
        projection.flush();
        adapter.recover();
        for(int i=0;i<100&&!authorized(()->adapter.get(fixture.scope,task)).status().terminal();i++)Thread.sleep(50);
        var result=authorized(()->adapter.get(fixture.scope,task));
        assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.CANCELLED);
        assertThat(result.executionControl().executorStopped()).isTrue();
        verify(nativeEvidence).register(any(),eq(NativeS3ExecutionProofVerifier.STOP_TYPE),
            eq("recovery-stop-session"),any(),any());
        assertThat(recoveryStops).hasValue(1);
        assertThat(created).isFalse();
        assertThat(messageCalls).hasValue(0);
    }
    @Test void schedulerChangesCannotCreateAnotherTaskForTheSameRequest(){
        assertThat(authorized(()->capture.taskIdentity(fixture.scope,"native-request","temporal-native-request"))).isEqualTo("temporal-native-request");
        assertThat(authorized(()->capture.taskIdentity(fixture.scope,"native-request","native-request"))).isEqualTo("temporal-native-request");
        assertThat(created).isFalse();
    }
    @Test void lateManifestFromAnotherReceiptSessionCannotBecomeFinal()throws Exception{
        wrongReceiptSession=true;
        String file=fixture.assets.upload(fixture.owner,"input.csv","value\n42\n".getBytes()).id();
        var contract=authorized(()->capture.capture(fixture.scope,"native-stale",List.of(file),"Analyze authorized input","published-model",new CodingTaskContract.Budget("stale-budget",Instant.now().plusSeconds(90).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),-1,-1,-1,-1,"unlimited","USD",-1,1024*1024,1024*1024),Map.of("profile","artifact-integrity-v1"),List.of("RESULT")));
        authorized(()->adapter.submit(contract));CodingTaskOutcome result=null;
        for(int i=0;i<100;i++){result=authorized(()->tasks.get(fixture.scope,"native-stale"));if(result.status().terminal())break;Thread.sleep(100);}
        assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.FAILED);assertThat(result.resultDatasetRef()).isNull();
    }
    @Test void cancellationWaitsForExecutorProofAndCanSettleWhenHistoricalTelemetryIsMissing()throws Exception{
        asynchronousStop=true;missingStoppedTelemetry=true;
        String file=fixture.assets.upload(fixture.owner,"input.csv","value\n42\n".getBytes()).id();
        var facade=new NativeCodingTasks(capture,adapter,new NativeS3Settings(directory.resolve("connection.json").toString(),json),identity,fixture.assets,null,tasks);
        var submitted=authorized(()->facade.submit(fixture.scope,"native-cancel",List.of(file),"Long running task","published-model",List.of("RESULT"),Map.of()));
        String task=submitted.taskId();
        var frozen=authorized(()->tasks.contract(fixture.scope,task));
        assertThat(frozen.budget().hasDeadline()).isFalse();
        for(int i=0;i<100&&messageCalls.get()==0;i++)Thread.sleep(50);
        assertThat(messageCalls).hasValue(1);
        assertThat(grant.path("userManagedDeadline").asBoolean()).isTrue();
        assertThat(grant.path("expiresAt").asLong()).isEqualTo(frozen.budget().deadlineAt().toEpochMilli());
        authorized(()->facade.cancel(fixture.scope,task));
        CodingTaskOutcome result=null;for(int i=0;i<100;i++){result=authorized(()->tasks.get(fixture.scope,task));if(result.status().terminal())break;Thread.sleep(100);}
        assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.CANCELLED);assertThat(result.executionControl().executorStopped()).isTrue();
        assertThat(result.resultDatasetRef()).isNull();assertThat(System.nanoTime()).isGreaterThanOrEqualTo(stopReadyAt);
        var traces=new ArrayList<Map<String,Object>>();var receipt=authorized(()->facade.await(fixture.scope,task,()->false,traces::add));
        var committed=authorized(()->tasks.events(fixture.scope,task,0,128));
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
            if(path.endsWith("/recovery-stop")){
                recoveryStops.incrementAndGet();
                reply(exchange,200,Map.of("sessionId",identity.path("sessionId").asText(),"stopProof",
                    Map.of("containerId","a".repeat(64),"running",false,"allocationFenced",true)));
                return;
            }
            if(path.equals("/v1/sessions")){if(rejectAllocation){reply(exchange,429,Map.of("code","CAPACITY_EXHAUSTED"));return;}created=true;grant=identity;reply(exchange,201,state());return;}
            if(!created){reply(exchange,404,Map.of("code","NOT_FOUND"));return;}
            if(path.endsWith("/status")){if(blockStatus&&messaged){blockedStatus.countDown();releaseStatus.await(5,java.util.concurrent.TimeUnit.SECONDS);}reply(exchange,200,state());return;}
            if(path.endsWith("/observations")||path.endsWith("/receipts")){
                if(sealed&&missingStoppedTelemetry){reply(exchange,410,Map.of("code","HISTORICAL_TELEMETRY_UNAVAILABLE"));return;}
                if(path.endsWith("/receipts")&&grant.has("studioBudget")&&messaged
                        &&exchange.getRequestURI().getRawQuery().contains("afterEventId=0")){
                    var metering=Map.of("totalTokens",64);
                    var row=Map.of("eventId",1,"eventType","model.request.completed","requestId","studio-request",
                        "providerModel","published-model","reasoningEffort","medium","usage",metering,"durationMs",120);
                    reply(exchange,200,Map.of("events",List.of(row),"nextEventId",1,"hasMore",false));return;
                }
                reply(exchange,200,Map.of("events",List.of(),"nextEventId",0,"hasMore",false));return;
            }
            if(path.endsWith("/message-receipt")){
                String key=exchange.getRequestHeaders().getFirst("Idempotency-Key");
                var payload=new LinkedHashMap<String,Object>();
                payload.put("messageId",key);payload.put("receipt",messageReceipts.get(key));
                reply(exchange,200,payload);return;
            }
            if(path.endsWith("/messages")){
                messageCalls.incrementAndGet();
                if(dropBeforeAccept){dropBeforeAccept=false;exchange.close();return;}
                String key=exchange.getRequestHeaders().getFirst("Idempotency-Key");
                var accepted=Map.<String,Object>of("taskId","native-task","status","accepted");
                messageReceipts.putIfAbsent(key,accepted);
                if(repairSchema&&key.equals("delivery-rejection-v1"))
                    output="{\"value\":42}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                messaged=true;
                if(dropAfterAccept){dropAfterAccept=false;exchange.close();return;}
                reply(exchange,202,accepted);return;
            }
            if(path.endsWith("/delivery-content")){
                reply(exchange,200,Map.of("path",outputPath,"data",Base64.getEncoder().encodeToString(output)));return;
            }
            if(path.endsWith("/delivery-preview")||path.endsWith("/delivery")){
                if(path.endsWith("/delivery-preview")&&previewRejections-->0){
                    reply(exchange,422,Map.of("code","DELIVERY_SELECTION_INVALID","message","Schema mismatch","selectionSha256","a".repeat(64)));return;
                }
                manifest=json.valueToTree(new SiloCodingWorkspaceService.Delivery("coding_node_delivery.v1",grant.path("taskId").asText(),grant.path("deliveryBinding").path("runId").asText(),grant.path("tenantId").asText(),grant.path("deliveryBinding").path("inputSnapshotVersion").asText(),request.path("deliveryVersion").asText(),wrongReceiptSession?"old-session":"native-session","native-epoch","NATIVE_TURN_COMPLETED","PENDING_RUNTIME_VALIDATION",List.of(new SiloCodingWorkspaceService.Artifact(outputPath,"RESULT",output.length,UserAssetService.hash(output))),List.of(new SiloCodingWorkspaceService.Receipt("1","turn.completed","native-session","native-epoch"))));
                if(path.endsWith("/delivery"))sealed=true;reply(exchange,200,manifest);return;
            }
            if(path.endsWith("/executions")){reply(exchange,200,Map.of("executions",List.of()));return;}
            if(path.endsWith("/upload")){
                byte[] journal=json.writeValueAsBytes(Map.of("events",List.of(Map.of("eventId","1","eventType","turn.completed","sessionId","native-session","epoch","native-epoch"),Map.of("eventId","2","eventType","delivery.sealed","sessionId","native-session","epoch","native-epoch","payload",Map.of("localStopConfirmed",true)))));
                var artifact=request.path("artifacts").path(0);var part=put(artifact.path("parts").path(0).path("url").asText(),output);
                reply(exchange,200,Map.of("artifacts",List.of(Map.of("path",outputPath,"parts",List.of(Map.of("index",0,"size",output.length,"sha256",UserAssetService.hash(output),"objectVersion",part.get("objectVersion"))),"size",output.length,"sha256",UserAssetService.hash(output))),"manifest",put(request.path("manifestUrl").asText(),json.writeValueAsBytes(manifest)),"evidence",put(request.path("evidenceUrl").asText(),journal)));return;
            }
            if(path.endsWith("/acceptance")){reply(exchange,200,Map.of("accepted",true));return;}
            if(path.endsWith("/cancel")){if(asynchronousStop){if(stopReadyAt==0)stopReadyAt=System.nanoTime()+500_000_000;reply(exchange,202,Map.of("workerState","STOPPING"));}else{sealed=true;reply(exchange,200,state());}return;}
            reply(exchange,404,Map.of("path",path));
        }catch(Exception failed){reply(exchange,500,Map.of("error",failed.toString()));}
    }
    private Map<String,Object> state(){if(stopReadyAt>0&&System.nanoTime()>=stopReadyAt)sealed=true;var state=new LinkedHashMap<String,Object>();state.put("workerState",sealed?"STOPPED":messaged?asynchronousStop?"RUNNING":"COMPLETED":"READY");state.put("native",Map.of("sessionId","native-session","epoch","native-epoch","messageReceipts",true,"status",messaged?asynchronousStop?"running":"turn_completed":"idle"));if(sealed){if(manifest!=null)state.put("delivery",manifest);state.put("stopProof",Map.of("containerId","a".repeat(64),"running",false));}else if(stopReadyAt>0){state.put("stopProof",Map.of("containerId","a".repeat(64),"running",true));}return state;}
    private Map<String,Object> put(String url,byte[] bytes){String key=url.substring("put:".length());var stored=fixture.blobs.put(key,bytes,"application/octet-stream",UserAssetService.hash(bytes));return Map.of("size",bytes.length,"sha256",UserAssetService.hash(bytes),"objectVersion",stored.versionId());}
    private void reply(HttpExchange e,int code,Object value)throws java.io.IOException{byte[] bytes=json.writeValueAsBytes(value);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(code,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}}
}
