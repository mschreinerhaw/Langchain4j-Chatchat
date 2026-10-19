package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector;
import com.chatchat.api.runtime.coding.evidence.CodingValidationPlanService;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClineModelCallbackControllerTest {
    @Test void inputMetadataRequiresFrozenContractAndCurrentLeaseAndExcludesRawFields() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:input-context-" + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(source);
        var json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var evidence = new JdbcRuntimeEvidenceStore(new JdbcTemplate(source), json, new DataSourceTransactionManager(source));
        var scope = new KernelDataScope("tenant", "owner", null, null, "run", null, Map.of());
        var access = new CodingInvocationContext();
        var timer = Executors.newSingleThreadScheduledExecutor();
        try {
            var receipts = new ClineModelReceiptStore(evidence);
            var tasks = new DurableCodingTaskService(evidence, json, Clock.systemUTC(), access,
                (owner, ref) -> true, receipts, Runnable::run, new ConcurrentTaskScheduler(timer));
            var base = contract(scope);
            String fileId = "c" + "a".repeat(63);
            var contract = new CodingTaskContract(CodingProtocol.ANALYSIS_VERSION, base.taskId(), scope,
                base.objective(), base.inputSnapshotRef(), base.metadataRefs(), base.workspaceRef(),
                new CodingTaskContract.Authorization("decision:1", List.of(), List.of(fileId), List.of(), List.of(),
                    CodingTaskContract.NetworkPolicy.NONE, List.of(), List.of()),
                base.budget(), base.environmentRef(), base.validationPlanRef(), base.outputContract(),
                Map.of("schemaVersion", "runtime_os.coding.context.v1", "consistency", "TASK_SNAPSHOT", "inputFiles",
                    Map.of("schemaVersion", "runtime_os.coding.input.v1", "format", "PARQUET", "delivery", "SHARED_DIRECTORY",
                        "manifestFileId", fileId, "manifestSha256", "a".repeat(64))));
            var lease = access.as(scope, EnumSet.allOf(CodingTaskAccess.Action.class), () -> {
                var accepted = tasks.submit(contract);
                return tasks.claim(scope, "task", "cline-adapter:test", Duration.ofSeconds(90), accepted.revision());
            });
            var inputs = mock(CodingInputSnapshotService.class);
            var snapshot = new CodingInputSnapshotService.Snapshot("runtime-evidence:input", fileId,
                "manifest.json", "a".repeat(64), 2, 10, "source");
            when(inputs.requireAuthorized(any())).thenReturn(snapshot);
            when(inputs.describe(scope, "task", snapshot)).thenReturn(Map.of("fileId", fileId, "format", "PARQUET",
                "inputManifest", Map.of("schemaVersion", "runtime_os.coding.input.v1"), "rows", List.of("private"),
                "hostPath", "/private/data", "schemaJson", "duplicate"));
            var controller = new ClineModelCallbackController(access, tasks, mock(ClineRuntimeModelBridge.class),
                receipts, inputs, mock(CodingValidationPlanService.class), mock(CodingEvidenceCollector.class), json);
            var body = json.createObjectNode();
            body.set("scope", json.valueToTree(scope)); body.put("taskId", "task");
            body.put("contractHash", CodingCanonicalHash.sha256(contract)); body.put("fencingToken", lease.fencingToken());
            body.set("contract", json.valueToTree(contract));
            var response=controller.inputContext(body);
            assertThat(response).containsEntry("format", "PARQUET")
                .doesNotContainKeys("rows", "hostPath", "schemaJson");
            assertThat(json.valueToTree(response).path("files").get(0).path("virtualPath").asText())
                .isEqualTo("/data/input/"+fileId+"/manifest.json");
            body.put("fencingToken", lease.fencingToken() + 1);
            assertThatThrownBy(() -> controller.inputContext(body))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            verify(inputs, times(1)).requireAuthorized(any());
        } finally { timer.shutdownNow(); }
    }

    @Test void acknowledgedSdkAbortClosesAcceptedCancellationWithoutInventingSandboxProof() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:model-abort-" + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(source);
        var json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var evidence = new JdbcRuntimeEvidenceStore(new JdbcTemplate(source), json,
            new DataSourceTransactionManager(source));
        var scope = new KernelDataScope("tenant", "owner", null, null, "run", null, Map.of());
        var access = new CodingInvocationContext();
        var timer = Executors.newSingleThreadScheduledExecutor();
        try {
            var receipts = new ClineModelReceiptStore(evidence);
            var tasks = new DurableCodingTaskService(evidence, json, Clock.systemUTC(), access,
                (owner, ref) -> evidence.find(owner, ref.replace("runtime-evidence:", "")).isPresent(),
                receipts, Runnable::run, new ConcurrentTaskScheduler(timer));
            var contract = contract(scope);
            var lease = access.as(scope, EnumSet.allOf(CodingTaskAccess.Action.class), () -> {
                var accepted = tasks.submit(contract);
                return tasks.claim(scope, "task", "cline-adapter:test", Duration.ofSeconds(90), accepted.revision());
            });
            var received = new CountDownLatch(1);
            var failure = new AtomicReference<CodingAgentSPI.Error>();
            try (var subscription = access.as(scope, EnumSet.of(CodingTaskAccess.Action.READ), () ->
                tasks.subscribe(scope, "task", 0, new CodingAgentSPI.Observer() {
                    public void onEvent(CodingExecutionEvent event) { received.countDown(); }
                    public void onError(CodingAgentSPI.Error error) { failure.set(error); received.countDown(); }
                    public void onClosed() { }
                }))) {
                try { assertThat(received.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                assertThat(failure.get()).isNull();
            }
            var reader = access.as(scope, EnumSet.of(CodingTaskAccess.Action.READ), () -> access.subscriptionAccess(scope));
            reader.require(scope, CodingTaskAccess.Action.READ);
            assertThatThrownBy(() -> reader.require(scope, CodingTaskAccess.Action.WORKER))
                .isInstanceOf(CodingContractException.class);
            var other = new KernelDataScope("another-tenant", "owner", null, null, "run", null, Map.of());
            assertThatThrownBy(() -> reader.require(other, CodingTaskAccess.Action.READ))
                .isInstanceOf(CodingContractException.class);
            var controller = new ClineModelCallbackController(access, tasks,
                mock(ClineRuntimeModelBridge.class), receipts, mock(CodingInputSnapshotService.class),
                mock(CodingValidationPlanService.class), mock(CodingEvidenceCollector.class), json);
            var body = json.createObjectNode();
            body.set("scope", json.valueToTree(scope)); body.put("taskId", "task");
            body.put("contractHash", CodingCanonicalHash.sha256(contract));
            body.put("fencingToken", lease.fencingToken()); body.set("contract", json.valueToTree(contract));
            body.set("outcome", json.valueToTree(Map.of("status", "aborted")));
            access.as(scope, EnumSet.allOf(CodingTaskAccess.Action.class), () ->
                tasks.cancel(scope, "task", "user request"));
            assertThat(controller.finish(body).get("status")).isEqualTo("CANCELLED");
            assertThat(controller.finish(body).get("status")).isEqualTo("CANCELLED");
            var stopped = access.as(scope, EnumSet.of(CodingTaskAccess.Action.READ), () -> tasks.get(scope, "task"));
            assertThat(stopped.executionControl().executorStopped()).isTrue();
            assertThat(stopped.executionControl().reconciliationRequired()).isFalse();
        } finally { timer.shutdownNow(); }
    }

    @Test void sdkFailureWithoutStartedOperationBecomesDurableFailedOutcome() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:model-failed-" + UUID.randomUUID()
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
        var access = new CodingInvocationContext();
        var timer = Executors.newSingleThreadScheduledExecutor();
        try {
            var receipts = new ClineModelReceiptStore(evidence);
            var tasks = new DurableCodingTaskService(evidence, json, Clock.systemUTC(), access,
                (owner, ref) -> evidence.find(owner, ref.replace("runtime-evidence:", "")).isPresent(),
                receipts, Runnable::run, new ConcurrentTaskScheduler(timer));
            var contract = contract(scope);
            var lease = access.as(scope, EnumSet.allOf(CodingTaskAccess.Action.class), () -> {
                var accepted = tasks.submit(contract);
                return tasks.claim(scope, "task", "cline-adapter:test", Duration.ofSeconds(90), accepted.revision());
            });
            var controller = new ClineModelCallbackController(access, tasks,
                mock(ClineRuntimeModelBridge.class), receipts, mock(CodingInputSnapshotService.class),
                mock(CodingValidationPlanService.class), mock(CodingEvidenceCollector.class), json);
            var body = json.createObjectNode();
            body.set("scope", json.valueToTree(scope)); body.put("taskId", "task");
            body.put("contractHash", CodingCanonicalHash.sha256(contract));
            body.put("fencingToken", lease.fencingToken()); body.set("contract", json.valueToTree(contract));
            body.set("outcome", json.valueToTree(Map.of("status", "failed")));
            assertThat(controller.finish(body).get("status")).isEqualTo("FAILED");
            assertThat(controller.finish(body).get("status")).isEqualTo("FAILED");
            assertThat(access.as(scope, EnumSet.of(CodingTaskAccess.Action.READ), () ->
                tasks.get(scope, "task").reasonCode())).isEqualTo(CodingErrorCode.EXECUTION_FAILED);
        } finally { timer.shutdownNow(); }
    }

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
            var controller = new ClineModelCallbackController(access, tasks, model, receipts,
                mock(CodingInputSnapshotService.class), mock(CodingValidationPlanService.class),
                mock(CodingEvidenceCollector.class), json);
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
            assertThat(receipts.response(scope, "task", "model-1")).isPresent();
            var abort = base.deepCopy();
            abort.set("outcome", json.valueToTree(Map.of("status", "aborted")));
            assertThatThrownBy(() -> controller.finish(abort))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("EXECUTION_OUTCOME_UNKNOWN");
            access.as(scope, EnumSet.allOf(CodingTaskAccess.Action.class), () -> {
                var current = tasks.get(scope, "task");
                return tasks.reconcileStopped(scope, "task", lease, current.revision(), "model-1",
                    receipts.proofRef(scope, "task", "model-1"));
            });
            assertThat(controller.finish(abort).get("status")).isEqualTo("CANCELLED");
            verify(model, times(1)).complete(any(), any());
        } finally { timer.shutdownNow(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void modelCallHasDurableIntentReceiptReconciliationAndUnknownCostReservation(boolean invalidResponse) {
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
            if (invalidResponse) when(model.complete(any(), any())).thenThrow(
                new ClineRuntimeModelBridge.InvalidModelResponseException(Map.of("error", "Invalid tool JSON",
                    "toolCalls", List.of(), "usage", Map.of("inputTokens", 4, "outputTokens", 2))));
            var controller = new ClineModelCallbackController(access, tasks, model, receipts,
                mock(CodingInputSnapshotService.class), mock(CodingValidationPlanService.class),
                mock(CodingEvidenceCollector.class), json);
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
            if (invalidResponse) {
                assertThatThrownBy(() -> controller.model(execution)).hasMessageContaining("receipt archived");
                assertThatThrownBy(() -> controller.model(execution)).hasMessageContaining("Archived model response");
                verify(model, times(1)).complete(any(), any());
            } else {
                assertThat(controller.model(execution).get("text")).isEqualTo("done");
                assertThat(controller.model(execution).get("text")).isEqualTo("done");
            }
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
            access.as(scope, EnumSet.allOf(CodingTaskAccess.Action.class), () ->
                tasks.cancel(scope, "task", "user request after model receipt"));
            var abort = base.deepCopy();
            abort.set("outcome", json.valueToTree(Map.of("status", "aborted")));
            assertThat(controller.finish(abort).get("status")).isEqualTo("CANCELLED");
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
