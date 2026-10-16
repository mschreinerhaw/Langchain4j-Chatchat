package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.evidence.ToolAnalysisEvidence;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real JDBC/A2, snapshots and fixed template operator. External governed tool and upload are doubles. */
class CodingDataSupplyServiceTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final CodingInvocationContext identity=new CodingInvocationContext();
    final KernelDataScope scope=new KernelDataScope("tenant","user","request",null,"run",null,Map.of());
    final CodingDataSupplyProperties policy=new CodingDataSupplyProperties();
    final RegisteredToolAnalysisOperator tool=mock(RegisteredToolAnalysisOperator.class);
    final McpPythonControlPlanePort python=mock(McpPythonControlPlanePort.class);
    final com.chatchat.common.retrieval.SkillExecutionScopePort scopes=mock(com.chatchat.common.retrieval.SkillExecutionScopePort.class);
    final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    JdbcRuntimeEvidenceStore evidence;DurableCodingTaskService tasks;CodingDataSupplyService service;
    CodingInputSnapshotService inputs;CodingTaskContract contract;CodingJournal.Lease lease;
    <T>T actor(Supplier<T> action){return identity.as(scope,EnumSet.allOf(CodingTaskAccess.Action.class),action);}
    @BeforeEach void setup() {
        var db=new DriverManagerDataSource("jdbc:h2:mem:data-supply-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(db);
        evidence=new JdbcRuntimeEvidenceStore(new JdbcTemplate(db),json,new DataSourceTransactionManager(db));
        var dataProof=new CodingDataSupplyProofVerifier(evidence);var sqlProof=new CodingWorkspaceProofVerifier(evidence);
        tasks=new DurableCodingTaskService(evidence,json,Clock.systemUTC(),identity,(s,r)->true,(s,t,o,h,r)->dataProof.confirmsStopped(s,t,o,h,r)||sqlProof.confirmsStopped(s,t,o,h,r),Runnable::run,new ConcurrentTaskScheduler(timer));
        when(python.uploadDataFile(anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(call->
            new McpPythonControlPlanePort.DataFileResult(call.getArgument(2),"stored","mounted",((byte[])call.getArgument(5)).length,call.getArgument(4),"AVAILABLE"));
        inputs=new CodingInputSnapshotService(evidence,python,identity,json);
        policy.setEnabled(true);policy.setMaxRounds(2);policy.setGrants(List.of(new CodingDataSupplyProperties.Grant(
            "tenant","user","skill","prices","asset","test",Map.of("date",List.of("2026-10-14","2026-10-15")),List.of("price","date"))));
        when(scopes.resolve(anyString(),anyString(),anyString(),anyList(),anyList())).thenReturn(new com.chatchat.common.retrieval.SkillExecutionScopePort.EffectiveScope(List.of(),List.of(),List.of("finance-reader"),true,true));
        service=new CodingDataSupplyService(policy,tasks,new PreauthorizedStructuredDataOperator(tool,json),inputs,evidence,json,scopes);
        var input=actor(()->inputs.capture(scope,"task",List.of(new Dataset("source:v1",Map.of(),List.of(Map.of("price","100")))),"source:v1","decision","workspace",10,10000));
        contract=new CodingTaskContract(CodingProtocol.ANALYSIS_VERSION,"task",scope,new CodingTaskContract.Objective("Analyze",List.of("rows.jsonl"),List.of("rules")),
            input.reference(),List.of(service.policyRef(scope)),"workspace",new CodingTaskContract.Authorization("decision",List.of("python_execute","data_request","sql_validate"),List.of(input.fileId()),List.of(),List.of(),CodingTaskContract.NetworkPolicy.NONE,List.of(),List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
            new CodingTaskContract.Budget("same-budget",Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),8,16000,8,4,"10","USD",10000,1024*1024,100000),"env","rules",
            new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,"schema","validation",List.of("rows.jsonl"),List.of("application/x-ndjson"),100,100000,List.of(CodingTaskContract.RequiredEvidence.values())));
        actor(()->tasks.submit(contract));lease=actor(()->tasks.claim(scope,"task","cline-adapter:test",Duration.ofSeconds(90),tasks.get(scope,"task").revision()));
        respond(List.of(Map.of("date","2026-10-14","price","101.00")));
    }
    @AfterEach void close(){timer.shutdownNow();}
    void respond(List<Map<String,Object>> rows) {
        try {var content=json.writeValueAsString(Map.of("success",true,"data",Map.of("rowCount",rows.size(),"rows",rows,"complete",true)));
            when(tool.execute(any(),any(),isNull())).thenReturn(new WorkflowExecutionResult(List.of(new ToolAnalysisEvidence("source-receipt","sql_template_analysis_execute","request",content,Map.of())),Map.of("status","SUCCESS"),List.of()));
        }catch(Exception e){throw new AssertionError(e);}
    }
    com.fasterxml.jackson.databind.node.ObjectNode request(String id) {
        return json.valueToTree(Map.of("taskId","task","requestId",id,"operationId","data-"+service.hash(Map.of("task","task","request",id)),
            "datasetRefs",List.of("source:v1"),"authorizedTemplateId","prices","parameterBindings",Map.of("date","2026-10-14"),
            "requiredFields",List.of("date","price"),"reason","Missing trading date","expectedUsage","Recalculate returns"));
    }
    @Test void repeatedReceiptDoesNotQueryAgainOrChargeAgainAndSameTaskGetsImmutableInputVersion() {
        var first=actor(()->service.request(contract,lease,request("one")));
        var second=actor(()->service.request(contract,lease,request("one")));
        assertThat(second).isEqualTo(first);assertThat(first).containsEntry("status","AVAILABLE");
        var reordered=json.createObjectNode();var original=request("one");var fields=new ArrayList<String>();original.fieldNames().forEachRemaining(fields::add);Collections.reverse(fields);
        fields.forEach(key->reordered.set(key,original.get(key)));
        assertThat(actor(()->service.request(contract,lease,reordered))).isEqualTo(first);
        var sourceCall=org.mockito.ArgumentCaptor.forClass(com.chatchat.common.runtime.analysis.model.AnalysisContext.class);
        verify(tool,times(1)).execute(sourceCall.capture(),any(),isNull());
        assertThat(sourceCall.getValue().kernelScope().requestId()).isEqualTo(request("one").path("operationId").asText());
        assertThat(sourceCall.getValue().attributes()).containsEntry(RegisteredToolAnalysisOperator.CODING_DATA_OPERATION,request("one").path("operationId").asText());
        var all=actor(()->service.snapshots(contract,null));assertThat(all).hasSize(2);
        assertThat(all.get(0).reference()).isEqualTo(contract.inputSnapshotRef());
        var outcome=actor(()->tasks.get(scope,"task"));assertThat(outcome.usage().budgetId()).isEqualTo("same-budget");
        assertThat(outcome.usage().consumed().toolCalls()).isEqualTo(1);assertThat(outcome.status()).isEqualTo(CodingTaskOutcome.Status.RUNNING);
    }
    @Test void conflictingIdentityAndUndeliveredSourcesCannotBeAccepted() {
        actor(()->service.request(contract,lease,request("one")));
        var changed=request("one");changed.put("reason","Changed reason");
        assertThatThrownBy(()->actor(()->service.request(contract,lease,changed))).hasMessageContaining("identity conflict");
        var wrong=request("two");wrong.putArray("datasetRefs").add("other-run");
        assertThatThrownBy(()->actor(()->service.request(contract,lease,wrong))).isInstanceOf(SecurityException.class);
        verify(tool,times(1)).execute(any(),any(),isNull());
    }
    @Test void templateAndParameterInjectionAreRejectedBeforeToolDispatch() {
        var wrong=request("one");wrong.put("authorizedTemplateId","arbitrary-sql");
        assertThatThrownBy(()->actor(()->service.request(contract,lease,wrong))).isInstanceOf(SecurityException.class);
        var params=request("one");params.with("parameterBindings").put("date","2026-11-01");
        assertThatThrownBy(()->actor(()->service.request(contract,lease,params))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(tool);
    }
    @Test void emptyDataIsAnExplicitImmutableVersionRatherThanInventedObservations() {
        respond(List.of());var result=actor(()->service.request(contract,lease,request("one")));
        assertThat(result).containsEntry("status","EMPTY");assertThat(actor(()->service.snapshots(contract,null)).get(1).rowCount()).isZero();
    }
    @Test void changedLiveGrantDeniesReceiptReuse() {
        actor(()->service.request(contract,lease,request("one")));policy.setEnabled(false);
        assertThatThrownBy(()->actor(()->service.request(contract,lease,request("one")))).isInstanceOf(SecurityException.class);
        verify(tool,times(1)).execute(any(),any(),isNull());
    }
    @Test void liveSkillPermissionRevocationAlsoDeniesPreviouslyCompletedReceiptReuse() {
        actor(()->service.request(contract,lease,request("one")));
        when(scopes.resolve(anyString(),anyString(),anyString(),anyList(),anyList())).thenReturn(com.chatchat.common.retrieval.SkillExecutionScopePort.EffectiveScope.denied(List.of()));
        assertThatThrownBy(()->actor(()->service.request(contract,lease,request("one")))).isInstanceOf(SecurityException.class);
        verify(tool,times(1)).execute(any(),any(),isNull());
    }
    @Test void knownMissingFieldResponseIsDurableFailureWithoutInventingDataOrBlockingReconciliation() {
        respond(List.of(Map.of("date","2026-10-14")));
        var first=actor(()->service.request(contract,lease,request("one")));
        assertThat(first).containsEntry("status","INVALID_DATA").containsEntry("resultAvailable",false);
        assertThat(actor(()->service.request(contract,lease,request("one")))).isEqualTo(first);
        assertThat(actor(()->service.snapshots(contract,null))).hasSize(1);
        assertThat(actor(()->tasks.unresolvedOperations(scope,"task"))).isEmpty();verify(tool,times(1)).execute(any(),any(),isNull());
    }
    @Test void lostAcquisitionOutcomeCannotBeReplayedAndBlocksModelAdmission() {
        when(tool.execute(any(),any(),isNull())).thenThrow(new IllegalStateException("network reply lost"));
        assertThatThrownBy(()->actor(()->service.request(contract,lease,request("one")))).hasMessageContaining("network reply lost");
        assertThatThrownBy(()->actor(()->service.request(contract,lease,request("one")))).hasMessageContaining("not replayed");
        assertThatThrownBy(()->actor(()->tasks.reserve(scope,"task",lease,tasks.get(scope,"task").revision(),"model-1","a".repeat(64),new CodingTaskOutcome.Counters(1,100,0,0,0,0,0,"0")))).hasMessageContaining("WAITING_FOR_DATA");
        verify(tool,times(1)).execute(any(),any(),isNull());
    }
    @Test void operationInputsStayPinnedWhenLaterSourceVersionsArriveAndRoundsAreBounded() {
        actor(()->service.request(contract,lease,request("one")));
        var bound=actor(()->service.bindInputs(contract,lease,"cline-"+"a".repeat(64)));
        respond(List.of(Map.of("date","2026-10-15","price","102.00")));actor(()->service.request(contract,lease,request("two")));
        assertThat(actor(()->service.snapshots(contract,null))).hasSize(3);
        assertThat(actor(()->service.snapshots(contract,"cline-"+"a".repeat(64)))).isEqualTo(bound).hasSize(2);
        assertThatThrownBy(()->actor(()->service.request(contract,lease,request("three")))).hasMessageContaining("ROUND_LIMIT");
        verify(tool,times(2)).execute(any(),any(),isNull());
    }
    @Test void cancelPreventsAdditionalAcquisitionAndFencingPreventsStateMutation() {
        actor(()->tasks.cancel(scope,"task","cancel during data recovery"));
        assertThatThrownBy(()->actor(()->service.request(contract,lease,request("one")))).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(tool);
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="coding.sqlglotPython",matches=".+")
    void sqlAnalysisUsesRealStaticParserAndSameToolBudgetWithoutExecutingSql() {
        var sql=new CodingAnalysisSqlService(tasks,evidence,new com.chatchat.api.datascience.sql.SqlGlotToolchain(System.getProperty("coding.sqlglotPython"),json));
        String operation="workspace-"+"b".repeat(64);
        var first=actor(()->sql.validate(contract,lease,operation,"mysql","SELECT 1 AS amount"));
        assertThat(first).containsEntry("valid",true).containsEntry("sqlExecuted",false);
        assertThat(actor(()->sql.validate(contract,lease,operation,"mysql","SELECT 1 AS amount"))).isEqualTo(first);
        assertThatThrownBy(()->actor(()->sql.validate(contract,lease,operation,"mysql","SELECT 2"))).hasMessageContaining("conflict");
        assertThat(actor(()->sql.validate(contract,lease,"workspace-"+"c".repeat(64),"mysql","SELECT * FROM"))).containsEntry("valid",false);
        var result=actor(()->tasks.get(scope,"task"));assertThat(result.usage().consumed().toolCalls()).isEqualTo(2);assertThat(result.usage().consumed().codeExecutions()).isZero();
        verify(python,never()).start(any());
    }
}
