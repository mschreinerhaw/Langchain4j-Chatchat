package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import static org.assertj.core.api.Assertions.*;

/** Real cumulative ledger: unknown reservations are retained, not released to pass the boundary. */
class CodingBudgetShareTest {
    @Test void roundedUpSharesReproduceTheObservedTwelfthAdmissionDenial() {
        try (var fixture = new Ledger("10",4_194_304)) {
            for(int i=1;i<=12;i++) {
                var historicalShare=new CodingTaskOutcome.Counters(0,0,1,1,25000,0,349526,"0");
                assertThat(fixture.reserve(i,CodingJournal.OperationKind.CODE,historicalShare))
                    .isEqualTo(i<12);
            }
        }
    }

    @Test void allDeclaredExecutionSlotsFitWithoutInventingUnknownUsage() {
        try (var fixture = new Ledger("10", 4_194_304)) {
            for (int i=1;i<=12;i++) {
                var counters=new CodingTaskOutcome.Counters(0,0,1,1,
                    CodingBudgetShare.units(300_000,12),0,CodingBudgetShare.units(4_194_304,12),"0");
                assertThat(fixture.reserve(i,CodingJournal.OperationKind.CODE,counters))
                    .as("execution %s must fit the declared cumulative cap",i).isTrue();
            }
            var usage=fixture.service.get(fixture.scope,"task").usage();
            assertThat(usage.reserved().codeExecutions()).isEqualTo(12);
            assertThat(usage.reserved().outputBytes()).isLessThanOrEqualTo(4_194_304);
            assertThat(usage.consumed().cpuMillis()).isZero();
            assertThat(fixture.reserve(13,CodingJournal.OperationKind.CODE,
                new CodingTaskOutcome.Counters(0,0,1,1,1,0,1,"0"))).isFalse();
        }
    }

    @Test void decimalCostRoundingDoesNotRejectTheLastPermittedModelCall() {
        try (var fixture = new Ledger("10", 4_194_304)) {
            for(int i=1;i<=24;i++) assertThat(fixture.reserve(i,CodingJournal.OperationKind.MODEL,
                new CodingTaskOutcome.Counters(1,1,0,0,0,0,0,CodingBudgetShare.amount("10",24)))).isTrue();
            assertThat(fixture.reserve(25,CodingJournal.OperationKind.MODEL,
                new CodingTaskOutcome.Counters(1,1,0,0,0,0,0,CodingBudgetShare.amount("10",24)))).isFalse();
        }
    }

    @Test void tinyPositiveLimitsCannotProduceFreeUnmeteredCalls() {
        try (var fixture = new Ledger("0.0000000000001", 1)) {
            var counters=new CodingTaskOutcome.Counters(1,1,0,0,0,0,0,
                CodingBudgetShare.amount("0.0000000000001",24));
            assertThat(fixture.reserve(1,CodingJournal.OperationKind.MODEL,counters)).isTrue();
            assertThat(fixture.reserve(2,CodingJournal.OperationKind.MODEL,counters)).isFalse();
        }
        assertThat(CodingBudgetShare.units(1,12)).isEqualTo(1);
    }

    private static final class Ledger implements AutoCloseable {
        final KernelDataScope scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
        final ExecutorService workers=Executors.newSingleThreadExecutor();
        final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
        final JdbcTemplate jdbc;
        final DurableCodingTaskService service;
        final CodingJournal.Lease lease;
        Ledger(String cost,long output) {
            var dataSource=new DriverManagerDataSource("jdbc:h2:mem:share-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
            new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(dataSource);
            jdbc=new JdbcTemplate(dataSource);
            var json=new ObjectMapper().findAndRegisterModules();
            var store=new JdbcRuntimeEvidenceStore(jdbc,json,new DataSourceTransactionManager(dataSource));
            service=new DurableCodingTaskService(store,json,Clock.systemUTC(),
                new BoundCodingTaskAccess(()->scope,EnumSet.allOf(CodingTaskAccess.Action.class)),
                (owner,reference)->store.find(owner,reference).isPresent(),null,workers,new ConcurrentTaskScheduler(timer));
            var contract=new CodingTaskContract(CodingProtocol.VERSION,"task",scope,
                new CodingTaskContract.Objective("Check cumulative admission",List.of("rows.jsonl"),List.of("plan")),
                "input:fixture",List.of(),"workspace:fixture",
                new CodingTaskContract.Authorization("decision:fixture",List.of("python_execute"),List.of("input:fixture"),
                    List.of(),List.of(),CodingTaskContract.NetworkPolicy.NONE,List.of(),List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
                new CodingTaskContract.Budget("budget",Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                    24,786432,24,12,cost,"USD",300000,536870912,output),
                "environment","plan",new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,
                    "schema","validation",List.of("rows.jsonl"),List.of("application/x-ndjson"),100,output,
                    List.of(CodingTaskContract.RequiredEvidence.values())));
            service.submit(contract);
            lease=service.claim(scope,"task","worker",Duration.ofSeconds(30),service.get(scope,"task").revision());
        }
        boolean reserve(int ordinal,CodingJournal.OperationKind kind,CodingTaskOutcome.Counters counters) {
            return service.reserve(scope,"task",lease,service.get(scope,"task").revision(),"operation-"+ordinal,
                "a".repeat(64),kind,counters).admitted();
        }
        public void close() {
            timer.shutdownNow(); workers.shutdownNow();
            // SHUTDOWN invalidates its statement; JdbcTemplate's DEBUG warning
            // inspection would query that already-closed statement afterwards.
            try (var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
                connection.createStatement().execute("SHUTDOWN");
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException("Cannot close test ledger",failure);
            }
        }
    }
}
