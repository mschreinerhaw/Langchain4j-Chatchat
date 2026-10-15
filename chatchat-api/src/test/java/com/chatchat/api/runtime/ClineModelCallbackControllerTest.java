package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClineModelCallbackControllerTest {
    @Test void cancellationDuringProviderCallPreventsLateReceipt() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:model-cancel-" + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(source);
        var json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var evidence = new JdbcRuntimeEvidenceStore(new JdbcTemplate(source), json,
            new DataSourceTransactionManager(source));
        var scope = new KernelDataScope("tenant", "owner", null, null, "run", null, Map.of());
        var input = Map.<String, Object>of("fixture", "authorized");
        evidence.register(new EvidenceRecord(null, "input", scope, "CODING_INPUT_SNAPSHOT", "test",
            evidence.contentHash(input), input, null, System.currentTimeMillis(), Map.of()),
            new EvidenceLineage(null, "input", scope, List.of(), "task", "test", null, Map.of()));
        var contract = contract(scope);
        var access = new CodingInvocationContext();
        var receipts = new ClineModelReceiptStore(evidence);
        var timer = Executors.newSingleThreadScheduledExecutor();
        try {
            var tasks = new DurableCodingTaskService(evidence, json, Clock.systemUTC(), access,
                (owner, ref) -> evidence.find(owner, ref.replace("runtime-evidence:", "")).isPresent(),
                receipts, Runnable::run, new ConcurrentTaskScheduler(timer));
            var lease = access.as(scope, EnumSet.allOf(CodingTaskAccess.Action.class), () -> {
                var accepted = tasks.submit(contract);
                return tasks.claim(scope, "task", "cline-adapter:test", Duration.ofSeconds(90), accepted.revision());
            });
            var model = mock(ClineRuntimeModelBridge.class);
            when(model.complete(any(), any())).thenAnswer(invocation -> {
                access.as(scope, EnumSet.allOf(CodingTaskAccess.Action.class), () ->
                    tasks.cancel(scope, "task", "user cancelled"));
                return Map.of("text", "late", "toolCalls", List.of(),
                    "usage", Map.of("inputTokens", 4, "outputTokens", 2));
            });
            var controller = new ClineModelCallbackController(access, tasks, model, receipts, json);
            var base = json.createObjectNode();
            base.set("scope", json.valueToTree(scope)); base.put("taskId", "task");
            base.put("contractHash", CodingCanonicalHash.sha256(contract));
            base.put("fencingToken", lease.fencingToken());
            base.set("contract", json.valueToTree(contract));
            var admission = base.deepCopy();
            admission.set("model", json.valueToTree(Map.of("operationId", "model-1", "modelCalls", 1,
                "maxTokens", 2048)));
            controller.reserve(admission);
            var execution = base.deepCopy();
            execution.set("request", json.valueToTree(Map.of("operationId", "model-1",
                "messages", List.of(), "tools", List.of())));
            assertThatThrownBy(() -> controller.model(execution))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Coding ownership or stop state changed");
            assertThat(receipts.response(scope, "task", "model-1")).isEmpty();
        } finally { timer.shutdownNow(); }
    }

    @Test void modelCallHasDurableIntentReceiptReconciliationAndUnknownCostReservation() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:model-callback-" + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(source);
        var json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var evidence = new JdbcRuntimeEvidenceStore(new JdbcTemplate(source), json,
            new DataSourceTransactionManager(source));
        var scope = new KernelDataScope("tenant", "owner", null, null, "run", null, Map.of());
        var input = Map.<String, Object>of("fixture", "authorized");
        evidence.register(new EvidenceRecord(null, "input", scope, "CODING_INPUT_SNAPSHOT", "test",
            evidence.contentHash(input), input, null, System.currentTimeMillis(), Map.of()),
            new EvidenceLineage(null, "input", scope, List.of(), "task", "test", null, Map.of()));
        var contract = contract(scope);
        var access = new CodingInvocationContext();
        var receipts = new ClineModelReceiptStore(evidence);
        var timer = Executors.newSingleThreadScheduledExecutor();
        try {
            var tasks = new DurableCodingTaskService(evidence, json, Clock.systemUTC(), access,
                (owner, ref) -> evidence.find(owner, ref.replace("runtime-evidence:", "")).isPresent(),
                receipts, Runnable::run, new ConcurrentTaskScheduler(timer));
            var lease = access.as(scope, EnumSet.allOf(CodingTaskAccess.Action.class), () -> {
                var accepted = tasks.submit(contract);
                return tasks.claim(scope, "task", "cline-adapter:test", Duration.ofSeconds(90), accepted.revision());
            });
            var model = mock(ClineRuntimeModelBridge.class);
            when(model.complete(any(), any())).thenReturn(Map.of("text", "done", "toolCalls", List.of(),
                "usage", Map.of("inputTokens", 4, "outputTokens", 2), "requestId", "provider-1"));
            var controller = new ClineModelCallbackController(access, tasks, model, receipts, json);
            String hash = CodingCanonicalHash.sha256(contract);
            var base = json.createObjectNode();
            base.set("scope", json.valueToTree(scope)); base.put("taskId", "task");
            base.put("contractHash", hash); base.put("fencingToken", lease.fencingToken());
            base.set("contract", json.valueToTree(contract));
            var admission = base.deepCopy();
            admission.set("model", json.valueToTree(Map.of("operationId", "model-1", "modelCalls", 1, "maxTokens", 2048)));
            assertThat(controller.reserve(admission).get("dispatchIntentCreated")).isEqualTo(true);
            var execution = base.deepCopy();
            execution.set("request", json.valueToTree(Map.of("operationId", "model-1",
                "messages", List.of(), "tools", List.of())));
            assertThat(controller.model(execution).get("text")).isEqualTo("done");
            assertThat(controller.model(execution).get("text")).isEqualTo("done");
            verify(model, times(1)).complete(any(), any());
            assertThat(controller.settle(admission).get("meteringComplete")).isEqualTo(false);
            var stale = admission.deepCopy();
            stale.put("fencingToken", lease.fencingToken() + 1);
            assertThatThrownBy(() -> controller.reserve(stale))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Callback task, lease, or stop state");
            var outcome = access.as(scope, EnumSet.of(CodingTaskAccess.Action.READ), () -> tasks.get(scope, "task"));
            assertThat(outcome.executionControl().reconciliationRequired()).isFalse();
            assertThat(outcome.usage().consumed().modelCalls()).isEqualTo(1);
            assertThat(outcome.usage().consumed().modelTokens()).isEqualTo(6);
            assertThat(outcome.usage().reserved().costAmount()).isNotEqualTo("0");
            assertThat(receipts.response(scope, "task", "model-1")).isPresent();
        } finally { timer.shutdownNow(); }
    }

    private static CodingTaskContract contract(KernelDataScope scope) {
        return new CodingTaskContract(CodingProtocol.VERSION, "task", scope,
            new CodingTaskContract.Objective("calculate", List.of("report"), List.of("validation:1")),
            "runtime-evidence:input", List.of(), "workspace:1",
            new CodingTaskContract.Authorization("decision:1", List.of(), List.of(), List.of(), List.of(),
                CodingTaskContract.NetworkPolicy.NONE, List.of(), List.of()),
            new CodingTaskContract.Budget("budget:1", Instant.now().plusSeconds(180).truncatedTo(ChronoUnit.MILLIS),
                1, 2048, 0, 0, "1", "USD", 0, 0, 4096), "env:1", "validation:1",
            new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,
                "schema:1", "schema:2", List.of("rows.jsonl"), List.of("application/x-ndjson"),
                1, 4096, List.of(CodingTaskContract.RequiredEvidence.values())));
    }
}
