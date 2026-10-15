package com.chatchat.mcpserver.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PythonOperationWorkspaceTest {
    @TempDir Path temporary;

    @Test void zeroRowsMissingOutputAndPartialJsonlAreDistinct() throws Exception {
        PythonRuntimeProperties properties = new PythonRuntimeProperties();
        properties.setWorkspaceRoot(temporary.toString());
        PythonOperationWorkspace workspaces = new PythonOperationWorkspace(properties, mock(PythonDataFileService.class), new ObjectMapper());
        Path empty = workspaces.path("tenant", "owner", "run", "task", "empty", 1);
        workspaces.prepare(empty, "main.py", "print(1)", Map.of(), "tenant", "owner", List.of());
        assertEquals("MISSING", workspaces.archive(empty).resultCompleteness());
        Path zero = workspaces.path("tenant", "owner", "run", "task", "zero", 1);
        workspaces.prepare(zero, "main.py", "print(1)", Map.of(), "tenant", "owner", List.of());
        Files.writeString(zero.resolve("output/results/rows.jsonl"), "");
        var zeroManifest = workspaces.archive(zero);
        assertEquals("EMPTY", zeroManifest.resultCompleteness());
        assertEquals(0, zeroManifest.artifacts().get(0).rows());
        Path partial = workspaces.path("tenant", "owner", "run", "task", "partial", 1);
        workspaces.prepare(partial, "main.py", "print(1)", Map.of(), "tenant", "owner", List.of());
        Files.writeString(partial.resolve("output/results/rows.jsonl"), "{\"amount\":1}\n{broken");
        var partialManifest = workspaces.archive(partial);
        assertEquals("PARTIAL", partialManifest.resultCompleteness());
        assertEquals(1, partialManifest.artifacts().get(0).rows());
        assertNotNull(partialManifest.artifacts().get(0).schemaJson());
        assertThrows(IllegalArgumentException.class, () -> workspaces.artifact(partial, "results", "../outside"));
    }
}
