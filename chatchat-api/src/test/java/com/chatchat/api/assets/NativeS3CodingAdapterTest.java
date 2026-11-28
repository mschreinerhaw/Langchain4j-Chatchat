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
    final AtomicInteger allocations=new AtomicInteger();
    int startupFailures,startupHttp=503;
    boolean failAfterDispatch,unfencedStartup;
    String dispatchedAnalysisFailure;
    boolean reportedInitialTransportFailure;
    final Map<String,Map<String,Object>> messageReceipts=new java.util.concurrent.ConcurrentHashMap<>();
    volatile boolean wrongReceiptSession;
    final java.util.concurrent.CountDownLatch blockedStatus=new java.util.concurrent.CountDownLatch(1),releaseStatus=new java.util.concurrent.CountDownLatch(1);
    volatile boolean asynchronousStop,missingStoppedTelemetry,rejectAllocation;volatile long stopReadyAt;
    volatile boolean loseLiveStopProof, liveStopLost;
    boolean failFirstRecovery;
    final com.chatchat.api.enterprise.service.TokenQuotaLedger quota=mock(com.chatchat.api.enterprise.service.TokenQuotaLedger.class);
    final com.chatchat.enterprise.service.EnterpriseAdminService enterprise=
        mock(com.chatchat.enterprise.service.EnterpriseAdminService.class);
    byte[] output="{\"value\":42}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @BeforeEach void setup()throws Exception{
        fixture=new SiloCodingWorkspaceServiceTest();fixture.setup();
        when(enterprise.isActiveIdentity(fixture.scope.tenantId(),fixture.scope.userId())).thenReturn(true);
        var tx=new DataSourceTransactionManager(fixture.jdbc.getDataSource());store=new JdbcRuntimeEvidenceStore(fixture.jdbc,json,tx);
        var policy=new AssetStorageProperties();policy.setEndpoint("http://silo.invalid:9000");workspace=new SiloCodingWorkspaceService(fixture.assets,fixture.blobs,policy,store,json);
        scheduler=new ThreadPoolTaskScheduler();scheduler.initialize();
        tasks=new DurableCodingTaskService(store,json,Clock.systemUTC(),identity,(scope,ref)->store.find(scope,NativeS3Evidence.id(ref)).isPresent()||store.snapshot(scope,NativeS3Evidence.id(ref)).isPresent(),new NativeS3ExecutionProofVerifier(store),Runnable::run,scheduler);
        capture=new NativeSiloTaskService(identity,workspace,store,json);
        http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/",this::node);http.start();
        Path config=directory.resolve("connection.json");Files.writeString(config,json.writeValueAsString(Map.of("endpoint","http://127.0.0.1:"+http.getAddress().getPort(),"issuerKey","k".repeat(64),"defaultModel","published-model","modelProfiles",Map.of("published-model","fixed"))));
        var connection=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readString(config));
        connection.putObject("recoveryNodes").put("default",connection.path("endpoint").asText());
        Files.writeString(config,json.writeValueAsString(connection));
        McpLicenseEntitlementPort license=new McpLicenseEntitlementPort(){public CodingDispatchEntitlement codingDispatchEntitlement(){return new CodingDispatchEntitlement(true,"VALID");}};
        nativeEvidence=spy(new NativeS3Evidence(fixture.assets,store,json));
        settings=new NativeS3Settings(config.toString(),json);
        adapter=new NativeS3CodingAdapter(tasks,capture,workspace,settings,nativeEvidence,identity,json,fixture.jdbc,license,tx,enterprise,quota);
    }
    @AfterEach void stop(){if(adapter!=null)adapter.close();if(http!=null)http.stop(0);if(scheduler!=null)scheduler.shutdown();}
    <T>T authorized(java.util.function.Supplier<T> action){return identity.as(fixture.scope,Set.of(READ,SUBMIT,WORKER,EVIDENCE,STOP,RECOVER),action);}
    private CodingTaskOutcome runManagedStartup(String task){
        String file=fixture.assets.upload(fixture.owner,"input.csv","value\n42\n".getBytes()).id();
        return authorized(()->{
            capture.capture(fixture.scope,task,List.of(file),"Analyze input","published-model",
                new CodingTaskContract.Budget("startup",Instant.now().plusSeconds(90)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS),-1,-1,-1,-1,"unlimited","USD",-1,1024*1024,1024*1024),
                Map.of("profile","artifact-integrity-v1"),List.of("RESULT"));
            return adapter.runManaged(fixture.scope,task,new NativeExecutionControl(){
                public String owner(){return "managed-startup-test";}
                public int attempt(){return 1;}
                public String workflowId(){return "startup-test-workflow";}
                public void heartbeat(String phase,String node,String session,String epoch){}
            });
        });
    }

    @Test void transientStartupFailureIsFencedAndRecoveredInTheSameActivity(){
        startupFailures=1;
        var result=runManagedStartup("native-startup-retry");
        assertThat(result.status()).as(result.summary()).isEqualTo(CodingTaskOutcome.Status.COMPLETED);
        assertThat(allocations).hasValue(2);
        assertThat(recoveryStops).hasValue(1);
        assertThat(messageCalls).hasValue(1);
    }

    @Test void managedExecutionStopsBeforeWorkerAllocationWhenIdentityIsExpired(){
        when(enterprise.isActiveIdentity(fixture.scope.tenantId(),fixture.scope.userId())).thenReturn(false);

        var result=runManagedStartup("native-expired-identity");

        assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.FAILED);
        assertThat(allocations).hasValue(0);
        assertThat(messageCalls).hasValue(0);
    }

    @Test void persistentStartupFailureFinishesAfterOneReplacementWithoutDispatch(){
        startupFailures=10;
        var result=runManagedStartup("native-startup-persistent");
        assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.FAILED);
        assertThat(result.summary()).contains("执行容器分配失败","HTTP 503","任务未下发");
        assertThat(allocations).hasValue(2);
        assertThat(recoveryStops).hasValue(2);
        assertThat(messageCalls).hasValue(0);
    }

    @Test void modelAuthorityRejectionDoesNotCreateAReplacement(){
        startupFailures=10;
        startupHttp=403;
        var result=runManagedStartup("native-startup-binding");
        assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.FAILED);
        assertThat(result.summary()).contains("模型绑定失败","HTTP 403");
        assertThat(allocations).hasValue(1);
        assertThat(messageCalls).hasValue(0);
    }

    @Test void uncertainStopNeverPermitsAnotherWorker(){
        startupFailures=1;
        unfencedStartup=true;
        assertThatThrownBy(()->runManagedStartup("native-startup-unfenced")).hasMessageContaining("NATIVE_RECOVERY_STOP_UNVERIFIED");
        assertThat(allocations).hasValue(1);
        assertThat(messageCalls).hasValue(0);
    }

    @Test void recoveryAfterAnAcceptedMessageDoesNotReplayTheTask(){
        failAfterDispatch=true;
        var result=runManagedStartup("native-startup-dispatched");
        assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.FAILED);
        assertThat(allocations).hasValue(1);
        assertThat(messageCalls).hasValue(1);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"STOPPED_DIRECT","STOPPED","404","UNFENCED","MODEL_REJECTED"})
    void dispatchedTaskWithoutReplayClosesAfterVerifiedStop(String failure){
        dispatchedAnalysisFailure=failure;
        var attempt=new AtomicInteger(1);
        String task="native-stopped-without-replay";
        String file=fixture.assets.upload(fixture.owner,"input.csv","value\n42\n".getBytes()).id();
        authorized(()->{
            capture.capture(fixture.scope,task,List.of(file),"Analyze input","published-model",
                new CodingTaskContract.Budget("stopped",CodingTaskContract.Budget.USER_MANAGED_DEADLINE,
                    -1,-1,-1,-1,"unlimited","USD",-1,1024*1024,1024*1024),
                Map.of("profile","artifact-integrity-v1"),List.of("RESULT"));
            var control=new NativeExecutionControl(){
                public String owner(){return "managed-stop-test";}
                public int attempt(){return attempt.get();}
                public String workflowId(){return "stop-test-workflow";}
                public void heartbeat(String phase,String node,String session,String epoch){}
            };
            if(!"STOPPED_DIRECT".equals(failure)){
                assertThatThrownBy(()->adapter.runManaged(fixture.scope,task,control))
                    .isInstanceOf(NativeS3NodeClient.NodeException.class);
                attempt.incrementAndGet();
            }
            if("UNFENCED".equals(failure)){
                assertThatThrownBy(()->adapter.runManaged(fixture.scope,task,control))
                    .hasMessageContaining("NATIVE_RECOVERY_STOP_UNVERIFIED");
                assertThat(tasks.get(fixture.scope,task).status().terminal()).isFalse();
            }else{
                var result=adapter.runManaged(fixture.scope,task,control);
                assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.FAILED);
                assertThat(result.summary()).contains("MODEL_REJECTED".equals(failure)
                    ?"模型请求超过网关大小限制":"模型上游连接或响应超时");
                assertThat(result.usage().consumed().modelCalls()).isEqualTo(2);
                assertThat(tasks.unresolvedOperations(fixture.scope,task)).isEmpty();
            }
            assertThat(allocations).hasValue(1);
            assertThat(messageCalls).hasValue(1);
            assertThat(recoveryStops).hasValue(1);
            return null;
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"STOPPED","404","RECOVERABLE","UNFENCED","PERSISTENT"})
    void interruptedAnalysisRecoversOnlyAfterVerifiedStop(String failure){
        dispatchedAnalysisFailure=failure;
        String task="native-analysis-replay";
        String file=fixture.assets.upload(fixture.owner,"data-supply-manifest.json","{}".getBytes()).id();
        var attempt=new AtomicInteger(1);
        authorized(()->{
            capture.capture(fixture.scope,task,List.of(file),"Analyze the frozen input","published-model",
                new CodingTaskContract.Budget("analysis-replay",Instant.now().plusSeconds(90)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS),-1,-1,-1,-1,"unlimited","USD",-1,1024*1024,1024*1024),
                Map.of("profile","artifact-integrity-v1"),List.of("RESULT"),Map.of(),
                Map.of("analysisMetadataPath","input/data/0/data-supply-manifest.json"));
            var control=new NativeExecutionControl(){
                public String owner(){return "managed-analysis-replay-test";}
                public int attempt(){return attempt.get();}
                public String workflowId(){return "analysis-replay-test-workflow";}
                public void heartbeat(String phase,String node,String session,String epoch){}
            };
            if(!Set.of("RECOVERABLE","PERSISTENT").contains(failure)){
                assertThatThrownBy(()->adapter.runManaged(fixture.scope,task,control))
                    .isInstanceOf(NativeS3NodeClient.NodeException.class);
                attempt.incrementAndGet();
            }
            if("UNFENCED".equals(failure)){
                assertThatThrownBy(()->adapter.runManaged(fixture.scope,task,control))
                    .hasMessageContaining("NATIVE_RECOVERY_STOP_UNVERIFIED");
                assertThat(allocations).hasValue(1);
                assertThat(messageCalls).hasValue(1);
            }else{
                var result=adapter.runManaged(fixture.scope,task,control);
                assertThat(result.status()).as(result.summary()).isEqualTo("PERSISTENT".equals(failure)
                    ?CodingTaskOutcome.Status.FAILED:CodingTaskOutcome.Status.COMPLETED);
                assertThat(allocations).hasValue(2);
                assertThat(messageCalls).hasValue(2);
                assertThat(tasks.privateState(fixture.scope,task,"native-s3:analysis-replay").asText())
                    .isNotEqualTo(grant.path("sessionId").asText());
            }
            assertThat(recoveryStops).hasValue("PERSISTENT".equals(failure)?2:1);
            return null;
        });
    }

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
            identity,json,fixture.jdbc,license,new DataSourceTransactionManager(fixture.jdbc.getDataSource()),enterprise,quota);
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
        byte[] inventory=json.writeValueAsBytes(Map.of("schemaVersion","native-data-supply-inventory.v1"));
        var file=fixture.assets.store(fixture.owner,UserAssetService.Kind.DATASET,"native-stale",fixture.scope.runId(),
            "native-supply-inventory:"+UserAssetService.hash(inventory),"data-supply-manifest.json","application/json",inventory);
        var contract=authorized(()->capture.capture(fixture.scope,"native-stale",List.of(file.id()),"Analyze authorized input","published-model",new CodingTaskContract.Budget("stale-budget",Instant.now().plusSeconds(90).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),-1,-1,-1,-1,"unlimited","USD",-1,1024*1024,1024*1024),Map.of("profile","artifact-integrity-v1"),List.of("RESULT"),Map.of(),Map.of(
                "analysisMetadataPath","input/data/0/data-supply-manifest.json","analysisSchemaInInventory",true)));
        authorized(()->adapter.submit(contract));CodingTaskOutcome result=null;
        for(int i=0;i<100;i++){result=authorized(()->tasks.get(fixture.scope,"native-stale"));if(result.status().terminal())break;Thread.sleep(100);}
        assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.FAILED);assertThat(result.resultDatasetRef()).isNull();
        var facade=new NativeCodingTasks(capture,adapter,settings,identity,fixture.assets,null,tasks);
        var receipt=authorized(()->facade.delivery(fixture.scope,"native-stale"));
        assertThat(receipt).containsEntry("status","FAILED").containsEntry("resultAvailable",false);
        assertThat(receipt.get("dataSupplyEvidence")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("assetId",file.id()).containsEntry("sha256",file.sha256()).containsEntry("level","INPUT_SNAPSHOT_EVIDENCE");
    }
    @Test void cancellationWaitsForExecutorProofAndCanSettleWhenHistoricalTelemetryIsMissing()throws Exception{
        asynchronousStop=true;missingStoppedTelemetry=true;
        var facade=new NativeCodingTasks(capture,adapter,new NativeS3Settings(directory.resolve("connection.json").toString(),json),identity,fixture.assets,null,tasks);
        String target=authorized(()->facade.taskIdentity(fixture.scope,"native-cancel"));
        byte[] inventory=json.writeValueAsBytes(Map.of("schemaVersion","native-data-supply-inventory.v1"));
        var file=fixture.assets.store(fixture.owner,UserAssetService.Kind.DATASET,target,fixture.scope.runId(),
            "native-supply-inventory:"+UserAssetService.hash(inventory),"data-supply-manifest.json","application/json",inventory);
        var submitted=authorized(()->facade.submit(fixture.scope,"native-cancel",List.of(file.id()),"Long running task","published-model",List.of("RESULT"),Map.of(),Map.of(
            "analysisMetadataPath","input/data/0/data-supply-manifest.json","analysisSchemaInInventory",true)));
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
        assertThat(receipt).doesNotContainKey("dataSupplyEvidence");
    }
    @Test void liveNodeWithoutStopProofFallsBackToIndependentExecutor()throws Exception{
        asynchronousStop=true;
        loseLiveStopProof=true;
        String file=fixture.assets.upload(fixture.owner,"input.csv","value\n42\n".getBytes()).id();
        var facade=new NativeCodingTasks(capture,adapter,settings,identity,fixture.assets,null,tasks);
        var submitted=authorized(()->facade.submit(fixture.scope,"native-lost-stop",List.of(file),"Long running task","published-model",List.of("RESULT"),Map.of()));
        String task=submitted.taskId();
        for(int i=0;i<100&&messageCalls.get()==0;i++)Thread.sleep(50);
        assertThat(messageCalls).hasValue(1);
        authorized(()->facade.cancel(fixture.scope,task));
        CodingTaskOutcome result;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        do{
            result=authorized(()->tasks.get(fixture.scope,task));
            if(result.status().terminal())break;
            Thread.sleep(100);
        }while(System.nanoTime()<deadline);
        assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.CANCELLED);
        assertThat(result.executionControl().executorStopped()).isTrue();
        assertThat(recoveryStops).hasValue(1);
        assertThat(messageCalls).hasValue(1);
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
        failFirstRecovery=true;
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
            if(path.equals("/health/live")){reply(exchange,200,Map.of("engine","cline-standalone","version","4.1.27","legacySdkWorker",false));return;}
            String token=exchange.getRequestHeaders().getFirst("Authorization").substring(7);var identity=json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
            if(path.endsWith("/recovery-stop")){
                if(recoveryStops.incrementAndGet()==1&&failFirstRecovery){reply(exchange,503,Map.of("code","RECOVERY_UNAVAILABLE"));return;}
                reply(exchange,200,Map.of("sessionId",identity.path("sessionId").asText(),"stopProof",
                    rejectAllocation||startupFailures>0&&allocations.get()<=startupFailures?Map.of("neverAllocated",true,"running",false,"allocationFenced",!unfencedStartup):
                        Map.of("containerId","a".repeat(64),"running",false,"allocationFenced",!"UNFENCED".equals(dispatchedAnalysisFailure))));
                return;
            }
            if(path.equals("/v1/sessions")){if(rejectAllocation){reply(exchange,429,Map.of("code","CAPACITY_EXHAUSTED"));return;}if(allocations.incrementAndGet()>1&&dispatchedAnalysisFailure!=null)messaged=false;created=true;grant=identity;reply(exchange,201,state());return;}
            if(!created){reply(exchange,404,Map.of("code","NOT_FOUND"));return;}
            if(path.endsWith("/status")){
                if(dispatchedAnalysisFailure!=null&&!identity.path("sessionId").equals(grant.path("sessionId"))){
                    reply(exchange,404,Map.of("code","NOT_FOUND"));return;
                }
                if(messaged&&("PERSISTENT".equals(dispatchedAnalysisFailure)
                        ||dispatchedAnalysisFailure!=null&&allocations.get()==1)){
                    if(Set.of("RECOVERABLE","PERSISTENT").contains(dispatchedAnalysisFailure)){
                        reply(exchange,200,Map.of("workerState","RECOVERABLE"));return;
                    }
                    if(!reportedInitialTransportFailure&&!"STOPPED_DIRECT".equals(dispatchedAnalysisFailure)){
                        reportedInitialTransportFailure=true;
                        reply(exchange,503,Map.of("code","TEMPORARY_FAILURE"));return;
                    }
                    if("404".equals(dispatchedAnalysisFailure)){
                        reply(exchange,404,Map.of("code","NOT_FOUND"));return;
                    }
                    reply(exchange,200,Map.of("workerState","STOPPED"));return;
                }
                if(startupFailures>0&&!identity.path("sessionId").equals(grant.path("sessionId"))){reply(exchange,404,Map.of("code","NOT_FOUND"));return;}
                if(startupFailures>0&&allocations.get()<=startupFailures){
                    reply(exchange,200,Map.of("workerState","START_FAILED","startFailure",
                        Map.of("stage",startupHttp==403?"model_binding":"worker_allocation","httpStatus",startupHttp)));return;
                }
                if(blockStatus&&messaged){blockedStatus.countDown();releaseStatus.await(5,java.util.concurrent.TimeUnit.SECONDS);}
                reply(exchange,200,liveStopLost||failAfterDispatch&&messaged?Map.of("workerState","RECOVERABLE"):state());return;
            }
            if(path.endsWith("/observations")||path.endsWith("/receipts")){
                if(sealed&&missingStoppedTelemetry){reply(exchange,410,Map.of("code","HISTORICAL_TELEMETRY_UNAVAILABLE"));return;}
                if(path.endsWith("/receipts")&&grant.path("taskId").asText().equals("native-stopped-without-replay")&&recoveryStops.get()>0
                        &&exchange.getRequestURI().getRawQuery().contains("afterEventId=0")){
                    String at="2026-11-25T09:12:15Z";
                    var row=Map.of("eventId",1,"eventType","model.request.completed","requestId","timeout-request",
                        "model","published-model","status","FAILED","startedAt",at,"finishedAt",at,"errorCode","UPSTREAM_TIMEOUT");
                    var lateStart=Map.of("eventId",2,"eventType","model.request.started","requestId","late-start",
                        "model","published-model","startedAt",at);
                    if("MODEL_REJECTED".equals(dispatchedAnalysisFailure)){
                        var rejection=Map.of("eventId",3,"eventType","model.request.rejected","code","BROKER_MODEL_PAYLOAD_TOO_LARGE",
                            "timestamp","2026-11-25T09:12:16Z","receivedBytes",1000,"limitBytes",999,"providerDispatched",false);
                        reply(exchange,200,Map.of("events",List.of(row,lateStart,rejection),"nextEventId",3,"hasMore",false));return;
                    }
                    reply(exchange,200,Map.of("events",List.of(row,lateStart),"nextEventId",2,"hasMore",false));return;
                }
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
            if(path.endsWith("/cancel")){if(loseLiveStopProof){liveStopLost=true;reply(exchange,202,Map.of("workerState","STOPPING"));}else if(asynchronousStop){if(stopReadyAt==0)stopReadyAt=System.nanoTime()+500_000_000;reply(exchange,202,Map.of("workerState","STOPPING"));}else{sealed=true;reply(exchange,200,state());}return;}
            reply(exchange,404,Map.of("path",path));
        }catch(Exception failed){reply(exchange,500,Map.of("error",failed.toString()));}
    }
    private Map<String,Object> state(){if(stopReadyAt>0&&System.nanoTime()>=stopReadyAt)sealed=true;var state=new LinkedHashMap<String,Object>();state.put("workerState",sealed?"STOPPED":messaged?asynchronousStop?"RUNNING":"COMPLETED":"READY");state.put("native",Map.of("sessionId","native-session","epoch","native-epoch","messageReceipts",true,"status",messaged?asynchronousStop?"running":"turn_completed":"idle"));if(sealed){if(manifest!=null)state.put("delivery",manifest);state.put("stopProof",Map.of("containerId","a".repeat(64),"running",false));}else if(stopReadyAt>0){state.put("stopProof",Map.of("containerId","a".repeat(64),"running",true));}return state;}
    private Map<String,Object> put(String url,byte[] bytes){String key=url.substring("put:".length());var stored=fixture.blobs.put(key,bytes,"application/octet-stream",UserAssetService.hash(bytes));return Map.of("size",bytes.length,"sha256",UserAssetService.hash(bytes),"objectVersion",stored.versionId());}
    private void reply(HttpExchange e,int code,Object value)throws java.io.IOException{byte[] bytes=json.writeValueAsBytes(value);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(code,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}}
}
