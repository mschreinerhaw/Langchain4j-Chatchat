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
    @Test void optionalAndNullFieldsDoNotMakeSyntacticallyCompleteJsonlPartial() throws Exception {
        var properties=new PythonRuntimeProperties();properties.setWorkspaceRoot(temporary.toString());
        var json=new ObjectMapper();
        var workspaces=new PythonOperationWorkspace(properties,mock(PythonDataFileService.class),json);
        Path path=workspaces.path("tenant","owner","run","task","optional",1);
        workspaces.prepare(path,"main.py","print(1)",Map.of(),"tenant","owner",List.of());
        Files.writeString(path.resolve("output/results/rows.jsonl"),
            "{\"value\":1,\"note\":\"observed\"}\n{\"value\":null}\n{\"note\":\"not applicable\"}\n");
        var manifest=workspaces.archive(path);
        var result=manifest.artifacts().get(0);
        assertEquals("PRESENT",manifest.resultCompleteness());
        assertEquals(3,result.rows());
        assertEquals("MISSING|NULL|NUMBER",json.readTree(result.schemaJson()).path("value").asText());
        assertEquals("MISSING|STRING",json.readTree(result.schemaJson()).path("note").asText());
    }

    @Test void twoJsonValuesOnOnePhysicalLineAreStillAnIncompleteJsonlArtifact() throws Exception {
        var properties=new PythonRuntimeProperties();properties.setWorkspaceRoot(temporary.toString());
        var workspaces=new PythonOperationWorkspace(properties,mock(PythonDataFileService.class),new ObjectMapper());
        Path path=workspaces.path("tenant","owner","run","task","trailing",1);
        workspaces.prepare(path,"main.py","print(1)",Map.of(),"tenant","owner",List.of());
        Files.writeString(path.resolve("output/results/rows.jsonl"),"{\"value\":1} {\"value\":2}\n");
        var manifest=workspaces.archive(path);
        assertEquals("PARTIAL",manifest.resultCompleteness());
        assertEquals(0,manifest.artifacts().get(0).rows());
    }

    @Test void underscoreAuxiliaryFilesAreArchivedWhileHiddenFilesAndDirectoriesStayRejected() throws Exception {
        var properties=new PythonRuntimeProperties();properties.setWorkspaceRoot(temporary.toString());
        var workspaces=new PythonOperationWorkspace(properties,mock(PythonDataFileService.class),new ObjectMapper());
        Path valid=workspaces.path("tenant","owner","run","task","auxiliary",1);
        workspaces.prepare(valid,"main.py","print(1)",Map.of(),"tenant","owner",List.of());
        Files.writeString(valid.resolve("output/results/rows.jsonl"),"{\"count\":1}\n");
        Files.writeString(valid.resolve("output/results/_summary.json"),"{\"rows\":1}");
        var manifest=workspaces.archive(valid);
        assertEquals("UNVERIFIED",manifest.resultCompleteness());
        assertTrue(manifest.artifacts().stream().anyMatch(a->a.fileName().equals("_summary.json")));
        for(String name:List.of(".hidden","nested")) {
            Path invalid=workspaces.path("tenant","owner","run","task",name.replace(".",""),1);
            workspaces.prepare(invalid,"main.py","print(1)",Map.of(),"tenant","owner",List.of());
            if(name.equals("nested"))Files.createDirectory(invalid.resolve("output/results/nested"));
            else Files.writeString(invalid.resolve("output/results/"+name),"{}");
            assertThrows(IllegalStateException.class,()->workspaces.archive(invalid));
        }
    }

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
