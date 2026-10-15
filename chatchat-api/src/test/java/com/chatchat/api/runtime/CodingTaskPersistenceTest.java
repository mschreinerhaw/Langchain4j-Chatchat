package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.coding.CodingTaskOutcome.*;
import com.chatchat.common.runtime.evidence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static com.chatchat.common.runtime.coding.CodingErrorCode.*;

/** Real JDBC transactions and independent connections. No mocked store, CAS, lock or recovery. */
class CodingTaskPersistenceTest {
    @TempDir Path disk;
    DriverManagerDataSource dataSource;
    JdbcTemplate jdbc;
    JdbcRuntimeEvidenceStore evidence;
    DurableCodingTaskService service;
    ExecutorService workers;
    ScheduledExecutorService timer;
    final MutableClock clock=new MutableClock();
    final KernelDataScope scope=scope("tenant","owner","run");
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String intent="a".repeat(64);

    @BeforeEach void setup() {
        workers=Executors.newFixedThreadPool(8); timer=Executors.newSingleThreadScheduledExecutor();
        connect("jdbc:h2:mem:coding-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000",true);
    }
    @AfterEach void shutdown() { timer.shutdownNow(); workers.shutdownNow(); }
    void connect(String url,boolean create) {
        dataSource=new DriverManagerDataSource(url,"sa","");
        if(create)new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(dataSource);
        jdbc=new JdbcTemplate(dataSource); evidence=store(jdbc); service=service(evidence);
    }
    JdbcRuntimeEvidenceStore store(JdbcTemplate template) {
        return new JdbcRuntimeEvidenceStore(template,json,new DataSourceTransactionManager(dataSource));
    }
    DurableCodingTaskService service(JdbcRuntimeEvidenceStore database) {
        return new DurableCodingTaskService(database,json,clock,
            new BoundCodingTaskAccess(()->scope,EnumSet.allOf(CodingTaskAccess.Action.class)),
            (owner,reference)->database.find(owner,reference).isPresent(),null,workers,new ConcurrentTaskScheduler(timer));
    }
    static KernelDataScope scope(String tenant,String user,String run) {
        return new KernelDataScope(tenant,user,"trace",null,run,null,Map.of());
    }
    CodingTaskContract contract(String id,long executions) {
        return new CodingTaskContract(CodingProtocol.VERSION,id,scope,
            new CodingTaskContract.Objective("Verify authorized financial fixture",List.of("result.jsonl"),List.of("criteria:fixed")),
            "input:fixture",List.of(),"workspace:"+id,
            new CodingTaskContract.Authorization("decision:fixture",List.of("python.run"),List.of("input:fixture"),List.of("input:fixture"),
                List.of("output:"+id),CodingTaskContract.NetworkPolicy.NONE,List.of(),List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
            new CodingTaskContract.Budget("shared-budget",Instant.parse("2026-10-15T01:00:00Z"),10,1000,100,executions,"10","USD",1000,1024,1000),
            "environment:fixture","validation:fixture",
            new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,"schema:result","schema:validation",List.of("result.jsonl"),
                List.of("application/x-ndjson"),100,1000,List.of(CodingTaskContract.RequiredEvidence.values())));
    }
    Counters counters(long executions,String cost) { return new Counters(0,0,0,executions,0,0,0,cost); }
    long rev(String id) { return service.get(scope,id).revision(); }
    CodingJournal.Lease claim(String id,String owner) { return service.claim(scope,id,owner,Duration.ofSeconds(10),rev(id)); }
    void register(String reference) {
        Map<String,Object> payload=Map.of("fixture",reference,"source","deterministic test data; no Sandbox execution");
        var record=new EvidenceRecord(null,reference,scope,"TEST_FIXTURE","a2-contract-test",evidence.contentHash(payload),payload,null,clock.millis(),Map.of());
        evidence.register(record,new EvidenceLineage(null,reference,scope,List.of(),"test","fixture",null,Map.of()));
    }
    CodingTaskOutcome proposal(String id) {
        for(String ref:List.of("input:fixture","code:fixture","receipt:fixture","result:fixture","checks:fixture","bundle:fixture"))register(ref);
        var old=service.get(scope,id); Instant at=clock.instant();
        return new CodingTaskOutcome(old.schemaVersion(),id,scope,old.contractSha256(),Status.COMPLETED,old.revision()+1,old.lastEventSequence()+1,
            old.createdAt(),at,old.startedAt(),at,false,null,"Fixture-only task complete",List.of(),old.inputSnapshotRef(),
            "code:fixture","receipt:fixture","result:fixture","checks:fixture","bundle:fixture",ValidationStatus.FAILED,
            old.reproducibility(),old.usage(),new ExecutionControl(null,null,true,false),null,null,Map.of());
    }

    @Test void acceptedSnapshotAndEventAreCommittedBeforeSubmitReturnsAndDuplicatesAreIdempotent() throws Exception {
        var contract=contract("task",10); var accepted=service.submit(contract);
        assertThat(accepted.status()).isEqualTo(Status.CREATED);
        var concurrent=parallel(12,()->service(evidence).submit(contract));
        assertThat(concurrent).allSatisfy(value->assertThat(value).isEqualTo(accepted));
        assertThat(service.events(scope,"task",0,20)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from runtime_evidence_entry where entry_kind='EXEC_CHECKPOINT'",Integer.class)).isEqualTo(1);
        assertThat(service(store(new JdbcTemplate(dataSource))).get(scope,"task")).isEqualTo(accepted);
    }

    @Test void latestCodeExecutionSurvivesProgressHistoryCompaction() {
        service.submit(contract("task", 10));
        var lease = claim("task", "worker");
        for (String id : List.of("z-earlier", "a-later")) {
            service.reserve(scope, "task", lease, rev("task"), id, intent, counters(1, "1"));
            service.startIntent(scope, "task", lease, rev("task"), id, "execution:" + id);
        }
        for (int sequence = 1; sequence <= 130; sequence++)
            service.recordWorkerProgress(scope, "task", lease, rev("task"), sequence,
                "TOOL_STARTED", null, intent);
        var restarted = service(store(new JdbcTemplate(dataSource)));
        assertThat(restarted.startedCodeOperations(scope, "task")).containsExactly("z-earlier", "a-later");
        code(() -> restarted.events(scope, "task", 0, 10), EVENT_CURSOR_EXPIRED);
    }

    @Test void workerProgressSurvivesReconnectWithoutGrantingExecutionAuthority() throws Exception {
        service.submit(contract("task", 10));
        var lease = claim("task", "worker");
        long cursor = service.get(scope, "task").lastEventSequence();
        var observed = service.recordWorkerProgress(scope, "task", lease, rev("task"), 1,
            "TOOL_STARTED", null, intent);
        var restarted = service(store(new JdbcTemplate(dataSource)));
        assertThat(restarted.recordWorkerProgress(scope, "task", lease, 0, 1,
            "TOOL_STARTED", null, intent)).isEqualTo(observed);
        code(() -> restarted.recordWorkerProgress(scope, "task", lease, rev("task"), 1,
            "TOOL_STARTED", null, "b".repeat(64)), TASK_ID_CONFLICT);
        code(() -> restarted.recordWorkerProgress(scope, "task", lease, rev("task"), 2,
            "TOOL_FINISHED", "unregistered", intent), NOT_FOUND);
        var received = new CopyOnWriteArrayList<CodingExecutionEvent>();
        var latch = new CountDownLatch(1);
        try (var subscription = restarted.subscribe(scope, "task", cursor, new CodingAgentSPI.Observer() {
            public void onEvent(CodingExecutionEvent event) { received.add(event); latch.countDown(); }
            public void onError(CodingAgentSPI.Error error) { fail(error.message()); }
            public void onClosed() { }
        })) {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(received).hasSize(1);
        assertThat(received.get(0).summary()).isEqualTo("Worker observation: TOOL_STARTED");
        assertThat(restarted.hasStartedOperations(scope, "task")).isFalse();
        assertThat(restarted.get(scope, "task").usage().consumed().codeExecutions()).isZero();
        clock.advance(Duration.ofSeconds(11));
        var takeover = restarted.claim(scope, "task", "new-worker", Duration.ofSeconds(10), rev("task"));
        code(() -> restarted.recordWorkerProgress(scope, "task", lease, rev("task"), 2,
            "TOOL_STARTED", null, intent), INVALID_STATE_TRANSITION);
        assertThat(takeover.fencingToken()).isGreaterThan(lease.fencingToken());
    }

    @Test void sameIdWithDifferentContractOrSharedBudgetLimitsIsRejected() {
        var first=contract("task",10); service.submit(first);
        var changed=new CodingTaskContract(first.schemaVersion(),first.taskId(),first.scope(),first.objective(),"input:other",first.metadataRefs(),
            first.workspaceRef(),first.authorization(),first.budget(),first.environmentRef(),first.validationPlanRef(),first.outputContract());
        code(()->service.submit(changed),TASK_ID_CONFLICT);
        code(()->service.submit(contract("another-task",11)),TASK_ID_CONFLICT);
    }

    @Test void independentDatabaseConnectionsCompeteForOneOwner() throws Exception {
        service.submit(contract("task",10));
        AtomicInteger owners=new AtomicInteger();
        parallel(12,()->{
            try { service(store(new JdbcTemplate(dataSource))).claim(scope,"task",UUID.randomUUID().toString(),Duration.ofSeconds(10),0); owners.incrementAndGet(); }
            catch(CodingContractException denied){assertThat(denied.error().code()).isEqualTo(INVALID_STATE_TRANSITION);}
            return true;
        });
        assertThat(owners).hasValue(1); assertThat(service.ownership(scope,"task").fencingToken()).isEqualTo(1);
        assertThat(service.events(scope,"task",0,20)).hasSize(2);
    }

    @Test void expiredLeaseAndOldFencingCannotReserveStartOrPublish() {
        service.submit(contract("task",10)); var old=claim("task","old");
        service.reserve(scope,"task",old,rev("task"),"op",intent,counters(1,"1"));
        clock.advance(Duration.ofSeconds(11));
        code(()->service.reserve(scope,"task",old,rev("task"),"other",intent,counters(1,"1")),INVALID_STATE_TRANSITION);
        var next=claim("task","next"); assertThat(next.fencingToken()).isEqualTo(2);
        code(()->service.startIntent(scope,"task",old,rev("task"),"op","execution:fixture"),INVALID_STATE_TRANSITION);
        var proposal=proposal("task");
        code(()->service.commitOutcome(scope,"task",old,rev("task"),proposal),INVALID_STATE_TRANSITION);
        assertThat(service.get(scope,"task").usage().reserved().costAmount()).isEqualTo("1");
    }

    @Test void takeoverPreservesUnknownExecutionAndDoesNotFabricateStopOrReplay() {
        service.submit(contract("task",10)); var old=claim("task","old");
        service.reserve(scope,"task",old,rev("task"),"op",intent,counters(1,"1"));
        service.startIntent(scope,"task",old,rev("task"),"op","execution:unknown");
        assertThat(service.hasStartedOperations(scope,"task")).isTrue();
        assertThat(service.contract(scope,"task")).isEqualTo(contract("task",10));
        clock.advance(Duration.ofSeconds(11)); var next=claim("task","next");
        var state=service.resume(scope,"task");
        assertThat(state.executionControl().reconciliationRequired()).isTrue();
        assertThat(state.executionControl().executorStopped()).isFalse();
        code(()->service.reserve(scope,"task",next,rev("task"),"new-op",intent,counters(1,"1")),EXECUTION_OUTCOME_UNKNOWN);
        code(()->service.startIntent(scope,"task",next,rev("task"),"op","execution:unknown"),EXECUTION_OUTCOME_UNKNOWN);
        code(()->service.reconcileStopped(scope,"task",next,rev("task"),"op","model-claims-stopped"),EXECUTION_OUTCOME_UNKNOWN);
        service.cancel(scope,"task","stop requested");
        code(()->service.finishStoppedWithoutExecution(scope,"task",rev("task")),EXECUTION_OUTCOME_UNKNOWN);
        code(()->service.finishStoppedAfterReconciliation(scope,"task",next,rev("task")),EXECUTION_OUTCOME_UNKNOWN);
        assertThat(service.get(scope,"task").status()).isEqualTo(Status.RUNNING);
    }

    @Test void cancelBeforeDispatchHasDurableNoStartProofAndCannotBeCompleted() {
        service.submit(contract("task",10)); var lease=claim("task","worker"); var proposal=proposal("task");
        service.cancel(scope,"task","requested by owner");
        assertThat(service.commitOutcome(scope,"task",lease,rev("task"),proposal).status()).isEqualTo(Status.RUNNING);
        assertThat(service.get(scope,"task").executionControl().executorStopped()).isFalse();
        var terminal=service.finishStoppedWithoutExecution(scope,"task",rev("task"));
        assertThat(terminal.status()).isEqualTo(Status.CANCELLED);
        assertThat(terminal.executionControl().executorStopped()).isTrue();
        assertThat(service.resume(scope,"task")).isEqualTo(terminal);
    }

    @Test void cancellationAndCompletionSerializeOnTheSameDatabaseTransaction() throws Exception {
        service.submit(contract("task",10)); var lease=claim("task","worker"); var proposed=proposal("task"); long revision=rev("task");
        CountDownLatch go=new CountDownLatch(1);
        Future<?> cancelling=workers.submit(()->{await(go);service.cancel(scope,"task","race");});
        Future<?> completing=workers.submit(()->{
            await(go);
            try { service.commitOutcome(scope,"task",lease,revision,proposed); }
            catch(CodingContractException conflict){assertThat(conflict.error().code()).isEqualTo(INVALID_STATE_TRANSITION);}
        });
        go.countDown(); cancelling.get(10,TimeUnit.SECONDS); completing.get(10,TimeUnit.SECONDS);
        var result=service.get(scope,"task");
        if(result.cancellationRequested())assertThat(result.status()).isNotEqualTo(Status.COMPLETED);
        else assertThat(result.status()).isEqualTo(Status.COMPLETED);
        assertEventsMatchSnapshot("task");
    }

    @Test void concurrentReservationsAcrossTasksCannotOverspendOneSharedBudget() throws Exception {
        for(int index=0;index<6;index++){String id="task-"+index;service.submit(contract(id,2));claim(id,"worker");}
        AtomicInteger ids=new AtomicInteger();
        var results=parallel(6,()->{
            String id="task-"+ids.getAndIncrement();
            return service(store(new JdbcTemplate(dataSource))).reserve(scope,id,service.ownership(scope,id),rev(id),"op",intent,counters(1,"0")).admitted();
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(2);
        assertThat(service.budgetUsage(scope,"shared-budget").codeExecutions()).isEqualTo(2);
        for(int index=0;index<6;index++)assertEventsMatchSnapshot("task-"+index);
    }

    @Test void unknownCostRetainsResidualReservationAndRepeatedSettlementIsIdempotent() {
        service.submit(contract("task",10)); var lease=claim("task","worker");
        service.reserve(scope,"task",lease,rev("task"),"op",intent,counters(1,"2"));
        service.startIntent(scope,"task",lease,rev("task"),"op","execution:unknown");
        var unknown=service.settle(scope,"task",lease,rev("task"),"op",counters(1,"0.5"),false);
        assertThat(unknown.usage().consumed().costAmount()).isEqualTo("0.5");
        assertThat(unknown.usage().reserved().costAmount()).isEqualTo("1.5");
        assertThat(unknown.usage().meteringComplete()).isFalse();
        assertThat(service.settle(scope,"task",lease,0,"op",counters(1,"0.5"),false)).isEqualTo(unknown);
        var known=service.settle(scope,"task",lease,rev("task"),"op",counters(1,"0.75"),true);
        assertThat(known.usage().reserved().costAmount()).isEqualTo("0");
        assertThat(known.usage().consumed().costAmount()).isEqualTo("0.75");
        code(()->service.settle(scope,"task",lease,rev("task"),"op",counters(1,"0"),true),CONTRACT_INVALID);
    }

    @Test void allCumulativeDimensionsAndDecimalCostParticipateInAdmission() {
        service.submit(contract("task",10)); var lease=claim("task","worker");
        Counters reservation=new Counters(10,1000,100,10,1000,0,1000,"10");
        assertThat(service.reserve(scope,"task",lease,rev("task"),"op",intent,reservation).admitted()).isTrue();
        assertThat(service.budgetUsage(scope,"shared-budget")).isEqualTo(reservation);
        assertThat(service.reserve(scope,"task",lease,rev("task"),"overflow",intent,counters(1,"0.01")).admitted()).isFalse();
        assertThat(service.get(scope,"task").reasonCode()).isEqualTo(BUDGET_EXCEEDED);
        assertThat(service.get(scope,"task").limitations()).contains(DurableCodingTaskService.ENFORCEMENT_LIMITATION);
    }

    @Test void actualOverrunIsPreservedAndStopsOtherTasksSharingTheBudget() {
        service.submit(contract("one",10));service.submit(contract("two",10)); var lease=claim("one","worker");claim("two","worker");
        service.reserve(scope,"one",lease,rev("one"),"op",intent,counters(1,"1"));
        service.startIntent(scope,"one",lease,rev("one"),"op","execution:unknown");
        var over=service.settle(scope,"one",lease,rev("one"),"op",counters(1,"11"),true);
        assertThat(over.usage().consumed().costAmount()).isEqualTo("11");
        assertThat(service.get(scope,"two").reasonCode()).isEqualTo(BUDGET_EXCEEDED);
        assertThat(over.status()).isEqualTo(Status.RUNNING);
    }

    @Test void terminalRevisionsMayRecordLateFeesButNeverRestartTheTask() {
        service.submit(contract("task",10)); var lease=claim("task","worker");
        service.reserve(scope,"task",lease,rev("task"),"reservation",intent,counters(0,"1"));
        service.cancel(scope,"task","stop before dispatch"); service.finishStoppedWithoutExecution(scope,"task",rev("task"));
        var before=service.get(scope,"task");
        var late=service.settle(scope,"task",lease,rev("task"),"reservation",counters(0,"0.1"),true);
        assertThat(late.status()).isEqualTo(Status.CANCELLED); assertThat(late.endedAt()).isEqualTo(before.endedAt());
        assertThat(late.revision()).isGreaterThan(before.revision());
        code(()->service.reserve(scope,"task",lease,rev("task"),"new",intent,counters(1,"0")),INVALID_STATE_TRANSITION);
        assertThat(service.events(scope,"task",0,100).stream().filter(event->event.type()==CodingExecutionEvent.Type.TASK_FINISHED).count()).isEqualTo(1);
    }

    @Test void reopenedFileDatabaseRecoversLeaseBudgetAndUnknownExecution() {
        String url="jdbc:h2:file:"+disk.resolve("coding").toString().replace('\\','/')+";DATABASE_TO_LOWER=TRUE";
        connect(url,true); service.submit(contract("task",10)); var old=claim("task","old-process");
        service.reserve(scope,"task",old,rev("task"),"op",intent,counters(1,"2"));
        service.startIntent(scope,"task",old,rev("task"),"op","external:unknown");
        clock.advance(Duration.ofSeconds(11)); connect(url,false);
        var fresh=claim("task","reconstructed-process");
        assertThat(fresh.fencingToken()).isEqualTo(2);
        assertThat(service.resume(scope,"task").executionControl().reconciliationRequired()).isTrue();
        assertThat(service.budgetUsage(scope,"shared-budget").costAmount()).isEqualTo("2");
        code(()->service.renew(scope,"task",old,Duration.ofSeconds(10),rev("task")),INVALID_STATE_TRANSITION);
    }

    @Test void snapshotEventsLeaseAndBudgetRollBackTogetherAfterRealJdbcWriteFailure() {
        service.submit(contract("task",10)); var lease=claim("task","worker");
        String before=evidence.readExecutionCheckpoint(scope,DurableCodingTaskService.CHECKPOINT_KEY).orElseThrow();
        long partitionRevision=jdbc.queryForObject("select revision from runtime_evidence_partition",Long.class);
        var failOnce=new AtomicBoolean(true);
        JdbcTemplate failingJdbc=new JdbcTemplate(dataSource) {
            @Override public int update(String sql,Object... arguments) {
                int changed=super.update(sql,arguments);
                if(sql.startsWith("update runtime_evidence_entry set revision=") && failOnce.compareAndSet(true,false))
                    throw new IllegalStateException("Injected failure after actual JDBC checkpoint UPDATE");
                return changed;
            }
        };
        var failingService=service(store(failingJdbc));
        assertThatThrownBy(()->failingService.reserve(scope,"task",lease,rev("task"),"op",intent,counters(1,"1")))
            .isInstanceOf(IllegalStateException.class);
        assertThat(evidence.readExecutionCheckpoint(scope,DurableCodingTaskService.CHECKPOINT_KEY)).contains(before);
        assertThat(jdbc.queryForObject("select revision from runtime_evidence_partition",Long.class)).isEqualTo(partitionRevision);
        assertThat(service.budgetUsage(scope,"shared-budget").costAmount()).isEqualTo("0");
        assertEventsMatchSnapshot("task");
    }

    @Test void ownerTenantAndRunAreAuthenticatedBeforeAnyDatabaseAccess() {
        service.submit(contract("task",10));
        for(var foreign:List.of(scope("other","owner","run"),scope("tenant","other","run"),scope("tenant","owner","other"))) {
            code(()->service.get(foreign,"task"),AUTHORIZATION_DENIED);
            code(()->service.cancel(foreign,"task","not authorized"),AUTHORIZATION_DENIED);
            code(()->service.resume(foreign,"task"),AUTHORIZATION_DENIED);
            code(()->service.events(foreign,"task",0,10),AUTHORIZATION_DENIED);
        }
        assertThat(jdbc.queryForObject("select count(*) from runtime_evidence_partition",Integer.class)).isEqualTo(1);
    }

    @Test void persistedEventsReplayAndTailAcrossIndependentServiceInstances() throws Exception {
        service.submit(contract("task",10)); CountDownLatch delivered=new CountDownLatch(2);
        List<CodingExecutionEvent> received=new CopyOnWriteArrayList<>(); List<CodingAgentSPI.Error> errors=new CopyOnWriteArrayList<>();
        try(var subscription=service.subscribe(scope,"task",0,new CodingAgentSPI.Observer(){
            @Override public void onEvent(CodingExecutionEvent event){received.add(event);delivered.countDown();}
            @Override public void onError(CodingAgentSPI.Error error){errors.add(error);}
            @Override public void onClosed(){}
        })) {
            service(store(new JdbcTemplate(dataSource))).cancel(scope,"task","other node");
            assertThat(delivered.await(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(errors).isEmpty(); assertThat(received).extracting(CodingExecutionEvent::sequence).contains(1L,2L);
        assertEventsMatchSnapshot("task");
    }

    @Test void deadlineStopIsPersistedEvenWhenCompletionIsAttempted() {
        service.submit(contract("task",10)); var lease=claim("task","worker"); var proposal=proposal("task");
        clock.advance(Duration.ofHours(1));
        lease=service.claim(scope,"task","cleanup-worker",Duration.ofSeconds(10),rev("task"));
        var state=service.commitOutcome(scope,"task",lease,rev("task"),proposal);
        assertThat(state.reasonCode()).isEqualTo(DEADLINE_EXCEEDED);
        assertThat(state.status()).isEqualTo(Status.RUNNING);
        assertThat(state.executionControl().executorStopped()).isFalse();
    }

    @Test void repeatedReservationAndStartIntentNeverGrantASecondDispatch() {
        service.submit(contract("task",10));var lease=claim("task","worker");
        var reserved=service.reserve(scope,"task",lease,rev("task"),"op",intent,counters(1,"1"));
        assertThat(service.reserve(scope,"task",lease,0,"op",intent,counters(1,"1")).outcome()).isEqualTo(reserved.outcome());
        assertThat(service.startIntent(scope,"task",lease,rev("task"),"op","execution:one").dispatchIntentCreated()).isTrue();
        assertThat(service.startIntent(scope,"task",lease,0,"op","execution:one").dispatchIntentCreated()).isFalse();
        code(()->service.startIntent(scope,"task",lease,rev("task"),"op","execution:two"),EXECUTION_OUTCOME_UNKNOWN);
        code(()->service.reserve(scope,"task",lease,rev("task"),"op","b".repeat(64),counters(1,"1")),TASK_ID_CONFLICT);
    }

    @Test void accountingReservationCannotBypassExecutionCallBudget() {
        service.submit(contract("task",10));var lease=claim("task","worker");
        service.reserve(scope,"task",lease,rev("task"),"accounting",intent,counters(0,"1"));
        code(()->service.startIntent(scope,"task",lease,rev("task"),"accounting","execution:illegal"),CONTRACT_INVALID);
        code(()->service.settle(scope,"task",lease,rev("task"),"accounting",counters(1,"1"),true),EXECUTION_OUTCOME_UNKNOWN);
        assertThat(service.get(scope,"task").executionControl().executorStopped()).isFalse();
    }

    @Test void releasingAnUnstartedReservationDoesNotPermitAnUnbudgetedDispatch() {
        service.submit(contract("task",10));var lease=claim("task","worker");
        service.reserve(scope,"task",lease,rev("task"),"released",intent,counters(1,"1"));
        service.settle(scope,"task",lease,rev("task"),"released",counters(0,"0"),true);
        code(()->service.startIntent(scope,"task",lease,rev("task"),"released","execution:unbudgeted"),INVALID_STATE_TRANSITION);
    }

    @Test void renewalAndNoStartTakeoverPreserveReservations() {
        service.submit(contract("task",10));var lease=claim("task","worker");
        service.reserve(scope,"task",lease,rev("task"),"op",intent,counters(1,"1"));
        clock.advance(Duration.ofSeconds(5));
        var renewed=service.renew(scope,"task",lease,Duration.ofSeconds(10),rev("task"));
        assertThat(renewed.fencingToken()).isEqualTo(lease.fencingToken());
        clock.advance(Duration.ofSeconds(11));var next=claim("task","replacement");
        assertThat(service.resume(scope,"task").executionControl().reconciliationRequired()).isFalse();
        assertThat(service.reserve(scope,"task",next,rev("task"),"another",intent,counters(1,"1")).admitted()).isTrue();
        assertThat(service.budgetUsage(scope,"shared-budget").codeExecutions()).isEqualTo(2);
    }

    @Test void terminalEvidenceReconciliationIsFencedAndDoesNotRewriteTheResult() {
        service.submit(contract("task",10));var lease=claim("task","worker");
        service.cancel(scope,"task","no dispatch");var before=service.finishStoppedWithoutExecution(scope,"task",rev("task"));
        register("evidence:late");
        var updated=service.reconcileTerminalEvidence(scope,"task",lease,rev("task"),List.of("evidence:late"));
        assertThat(updated.status()).isEqualTo(Status.CANCELLED);assertThat(updated.endedAt()).isEqualTo(before.endedAt());
        assertThat(updated.revision()).isEqualTo(before.revision()+1);
        clock.advance(Duration.ofSeconds(11));claim("task","new-owner");
        code(()->service.reconcileTerminalEvidence(scope,"task",lease,rev("task"),List.of("evidence:late")),INVALID_STATE_TRANSITION);
    }

    @Test void eventCompactionIsExplicitAndTheLatestEventStillMatchesTheSnapshot() {
        service.submit(contract("task",10));var lease=claim("task","worker");
        for(int index=0;index<130;index++)service.renew(scope,"task",lease,Duration.ofSeconds(10),rev("task"));
        code(()->service.events(scope,"task",0,20),EVENT_CURSOR_EXPIRED);
        var snapshot=service.get(scope,"task");var events=service.events(scope,"task",snapshot.lastEventSequence()-128,256);
        assertThat(events).hasSize(128);assertThat(events.get(127).revision()).isEqualTo(snapshot.revision());
    }

    @Test void readOnlyGrantCannotAcquireExecutionOwnership() {
        service.submit(contract("task",10));
        var reader=new DurableCodingTaskService(evidence,json,clock,
            new BoundCodingTaskAccess(()->scope,Set.of(CodingTaskAccess.Action.READ)),
            (owner,reference)->false,null,workers,new ConcurrentTaskScheduler(timer));
        assertThat(reader.get(scope,"task").status()).isEqualTo(Status.CREATED);
        code(()->reader.claim(scope,"task","unprivileged",Duration.ofSeconds(10),0),AUTHORIZATION_DENIED);
    }

    @Test void missingOrUnknownJournalVersionCannotSilentlyEraseTasks() {
        service.submit(contract("task",10));
        String saved=evidence.readExecutionCheckpoint(scope,DurableCodingTaskService.CHECKPOINT_KEY).orElseThrow();
        assertThat(evidence.compareAndSetExecutionCheckpoint(scope,DurableCodingTaskService.CHECKPOINT_KEY,saved,"{}")).isTrue();
        code(()->service.get(scope,"task"),VERSION_UNSUPPORTED);
    }

    @Test void actualWallClockExpiryRejectsTheOldOwner() throws Exception {
        var prototype=contract("wall-clock-task",10); var old=prototype.budget();
        var futureBudget=new CodingTaskContract.Budget(old.budgetId(),Instant.ofEpochMilli(System.currentTimeMillis()+60000),
            old.maxModelCalls(),old.maxModelTokens(),old.maxToolCalls(),old.maxCodeExecutions(),old.maxCostAmount(),old.costCurrency(),
            old.maxCpuMillis(),old.maxMemoryBytes(),old.maxOutputBytes());
        var timed=new CodingTaskContract(prototype.schemaVersion(),prototype.taskId(),prototype.scope(),prototype.objective(),prototype.inputSnapshotRef(),
            prototype.metadataRefs(),prototype.workspaceRef(),prototype.authorization(),futureBudget,prototype.environmentRef(),prototype.validationPlanRef(),prototype.outputContract());
        var realClockService=new DurableCodingTaskService(evidence,json,Clock.systemUTC(),
            new BoundCodingTaskAccess(()->scope,EnumSet.allOf(CodingTaskAccess.Action.class)),
            (owner,ref)->false,null,workers,new ConcurrentTaskScheduler(timer));
        realClockService.submit(timed);
        var lease=realClockService.claim(scope,timed.taskId(),"old",Duration.ofMillis(30),0);
        Thread.sleep(80);
        code(()->realClockService.renew(scope,timed.taskId(),lease,Duration.ofSeconds(1),realClockService.get(scope,timed.taskId()).revision()),INVALID_STATE_TRANSITION);
        var next=realClockService.claim(scope,timed.taskId(),"new",Duration.ofSeconds(1),realClockService.get(scope,timed.taskId()).revision());
        assertThat(next.fencingToken()).isEqualTo(2);
    }

    @Test void twoIndependentJvmProcessesRecoverThePersistedUnknownStartIntent() throws Exception {
        String url="jdbc:h2:file:"+disk.resolve("process-recovery").toString().replace('\\','/')+";DATABASE_TO_LOWER=TRUE";
        assertThat(child("create",url)).contains("CREATED_IN_SEPARATE_JVM");
        assertThat(child("recover",url)).contains("RECOVERED|2|true|false|2");
    }

    String child(String mode,String url) throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Path output=disk.resolve(mode+"-jvm.log");
        Path arguments=disk.resolve(mode+"-jvm.args");
        Files.writeString(arguments,List.of("-cp",classpath,CodingRecoveryProcess.class.getName(),mode,url).stream()
            .map(value->"\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"")
            .collect(java.util.stream.Collectors.joining("\n")));
        var process=new ProcessBuilder(executable,"@"+arguments)
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();fail("Recovery child JVM timed out");}
        String log=Files.readString(output);
        assertThat(process.exitValue()).as(log).isZero();return log;
    }

    void assertEventsMatchSnapshot(String id) {
        var state=service.get(scope,id); var events=service.events(scope,id,0,256);
        var latest=events.get(events.size()-1);
        assertThat(latest.revision()).isEqualTo(state.revision()); assertThat(latest.sequence()).isEqualTo(state.lastEventSequence());
        assertThat(latest.status()).isEqualTo(state.status()); assertThat(latest.usage()).isEqualTo(state.usage());
    }
    static void code(Runnable operation,CodingErrorCode expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(CodingContractException.class,
            exception->assertThat(exception.error().code()).isEqualTo(expected));
    }
    <T> List<T> parallel(int count,Callable<T> action) throws Exception {
        CountDownLatch start=new CountDownLatch(1); List<Future<T>> futures=new ArrayList<>();
        for(int index=0;index<count;index++)futures.add(workers.submit(()->{await(start);return action.call();}));
        start.countDown(); List<T> result=new ArrayList<>(); for(var future:futures)result.add(future.get(20,TimeUnit.SECONDS));return result;
    }
    static void await(CountDownLatch latch) {
        try {if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Barrier timed out");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new RuntimeException(error);}
    }
    static final class MutableClock extends Clock {
        final AtomicLong time=new AtomicLong(Instant.parse("2026-10-15T00:00:00Z").toEpochMilli());
        void advance(Duration duration){time.addAndGet(duration.toMillis());}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return Instant.ofEpochMilli(time.get());}
        @Override public long millis(){return time.get();}
    }
}
