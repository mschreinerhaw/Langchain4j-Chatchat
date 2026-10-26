package com.chatchat.mcpserver.python;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalSecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PythonManagedExecutionServiceTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    @TempDir Path temporary;
    private PythonManagedExecutionService service;

    @AfterEach void shutdown() { if (service != null) service.shutdown(); }

    @Test void concurrentCompletionAndPollingArchiveOnceAndKeepTheStopReceiptStable() throws Exception {
        var h=harness(false);
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return call.callRealMethod();})
            .when(h.workspace).archive(any());
        h.start("racing-archive",1,"print('fixture')");
        assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var poll=pool.submit(()->service.get("tenant","owner","run","task","racing-archive"));
            assertThrows(java.util.concurrent.TimeoutException.class,()->poll.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals("SUCCEEDED",poll.get(5,java.util.concurrent.TimeUnit.SECONDS).status());
            var stopped=await("SUCCEEDED","tenant","racing-archive");
            byte[] receipt=service.artifact("tenant","owner","run","task","racing-archive","logs","receipt.json");
            assertTrue(new ObjectMapper().readTree(receipt).path("stopConfirmed").asBoolean());
            for(int i=0;i<5;i++)assertEquals(stopped.manifestHash(),service.get("tenant","owner","run","task","racing-archive").manifestHash());
            assertArrayEquals(receipt,service.artifact("tenant","owner","run","task","racing-archive","logs","receipt.json"));
            verify(h.workspace,times(1)).archive(any());
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void cancelBeforeAdmissionPersistsNoStartProofAndBlocksLateStartAcrossRestart() throws Exception {
        Harness h = harness(false);
        var stopped = service.cancel("tenant", "owner", "run", "task", "not-admitted", 1);
        assertEquals("CANCELLED", stopped.status()); assertTrue(stopped.executorStopped());
        assertNull(stopped.containerId()); assertNull(stopped.imageDigest());
        byte[] receipt = service.artifact("tenant", "owner", "run", "task", "not-admitted", "logs", "receipt.json");
        assertTrue(new ObjectMapper().readTree(receipt).path("dispatchPrevented").asBoolean());
        assertEquals(stopped, h.start("not-admitted", 1, "print('must never execute')"));
        assertArrayEquals(receipt, service.artifact("tenant", "owner", "run", "task", "not-admitted", "logs", "receipt.json"));
        assertThrows(Exception.class, () -> service.artifact("tenant", "owner", "run", "task", "not-admitted", "results", "rows.jsonl"));
        assertThrows(Exception.class, () -> service.get("other-tenant", "owner", "run", "task", "not-admitted"));
        service.shutdown();
        service = new PythonManagedExecutionService(h.jdbc, new DataSourceTransactionManager(h.jdbc.getDataSource()),
            mock(PythonCapabilityService.class), h.docker, mock(PythonOperationWorkspace.class), mock(InternalCredentialProperties.class), new ObjectMapper());
        assertEquals(stopped, service.get("tenant", "owner", "run", "task", "not-admitted"));
        assertEquals(stopped, h.start("not-admitted", 2, "print('still forbidden')"));
        verifyNoInteractions(h.docker);
        assertEquals(0L, h.jdbc.queryForObject("select count(*) from mcp_python_managed_operation", Long.class));
    }

    @Test void cancellationWinsAgainstStartAlreadyPreparingItsAdmission() throws Exception {
        Harness h = harness(false);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(h.docker.resolveImageId(any())).thenAnswer(call -> { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); return "sha256:" + "a".repeat(64); });
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> h.start("racing", 1, "print('late')"));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("CANCELLED", service.cancel("tenant", "owner", "run", "task", "racing", 1).status());
            release.countDown();
            assertEquals("CANCELLED", pending.get(5, java.util.concurrent.TimeUnit.SECONDS).status());
            assertEquals(0, h.created.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void realJdbcIdempotencyFenceScopeAndArtifactAfterCleanup() throws Exception {
        Harness h = harness(false);
        var first = h.start("op-1", 1, "print('one')");
        assertEquals("op-1", first.operationId());
        await("SUCCEEDED", "tenant", "op-1");
        assertEquals(1, h.created.get());
        assertEquals("SUCCEEDED", h.start("op-1", 1, "print('one')").status());
        assertEquals(1, h.created.get());
        assertThrows(SecurityException.class, () -> h.start("op-1", 1, "print('different')"));
        h.start("op-2", 2, "print('two')");
        await("SUCCEEDED", "tenant", "op-2");
        assertThrows(IllegalStateException.class, () -> h.start("op-3", 1, "print('stale')"));
        assertThrows(Exception.class, () -> service.get("another-tenant", "owner", "run", "task", "op-1"));
        assertTrue(new String(service.artifact("tenant", "owner", "run", "task", "op-1", "results", "rows.jsonl"))
                .contains("amount"));
        assertEquals(2L, h.jdbc.queryForObject("select count(*) from mcp_python_managed_operation", Long.class));
        assertEquals(2L, h.jdbc.queryForObject("select fencing_token from mcp_python_managed_fence", Long.class));
    }

    @Test void realJdbcCancelAcceptedOperationCannotDispatchAfterStop() throws Exception {
        Harness h = harness(false);
        h.start("op-cancel", 1, "print('never')");
        // Cancellation races dispatch; either Docker was never created or the node confirms its stop.
        var stopped = service.cancel("tenant", "owner", "run", "task", "op-cancel", 1);
        assertTrue(stopped.cancelRequested() || "SUCCEEDED".equals(stopped.status()));
        if (stopped.cancelRequested()) {
            for (int attempt = 0; attempt < 100 && !service.get("tenant", "owner", "run", "task", "op-cancel").executorStopped(); attempt++)
                Thread.sleep(25);
            assertTrue(service.get("tenant", "owner", "run", "task", "op-cancel").executorStopped());
        }
    }

    @Test void realJdbcConcurrentDuplicateStartCreatesOnlyOneContainer() throws Exception {
        Harness h = harness(false);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<com.chatchat.common.mcp.python.McpPythonControlPlanePort.OperationView>> calls =
                    java.util.stream.IntStream.range(0, 8).mapToObj(i -> pool.submit(() -> {
                        release.await(); return h.start("same-operation", 1, "print('same')");
                    })).toList();
            release.countDown();
            for (var call : calls) assertEquals("same-operation", call.get(15, java.util.concurrent.TimeUnit.SECONDS).operationId());
            await("SUCCEEDED", "tenant", "same-operation");
            assertEquals(1, h.created.get());
            assertEquals(1L, h.jdbc.queryForObject("select count(*) from mcp_python_managed_operation", Long.class));
        } finally { pool.shutdownNow(); }
    }

    @Test void realJdbcUnknownStartIntentIsNeverDispatchedAgain() throws Exception {
        Harness h = harness(false);
        h.start("unknown", 1, "print('once')");
        var completed = await("SUCCEEDED", "tenant", "unknown");
        // Terminal journal commit precedes asynchronous Docker cleanup. Inject the
        // missing-container fault only after the fixture actually lost that container.
        awaitRemoved(h.docker, completed.containerId());
        h.jdbc.update("update mcp_python_managed_operation set status='STARTING',executor_stopped=false,manifest_hash=null,updated_at=? where operation_id='unknown'",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)));
        assertEquals("UNKNOWN", service.get("tenant", "owner", "run", "task", "unknown").status());
        assertEquals(1, h.created.get());
    }

    @Test void realDockerRunsArchivesAndCancelsTheActualContainer() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("coding.realDocker"));
        Harness h = harness(true);
        h.start("op-real", 1, "import pathlib\np=pathlib.Path('/workspace/output/results/rows.jsonl')\np.write_text('{\"amount\":12.50}\\n')");
        var done = await("SUCCEEDED", "tenant", "op-real");
        assertTrue(done.executorStopped());
        assertNotNull(done.manifestHash());
        assertTrue(new String(service.artifact("tenant", "owner", "run", "task", "op-real", "results", "rows.jsonl"))
                .contains("amount"));
        assertTrue(new String(service.artifact("tenant", "owner", "run", "task", "op-real", "code", "main.py"))
                .contains("write_text"));
        awaitRemoved(h.docker, done.containerId());
        h.start("op-a", 1, "import pathlib,time\ntime.sleep(1)\npathlib.Path('/workspace/output/results/rows.jsonl').write_text('{\"amount\":1}\\n')");
        h.start("op-b", 1, "import pathlib,time\ntime.sleep(1)\npathlib.Path('/workspace/output/results/rows.jsonl').write_text('{\"amount\":2}\\n')");
        await("SUCCEEDED", "tenant", "op-a");
        await("SUCCEEDED", "tenant", "op-b");
        assertNotEquals(new String(service.artifact("tenant", "owner", "run", "task", "op-a", "results", "rows.jsonl")),
                new String(service.artifact("tenant", "owner", "run", "task", "op-b", "results", "rows.jsonl")));
        h.start("op-fenced", 1, "import time\ntime.sleep(60)");
        await("RUNNING", "tenant", "op-fenced");
        h.start("op-new-owner", 2, "print('new owner')");
        service.recoverPending();
        assertEquals("FENCED", await("FENCED", "tenant", "op-fenced").status());
        assertThrows(IllegalStateException.class, () -> h.start("op-stale", 1, "print('stale')"));
        h.start("op-sleep", 2, """
                import subprocess,time,pathlib
                subprocess.Popen(['python','-c','import time;time.sleep(60)'])
                p=pathlib.Path('/workspace/output/results/ticks.jsonl')
                for i in range(600):
                    p.open('a').write('{"tick":1}\\n')
                    time.sleep(.1)
                """);
        for (int i = 0; i < 100; i++) {
            var state = service.get("tenant", "owner", "run", "task", "op-sleep");
            if ("RUNNING".equals(state.status())) break;
            Thread.sleep(50);
        }
        Thread.sleep(500);
        service.cancel("tenant", "owner", "run", "task", "op-sleep", 2);
        var cancelled = await("CANCELLED", "tenant", "op-sleep");
        assertTrue(cancelled.executorStopped());
        awaitRemoved(h.docker, cancelled.containerId());
        byte[] frozen = service.artifact("tenant", "owner", "run", "task", "op-sleep", "results", "ticks.jsonl");
        Thread.sleep(300);
        assertArrayEquals(frozen, service.artifact("tenant", "owner", "run", "task", "op-sleep", "results", "ticks.jsonl"));
    }

    @Test void realDockerAndFileDatabaseRecoverAfterChildJvmCrashWithoutRerun() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("coding.realDocker"));
        Path db = temporary.resolve("recovery-db").resolve("operation");
        java.nio.file.Files.createDirectories(db.getParent());
        // Parent reopens only after the child exits; no H2 TCP server or host reverse DNS is needed.
        String url = "jdbc:h2:file:" + db.toAbsolutePath().toString().replace('\\', '/');
        Path workspaceRoot = temporary.resolve("recovery-workspace");
        Path migration = Path.of("../database/migration/h2/V20261015_01__mcp_python_managed_operation.sql").toAbsolutePath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path arguments = temporary.resolve("recovery.args");
        java.nio.file.Files.writeString(arguments, List.of("-cp", classpath, PythonManagedRecoveryProcess.class.getName(),
                        url, workspaceRoot.toString(), migration.toString()).stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining("\n")));
        Path output = temporary.resolve("child.log");
        Process child = new ProcessBuilder(javaExecutable, "@" + arguments).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!child.waitFor(35, java.util.concurrent.TimeUnit.SECONDS)) {
            child.destroyForcibly(); child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            fail("Child JVM did not crash after Docker start: "+java.nio.file.Files.readString(output));
        }
        assertEquals(0, child.exitValue(), java.nio.file.Files.readString(output));
        assertTrue(java.nio.file.Files.readString(output).contains("RUNNING"));
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        PythonRuntimeProperties properties = new PythonRuntimeProperties();
        properties.setWorkspaceRoot(workspaceRoot.toString());
        ObjectMapper mapper = new ObjectMapper();
        InternalCredentialProperties credentials = mock(InternalCredentialProperties.class);
        when(credentials.resolvedSecret()).thenReturn(SECRET);
        PythonDataFileService dataFiles = new PythonDataFileService(properties, credentials, mapper);
        PythonDockerRuntime docker = new PythonDockerRuntime(properties, mapper, dataFiles);
        PythonCapabilityService capability = mock(PythonCapabilityService.class);
        when(capability.managedEnvironmentState("env")).thenReturn(PythonManagedRecoveryProcess.environment());
        service = new PythonManagedExecutionService(jdbc, new DataSourceTransactionManager(dataSource), capability, docker,
                new PythonOperationWorkspace(properties, dataFiles, mapper), credentials, mapper);
        var recovered = await("SUCCEEDED", "tenant", "restart");
        assertEquals(1L, jdbc.queryForObject("select count(*) from mcp_python_managed_operation", Long.class));
        assertTrue(new String(service.artifact("tenant", "owner", "run", "task", "restart", "results", "rows.jsonl"))
                .contains("amount"));
        awaitRemoved(docker, recovered.containerId());
    }

    @Test void changedEnvironmentVersionOrImageIsRejectedBeforeCreatingAnOperation() throws Exception {
        var h = harness(false);
        String code = "print('ok')";
        for (boolean changedVersion : List.of(true, false)) {
            var command = new PythonManagedExecutionService.StartCommand(
                "tenant", "owner", "run", "task", "drift", 1, 1, "env", "asset", "main.py",
                InternalSecretCipher.encrypt(code, SECRET), Map.of(), "{}", List.of(), Map.of(),
                PythonOperationWorkspace.hash(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                h.deadline, "reservation-1", changedVersion ? 2 : 1, "sha256:" + (changedVersion ? "a" : "b").repeat(64));
            assertThrows(IllegalStateException.class, () -> service.start(command, "tenant", "owner"));
        }
        assertEquals(0, h.created.get());
        assertEquals(0L, h.jdbc.queryForObject("select count(*) from mcp_python_managed_operation", Long.class));
    }

    private Harness harness(boolean realDocker) throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:pyop-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("../database/migration/h2/V20261015_01__mcp_python_managed_operation.sql")));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("../database/migration/h2/V20261019_03__mcp_python_unadmitted_cancellation.sql")));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        PythonRuntimeProperties properties = new PythonRuntimeProperties();
        properties.setWorkspaceRoot(temporary.toString());
        PythonDataFileService dataFiles = mock(PythonDataFileService.class);
        ObjectMapper mapper = new ObjectMapper();
        PythonOperationWorkspace workspace = spy(new PythonOperationWorkspace(properties, dataFiles, mapper));
        PythonDockerRuntime docker = realDocker ? new PythonDockerRuntime(properties, mapper, dataFiles) : mock(PythonDockerRuntime.class);
        AtomicInteger created = new AtomicInteger();
        if (!realDocker) {
            when(docker.resolveImageId(any())).thenReturn("sha256:" + "a".repeat(64));
            AtomicReference<PythonDockerRuntime.ContainerState> state = new AtomicReference<>(new PythonDockerRuntime.ContainerState(false, false, false, null, "missing"));
            when(docker.managedContainerName(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt())).thenCallRealMethod();
            when(docker.inspectManaged(anyString())).thenAnswer(invocation -> state.get());
            doAnswer(invocation -> { created.incrementAndGet(); state.set(new PythonDockerRuntime.ContainerState(true, false, false, null, ""));
                Path root = invocation.getArgument(1);
                java.nio.file.Files.writeString(root.resolve("output/results/rows.jsonl"), "{\"amount\":12.50}\n"); return null; })
                    .when(docker).createManaged(any(), any(), anyString(), anyString(), anyString(), anyString());
            doAnswer(invocation -> {
                java.time.Instant started = java.time.Instant.now();
                state.set(new PythonDockerRuntime.ContainerState(true, true, false, null, false, started, null, ""));
                Thread finisher = new Thread(() -> {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    if (state.get().running()) state.set(new PythonDockerRuntime.ContainerState(true, false, true, 0, false, started,
                            java.time.Instant.now(), ""));
                });
                finisher.setDaemon(true); finisher.start();
                return null;
            }).when(docker).startManagedDetached(anyString());
            when(docker.waitManaged(anyString(), anyInt())).thenAnswer(invocation -> {
                Thread.sleep(60);
                return new PythonDockerRuntime.ExecResult(invocation.getArgument(0), 0, "ok", "", 1, false);
            });
            when(docker.stopManaged(anyString())).thenAnswer(invocation -> {
                state.set(new PythonDockerRuntime.ContainerState(true, false, true, 137, false,
                        java.time.Instant.now().minusMillis(100), java.time.Instant.now(), ""));
                return new PythonDockerRuntime.StopCheck(true, "");
            });
            when(docker.logsManaged(anyString())).thenReturn(new PythonDockerRuntime.ExecResult("", 0, "ok", "", 1, false));
            when(docker.removeManaged(anyString())).thenAnswer(invocation -> { state.set(new PythonDockerRuntime.ContainerState(false, false, false, null, "removed")); return true; });
        }
        PythonCapabilityService capability = mock(PythonCapabilityService.class);
        PythonEnvironment environment = new PythonEnvironment();
        environment.setId("env"); environment.setStatus("PUBLISHED"); environment.setVersionNumber(1); environment.setDockerImage("python:3.11-alpine");
        environment.setRuntimeUser("10001:10001"); environment.setNetworkPolicy("NONE"); environment.setCpuLimit("1");
        environment.setMemoryLimit("256m"); environment.setDiskLimit("64m"); environment.setTmpfsLimit("16m");
        environment.setTimeoutSeconds(10);
        when(capability.managedEnvironmentState("env")).thenReturn(environment);
        InternalCredentialProperties credentials = mock(InternalCredentialProperties.class);
        when(credentials.resolvedSecret()).thenReturn(SECRET);
        service = new PythonManagedExecutionService(jdbc, new DataSourceTransactionManager(dataSource), capability, docker, workspace, credentials, mapper);
        return new Harness(jdbc, docker, created,workspace);
    }

    private com.chatchat.common.mcp.python.McpPythonControlPlanePort.OperationView await(String status, String tenant, String op) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            var view = service.get(tenant, "owner", "run", "task", op);
            if (status.equals(view.status())) return view;
            Thread.sleep(50);
        }
        fail("Operation did not reach " + status + ": " + service.get(tenant, "owner", "run", "task", op));
        return null;
    }

    private void awaitRemoved(PythonDockerRuntime docker, String container) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (!docker.inspectManaged(container).exists()) return;
            Thread.sleep(50);
        }
        fail("Container remains after archive: " + container);
    }

    private final class Harness {
        final JdbcTemplate jdbc;
        final PythonDockerRuntime docker;
        final AtomicInteger created;
        final PythonOperationWorkspace workspace;
        final java.time.Instant deadline = java.time.Instant.now().plusSeconds(120);
        Harness(JdbcTemplate jdbc, PythonDockerRuntime docker, AtomicInteger created,PythonOperationWorkspace workspace) {
            this.jdbc = jdbc; this.docker = docker; this.created = created;this.workspace=workspace;
        }
        com.chatchat.common.mcp.python.McpPythonControlPlanePort.OperationView start(String operation, long token, String code) {
            return PythonManagedExecutionServiceTest.this.service.start(new PythonManagedExecutionService.StartCommand(
                    "tenant", "owner", "run", "task", operation, 1, token, "env", "asset", "main.py",
                    InternalSecretCipher.encrypt(code, SECRET), Map.of(), "{}", List.of(), Map.of(),
                    PythonOperationWorkspace.hash(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    deadline, "reservation-1"), "tenant", "owner");
        }
    }
}
