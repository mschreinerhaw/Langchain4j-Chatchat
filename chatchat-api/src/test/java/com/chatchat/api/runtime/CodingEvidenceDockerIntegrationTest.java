package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.security.*;
import com.chatchat.mcpserver.python.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.*;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real B Docker execution and two H2 journals, joined through the production A2 bridge and C collector. */
class CodingEvidenceDockerIntegrationTest extends CodingDockerFixture {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String INPUT = "trade_date,amount\n2026-10-12,12.50\n2026-10-13,7.50\n";
    private static final String SCRIPT = """
        import csv, decimal, pathlib
        rows=list(csv.DictReader(open('/data/input/prices/prices.csv')))
        amount=sum((decimal.Decimal(r['amount']) for r in rows),decimal.Decimal('0'))
        pathlib.Path('/workspace/output/results/rows.jsonl').write_text('{"amount":'+str(amount)+'}\\n')
        """;
    private static final String EMPTY_SCRIPT = """
        import pathlib
        pathlib.Path('/workspace/output/results/rows.jsonl').write_text('')
        """;
    @Test void fixedFinancialScriptsProduceGovernedEvidenceWithBudgetAndExecutionIdentity() throws Exception {
        Run normal = run("normal", SCRIPT, "SUCCEEDED");
        var good = collect(normal, CodingEvidenceDockerIntegrationTest::checkFinancialSum);
        assertThat(good.businessValidationPassed()).isTrue();
        assertThat(good.codeArtifactRef()).isEqualTo("runtime-evidence:coding:"
            + "e6e9098b60c40d05725cf8f695a22e2df8240d07312f3ad61cd4410b1fcb7222:code");
        assertSix(good, normal);
        publish(normal, good);
        try (var dataset = collector.openResult(scope, "normal", good.resultDatasetRef())) {
            assertThat(dataset.recordCount()).isEqualTo(1);
            assertThat(new java.math.BigDecimal(dataset.readPage(0, 1).rows().get(0).get("amount").toString()))
                .isEqualByComparingTo("20.0");
        }
        Run empty = run("empty", EMPTY_SCRIPT, "SUCCEEDED");
        var zero = collect(empty, rows -> new CodingEvidenceCollector.Validation(rows.isEmpty(), Map.of("zeroRowsAllowed", true)));
        assertThat(collector.openResult(scope, "empty", zero.resultDatasetRef()).recordCount()).isZero();
        assertSix(zero, empty);
        publish(empty, zero);
        Run mismatch = run("mismatch", SCRIPT, "SUCCEEDED");
        var failed = collect(mismatch, rows -> new CodingEvidenceCollector.Validation(false,
            Map.of("expectedReconciledAmount", "21.00", "actual", rows.get(0).get("amount"))));
        assertThat(failed.businessValidationPassed()).isFalse();
        assertThat(evidence.find(scope, id(failed.validationRecordRef())).orElseThrow().payload().get("status"))
            .isEqualTo("FAILED");
        assertThatThrownBy(() -> collector.resolve(scope, "normal", failed.resultDatasetRef()))
            .isInstanceOf(SecurityException.class);
        publish(mismatch, failed);
        assertThat(tasks.get(scope, "mismatch").validationStatus())
            .isEqualTo(CodingTaskOutcome.ValidationStatus.FAILED);
        Run pythonFailure = run("python-failure", "raise ValueError('injected')", "FAILED");
        assertThat(bridge.stderr(scope, "python-failure", pythonFailure.operationId()))
            .contains("ValueError: injected");
        assertThatThrownBy(() -> collect(pythonFailure, rows -> new CodingEvidenceCollector.Validation(true, Map.of())))
            .isInstanceOf(IllegalStateException.class);
        assertThat(evidence.snapshot(scope, "coding:" + sha("unused") + ":bundle")).isEmpty();
        KernelDataScope other = new KernelDataScope("other", "owner", "trace", null, "run", null, Map.of());
        assertThat(evidence.find(other, id(good.resultDatasetRef()))).isEmpty();
        assertThatThrownBy(() -> collector.openResult(other, "normal", good.resultDatasetRef()))
            .isInstanceOf(RuntimeException.class);
        managed.shutdown();
        managed = new PythonManagedExecutionService(new JdbcTemplate(operationSource),
            new DataSourceTransactionManager(operationSource), capability, docker, workspace, credentials, json);
        collector = new CodingEvidenceCollector(evidenceStore(), evidenceStore(), tasks, port, json);
        assertThat(collector.openResult(scope, "normal", good.resultDatasetRef()).recordCount()).isEqualTo(1);
        assertThat(collect(normal, CodingEvidenceDockerIntegrationTest::checkFinancialSum)).isEqualTo(good);
    }

    @Test void clinePythonCallbackUsesAuthorizedSnapshotAndArchivesSixReferences() throws Exception {
        when(port.uploadDataFile(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
            .thenAnswer(call -> {
                String fileId = call.getArgument(2), name = call.getArgument(3), hash = call.getArgument(4);
                byte[] bytes = call.getArgument(5);
                var saved = dataFiles.store(call.getArgument(0), call.getArgument(1), fileId,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8)),
                    hash, InternalSecretCipher.encryptBytes(bytes, SECRET));
                return new McpPythonControlPlanePort.DataFileResult(saved.id(), saved.storagePath(),
                    saved.pythonPath(), saved.fileSize(), saved.fileHash(), saved.status());
            });
        when(port.downloadDataFile(anyString(), anyString(), anyString())).thenAnswer(call ->
            InternalSecretCipher.decryptBytes(dataFiles.content(call.getArgument(0),
                call.getArgument(1), call.getArgument(2)), SECRET));
        var identity = new CodingInvocationContext();
        var inputs = new CodingInputSnapshotService(evidence, port, identity, json);
        var plans = new CodingValidationPlanService(evidence, inputs, identity, json,
            List.of(new FixtureDecimalCodingVerifier(port, json)));
        var delivered = new com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset(
            "template#financial", Map.of(), List.of(Map.of("tradeId", "t1", "amount", "0.10"),
                Map.of("tradeId", "t2", "amount", "0.20")));
        var input = identity.as(scope, EnumSet.of(CodingTaskAccess.Action.SUBMIT), () ->
            inputs.capture(scope, "cline-docker", List.of(delivered), "template#financial",
                "decision:financial", "workspace:cline-docker", 10, 4096));
        String validation = identity.as(scope, EnumSet.of(CodingTaskAccess.Action.SUBMIT), () ->
            plans.register(scope, "cline-docker", input,
                FixtureDecimalCodingVerifier.plan("amount", "total", "tradeId", false)));
        var contract = new CodingTaskContract(CodingProtocol.VERSION, "cline-docker", scope,
            new CodingTaskContract.Objective("Sum authorized amounts", List.of("rows.jsonl"), List.of(validation)),
            input.reference(), List.of(), "workspace:cline-docker",
            new CodingTaskContract.Authorization("decision:financial", List.of("python_execute"),
                List.of(input.fileId()), List.of(), List.of(), CodingTaskContract.NetworkPolicy.NONE,
                List.of(), List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
            new CodingTaskContract.Budget("budget-cline-docker", Instant.now().plusSeconds(180)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                0, 0, 2, 1, "1", "USD", 10000, 256 * 1024 * 1024, 100000),
            "env", validation, new CodingTaskContract.OutputContract(
                CodingTaskContract.ResultFormat.JSON_LINES, "schema:total", "schema:validation",
                List.of("rows.jsonl"), List.of("application/x-ndjson"), 100, 100000,
                List.of(CodingTaskContract.RequiredEvidence.values())));
        tasks.submit(contract);
        var lease = tasks.claim(scope, contract.taskId(), "cline-adapter:test", Duration.ofSeconds(90),
            tasks.get(scope, contract.taskId()).revision());
        var controller = new ClinePythonCallbackController(identity, tasks, inputs, plans, bridge, collector, json);
        String toolCall = "tool-1";
        String operationId = "cline-" + sha(scope.tenantId() + "\0" + scope.userId() + "\0"
            + scope.runId() + "\0" + contract.taskId() + "\0" + toolCall);
        var body = json.createObjectNode();
        body.set("scope", json.valueToTree(scope)); body.put("taskId", contract.taskId());
        body.put("contractHash", CodingCanonicalHash.sha256(contract));
        body.put("fencingToken", lease.fencingToken()); body.set("contract", json.valueToTree(contract));
        String script = "import json,decimal,pathlib\n"
            + "p=pathlib.Path('/data/input/" + input.fileId() + "/input.jsonl')\n"
            + "rows=[json.loads(x) for x in p.read_text().splitlines()]\n"
            + "v=sum((decimal.Decimal(x['amount']) for x in rows),decimal.Decimal('0'))\n"
            + "pathlib.Path('/workspace/output/results/rows.jsonl').write_text(json.dumps({'total':str(v)})+'\\n')\n";
        body.set("execution", json.valueToTree(Map.of("operationId", operationId,
            "toolCallId", toolCall, "fileName", "main.py", "source", script)));
        assertThat(controller.execute(body).get("operationId")).isEqualTo(operationId);
        assertThat(controller.execute(body).get("operationId")).isEqualTo(operationId);
        var query = body.deepCopy(); query.put("operationId", operationId);
        Map<String, Object> result = Map.of();
        for (int attempt = 0; attempt < 300; attempt++) {
            result = controller.get(query);
            if (Boolean.TRUE.equals(result.get("executorStopped"))) break;
            Thread.sleep(50);
        }
        assertThat(result.get("status")).isEqualTo("SUCCEEDED");
        assertThat(result.get("businessValidationPassed")).isEqualTo(true);
        for (String key : List.of("inputSnapshotRef", "codeArtifactRef", "executionReceiptRef",
                "resultDatasetRef", "validationRecordRef", "evidenceBundleRef"))
            assertThat(result.get(key)).isInstanceOf(String.class);
        assertThat(tasks.get(scope, contract.taskId()).usage().consumed().codeExecutions()).isEqualTo(1);
        var stale = body.deepCopy(); stale.put("fencingToken", lease.fencingToken() + 1);
        assertThatThrownBy(() -> controller.execute(stale)).isInstanceOf(RuntimeException.class);
        var finish = new ClineModelCallbackController(identity, tasks,
            mock(ClineRuntimeModelBridge.class), new ClineModelReceiptStore(evidence),
            inputs, plans, collector, json);
        var completion = body.deepCopy();
        completion.set("outcome", json.valueToTree(Map.of("status", "completed", "operationId", operationId)));
        assertThat(finish.finish(completion).get("status")).isEqualTo("COMPLETED");
        assertThat(finish.finish(completion).get("evidenceBundleRef"))
            .isEqualTo(result.get("evidenceBundleRef"));
        assertThat(tasks.get(scope, contract.taskId()).validationStatus())
            .isEqualTo(CodingTaskOutcome.ValidationStatus.PASSED);
    }

    @Test void runningCancellationAndArchiveFaultDoNotPublishStructuredEvidence() throws Exception {
        Run cancellation = startOnly("cancel", "import time\ntime.sleep(40)\n");
        McpPythonControlPlanePort.OperationView running = null;
        for (int i = 0; i < 200; i++) {
            running = bridge.get(scope, "cancel", cancellation.operationId());
            if ("RUNNING".equals(running.status())) break;
            Thread.sleep(50);
        }
        assertThat(running.status()).isEqualTo("RUNNING");
        bridge.cancel(scope, "cancel", cancellation.operationId(), "fixture cancel");
        McpPythonControlPlanePort.OperationView stopped = null;
        for (int i = 0; i < 200; i++) {
            stopped = bridge.get(scope, "cancel", cancellation.operationId());
            if ("CANCELLED".equals(stopped.status())) break;
            Thread.sleep(50);
        }
        assertThat(stopped.status()).isEqualTo("CANCELLED");
        assertThat(stopped.executorStopped()).isTrue();
        assertThat(docker.inspectManaged(stopped.containerId()).running()).isFalse();
        bridge.reconcile(scope, "cancel", cancellation.lease(), cancellation.operationId());
        assertThatThrownBy(() -> collect(cancellation, rows -> new CodingEvidenceCollector.Validation(true, Map.of())))
            .isInstanceOf(IllegalStateException.class);
        Run archiveFault = startOnly("archive-fault", """
            import pathlib
            pathlib.Path('/workspace/output/results/rows.jsonl').mkdir()
            """);
        McpPythonControlPlanePort.OperationView unknown = null;
        for (int i = 0; i < 200; i++) {
            unknown = bridge.get(scope, "archive-fault", archiveFault.operationId());
            if ("UNKNOWN".equals(unknown.status())) break;
            Thread.sleep(50);
        }
        assertThat(unknown.status()).isEqualTo("UNKNOWN");
        assertThat(unknown.manifestHash()).isNull();
        assertThatThrownBy(() -> collect(archiveFault, rows -> new CodingEvidenceCollector.Validation(true, Map.of())))
            .isInstanceOf(IllegalStateException.class);
        managed.shutdown();
        managed = new PythonManagedExecutionService(new JdbcTemplate(operationSource),
            new DataSourceTransactionManager(operationSource), capability, docker, workspace, credentials, json);
        assertThat(managed.get("tenant", "owner", "run", "archive-fault", archiveFault.operationId()).status())
            .isEqualTo("UNKNOWN");
        assertThat(evidence.find(scope, id(archiveFault.contract().inputSnapshotRef()))).isEmpty();
        assertThat(docker.removeManaged(unknown.containerId())).isTrue(); // fixture cleanup after asserting retained unknown execution
    }

    private record Run(CodingTaskContract contract, CodingJournal.Lease lease, String operationId) { }
    private Run run(String taskId, String script, String expectedStatus) throws Exception {
        Run started = startOnly(taskId, script);
        String operationId = started.operationId();
        CodingJournal.Lease lease = started.lease();
        CodingTaskContract contract = started.contract();
        McpPythonControlPlanePort.OperationView view = null;
        for (int i = 0; i < 250; i++) {
            view = bridge.get(scope, taskId, operationId);
            if (expectedStatus.equals(view.status())) break;
            Thread.sleep(50);
        }
        assertThat(view.status()).isEqualTo(expectedStatus);
        assertThat(view.executorStopped()).isTrue();
        assertThat(view.manifestHash()).isNotBlank();
        // The terminal receipt is committed before best-effort Docker cleanup.
        for (int i = 0; i < 100 && docker.inspectManaged(view.containerId()).exists(); i++) {
            Thread.sleep(50);
        }
        assertThat(docker.inspectManaged(view.containerId()).exists()).isFalse();
        bridge.reconcile(scope, taskId, lease, operationId);
        return new Run(contract, lease, operationId);
    }
    private Run startOnly(String taskId, String script) {
        CodingTaskContract contract = contract(taskId);
        tasks.submit(contract);
        CodingJournal.Lease lease = tasks.claim(scope, taskId, "worker", Duration.ofSeconds(90), tasks.get(scope, taskId).revision());
        String operationId = "op-" + taskId;
        bridge.start(scope, contract, lease, operationId, "env", "asset", "main.py", script,
            Map.of(), "{}", Map.of("prices", sha(INPUT)),
            new CodingTaskOutcome.Counters(0, 0, 0, 1, 1000, 0, 10000, "0"));
        return new Run(contract, lease, operationId);
    }
    private CodingEvidenceCollector.Collected collect(Run run, CodingEvidenceCollector.IndependentValidator validation) {
        return collector.collect(scope, run.contract(), run.operationId(), "main.py", "rows.jsonl",
            List.of(new CodingEvidenceCollector.InputFile("prices", "prices.csv", sha(INPUT))), validation);
    }
    private void assertSix(CodingEvidenceCollector.Collected result, Run run) {
        for (String ref : List.of(result.inputSnapshotRef(), result.codeArtifactRef(), result.executionReceiptRef(),
                result.resultDatasetRef(), result.validationRecordRef()))
            assertThat(collector.resolve(scope, run.contract().taskId(), ref)).isNotNull();
        assertThat(collector.resolveBundle(scope, run.contract().taskId(), result.evidenceBundleRef()).evidenceIds()).hasSize(5);
        assertThat(tasks.get(scope, run.contract().taskId()).usage().budgetId()).isEqualTo(run.contract().budget().budgetId());
        assertThat(tasks.get(scope, run.contract().taskId()).usage().consumed().codeExecutions()).isEqualTo(1);
        assertThat(managed.get("tenant", "owner", "run", run.contract().taskId(), run.operationId()).operationId())
            .isEqualTo(run.operationId());
    }
    private void publish(Run run, CodingEvidenceCollector.Collected refs) {
        CodingTaskOutcome old = tasks.get(scope, run.contract().taskId());
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        CodingTaskOutcome proposal = new CodingTaskOutcome(old.schemaVersion(), old.taskId(), scope,
            old.contractSha256(), CodingTaskOutcome.Status.COMPLETED, old.revision() + 1,
            old.lastEventSequence() + 1, old.createdAt(), now, old.startedAt(), now, false, null,
            "Fixed Python execution and independent validation recorded", List.of(), refs.inputSnapshotRef(),
            refs.codeArtifactRef(), refs.executionReceiptRef(), refs.resultDatasetRef(),
            refs.validationRecordRef(), refs.evidenceBundleRef(), refs.businessValidationPassed()
                ? CodingTaskOutcome.ValidationStatus.PASSED : CodingTaskOutcome.ValidationStatus.FAILED,
            old.reproducibility(), old.usage(), new CodingTaskOutcome.ExecutionControl(null, null, true, false),
            null, null, Map.of());
        assertThat(tasks.commitOutcome(scope, old.taskId(), run.lease(), old.revision(), proposal).status())
            .isEqualTo(CodingTaskOutcome.Status.COMPLETED);
    }
    private CodingTaskContract contract(String taskId) {
        return new CodingTaskContract(CodingProtocol.VERSION, taskId, scope,
            new CodingTaskContract.Objective("Verify financial fixture", List.of("rows.jsonl"), List.of("criteria:fixed")),
            "runtime-evidence:input-" + taskId, List.of(), "workspace:" + taskId,
            new CodingTaskContract.Authorization("decision:fixture", List.of("python.run"), List.of("prices"),
                List.of("prices"), List.of("rows.jsonl"), CodingTaskContract.NetworkPolicy.NONE, List.of(),
                List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
            new CodingTaskContract.Budget("budget-" + taskId, Instant.now().plusSeconds(180).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                0, 0, 10, 1, "10", "USD", 10000, 256 * 1024 * 1024, 100000), "env", "validation:fixed",
            new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES, "schema:amount",
                "schema:validation", List.of("rows.jsonl"), List.of("application/x-ndjson"), 100, 100000,
                List.of(CodingTaskContract.RequiredEvidence.values())));
    }
    private static CodingEvidenceCollector.Validation checkFinancialSum(List<Map<String, Object>> rows) {
        java.math.BigDecimal expected = java.math.BigDecimal.ZERO;
        Set<String> dates = new HashSet<>();
        String[] input = INPUT.strip().split("\\R");
        for (int i = 1; i < input.length; i++) {
            String[] values = input[i].split(",", -1);
            if (values.length != 2 || !dates.add(values[0]))
                throw new IllegalStateException("Independent fixture has duplicate date or invalid columns");
            expected = expected.add(new java.math.BigDecimal(values[1]));
        }
        boolean passed = rows.size() == 1 && new java.math.BigDecimal(rows.get(0).get("amount").toString())
            .compareTo(expected) == 0;
        return new CodingEvidenceCollector.Validation(passed, "java-financial-fixture-checker", "v1",
            Map.of("independentCsvSum", expected.toPlainString(), "sourceRows", dates.size()));
    }
    private static String id(String ref) { return ref.startsWith("runtime-evidence:") ? ref.substring(17) : ref; }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
