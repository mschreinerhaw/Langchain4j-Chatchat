package com.chatchat.mcpserver.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PythonExecutionLogBoundaryTest {
    @TempDir Path workspace;

    @Test void untrustedDiagnosticFieldsAndOldLogsCannotPersistSourceRows() throws Exception {
        Path logs = Files.createDirectories(workspace.resolve("output/logs"));
        Files.writeString(logs.resolve("runtime-diagnostic.json"), """
            {"stdoutBytes":81,"stderrBytes":120,"exceptionType":"ValueError","line":7,
             "message":"private source record","stdoutSha256":"not-a-hash"}
            """);
        Files.writeString(logs.resolve("stdout.txt"), "private source record");
        PythonExecutionLogBoundary.persist(workspace, new ObjectMapper());
        for (String name : java.util.List.of("runtime-diagnostic.json", "stdout.txt", "stderr.txt"))
            assertFalse(Files.readString(logs.resolve(name)).contains("private source record"));
        String stderr = Files.readString(logs.resolve("stderr.txt"));
        assertTrue(stderr.contains("ValueError:"));
        assertTrue(stderr.contains("line 7"));
        assertFalse(Files.readString(logs.resolve("runtime-diagnostic.json")).contains("not-a-hash"));
    }

    @Test void missingOrMalformedDiagnosticFailsClosed() throws Exception {
        Path logs = Files.createDirectories(workspace.resolve("output/logs"));
        Files.writeString(logs.resolve("runtime-diagnostic.json"), "private source record");
        PythonExecutionLogBoundary.persist(workspace, new ObjectMapper());
        assertFalse(Files.readString(logs.resolve("runtime-diagnostic.json")).contains("private source record"));
        assertTrue(Files.readString(logs.resolve("stdout.txt")).contains("omitted"));
    }
}
