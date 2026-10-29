package com.chatchat.api.runtime.coding.workspace;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.datascience.sql.SqlGlotToolchain;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real H2/A2/Evidence and SQLGlot; no SDK/provider or database SQL execution. */
@EnabledIfSystemProperty(named="coding.sqlglotPython",matches=".+")
class CodingWorkspaceServiceTest {
    @Test void clineCanDiscoverThenRepairCustomerSqlWithoutAnyPreselectedTable(){
        var metadata=mock(com.chatchat.api.datascience.sql.SqlStudioMetadataService.class);
        var gateway=mock(com.chatchat.api.datascience.sql.SqlStudioMetadataGateway.class);
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("metadata",metadata);
        var context=new com.chatchat.api.datascience.sql.SqlStudioMetadataService.Context("discovery","project","QUERY","mysql",List.of(
            new com.chatchat.api.datascience.sql.SqlStudioMetadataService.Binding("binding","service","asset","SOURCE","biz",null,"mysql")),"READY");
        when(metadata.getScoped(any(),eq("project"),eq("discovery"))).thenReturn(context);
        when(metadata.operateScoped(any(),eq(context),any())).thenAnswer(call->{
            var arguments=call.<com.chatchat.api.datascience.sql.SqlStudioMetadataService.Operation>getArgument(2).arguments();
            boolean describe="DESCRIBE".equals(arguments.get("operation"));
            var table=Map.of("tableRef","trade-ref","tableName","customer_trade","database","biz","columns",describe?List.of(Map.of("name","client_no"),Map.of("name","amount"),Map.of("name","security_id")):List.of(),"relationsStatus","NOT_COLLECTED");
            return new com.chatchat.api.datascience.sql.SqlStudioMetadataGateway.Result("service",Map.of("status","SUCCEEDED","snapshotId","v1","items",List.of(table),"doesNotExecuteSql",true));
        });
        when(gateway.referenceScoped(any(),eq("service"),eq("enterprise_metadata_search"),anyMap())).thenReturn(
            Map.of("evidenceBundle",Map.of("standardDefinition","Customer identifier is the registered client number","metricDefinitionStatus","NOT_COLLECTED")));
        service.setExploration(new com.chatchat.api.datascience.sql.SqlWorkspaceMetadataTools(beans.getBeanProvider(com.chatchat.api.datascience.sql.SqlStudioMetadataService.class),gateway,
            new SqlGlotToolchain(System.getProperty("coding.sqlglotPython"),json),evidence,json));
        service.setFastPathEnabled(true);
        service.submit(scope,"autonomous","Find transactions, assets and P/L for customer 070200046604. Do not invent unknown definitions.","mysql","",Map.of("discoveryId","discovery","projectId","project"));
        var task=actor(()->tasks.contract(scope,"autonomous"));
        assertThat(Duration.between(Instant.now(),task.budget().deadlineAt()).toSeconds()).isBetween(850L,900L);
        assertThat(Duration.between(Instant.now(),contract.budget().deadlineAt()).toSeconds()).isLessThanOrEqualTo(120L);
        assertThat(task.budget().maxModelTokens()).isEqualTo(1048576);
        assertThat(contract.budget().maxModelTokens()).isEqualTo(262144);
        var owner=actor(()->tasks.claim(scope,"autonomous","cline-adapter:discovery",Duration.ofMinutes(2),tasks.get(scope,"autonomous").revision()));
        assertThat(task.authorization().allowedTools()).contains("workspace_metadata","workspace_validate");
        assertThat(task.metadataRefs()).doesNotContain(CodingWorkspaceService.FAST_PATH_REF);
        var missing=actor(()->service.validateSql(task,owner,operation('a'),Map.of("files",List.of(Map.of("path","q.sql","content","SELECT amount FROM customer_trade WHERE client_no='070200046604'")))));
        assertThat(missing).containsEntry("status","METADATA_REQUIRED").containsEntry("sqlValidity","UNDETERMINED");
        actor(()->service.metadata(task,owner,operation('b'),Map.of("operation","LIST_BINDINGS")));
        actor(()->service.metadata(task,owner,operation('c'),Map.of("operation","SEARCH","bindingId","binding","query","customer transactions assets profit")));
        actor(()->service.metadata(task,owner,operation('d'),Map.of("operation","BUSINESS_SEARCH","bindingId","binding","query","customer identifier and P/L definition")));
        actor(()->service.metadata(task,owner,operation('e'),Map.of("operation","DESCRIBE","bindingId","binding","tableName","customer_trade")));
        var repeated=actor(()->service.metadata(task,owner,operation('f'),Map.of("operation","DESCRIBE","bindingId","binding","tableName","customer_trade")));
        assertThat(repeated).containsEntry("reusedEvidence",true);
        var invalid=Map.<String,Object>of("schemaVersion","sql-studio.output.v1","files",List.of(Map.of("path","q.sql","content","SELECT guessed_amount FROM customer_trade WHERE client_no='070200046604'")),"notes","Asset and P/L definitions/relationships remain unconfirmed.");
        assertThat(actor(()->service.propose(task,owner,operation('1'),invalid))).containsEntry("valid",false);
        var repaired=Map.<String,Object>of("schemaVersion","sql-studio.output.v1","files",List.of(Map.of("path","q.sql","content","SELECT amount FROM customer_trade WHERE client_no='070200046604'")),"notes","Used actual amount field; asset/P&L joins and definitions remain unconfirmed; no SQL executed.");
        assertThat(actor(()->service.propose(task,owner,operation('2'),repaired))).containsEntry("valid",true);
        assertThat(service.diagnostics(scope,"autonomous")).containsEntry("repairAttempts",1L).containsEntry("finalSqlValid",true);
        assertThat(actor(()->service.complete(task,owner,operation('2'))).status()).isEqualTo(CodingTaskOutcome.Status.COMPLETED);
        assertThat(service.diagnostics(scope,"autonomous").get("operations").toString()).contains("receiptRef");
    }
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final CodingInvocationContext identity=new CodingInvocationContext();
    final KernelDataScope scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
    final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    JdbcRuntimeEvidenceStore evidence;DurableCodingTaskService tasks;CodingWorkspaceService service;
    CodingTaskContract contract;CodingJournal.Lease lease;
    <T>T actor(Supplier<T> action){return identity.as(scope,EnumSet.allOf(CodingTaskAccess.Action.class),action);}
    @BeforeEach void setup(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:workspace-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(source);
        evidence=spy(new JdbcRuntimeEvidenceStore(new JdbcTemplate(source),json,new DataSourceTransactionManager(source)));
        tasks=new DurableCodingTaskService(evidence,json,Clock.systemUTC(),identity,(owner,ref)->{
            String id=ref.substring("runtime-evidence:".length());
            return evidence.find(owner,id).isPresent() || evidence.snapshot(owner,id).isPresent();
        },new CodingWorkspaceProofVerifier(evidence),Runnable::run,new ConcurrentTaskScheduler(timer));
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("codingAgent",tasks);
        service=new CodingWorkspaceService(tasks,identity,evidence,beans.getBeanProvider(CodingAgentSPI.class),
            new SqlGlotToolchain(System.getProperty("coding.sqlglotPython"),json),json,120);
        service.submit(scope,"task","User request: modify sql/a.sql, preserve unrelated SQL. Mapping: source->target. Historical style: uppercase.","mysql","");
        contract=actor(()->tasks.contract(scope,"task"));
        lease=actor(()->tasks.claim(scope,"task","cline-adapter:test",Duration.ofMinutes(2),tasks.get(scope,"task").revision()));
    }
    @AfterEach void close(){timer.shutdownNow();}
    @Test void fastPathIsExplicitBoundedAndFrozenAcrossConfigurationChanges(){
        assertThat(contract.metadataRefs()).doesNotContain(CodingWorkspaceService.FAST_PATH_REF);
        service.setFastPathEnabled(true);
        service.submit(scope,"fast","Create one pure function.","python","");
        var fast=actor(()->tasks.contract(scope,"fast"));
        assertThat(fast.metadataRefs()).contains(CodingWorkspaceService.FAST_PATH_REF);
        assertThat(fast.budget().maxCodeExecutions()).isZero();
        service.setFastPathEnabled(false);
        service.submit(scope,"fast","Create one pure function.","python","");
        assertThat(actor(()->tasks.contract(scope,"fast"))).isEqualTo(fast);
        service.setFastPathEnabled(true);service.setFastPathMaxContextBytes(1024);
        service.submit(scope,"large","request".repeat(1000),"mysql","");
        assertThat(actor(()->tasks.contract(scope,"large")).metadataRefs()).doesNotContain(CodingWorkspaceService.FAST_PATH_REF);
        assertThatThrownBy(()->service.setFastPathMaxContextBytes(0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void reservesForMultiRoundGenerationAndKeepsPreviouslyFrozenBudget(){
        assertThat(contract.budget().maxModelTokens()).isGreaterThanOrEqualTo(8L*16384);
        assertThat(contract.budget().maxCodeExecutions()).isZero();
        service.setMaxModelTokens(32768);
        service.submit(scope,"task","User request: modify sql/a.sql, preserve unrelated SQL. Mapping: source->target. Historical style: uppercase.","mysql","");
        assertThat(actor(()->tasks.contract(scope,"task"))).isEqualTo(contract);
    }
    @Test void projectsDurableHistoryAfterServiceRestartAndDeduplicatesPolling(){
        var request=new com.chatchat.api.datascience.StudioTaskHistory.Request(null,"sql","Generate source","model",Map.of());
        service.captureHistory(scope,"task",request);
        actor(()->service.propose(contract,lease,operation('a'),proposal("SELECT 1;")));
        actor(()->service.complete(contract,lease,operation('a')));
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("codingAgent",tasks);
        var restarted=new CodingWorkspaceService(tasks,identity,evidence,beans.getBeanProvider(CodingAgentSPI.class),
            new SqlGlotToolchain(System.getProperty("coding.sqlglotPython"),json),json,120);
        var history=mock(com.chatchat.api.datascience.StudioTaskHistory.class);restarted.setHistory(history);
        restarted.get(scope,"task");restarted.get(scope,"task");
        verify(history,times(1)).project(eq(scope),eq("task"),eq(request),any(),argThat(value->value.containsKey("files")),anyList());
        assertThatThrownBy(()->restarted.captureHistory(scope,"task",
            new com.chatchat.api.datascience.StudioTaskHistory.Request(null,"sql","Different source","model",Map.of())))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void historyStorageFailureCannotFailCommittedCodeAndIsRetriedOnNextRead(){
        var history=mock(com.chatchat.api.datascience.StudioTaskHistory.class);service.setHistory(history);
        service.captureHistory(scope,"task",new com.chatchat.api.datascience.StudioTaskHistory.Request(null,"sql","Generate source","model",Map.of()));
        doThrow(new IllegalStateException("history store unavailable")).doNothing().when(history)
            .project(any(),anyString(),any(),any(),anyMap(),anyList());
        actor(()->service.propose(contract,lease,operation('a'),proposal("SELECT 1;")));
        var result=actor(()->service.complete(contract,lease,operation('a')));
        assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.COMPLETED);
        assertThat(service.get(scope,"task").status()).isEqualTo(CodingTaskOutcome.Status.COMPLETED);
        service.get(scope,"task");
        verify(history,times(2)).project(any(),anyString(),any(),any(),anyMap(),anyList());
    }
    String operation(char suffix){return "workspace-"+String.valueOf(suffix).repeat(64);}
    Map<String,Object> proposal(String source){return Map.of("schemaVersion","sql-studio.output.v1","files",List.of(
        Map.of("path","sql/a.sql","content",source),Map.of("path","etl/b.sql","content","SELECT 2;")),"notes","Preserved unrelated files");}
    @Test void multiFileCandidatesHaveSixReferencesAndNoDatabaseExecutionOrAutomaticProjectWrite(){
        var read=actor(()->service.read(contract,lease,operation('a')));
        assertThat(read.get("context").toString()).contains("Mapping","Historical");
        var proposed=actor(()->service.propose(contract,lease,operation('b'),proposal("-- preserve comment\nSELECT 1;")));
        assertThat(proposed).containsEntry("valid",true).containsEntry("sqlExecuted",false);
        var completed=actor(()->service.complete(contract,lease,operation('b')));
        assertThat(completed.schemaVersion()).isEqualTo(CodingProtocol.WORKSPACE_VERSION);
        assertThat(completed.status()).isEqualTo(CodingTaskOutcome.Status.COMPLETED);
        assertThat(completed.validationStatus()).isEqualTo(CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
        assertThat(completed.usage().consumed().codeExecutions()).isZero();
        assertThat(completed.usage().consumed().toolCalls()).isEqualTo(2);
        assertThat((List<?>)service.output(scope,"task",completed).get("files")).hasSize(2);
        assertThat(evidence.snapshot(scope,completed.evidenceBundleRef().substring("runtime-evidence:".length())).orElseThrow().evidenceIds()).hasSize(5);
        var again=service.submit(scope,"task",read.get("context").toString(),"mysql","");
        assertThat(again).isEqualTo(completed);
    }
    @Test void invalidSourceAndUnsafePathsReturnDiagnosticsAndCanBeRepaired(){
        var invalid=actor(()->service.propose(contract,lease,operation('a'),proposal("SELECT * FROM")));
        assertThat(invalid).containsEntry("valid",false).doesNotContainKey("evidenceBundleRef");
        var unsafe=new LinkedHashMap<>(proposal("SELECT 1;"));unsafe.put("files",List.of(Map.of("path","../unsafe.sql","content","SELECT 1")));
        assertThat(actor(()->service.propose(contract,lease,operation('b'),unsafe))).containsEntry("valid",false);
        var repaired=actor(()->service.propose(contract,lease,operation('c'),proposal("SELECT 1;")));
        assertThat(repaired).containsEntry("valid",true);
        assertThat(actor(()->service.complete(contract,lease,operation('c'))).status()).isEqualTo(CodingTaskOutcome.Status.COMPLETED);
    }
    @Test void metadataBoundFieldFailureIsRepairedAgainstTheSameFrozenEvidenceWithoutDatabaseAccess() throws Exception {
        var table=Map.<String,Object>of("tableName","orders","database","db","columns",List.of(Map.of("name","id","type","INT")),
            "snapshotId","controlled-snapshot","discoveryId","controlled-discovery","bindingId","controlled-binding","tableRef","controlled-orders");
        var schema=Map.<String,Object>of("tables",List.of(table));
        service.submit(scope,"metadata-task","Use only supplied schema; propose SQL, never execute it.","mysql","",schema);
        var bound=actor(()->tasks.contract(scope,"metadata-task"));
        var worker=actor(()->tasks.claim(scope,"metadata-task","cline-adapter:test",Duration.ofMinutes(2),tasks.get(scope,"metadata-task").revision()));
        assertThat(bound.authorization().allowedTools()).containsExactlyInAnyOrder("workspace_read","workspace_propose");
        var input=actor(()->service.read(bound,worker,operation('a')));
        assertThat(input.get("schema")).isEqualTo(schema);
        var failed=actor(()->service.propose(bound,worker,operation('b'),proposal("SELECT missing_field FROM db.orders;")));
        assertThat(failed).containsEntry("valid",false).containsEntry("sqlExecuted",false);
        assertThat(failed.get("validation").toString()).contains("missing_field","FAILED");
        assertThatThrownBy(()->actor(()->service.complete(bound,worker,operation('b')))).isInstanceOf(IllegalStateException.class);
        var repaired=actor(()->service.propose(bound,worker,operation('c'),proposal("SELECT id FROM db.orders;")));
        assertThat(repaired).containsEntry("valid",true).containsEntry("sqlExecuted",false);
        assertThat(repaired.get("validation").toString()).contains("CHECKED");
        var done=actor(()->service.complete(bound,worker,operation('c')));
        assertThat(done.status()).isEqualTo(CodingTaskOutcome.Status.COMPLETED);
        assertThat(done.usage().consumed().codeExecutions()).isZero();
        assertThat(done.inputSnapshotRef()).isEqualTo(bound.inputSnapshotRef());
        var failure=evidence.find(scope,"coding:workspace:"+operation('b')+":receipt").orElseThrow();
        assertThat(json.readTree(json.writeValueAsBytes(failure.payload().get("response"))))
            .isEqualTo(json.readTree(json.writeValueAsBytes(failed)));
        var directory=System.getProperty("sql.benchmarkEvidenceDirectory");
        if(directory!=null){
            var path=java.nio.file.Path.of(directory);java.nio.file.Files.createDirectories(path);
            json.writerWithDefaultPrettyPrinter().writeValue(path.resolve("real-sqlglot-repair.json").toFile(),Map.of(
                "kind","Controlled real SQLGlot and durable workspace receipts; no live model in this test",
                "schema",schema,"inputSnapshotRef",bound.inputSnapshotRef(),"failedReceiptId",failure.evidenceId(),
                "failed",failed,"repaired",repaired,"finalStatus",done.status(),"codeExecutions",done.usage().consumed().codeExecutions()));
        }
    }
    @Test void olderSuccessfulProposalCannotReplaceTheLatestInvalidRevision(){
        actor(()->service.propose(contract,lease,operation('a'),proposal("SELECT 1;")));
        actor(()->service.propose(contract,lease,operation('b'),proposal("SELECT * FROM")));
        assertThatThrownBy(()->actor(()->service.complete(contract,lease,operation('a')))).hasMessageContaining("current");
        assertThat(actor(()->service.recover(contract,lease))).isNull();
    }
    @Test void evidenceCommitFailureCanRecoverWithoutReparsingOrResettingBudget(){
        doThrow(new IllegalStateException("Injected evidence commit failure")).doCallRealMethod().when(evidence)
            .commitCodingEvidence(any(),anyString(),anyString(),anyString(),anyList(),anyList(),anyString(),anyList(),anyMap());
        var proposal=proposal("SELECT 1;");
        assertThatThrownBy(()->actor(()->service.propose(contract,lease,operation('a'),proposal))).hasMessageContaining("Injected");
        assertThat(evidence.snapshot(scope,"coding:workspace:"+operation('a')+":bundle")).isEmpty();
        var retry=actor(()->service.propose(contract,lease,operation('a'),proposal));
        assertThat(retry).containsEntry("valid",true);
        var completed=actor(()->service.complete(contract,lease,operation('a')));
        assertThat(completed.usage().consumed().toolCalls()).isEqualTo(1);
        assertThat(completed.usage().reserved().toolCalls()).isZero();
    }
    @Test void anotherIdentityAndRevokedWorkerLeaseCannotWriteOrReadSnapshots(){
        assertThatThrownBy(()->service.requireAuthorized(contract)).isInstanceOf(CodingContractException.class);
        var other=new KernelDataScope("other-tenant","owner",null,null,"run",null,Map.of());
        assertThatThrownBy(()->identity.as(other,EnumSet.allOf(CodingTaskAccess.Action.class),()->service.read(contract,lease,operation('a'))))
            .isInstanceOf(CodingContractException.class);
        var stale=new CodingJournal.Lease(lease.owner(),lease.leaseUntil(),lease.fencingToken()+1);
        assertThatThrownBy(()->actor(()->service.propose(contract,stale,operation('a'),proposal("SELECT 1"))))
            .isInstanceOf(CodingContractException.class);
        actor(()->tasks.cancel(scope,"task","User stopped"));
        assertThatThrownBy(()->actor(()->service.propose(contract,lease,operation('b'),proposal("SELECT 2"))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("budget denied");
    }
}
