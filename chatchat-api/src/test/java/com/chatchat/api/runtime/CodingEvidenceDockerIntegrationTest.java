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
class CodingEvidenceDockerIntegrationTest {
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
    @TempDir Path temporary;
    final KernelDataScope scope = new KernelDataScope("tenant", "owner", "trace", null, "run", null, Map.of());
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    DriverManagerDataSource evidenceSource, operationSource;
    JdbcRuntimeEvidenceStore evidence;
    DurableCodingTaskService tasks;
    CodingPythonExecutionBridge bridge;
    CodingEvidenceCollector collector;
    McpPythonControlPlanePort port;
    PythonManagedExecutionService managed;
    PythonDockerRuntime docker;
    ExecutorService workers;
    ScheduledExecutorService timer;
    PythonDataFileService dataFiles;
    PythonOperationWorkspace workspace;
    PythonCapabilityService capability;
    InternalCredentialProperties credentials;

    @BeforeEach void setup() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("coding.realDocker"));
        evidenceSource = new DriverManagerDataSource("jdbc:h2:mem:g2-evidence-" + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(evidenceSource);
        evidence = evidenceStore();
        operationSource = new DriverManagerDataSource("jdbc:h2:mem:g2-python-" + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = operationSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("../database/migration/h2/V20261015_01__mcp_python_managed_operation.sql")));
        }
        PythonRuntimeProperties properties = new PythonRuntimeProperties();
        properties.setWorkspaceRoot(temporary.resolve("workspaces").toString());
        properties.setDataRoot(temporary.resolve("data").toString());
        credentials = mock(InternalCredentialProperties.class);
        when(credentials.resolvedSecret()).thenReturn(SECRET);
        dataFiles = new PythonDataFileService(properties, credentials, json);
        dataFiles.store("tenant", "owner", "prices", Base64.getUrlEncoder().withoutPadding()
                .encodeToString("prices.csv".getBytes(StandardCharsets.UTF_8)), sha(INPUT),
            InternalSecretCipher.encryptBytes(INPUT.getBytes(StandardCharsets.UTF_8), SECRET));
        workspace = new PythonOperationWorkspace(properties, dataFiles, json);
        docker = new PythonDockerRuntime(properties, json, dataFiles);
        PythonEnvironment environment = new PythonEnvironment();
        environment.setId("env"); environment.setStatus("PUBLISHED"); environment.setVersionNumber(1);
        environment.setDockerImage("python:3.11-alpine"); environment.setRuntimeUser("10001:10001");
        environment.setNetworkPolicy("NONE"); environment.setCpuLimit("1"); environment.setMemoryLimit("256m");
        environment.setDiskLimit("64m"); environment.setTmpfsLimit("16m"); environment.setTimeoutSeconds(15);
        capability = mock(PythonCapabilityService.class);
        when(capability.managedEnvironmentState("env")).thenReturn(environment);
        managed = new PythonManagedExecutionService(new JdbcTemplate(operationSource),
            new DataSourceTransactionManager(operationSource), capability, docker, workspace, credentials, json);
        port = mock(McpPythonControlPlanePort.class);
        when(port.environment("env")).thenReturn(new McpPythonControlPlanePort.EnvironmentView("env", "Python", "fixture",
            "python:3.11-alpine", "3.11", "1", "256m", "64m", "16m", "10001:10001", "NONE", "", "{}",
            15, false, 1, "PUBLISHED"));
        when(port.start(any())).thenAnswer(call -> {
            McpPythonControlPlanePort.StartRequest r = call.getArgument(0);
            return managed.start(new PythonManagedExecutionService.StartCommand(r.tenantId(), r.ownerId(),
                r.runId(), r.taskId(), r.operationId(), r.attempt(), r.fencingToken(), r.environmentId(),
                r.assetId(), r.fileName(), InternalSecretCipher.encrypt(r.source(), SECRET), r.parameters(),
                r.inputSchemaJson(), r.authorizedFileIds(), r.authorizedFileHashes(), r.sourceSha256(),
                r.deadlineAt(), r.budgetReservationRef()), r.tenantId(), r.ownerId());
        });
        when(port.get(anyString(), anyString(), anyString(), anyString(), anyString())).thenAnswer(call ->
            managed.get(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                call.getArgument(3), call.getArgument(4)));
        when(port.cancel(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong())).thenAnswer(call ->
            managed.cancel(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                call.getArgument(3), call.getArgument(4), call.getArgument(5)));
        when(port.artifact(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(call -> managed.artifact(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                call.getArgument(3), call.getArgument(4), call.getArgument(5), call.getArgument(6)));
        workers = Executors.newFixedThreadPool(2); timer = Executors.newSingleThreadScheduledExecutor();
        tasks = new DurableCodingTaskService(evidence, json, Clock.systemUTC(),
            new BoundCodingTaskAccess(() -> scope, EnumSet.allOf(CodingTaskAccess.Action.class)),
            (owner, ref) -> evidence.find(owner, id(ref)).isPresent() || evidence.snapshot(owner, id(ref)).isPresent(),
            new McpPythonExecutionProofVerifier(port, json), workers, new ConcurrentTaskScheduler(timer));
        bridge = new CodingPythonExecutionBridge(tasks, port, json);
        collector = new CodingEvidenceCollector(evidence, evidence, tasks, port, json);
    }
    @AfterEach void stop() {
        if (managed != null) managed.shutdown();
        if (timer != null) timer.shutdownNow();
        if (workers != null) workers.shutdownNow();
    }
    JdbcRuntimeEvidenceStore evidenceStore() {
        return new JdbcRuntimeEvidenceStore(new JdbcTemplate(evidenceSource), json,
            new DataSourceTransactionManager(evidenceSource));
    }

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
            assertThat(dataset.readPage(0, 1).rows().get(0).get("amount").toString()).isEqualTo("20.0");
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
