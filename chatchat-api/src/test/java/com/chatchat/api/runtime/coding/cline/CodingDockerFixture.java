package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector;
import com.chatchat.api.runtime.coding.execution.CodingPythonExecutionBridge;
import com.chatchat.api.runtime.coding.execution.McpPythonExecutionProofVerifier;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.security.*;
import com.chatchat.mcpserver.python.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.*;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Shared genuine Docker/JDBC fixtures; no test or model assertions are inherited. */
abstract class CodingDockerFixture {
    private static String id(String ref){return ref.startsWith("runtime-evidence:")?ref.substring(17):ref;}
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String INPUT = "trade_date,amount\n2026-10-12,12.50\n2026-10-13,7.50\n";
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
        doAnswer(call -> {
            ((java.io.OutputStream)call.getArgument(7)).write(port.artifact(call.getArgument(0),call.getArgument(1),
                call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5),call.getArgument(6)));
            return null;
        }).when(port).downloadArtifact(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(java.io.OutputStream.class));
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

    private static String sha(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(Exception ex) {throw new IllegalStateException(ex);}
    }
}
