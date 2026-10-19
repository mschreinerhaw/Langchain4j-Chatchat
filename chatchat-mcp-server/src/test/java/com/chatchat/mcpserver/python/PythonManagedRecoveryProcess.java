package com.chatchat.mcpserver.python;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalSecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Real child JVM that crashes after Docker start, leaving the durable row and container for recovery. */
public final class PythonManagedRecoveryProcess {
    static final String SECRET = "0123456789abcdef0123456789abcdef";
    public static void main(String[] args) throws Exception {
        String url = args[0];
        Path workspaceRoot = Path.of(args[1]);
        Path migration = Path.of(args[2]);
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration.resolveSibling("V20261019_03__mcp_python_unadmitted_cancellation.sql")));
        }
        PythonRuntimeProperties properties = new PythonRuntimeProperties();
        properties.setWorkspaceRoot(workspaceRoot.toString());
        ObjectMapper mapper = new ObjectMapper();
        InternalCredentialProperties credentials = org.mockito.Mockito.mock(InternalCredentialProperties.class);
        org.mockito.Mockito.when(credentials.resolvedSecret()).thenReturn(SECRET);
        PythonDataFileService dataFiles = new PythonDataFileService(properties, credentials, mapper);
        PythonDockerRuntime docker = new PythonDockerRuntime(properties, mapper, dataFiles);
        PythonEnvironment environment = environment();
        PythonCapabilityService capability = org.mockito.Mockito.mock(PythonCapabilityService.class);
        org.mockito.Mockito.when(capability.managedEnvironmentState("env")).thenReturn(environment);
        var service = new PythonManagedExecutionService(new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource),
                capability, docker, new PythonOperationWorkspace(properties, dataFiles, mapper), credentials, mapper);
        String code = "import pathlib,time\npathlib.Path('/workspace/output/results/rows.jsonl').write_text('{\"amount\":7}\\n')\ntime.sleep(8)";
        service.start(new PythonManagedExecutionService.StartCommand("tenant", "owner", "run", "task", "restart", 1, 1,
                "env", "asset", "main.py", InternalSecretCipher.encrypt(code, SECRET), Map.of(), "{}", List.of(), Map.of(),
                PythonOperationWorkspace.hash(code.getBytes(StandardCharsets.UTF_8)), Instant.now().plusSeconds(60), "reservation-restart"),
                "tenant", "owner");
        for (int i = 0; i < 200; i++) {
            if ("RUNNING".equals(service.get("tenant", "owner", "run", "task", "restart").status())) {
                System.out.println("RUNNING"); System.out.flush();
                Runtime.getRuntime().halt(0);
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("Container never reached RUNNING");
    }

    static PythonEnvironment environment() {
        PythonEnvironment environment = new PythonEnvironment();
        environment.setId("env"); environment.setStatus("PUBLISHED"); environment.setVersionNumber(1); environment.setDockerImage("python:3.11-alpine");
        environment.setRuntimeUser("10001:10001"); environment.setNetworkPolicy("NONE"); environment.setCpuLimit("1");
        environment.setMemoryLimit("256m"); environment.setDiskLimit("64m"); environment.setTmpfsLimit("16m");
        environment.setTimeoutSeconds(20);
        return environment;
    }
}
