package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.analysis.PreauthorizedStructuredDataOperator;
import com.chatchat.api.runtime.analysis.RegisteredToolAnalysisOperator;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.analysis.CodingAnalysisSqlService;
import com.chatchat.api.runtime.coding.data.CodingDataSupplyProofVerifier;
import com.chatchat.api.runtime.coding.data.CodingDataSupplyProperties;
import com.chatchat.api.runtime.coding.data.CodingDataSupplyService;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector;
import com.chatchat.api.runtime.coding.evidence.CodingFinancialResultVerifier;
import com.chatchat.api.runtime.coding.evidence.CodingValidationPlanService;
import com.chatchat.api.runtime.coding.evidence.CodingSchemaResultVerifier;
import com.chatchat.api.runtime.coding.execution.CodingPythonExecutionBridge;
import com.chatchat.api.runtime.coding.execution.McpPythonExecutionProofVerifier;
import com.chatchat.api.runtime.coding.fixture.CodingLedgerFixture;
import com.chatchat.api.runtime.coding.workspace.CodingWorkspaceProofVerifier;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.analysis.evidence.ToolAnalysisEvidence;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.security.InternalSecretCipher;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** G3: actual SDK process + signed HTTP + Java/A2 + real Docker + independent Java finance validation.
 * Only model provider and external governed template source are deterministic doubles.
 * Reuses the G2 genuine execution fixtures; G1/G2 are run separately by the gate script.
 */
@EnabledIfSystemProperty(named="coding.realDocker",matches="true")
class CodingAnalysisDockerGateTest extends CodingDockerFixture {
    final String sharedSecret="0123456789abcdef0123456789abcdef";
    final CodingInvocationContext identity=new CodingInvocationContext();
    final AtomicLong clockOffset=new AtomicLong();
    final Clock recoveryClock=new Clock(){public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}public Clock withZone(java.time.ZoneId z){return this;}public Instant instant(){return Instant.now().plusMillis(clockOffset.get());}};
    CodingInputSnapshotService inputs;CodingValidationPlanService plans;CodingDataSupplyService supply;
    CodingDataSupplyProperties dataPolicy;ClineModelReceiptStore modelReceipts;ClineCodingAdapter adapter;
    CodingNodeHttpFixture node;CodingTaskContract contract;
    final AtomicInteger turns=new AtomicInteger();
    final ClineRuntimeModelBridge provider=mock(ClineRuntimeModelBridge.class);
    <T>T actor(java.util.function.Supplier<T> work){return identity.as(scope,EnumSet.allOf(CodingTaskAccess.Action.class),work);}
    @BeforeEach void wireRuntime() throws Exception {
        json.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        when(port.uploadDataFile(anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(call->{
            var saved=dataFiles.store(call.getArgument(0),call.getArgument(1),call.getArgument(2),Base64.getUrlEncoder().withoutPadding().encodeToString(((String)call.getArgument(3)).getBytes(StandardCharsets.UTF_8)),call.getArgument(4),InternalSecretCipher.encryptBytes(call.getArgument(5),sharedSecret));
            return new McpPythonControlPlanePort.DataFileResult(saved.id(),saved.storagePath(),saved.pythonPath(),saved.fileSize(),saved.fileHash(),saved.status());
        });
        when(port.downloadDataFile(anyString(),anyString(),anyString())).thenAnswer(call->
            InternalSecretCipher.decryptBytes(dataFiles.content(call.getArgument(0),call.getArgument(1),call.getArgument(2)),sharedSecret));
        dataPolicy=new CodingDataSupplyProperties();dataPolicy.setEnabled(true);dataPolicy.setMaxRounds(2);
        dataPolicy.setGrants(List.of(new CodingDataSupplyProperties.Grant("tenant","owner","finance","ledger","finance","test",Map.of("date",List.of("2026-10-13")),List.of("date","assets","netFlow","returnRate"))));
        newRuntime();
    }
    void newRuntime() throws Exception {
        evidence=evidenceStore();modelReceipts=new ClineModelReceiptStore(evidence);
        var dataProof=new CodingDataSupplyProofVerifier(evidence);var pythonProof=new McpPythonExecutionProofVerifier(port,json);
        var sqlProof=new CodingWorkspaceProofVerifier(evidence);
        tasks=new DurableCodingTaskService(evidence,json,recoveryClock,identity,(s,ref)->evidence.find(s,ref.substring(17)).isPresent()||evidence.snapshot(s,ref.substring(17)).isPresent(),
            (s,t,o,h,r)->pythonProof.confirmsStopped(s,t,o,h,r)||dataProof.confirmsStopped(s,t,o,h,r)||sqlProof.confirmsStopped(s,t,o,h,r)||modelReceipts.confirmsStopped(s,t,o,h,r),Runnable::run,new ConcurrentTaskScheduler(timer));
        inputs=new CodingInputSnapshotService(evidence,port,identity,json);
        var financial=new CodingFinancialResultVerifier();financial.setPython(port);
        plans=new CodingValidationPlanService(evidence,inputs,identity,json,List.of(financial,new CodingSchemaResultVerifier()));
        var tool=mock(RegisteredToolAnalysisOperator.class);
        when(tool.execute(any(),any(),isNull())).thenAnswer(call->{
            var rows=new ArrayList<Map<String,Object>>(List.of(new LinkedHashMap<>(Map.of("date","2026-10-13","assets","121.00","netFlow","0.00","returnRate","0.10000000"))));
            if(contract!=null&&contract.taskId().equals("combined")){rows.get(0).put("assets","121.001");rows.get(0).put("returnRate","0.20000000");rows.add(new LinkedHashMap<>(rows.get(0)));}
            String content=json.writeValueAsString(Map.of("success",true,"data",Map.of("rowCount",rows.size(),"rows",rows,"complete",true)));
            return new WorkflowExecutionResult(List.of(new ToolAnalysisEvidence("source-ledger-v2","sql_template_analysis_execute","source-call",content,Map.of())),Map.of("status","SUCCESS"),List.of());
        });
        supply=new CodingDataSupplyService(dataPolicy,tasks,new PreauthorizedStructuredDataOperator(tool,json),inputs,evidence,json,
            (t,u,s,d,tags)->new com.chatchat.common.retrieval.SkillExecutionScopePort.EffectiveScope(List.of(),List.of(),List.of("finance-reader"),true,true));
        bridge=new CodingPythonExecutionBridge(tasks,port,json);bridge.setDataSupply(supply);
        collector=new CodingEvidenceCollector(evidence,evidence,tasks,port,json);collector.setDataSupply(supply);collector.setValidationPlans(plans);
        var model=new ClineModelCallbackController(identity,tasks,provider,modelReceipts,inputs,plans,collector,json);model.setDataSupply(supply);
        if(System.getProperty("coding.sqlglotPython")!=null)model.setAnalysisSql(new CodingAnalysisSqlService(tasks,evidence,
            new com.chatchat.api.datascience.sql.SqlGlotToolchain(System.getProperty("coding.sqlglotPython"),json)));
        var pythonCallback=new ClinePythonCallbackController(identity,tasks,inputs,plans,bridge,collector,json);
        pythonCallback.setFeedback(new com.chatchat.api.runtime.coding.execution.CodingExecutionFeedbackService(tasks,bridge,json,2));
        node=new CodingNodeHttpFixture(json,model,pythonCallback);
        adapter=new ClineCodingAdapter(tasks,bridge,inputs,plans,collector,modelReceipts,node.uri(),sharedSecret,json,recoveryClock);adapter.setDataSupply(supply);
        adapter.setLicenseEntitlements(new com.chatchat.common.mcp.license.McpLicenseEntitlementPort() {
            @Override public CodingDispatchEntitlement codingDispatchEntitlement() {
                return new CodingDispatchEntitlement(true, "VALID");
            }
        });
    }
    @AfterEach void stopNode() throws Exception {if(node!=null)node.close();}
    void prepare(String fault,boolean longRunning) throws Exception {
        var rows=CodingLedgerFixture.cleanRows();rows.remove(1); // explicit missing observation requires authorized acquisition
        if(fault.equals("type")||fault.equals("combined"))rows.get(0).put("assets",110.0);
        if(fault.equals("missing")||fault.equals("combined"))rows.remove(1);
        if(fault.equals("duplicate"))rows.add(new LinkedHashMap<>(rows.get(1)));
        if(fault.equals("precision"))rows.get(1).put("assets","133.101");
        if(fault.equals("return"))rows.get(1).put("returnRate","0.20000000");
        var initialTool=mock(RegisteredToolAnalysisOperator.class);
        String initialJson=json.writeValueAsString(Map.of("success",true,"data",Map.of("rowCount",rows.size(),"rows",rows,"complete",true)));
        when(initialTool.execute(any(),any(),isNull())).thenReturn(new WorkflowExecutionResult(List.of(new ToolAnalysisEvidence("initial-ledger-receipt","sql_template_analysis_execute","initial-call",initialJson,Map.of())),Map.of("status","SUCCESS"),List.of()));
        var initialContext=new com.chatchat.common.runtime.analysis.model.AnalysisContext("Initial authorized ledger acquisition",scope,"finance",List.of(),List.of(),List.of("finance-reader"),null,
            Map.of(PreauthorizedStructuredDataOperator.TEMPLATE_ID,"ledger-initial",PreauthorizedStructuredDataOperator.ASSET_NAME,"finance",PreauthorizedStructuredDataOperator.ENVIRONMENT,"test",PreauthorizedStructuredDataOperator.PARAMETERS,Map.of()));
        var initial=new PreauthorizedStructuredDataOperator(initialTool,json).execute(initialContext,new com.chatchat.common.runtime.analysis.model.AnalysisScope("tenant","owner",List.of("finance-reader"),List.of(),Map.of()),null);
        assertThat(initial.evidence()).hasSize(1);
        List<Map<String,Object>> acquired=json.convertValue(json.readTree(initial.evidence().get(0).content()).path("data").path("rows"),new com.fasterxml.jackson.core.type.TypeReference<>(){});
        var snapshot=actor(()->inputs.capture(scope,fault,List.of(new Dataset("ledger:v1",Map.of("sourceEvidenceId",initial.evidence().get(0).evidenceId()),acquired)),"ledger:v1","decision","workspace",100,100000));
        String plan=actor(()->plans.register(scope,fault,snapshot,new CodingValidationPlanService.Plan("runtime-financial-ledger","v1",CodingLedgerFixture.validationRules())));
        contract=new CodingTaskContract(CodingProtocol.ANALYSIS_VERSION,fault,scope,new CodingTaskContract.Objective("Inspect supplied ledger, request missing 2026-10-13 from the permitted Java template. Preserve all source values; never repair business data. Produce sorted ledger rows and report independent validation limits.",List.of("rows.jsonl"),List.of(plan)),snapshot.reference(),List.of(supply.policyRef(scope)),"workspace",
            new CodingTaskContract.Authorization("decision",List.of("python_execute","data_request"),List.of(snapshot.fileId()),List.of(),List.of(),CodingTaskContract.NetworkPolicy.NONE,List.of(),List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
            new CodingTaskContract.Budget("budget-"+fault,Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),8,32768,8,4,"10","USD",200000,256*1024*1024,1000000),"env",plan,
            new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,"schema:ledger","validation:ledger",List.of("rows.jsonl"),List.of("application/x-ndjson"),100,100000,List.of(CodingTaskContract.RequiredEvidence.values())));
        when(provider.complete(any(),any())).thenAnswer(call->{
            int turn=turns.incrementAndGet();List<Map<String,Object>> calls;
            if(turn==1)calls=List.of(Map.of("id","technical-fault","name","python_execute","input",Map.of("fileName","main.py","source",longRunning?"import time\nwhile True: time.sleep(1)":"raise ValueError('D3 injected technical error')")));
            else if(turn==2){assertThat(((JsonNode)call.getArgument(1)).toString()).contains("ValueError","PROGRAM_ERROR");
                calls=List.of(Map.of("id","missing-data","name","data_request","input",Map.of("requestId","missing-date","datasetRefs",List.of("ledger:v1"),"authorizedTemplateId","ledger","parameterBindings",Map.of("date","2026-10-13"),"requiredFields",List.of("date","assets","netFlow","returnRate"),"reason","Missing trading date","expectedUsage","Complete explicitly authorized calendar coverage")));}
            else if(turn==3){
                var snapshots=actor(()->supply.snapshots(contract,null));
                String paths=json.writeValueAsString(snapshots.stream().map(s->"/data/input/"+s.fileId()+"/input.jsonl").toList());
                String source="import json,pathlib\nrows=[]\nfor p in "+paths+":\n rows.extend(json.loads(line) for line in pathlib.Path(p).read_text().splitlines())\nrows.sort(key=lambda r:r['date'])\n# Technical representation only; original bytes/types are independently checked by Java.\nfor r in rows:\n for k in ('assets','netFlow','returnRate'): r[k]=str(r[k])\npathlib.Path('/workspace/output/results/rows.jsonl').write_text(''.join(json.dumps(r)+'\\n' for r in rows))\n";
                Object proposal=Map.of("fileName","main.py","source",source);
                if(contract.runtimeContext().containsKey("codingLifecycle")) {
                    String aggregate="import json,pathlib,decimal\nrows=[]\nfor p in "+paths+":\n rows.extend(json.loads(line) for line in pathlib.Path(p).read_text().splitlines())\ntotal=sum(decimal.Decimal(row['assets']) for row in rows)\npathlib.Path('/workspace/output/results/rows.jsonl').write_text(json.dumps({'assetsTotal':str(total)})+'\\n')\n";
                    proposal=Map.of("repair",Map.of("parentCodeVersion",1,
                        "baseCodeSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("raise ValueError('D3 injected technical error')".getBytes(StandardCharsets.UTF_8))),
                        "edits",List.of(Map.of("oldText","raise ValueError('D3 injected technical error')","newText",aggregate))));
                }
                calls=List.of(Map.of("id","repaired-program","name","python_execute","input",proposal));
            }else calls=List.of();
            return Map.of("text",turn>3?"Source values preserved. Runtime independently validates the ledger.":"","toolCalls",calls,"usage",Map.of("inputTokens",20,"outputTokens",20));
        });
    }
    CodingTaskOutcome awaitTerminal() throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);CodingTaskOutcome state;
        do{Thread.sleep(50);state=actor(()->adapter.get(scope,contract.taskId()));if(state.status().terminal())return state;
            var remote=node.state(contract,actor(()->tasks.ownership(scope,contract.taskId())).fencingToken());
            if("FAILED".equals(remote.path("status").asText()))throw new AssertionError("Worker failed: "+remote+"; A2: "+state);
        }while(System.nanoTime()<until);
        throw new AssertionError("Coding task did not terminate: "+state+"; worker: "+node.log());
    }
    @Test void negotiatedLifecycleRepairsThroughRealSdkAndSandboxWithVersionLineage() throws Exception {
        prepare("clean",false);
        var c=contract;
        String plan=actor(()->plans.register(scope,c.taskId(),inputs.requireAuthorized(c),new CodingValidationPlanService.Plan(
            "runtime-result-schema","v1",Map.of("rowSchema",Map.of("type","object","properties",Map.of("assetsTotal",Map.of("type","string")),"required",List.of("assetsTotal"),"additionalProperties",false)))));
        contract=new CodingTaskContract(c.schemaVersion(),c.taskId(),c.scope(),c.objective(),c.inputSnapshotRef(),c.metadataRefs(),c.workspaceRef(),
            c.authorization(),c.budget(),c.environmentRef(),plan,c.outputContract(),
            Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT",
                "codingLifecycle",Map.of("schemaVersion","runtime_os.code_lifecycle.v1","patchOnlyAfterFirst",true)));
        actor(()->adapter.submit(contract));
        var outcome=awaitTerminal();
        assertThat(outcome.status()).isEqualTo(CodingTaskOutcome.Status.COMPLETED);
        assertThat(outcome.validationStatus()).isEqualTo(CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
        assertThat(outcome.usage().consumed().codeExecutions()).isEqualTo(2);
        assertThat(actor(()->tasks.events(scope,contract.taskId(),0,256)).toString()).contains("CODE_READY","PATCH","parentCodeVersion");
        assertThat(actor(()->collector.readResultPage(scope,contract.taskId(),outcome.resultDatasetRef(),0,1)).rows())
            .containsExactly(Map.of("assetsTotal","364.10"));
        int modelCalls=node.callbackCounts.get("model").get();
        assertThat(modelCalls).isEqualTo(turns.get());
        if(Boolean.parseBoolean(System.getProperty("coding.nativeSdk","true"))) {
            assertThat(node.callbackCounts).doesNotContainKeys("reserve-model","settle-model");
        } else {
            assertThat(node.callbackCounts.get("reserve-model").get()).isEqualTo(modelCalls);
            assertThat(node.callbackCounts.get("settle-model").get()).isEqualTo(modelCalls);
        }
        System.out.println("SDK_RPC_COMPARISON "+json.writeValueAsString(Map.of("nativeSdk",Boolean.parseBoolean(System.getProperty("coding.nativeSdk","true")),
            "models",modelCalls,"callbacks",node.callbackCounts.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->e.getValue().get())),
            "codeExecutions",outcome.usage().consumed().codeExecutions(),"modelTokens",outcome.usage().consumed().modelTokens())));
    }
    @ParameterizedTest @ValueSource(strings={"clean","type","missing","duplicate","precision","return","combined"})
    void rawLedgerCopiesAreRejectedAfterDynamicSupplyAndTechnicalRepair(String fault) throws Exception {
        prepare(fault,false);actor(()->adapter.submit(contract));var outcome=awaitTerminal();
        assertThat(outcome.status()).isEqualTo(CodingTaskOutcome.Status.FAILED);
        assertThat(outcome.validationStatus()).isEqualTo(CodingTaskOutcome.ValidationStatus.FAILED);
        assertThat(outcome.resultDatasetRef()).isNull();
        assertThat(outcome.usage().consumed().modelCalls()).isEqualTo(3);assertThat(outcome.usage().consumed().codeExecutions()).isEqualTo(2);assertThat(outcome.usage().consumed().toolCalls()).isEqualTo(3);
        for(String ref:List.of(outcome.inputSnapshotRef(),outcome.codeArtifactRef(),outcome.executionReceiptRef(),outcome.validationRecordRef(),outcome.evidenceBundleRef()))
            assertThat(evidence.find(scope,ref.substring(17)).isPresent()||evidence.snapshot(scope,ref.substring(17)).isPresent()).isTrue();
        var validation=evidence.find(scope,outcome.validationRecordRef().substring(17)).orElseThrow();
        var checks=(Map<?,?>)validation.payload().get("checks");assertThat(checks.get("sourceReconciled")).isEqualTo(true);
        assertThat(checks.get("copiedSourceContentDetected")).isEqualTo(true);
        var faults=(List<String>)checks.get("faults");
        if(fault.equals("clean"))assertThat(faults).isEmpty();
        else if(fault.equals("combined"))assertThat(faults).contains("FIELD_TYPE","MISSING_TRADING_DATE","DUPLICATE_DATE","DECIMAL_PRECISION","RETURN_RECONCILIATION");
        else assertThat(faults).contains(Map.of("type","FIELD_TYPE","missing","MISSING_TRADING_DATE","duplicate","DUPLICATE_DATE","precision","DECIMAL_PRECISION","return","RETURN_RECONCILIATION").get(fault));
        // No copied ledger rows are admitted, even when their financial checks happened to pass.
        assertThat(actor(()->tasks.events(scope,contract.taskId(),0,128))).isNotEmpty();
    }
    @Test void workerAndJavaComponentRestartRestoreSettledSdkBoundaryWithoutBudgetReset() throws Exception {
        prepare("clean",false);var saved=new CountDownLatch(1);var release=new CountDownLatch(1);
        node.afterCheckpoint=checkpoint->{if(checkpoint.path("modelOrdinal").asLong()==1){saved.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}};
        actor(()->adapter.submit(contract));assertThat(saved.await(30,TimeUnit.SECONDS)).isTrue();
        node.stopWorker();release.countDown();node.close();node=null;
        clockOffset.set(TimeUnit.MINUTES.toMillis(6));newRuntime();
        var before=actor(()->tasks.get(scope,contract.taskId()));actor(()->adapter.resume(scope,contract.taskId()));var outcome=awaitTerminal();
        assertThat(outcome.status()).isEqualTo(CodingTaskOutcome.Status.FAILED);assertThat(outcome.usage().budgetId()).isEqualTo(before.usage().budgetId());
        assertThat(outcome.usage().consumed().modelCalls()).isEqualTo(3);assertThat(outcome.usage().consumed().codeExecutions()).isEqualTo(2);
        assertThat(actor(()->tasks.ownership(scope,contract.taskId())).fencingToken()).isEqualTo(2);
        assertThat(outcome.validationStatus()).isEqualTo(CodingTaskOutcome.ValidationStatus.FAILED);
    }
    @Test void cancellationStopsRealSandboxBehindLiveSdkAndPublishesNoDataset() throws Exception {
        prepare("cancel",true);actor(()->adapter.submit(contract));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(actor(()->tasks.startedCodeOperations(scope,contract.taskId())).isEmpty()&&System.nanoTime()<until)Thread.sleep(25);
        assertThat(actor(()->tasks.startedCodeOperations(scope,contract.taskId()))).hasSize(1);
        String operation=actor(()->tasks.startedCodeOperations(scope,contract.taskId())).get(0);
        McpPythonControlPlanePort.OperationView running=null;
        do {try{running=managed.get(scope.tenantId(),scope.userId(),scope.runId(),contract.taskId(),operation);}catch(NullPointerException notAcceptedYet){}Thread.sleep(25);}
        while((running==null||!"RUNNING".equals(running.status()))&&System.nanoTime()<until);
        assertThat(running).isNotNull();assertThat(running.status()).isEqualTo("RUNNING");
        var inspectFile=temporary.resolve("running-container-inspect.json");
        var inspect=new ProcessBuilder("docker","inspect",running.containerId()).redirectErrorStream(true).redirectOutput(inspectFile.toFile()).start();
        if(!inspect.waitFor(5,TimeUnit.SECONDS)){inspect.destroyForcibly();throw new AssertionError("Docker inspect timed out");}
        assertThat(inspect.exitValue()).isZero();byte[] config=java.nio.file.Files.readAllBytes(inspectFile);
        var host=json.readTree(config).get(0).path("HostConfig");
        assertThat(host.path("NetworkMode").asText()).isEqualTo("none");assertThat(host.path("ReadonlyRootfs").asBoolean()).isTrue();
        assertThat(host.path("Memory").asLong()).isEqualTo(256L*1024*1024);assertThat(host.path("NanoCpus").asLong()).isEqualTo(1_000_000_000L);assertThat(host.path("PidsLimit").asLong()).isEqualTo(256);
        assertThat(json.readTree(config).get(0).path("Config").path("User").asText()).isEqualTo("10001:10001");
        actor(()->adapter.cancel(scope,contract.taskId(),"G3 user cancellation"));
        CodingTaskOutcome outcome=null;until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        do{Thread.sleep(100);outcome=actor(()->adapter.resume(scope,contract.taskId()));}while(!outcome.status().terminal()&&System.nanoTime()<until);
        assertThat(outcome.status()).isEqualTo(CodingTaskOutcome.Status.CANCELLED);assertThat(outcome.resultDatasetRef()).isNull();
        assertThat(managed.get(scope.tenantId(),scope.userId(),scope.runId(),contract.taskId(),operation).executorStopped()).isTrue();
    }
    @Test @EnabledIfSystemProperty(named="coding.sqlglotPython",matches=".+")
    void sqlAnalysisValidatesWithSqlGlotAndExecutesOnlyInSandboxSqlite() throws Exception {
        prepare("clean",false);var c=contract;
        contract=new CodingTaskContract(c.schemaVersion(),c.taskId(),c.scope(),c.objective(),c.inputSnapshotRef(),c.metadataRefs(),c.workspaceRef(),
            new CodingTaskContract.Authorization(c.authorization().decisionRef(),List.of("python_execute","data_request","sql_validate"),c.authorization().allowedDataRefs(),List.of(),List.of(),CodingTaskContract.NetworkPolicy.NONE,List.of(),c.authorization().allowedSideEffects()),
            c.budget(),c.environmentRef(),c.validationPlanRef(),c.outputContract());
        String sql="SELECT payload, COUNT(*) OVER (ORDER BY day) AS observed_days FROM ledger ORDER BY day";
        doAnswer(call->{
            int turn=turns.incrementAndGet();List<Map<String,Object>> calls;
            if(turn==1)calls=List.of(Map.of("id","sql-data","name","data_request","input",Map.of("requestId","sql-missing-date","datasetRefs",List.of("ledger:v1"),"authorizedTemplateId","ledger","parameterBindings",Map.of("date","2026-10-13"),"requiredFields",List.of("date","assets","netFlow","returnRate"),"reason","SQL needs complete calendar input","expectedUsage","Sandbox SQLite aggregation")));
            else if(turn==2)calls=List.of(Map.of("id","sql-parse","name","sql_validate","input",Map.of("dialect","ansi","sql",sql)));
            else if(turn==3){
                assertThat(((JsonNode)call.getArgument(1)).toString()).contains("sqlExecuted","false","sqlglot");
                String paths=json.writeValueAsString(actor(()->supply.snapshots(contract,null)).stream().map(s->"/data/input/"+s.fileId()+"/input.jsonl").toList());
                // Produce an actual SQL-derived metric, while preserving every original value.
                // A plain source copy must continue to fail computed-result admission.
                String source="import json,pathlib,sqlite3\nc=sqlite3.connect(':memory:')\nc.execute('CREATE TABLE ledger(payload TEXT, day TEXT)')\nfor p in "+paths+":\n for line in pathlib.Path(p).read_text().splitlines():\n  r=json.loads(line)\n  c.execute('INSERT INTO ledger VALUES (?,?)',(line,r['date']))\nrows=[]\nfor payload, observed_days in c.execute('"+sql+"'):\n r=json.loads(payload)\n r['observedTradingDays']=observed_days\n rows.append(r)\npathlib.Path('/workspace/output/results/rows.jsonl').write_text(''.join(json.dumps(r)+'\\n' for r in rows))\nprint('sandbox_sqlite_rows',len(rows))\n";
                calls=List.of(Map.of("id","sql-run","name","python_execute","input",Map.of("fileName","main.py","source",source)));
            }else {assertThat(((JsonNode)call.getArgument(1)).toString()).contains("\"resultAvailable\":true");calls=List.of();}
            return Map.of("text",turn>3?"SQLite executed only in the network-disabled Python Sandbox.":"","toolCalls",calls,"usage",Map.of("inputTokens",20,"outputTokens",20));
        }).when(provider).complete(any(),any());
        actor(()->adapter.submit(contract));var outcome=awaitTerminal();
        var diagnostics=new org.springframework.jdbc.core.JdbcTemplate(evidenceSource).queryForList(
            "select entry_id,record_json from runtime_evidence_entry where lower(evidence_type) like '%validation%'");
        assertThat(outcome.status()).as("%s %s; validation: %s",outcome.reasonCode(),outcome.summary(),diagnostics).isEqualTo(CodingTaskOutcome.Status.COMPLETED);
        assertThat(outcome.validationStatus()).isEqualTo(CodingTaskOutcome.ValidationStatus.PASSED);
        assertThat(outcome.usage().consumed().modelCalls()).isEqualTo(4);assertThat(outcome.usage().consumed().toolCalls()).isEqualTo(3);assertThat(outcome.usage().consumed().codeExecutions()).isEqualTo(1);
        assertThat(actor(()->collector.readResultPage(scope,"clean",outcome.resultDatasetRef(),0,10)).rows())
            .extracting(row->row.get("observedTradingDays")).containsExactly(1,2,3);
    }
}
