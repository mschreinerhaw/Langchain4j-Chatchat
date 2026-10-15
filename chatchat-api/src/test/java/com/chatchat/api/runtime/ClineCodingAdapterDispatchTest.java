package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.CodingJournal;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.coding.CodingTaskOutcome;
import com.chatchat.common.runtime.coding.CodingProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ClineCodingAdapterDispatchTest {
    @Test void missingNodeSessionCanRestartOnlyBeforeAnExternalOperationWasStarted() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:adapter-dispatch-" + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(source);
        var json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var evidence = new JdbcRuntimeEvidenceStore(new JdbcTemplate(source), json,
            new DataSourceTransactionManager(source));
        var scope = new KernelDataScope("tenant", "owner", null, null, "run", null, Map.of());
        var access = new CodingInvocationContext();
        var timer = Executors.newSingleThreadScheduledExecutor();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var dispatches = new AtomicInteger();
        http.createContext("/v1/runs", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/v1/runs/get")) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                dispatches.incrementAndGet();
                var reply = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(202, reply.length);
                try (var output = exchange.getResponseBody()) { output.write(reply); }
            }
            exchange.close();
        });
        http.start();
        try {
            var receipts = new ClineModelReceiptStore(evidence);
            var tasks = new DurableCodingTaskService(evidence, json, Clock.systemUTC(), access,
                (owner, ref) -> false, receipts, Runnable::run, new ConcurrentTaskScheduler(timer));
            var adapter = new ClineCodingAdapter(tasks, mock(CodingPythonExecutionBridge.class),
                mock(CodingInputSnapshotService.class), mock(CodingValidationPlanService.class),
                receipts, URI.create("http://127.0.0.1:" + http.getAddress().getPort()),
                "0123456789abcdef0123456789abcdef", json);
            var contract = contract(scope);
            access.as(scope, EnumSet.allOf(CodingTaskAccess.Action.class), () -> {
                adapter.submit(contract);
                assertThat(dispatches).hasValue(1);
                adapter.resume(scope, contract.taskId()); // Node lost a session before any external call.
                assertThat(dispatches).hasValue(2);
                var lease = tasks.ownership(scope, contract.taskId());
                var reserved = tasks.reserve(scope, contract.taskId(), lease,
                    tasks.get(scope, contract.taskId()).revision(), "model-1", "a".repeat(64),
                    CodingJournal.OperationKind.MODEL,
                    new CodingTaskOutcome.Counters(1, 2048, 0, 0, 0, 0, 0, "1"));
                tasks.startIntent(scope, contract.taskId(), lease, reserved.outcome().revision(),
                    "model-1", "runtime-model:model-1");
                adapter.submit(contract);
                adapter.resume(scope, contract.taskId()); // Unknown model outcome must retain reservation.
                assertThat(dispatches).hasValue(2);
                assertThat(tasks.unresolvedOperations(scope, contract.taskId())).containsExactly("model-1");
                return null;
            });
        } finally {
            http.stop(0);
            timer.shutdownNow();
        }
    }

    private static CodingTaskContract contract(KernelDataScope scope) {
        return new CodingTaskContract(CodingProtocol.VERSION, "task", scope,
            new CodingTaskContract.Objective("diagnose fixed fixture", List.of("rows.jsonl"), List.of("check")),
            "input:fixture", List.of(), "workspace:task",
            new CodingTaskContract.Authorization("decision:fixture", List.of(), List.of(), List.of(), List.of(),
                CodingTaskContract.NetworkPolicy.NONE, List.of(), List.of()),
            new CodingTaskContract.Budget("budget:task", Instant.now().plusSeconds(180)
                .truncatedTo(ChronoUnit.MILLIS), 1, 2048, 0, 0, "1", "USD", 0, 0, 4096),
            "environment:fixture", "validation:fixture",
            new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,
                "schema:result", "schema:validation", List.of("rows.jsonl"),
                List.of("application/x-ndjson"), 1, 4096,
                List.of(CodingTaskContract.RequiredEvidence.values())));
    }
}
