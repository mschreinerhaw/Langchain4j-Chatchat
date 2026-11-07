package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.assets.*;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.cline.ClineModelReceiptStore;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real A2/H2/evidence. Artifact bytes are an immutable in-memory stand-in for Silo. No model inference. */
class NativeCodingWorkspaceServiceTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final CodingInvocationContext identity=new CodingInvocationContext();
    final KernelDataScope scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
    final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    final Map<String,byte[]> blobs=new HashMap<>();
    JdbcRuntimeEvidenceStore evidence;DurableCodingTaskService tasks;RuntimeAssetArchive assets;
    ClineModelReceiptStore models;NativeCodingWorkspaceService service;CodingTaskContract c;CodingJournal.Lease lease;
    <T>T actor(Supplier<T> action){return identity.as(scope,EnumSet.allOf(CodingTaskAccess.Action.class),action);}
    @BeforeEach void setup(){
        var db=new DriverManagerDataSource("jdbc:h2:mem:native-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(db);
        evidence=new JdbcRuntimeEvidenceStore(new JdbcTemplate(db),json,new DataSourceTransactionManager(db));
        tasks=spy(new DurableCodingTaskService(evidence,json,Clock.systemUTC(),identity,(owner,ref)->
            evidence.find(owner,ref.substring("runtime-evidence:".length())).isPresent()||evidence.snapshot(owner,ref.substring("runtime-evidence:".length())).isPresent(),new NativeToolProofVerifier(evidence),
            Runnable::run,new ConcurrentTaskScheduler(timer)));
        assets=mock(RuntimeAssetArchive.class);models=mock(ClineModelReceiptStore.class);
        doAnswer(inv->{put(inv.getArgument(2),((String)inv.getArgument(4)).getBytes(StandardCharsets.UTF_8));return null;})
            .when(assets).code(any(),anyString(),anyString(),anyString(),anyString());
        when(assets.save(any(),anyString(),any(),anyString(),anyString(),any())).thenAnswer(inv->{put(inv.getArgument(3),inv.getArgument(5));return null;});
        when(assets.read(any(),anyString(),anyString())).thenAnswer(inv->Optional.ofNullable(blobs.get(inv.getArgument(2))));
        service=new NativeCodingWorkspaceService(tasks,evidence,assets,models,json);
        var initial=service.seed(scope,"task",Map.of("main.py","value = 1\n","tests/test_main.py","# fixed fixture\n"));
        var payload=Map.<String,Object>of("taskId","task","initial",initial,"modelName","published-model","readOnlyPaths",List.of("tests/test_main.py"));
        evidence.register(new EvidenceRecord(null,"input",scope,NativeCodingWorkspaceService.INPUT_TYPE,"test",evidence.contentHash(payload),payload,null,System.currentTimeMillis(),Map.of()),
            new EvidenceLineage(null,"input",scope,List.of(),"task","test",null,Map.of()));
        c=new CodingTaskContract(CodingProtocol.WORKSPACE_VERSION,"task",scope,
            new CodingTaskContract.Objective("Fix this project and test it",List.of("source-project.json"),List.of("native-tests")),
            "runtime-evidence:input",List.of(NativeCodeWorkspace.PROFILE),"native-workspace:task",
            new CodingTaskContract.Authorization("native-authority",List.copyOf(NativeCodeWorkspace.TOOLS),List.of(),List.of("native-project"),List.of("native-project"),
                CodingTaskContract.NetworkPolicy.NONE,List.of(),List.of(CodingTaskContract.SideEffect.WORKSPACE_WRITE,CodingTaskContract.SideEffect.CODE_EXECUTION)),
            new CodingTaskContract.Budget("native-budget",Instant.now().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),8,262144,16,4,"1","USD",300000,536870912,1048576),
            "native-python","native-tests",new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.WORKSPACE_FILES,"native-result","native-tests",
                List.of("source-project.json"),List.of("application/json"),32,256000,List.of(CodingTaskContract.RequiredEvidence.CODE_ARTIFACT)));
        actor(()->tasks.submit(c));lease=actor(()->tasks.claim(scope,"task","test-worker",Duration.ofMinutes(5),tasks.get(scope,"task").revision()));
        actor(()->service.open(c,lease));
    }
    void put(String source,byte[] bytes){var old=blobs.putIfAbsent(source,bytes.clone());if(old!=null&&!Arrays.equals(old,bytes))throw new IllegalStateException("Immutable artifact differs");}
    @AfterEach void close(){timer.shutdownNow();}
    void admit(String call,String tool,Map<String,Object> input){
        admit(call,tool,input,input);
    }
    void admit(String call,String tool,Map<String,Object> input,Map<String,Object> modelInput){
        var count=new CodingTaskOutcome.Counters(1,2048,0,0,0,0,0,"0");
        actor(()->{var r=tasks.reserve(scope,"task",lease,tasks.get(scope,"task").revision(),"model-1","a".repeat(64),CodingJournal.OperationKind.MODEL,count);
            tasks.startIntent(scope,"task",lease,r.outcome().revision(),"model-1","model-handle");return true;});
        when(models.response(scope,"task","model-1")).thenReturn(Optional.of(Map.of("toolCalls",List.of(Map.of("id",call,"name",tool,"input",modelInput)))));
        String raw;
        try{raw=json.writeValueAsString(input);}catch(Exception error){throw new RuntimeException(error);}
        var request=Map.<String,Object>of("action","TOOL","tool",tool,"operationId",call,"input",input,"inputJson",raw,"intentHash",NativeCodeWorkspace.sha(raw.getBytes(StandardCharsets.UTF_8)));
        actor(()->service.authorize(c,lease,request));
    }
    Map<String,Object> edit(String call,Map<String,String> files){
        var old=actor(()->service.current(c));var previous=(Map<?,?>)old.get("manifest");
        var next=NativeCodeWorkspace.manifest(scope,"task",files,((Number)previous.get("version")).longValue()+1,(String)previous.get("sha256"));
        return Map.of("kind","EDIT","files",files,"manifest",next,"operationId",call,"nativeDiff",Map.of("result","actual SDK diff","intentHash","sdk-input-digest"));
    }
    @Test void durableEditRematerializesAfterRestartAndHistoryReadsPreserveVersions(){
        admit("edit-1","editor",Map.of("path","main.py","old_text","1","new_text","2"));
        var changed=Map.of("main.py","value = 2\n","tests/test_main.py","# fixed fixture\n");
        var proposal=edit("edit-1",changed);actor(()->service.commit(c,lease,proposal));
        var restarted=new NativeCodingWorkspaceService(tasks,evidence,assets,models,json);
        assertThat(actor(()->restarted.open(c,lease)).get("files")).isEqualTo(changed);
        assertThat(actor(()->restarted.history(c))).hasSize(2);
        assertThat(actor(()->restarted.diff(c,1)).get("nativeDiff")).isEqualTo(Map.of("result","actual SDK diff","intentHash","sdk-input-digest"));
        assertThat(actor(()->restarted.commit(c,lease,proposal))).containsEntry("manifestHash",((Map<?,?>)proposal.get("manifest")).get("sha256"));
        assertThat(actor(()->restarted.history(c))).hasSize(2);
    }
    @Test void deniesReadOnlyTestsAndStaleWorkerBeforeAnyArtifactWrite(){
        admit("edit-1","editor",Map.of("path","tests/test_main.py","old_text","fixed","new_text","changed"));
        var proposal=edit("edit-1",Map.of("main.py","value = 1\n","tests/test_main.py","# changed fixture\n"));
        int before=blobs.size();assertThatThrownBy(()->actor(()->service.commit(c,lease,proposal))).hasMessageContaining("PATH_READ_ONLY");
        assertThat(blobs).hasSize(before);
        var stale=new CodingJournal.Lease(lease.owner(),lease.leaseUntil(),lease.fencingToken()+1);
        assertThatThrownBy(()->actor(()->service.commit(c,stale,proposal))).hasMessageContaining("fenced");
        assertThat(blobs).hasSize(before);
        assertThatThrownBy(()->service.current(c)).isInstanceOf(CodingContractException.class);
    }
    @Test void lostStopAcknowledgmentRetriesSettlementWithoutChangingArchivedResult(){
        admit("read-1","read_files",Map.of("files",List.of(Map.of("path","main.py"))));
        var result=Map.<String,Object>of("output",List.of(Map.of("success",true,"result","value = 1\n")),"isError",false);
        doThrow(new IllegalStateException("SETTLEMENT_INTERRUPTED")).doCallRealMethod().when(tasks)
            .settle(eq(scope),eq("task"),any(),anyLong(),eq(NativeCodingWorkspaceService.operation(c,"read-1")),any(),eq(true));
        assertThatThrownBy(()->actor(()->service.finishTool(c,lease,"read-1",result))).hasMessageContaining("SETTLEMENT_INTERRUPTED");
        assertThat(actor(()->tasks.get(scope,"task")).usage().reserved().toolCalls()).isEqualTo(1);
        actor(()->service.finishTool(c,lease,"read-1",result));
        assertThat(actor(()->tasks.get(scope,"task")).usage().consumed().toolCalls()).isEqualTo(1);
        assertThat(actor(()->tasks.get(scope,"task")).usage().reserved().toolCalls()).isZero();
        assertThat(actor(()->service.replay(c,"read-1"))).isEqualTo(result);
        assertThatThrownBy(()->actor(()->service.finishTool(c,lease,"read-1",Map.of("output","fabricated")))).hasMessageContaining("immutable");
    }
    @Test void generalAdmissionCreatesAnEmptyProjectAndKeepsTheOriginalModelBudgetAndDeadlineOnRetry(){
        var published=mock(com.chatchat.common.config.ModelResourceRegistry.class);when(published.defaultChatModel()).thenReturn("original-model");
        var connection=new com.chatchat.common.config.ModelsConfig.ModelConnectionConfig();connection.setModelName("original-provider-model");connection.setBaseUrl("http://approved.invalid/v1");
        when(published.connection(anyString())).thenReturn(connection);
        var python=mock(com.chatchat.common.mcp.python.McpPythonControlPlanePort.class);
        var environment=mock(com.chatchat.common.mcp.python.McpPythonControlPlanePort.EnvironmentView.class);
        when(environment.status()).thenReturn("PUBLISHED");when(environment.networkPolicy()).thenReturn("NONE");
        when(environment.cpuLimit()).thenReturn("1");when(environment.memoryLimit()).thenReturn("512m");when(environment.runtimeUser()).thenReturn("10001:10001");when(environment.versionNumber()).thenReturn(1);
        when(python.environment("native-env")).thenReturn(environment);
        when(python.environmentRuntime("native-env")).thenReturn(new com.chatchat.common.mcp.python.McpPythonControlPlanePort.EnvironmentRuntime(
            "native-env",1,"native-image","sha256:"+"a".repeat(64),"3.11",Map.of()));
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("codingAgent",tasks);
        var factory=new NativeCodingTaskService(tasks,service,evidence,beans.getBeanProvider(CodingAgentSPI.class),python,published,json,"native-env");
        factory.setAdmissionEnabled(true);
        String task=NativeCodingController.taskId(List.of("tenant","owner"),"generic-1");
        var owner=new KernelDataScope("tenant","owner",null,null,task,null,Map.of());
        var request=new NativeCodingTaskService.Request("Create a small multi-file Python project",Map.of(),List.of(),null,null,"python -m unittest discover -s tests -v");
        identity.as(owner,EnumSet.allOf(CodingTaskAccess.Action.class),()->factory.submit(owner,task,request));
        var original=identity.as(owner,EnumSet.allOf(CodingTaskAccess.Action.class),()->tasks.contract(owner,task));
        assertThat(original.authorization().allowedTools()).contains("editor","apply_patch","search_codebase","run_commands").doesNotContain("python_execute");
        assertThat(original.budget().maxModelCalls()).isEqualTo(8);assertThat(original.budget().maxCodeExecutions()).isEqualTo(4);
        assertThat(original.budget().maxModelTokens()).isEqualTo(262144);
        assertThat((Map<String,Object>)original.runtimeContext().get("model")).containsEntry("selectedModel","original-model").containsEntry("maxTokens",8192);
        when(published.defaultChatModel()).thenReturn("changed-default");
        factory.setAdmissionEnabled(false);
        identity.as(owner,EnumSet.allOf(CodingTaskAccess.Action.class),()->factory.submit(owner,task,request));
        assertThat(identity.as(owner,EnumSet.allOf(CodingTaskAccess.Action.class),()->tasks.contract(owner,task))).isEqualTo(original);
        assertThat(identity.as(owner,EnumSet.allOf(CodingTaskAccess.Action.class),()->service.current(original)).get("files")).isEqualTo(Map.of());
        var altered=new NativeCodingTaskService.Request(request.instruction(),Map.of("main.py","print(1)\n"),List.of(),null,null,request.acceptanceCommand());
        assertThatThrownBy(()->identity.as(owner,EnumSet.allOf(CodingTaskAccess.Action.class),()->factory.submit(owner,task,altered))).hasMessageContaining("identity conflict");
        String another=NativeCodingController.taskId(List.of("tenant","owner"),"generic-2");
        var nextOwner=new KernelDataScope("tenant","owner",null,null,another,null,Map.of());
        assertThatThrownBy(()->identity.as(nextOwner,EnumSet.allOf(CodingTaskAccess.Action.class),()->factory.submit(nextOwner,another,request))).hasMessageContaining("admission is disabled");
        factory.setAdmissionEnabled(true);when(environment.memoryLimit()).thenReturn("2g");int stored=blobs.size();
        assertThatThrownBy(()->identity.as(nextOwner,EnumSet.allOf(CodingTaskAccess.Action.class),()->factory.submit(nextOwner,another,request))).hasMessageContaining("exceeds the fixed");
        assertThat(blobs).hasSize(stored);
    }
    @Test void aNativeCommandCannotSubstituteItsModelAdmittedCommandOrFrozenFiles(){
        admit("command-1","run_commands",Map.of("commands",List.of("python -m unittest")));
        var python=mock(com.chatchat.common.mcp.python.McpPythonControlPlanePort.class);
        var bridge=mock(com.chatchat.api.runtime.coding.execution.CodingPythonExecutionBridge.class);
        var commands=new NativeCodingCommandService(tasks,service,bridge,python,json);
        var tree=actor(()->service.current(c));
        var bad=Map.<String,Object>of("operationId","command-1:command:1","command","cat /etc/shadow","manifest",tree.get("manifest"),"files",tree.get("files"));
        assertThatThrownBy(()->actor(()->commands.execute(c,lease,bad))).hasMessageContaining("differs from the actual model");
        var modified=new LinkedHashMap<>(bad);modified.put("command","python -m unittest");modified.put("files",Map.of("main.py","print('substituted')\n"));
        assertThatThrownBy(()->actor(()->commands.execute(c,lease,modified))).hasMessageContaining("CODE_SNAPSHOT_HASH_MISMATCH");
        verifyNoInteractions(python,bridge);
    }
    @Test void aWorkerCannotChangeToolArgumentsWhileKeepingARealModelCallIdentity(){
        var before=actor(()->service.current(c));
        assertThatThrownBy(()->admit("edit-1","editor",Map.of("path","main.py","new_text","unapproved"),
            Map.of("path","main.py","old_text","1","new_text","2"))).hasMessageContaining("input differs from the actual model receipt");
        assertThat(actor(()->service.current(c))).isEqualTo(before);
        assertThat(actor(()->tasks.get(scope,"task")).usage().reserved().toolCalls()).isZero();
    }
    @Test void finalEvidenceCommitFailureRetainsTheOriginalOutcomeAndHistoryOnRetry(){
        var factory=new NativeCodingTaskService(tasks,service,evidence,new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(CodingAgentSPI.class),
            mock(com.chatchat.common.mcp.python.McpPythonControlPlanePort.class),mock(com.chatchat.common.config.ModelResourceRegistry.class),json,"");
        doThrow(new IllegalStateException("FINAL_COMMIT_ACK_UNKNOWN")).doCallRealMethod().when(tasks).commitOutcome(eq(scope),eq("task"),any(),anyLong(),any());
        assertThatThrownBy(()->actor(()->factory.complete(c,lease,Map.of("status","failed")))).hasMessageContaining("FINAL_COMMIT_ACK_UNKNOWN");
        assertThat(actor(()->tasks.get(scope,"task")).status()).isEqualTo(CodingTaskOutcome.Status.RUNNING);
        // A retry cannot replace an already validated final intent with a different SDK claim.
        var result=actor(()->factory.complete(c,lease,Map.of("status","completed")));
        assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.FAILED);
        assertThat(evidence.find(scope,"native-final:task:validation").orElseThrow().payload()).containsEntry("declaredCommandPassed",false);
        assertThat(actor(()->service.current(c)).get("files")).isEqualTo(Map.of("main.py","value = 1\n","tests/test_main.py","# fixed fixture\n"));
        assertThat(actor(()->factory.complete(c,lease,Map.of("status","completed")))).isEqualTo(result);
    }
}
