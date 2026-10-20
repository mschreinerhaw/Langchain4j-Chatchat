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
