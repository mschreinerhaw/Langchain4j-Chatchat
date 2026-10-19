package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.runtime.coding.CodingJournal;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector;
import com.chatchat.api.runtime.coding.evidence.CodingValidationPlanService;
import com.chatchat.api.runtime.coding.execution.CodingPythonExecutionBridge;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP worker status; execution/collector doubles do not constitute Docker or SDK recovery. */
class ClineCodingAdapterRecoveryTest {
    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED", "CANCELLED", "MISSING", "UNKNOWN_MODEL", "FAILED_PYTHON", "MISSING_FAILED_PYTHON", "MISSING_STOPPED", "MISSING_STOPPED_UNKNOWN_MODEL", "MODEL_ONLY", "MISSING_MODEL_ONLY", "LIVE_UNKNOWN_MODEL", "UNAVAILABLE_UNKNOWN_MODEL"})
    void stoppedWorkerRequiresVerifiedLatestExecutionBeforeEvidenceOnlyRecovery(String scenario) throws Exception {
        var scope = new KernelDataScope("tenant", "owner", null, null, "run", null, Map.of());
        var contract = ClineCodingAdapterDispatchTest.contract(scope);
        var tasks = mock(DurableCodingTaskService.class);
        var python = mock(CodingPythonExecutionBridge.class);
        var inputs = mock(CodingInputSnapshotService.class);
        var plans = mock(CodingValidationPlanService.class);
        var collector = mock(CodingEvidenceCollector.class);
        var receipts = mock(ClineModelReceiptStore.class);
        var at = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        boolean stopping = scenario.startsWith("MISSING_STOPPED");
        boolean unknownModel = scenario.endsWith("UNKNOWN_MODEL");
        var zero = new CodingTaskOutcome.Counters(0, 0, 0, 0, 0, 0, 0, "0");
        var current = new CodingTaskOutcome(CodingProtocol.VERSION, contract.taskId(), scope,
            CodingCanonicalHash.sha256(contract), CodingTaskOutcome.Status.RUNNING, 4, 4,
            at, at, at, null, stopping, null, "Awaiting verified completion", List.of(),
            contract.inputSnapshotRef(), null, null, null, null, null,
            CodingTaskOutcome.ValidationStatus.NOT_RUN,
            new CodingTaskOutcome.Reproducibility(CodingTaskOutcome.ReproducibilityLevel.CONDITIONAL,
                List.of("Fixture environment"), List.of()),
            new CodingTaskOutcome.Usage(contract.budget().budgetId(), "USD", zero, zero, false),
            new CodingTaskOutcome.ExecutionControl(stopping ? CodingErrorCode.CANCEL_REQUESTED : null, stopping ? at : null, false, stopping), null, null, Map.of());
        when(tasks.resume(scope, contract.taskId())).thenReturn(current);
        when(tasks.get(scope, contract.taskId())).thenReturn(current);
        when(tasks.contract(scope, contract.taskId())).thenReturn(contract);
        when(tasks.ownership(scope, contract.taskId())).thenReturn(new CodingJournal.Lease("old", Instant.EPOCH, 0));
        when(tasks.claim(eq(scope), eq(contract.taskId()), anyString(), any(Duration.class), eq(current.revision())))
            .thenAnswer(call -> new CodingJournal.Lease(call.getArgument(2), Instant.now().plusSeconds(120), 1));
        String operationId = "cline-" + "a".repeat(64);
        when(tasks.unresolvedOperations(scope, contract.taskId()))
            .thenReturn(unknownModel ? List.of("model-1") : List.of());
        when(tasks.finishStoppedAfterReconciliation(eq(scope), eq(contract.taskId()), any(), eq(current.revision())))
            .thenReturn(current);
        when(tasks.hasStartedOperations(scope, contract.taskId())).thenReturn(true);
        when(tasks.startedCodeOperations(scope, contract.taskId())).thenReturn(scenario.endsWith("MODEL_ONLY") ? List.of() : List.of(operationId));
        when(tasks.verifiedOperation(scope, contract.taskId(), operationId)).thenReturn(Optional.of(
            new DurableCodingTaskService.VerifiedOperation(operationId, 1, "executor:fixture", "proof:fixture")));
        when(python.get(scope, contract.taskId(), operationId)).thenReturn(new McpPythonControlPlanePort.OperationView(
            operationId, scenario.endsWith("FAILED_PYTHON") ? "FAILED" : "SUCCEEDED", null, 1,
            false, true, 0, "a".repeat(64), "image:fixture", "b".repeat(64), null));
        when(inputs.requireAuthorized(contract)).thenReturn(new CodingInputSnapshotService.Snapshot(
            contract.inputSnapshotRef(), "input", "input.jsonl", "a".repeat(64), 1, 20, "source"));
        var validator = mock(CodingEvidenceCollector.IndependentValidator.class);
        when(plans.validator(contract)).thenReturn(validator);
        when(collector.collect(eq(scope), eq(contract), eq(operationId), eq("main.py"), eq("rows.jsonl"), anyList(), eq(validator)))
            .thenReturn(new CodingEvidenceCollector.Collected(contract.inputSnapshotRef(), "evidence:code",
                "evidence:receipt", "evidence:result", "evidence:validation", "evidence:bundle", false,
                CodingTaskOutcome.ValidationStatus.INCONCLUSIVE));
        when(tasks.commitOutcome(eq(scope), eq(contract.taskId()), any(), eq(current.revision()), any()))
            .thenAnswer(call -> call.getArgument(4));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var dispatches = new AtomicInteger();
        server.createContext("/v1/runs", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/v1/runs")) dispatches.incrementAndGet();
            if (scenario.startsWith("MISSING")) exchange.sendResponseHeaders(404, -1);
            else if (scenario.startsWith("UNAVAILABLE")) exchange.sendResponseHeaders(503, -1);
            else {
                String status = scenario.startsWith("LIVE") ? "RUNNING" : List.of("UNKNOWN_MODEL", "FAILED_PYTHON", "MODEL_ONLY").contains(scenario) ? "COMPLETED" : scenario;
                byte[] bytes = ("{\"status\":\"" + status + "\",\"fencingToken\":1}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            }
            exchange.close();
        });
        server.start();
        try {
            var adapter = new ClineCodingAdapter(tasks, python, inputs, plans, collector, receipts,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "0123456789abcdef0123456789abcdef", new ObjectMapper().findAndRegisterModules());
            var result = adapter.resume(scope, contract.taskId());
            if (stopping) {
                assertThat(result).isSameAs(current);
                verify(tasks, times(unknownModel ? 0 : 1)).finishStoppedAfterReconciliation(eq(scope), eq(contract.taskId()), any(), eq(current.revision()));
                verifyNoInteractions(collector);
            } else if (scenario.endsWith("FAILED_PYTHON") || scenario.endsWith("MODEL_ONLY")) {
                assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.FAILED);
                assertThat(result.reasonCode()).isEqualTo(CodingErrorCode.EXECUTION_FAILED);
                assertThat(result.executionControl().executorStopped()).isTrue();
                verifyNoInteractions(collector);
            } else if (unknownModel) {
                assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.RUNNING);
                verifyNoInteractions(collector);
                verify(tasks, never()).commitOutcome(any(), anyString(), any(), anyLong(), any());
                if (scenario.startsWith("LIVE") || scenario.startsWith("UNAVAILABLE")) {
                    verifyNoInteractions(receipts);
                    verify(tasks, never()).reconcileStopped(any(), anyString(), any(), anyLong(), anyString(), anyString());
                }
            } else {
                assertThat(result.status()).isEqualTo(CodingTaskOutcome.Status.COMPLETED);
                assertThat(result.evidenceBundleRef()).isEqualTo("evidence:bundle");
                assertThat(result.validationStatus()).isEqualTo(CodingTaskOutcome.ValidationStatus.INCONCLUSIVE);
                assertThat(result.usage()).isEqualTo(current.usage());
            }
            assertThat(dispatches).hasValue(0);
            verify(python, never()).start(any(), any(), any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyMap(), anyString(), anyMap(), any());
        } finally { server.stop(0); }
    }
}
