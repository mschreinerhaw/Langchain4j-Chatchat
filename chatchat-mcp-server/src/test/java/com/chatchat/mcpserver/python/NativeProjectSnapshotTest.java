package com.chatchat.mcpserver.python;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.NativeCodeWorkspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeProjectSnapshotTest {
    @TempDir Path temporary;
    final ObjectMapper json=new ObjectMapper();
    final KernelDataScope scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
    Map<String,Object> params;PythonRuntimeProperties properties;PythonOperationWorkspace workspaces;
    @BeforeEach void setup()throws Exception {
        properties=new PythonRuntimeProperties();properties.setWorkspaceRoot(temporary.toString());properties.setNativeCodingEnabled(true);
        var files=Map.of("src/calc.py","print(1)\n","README.md","# Fixed project\n");
        var manifest=NativeCodeWorkspace.manifest(scope,"task",files,0,null);
        byte[] tree=json.writeValueAsBytes(Map.of("manifest",manifest,"files",files));String hash=NativeCodeWorkspace.sha(tree);
        var data=mock(PythonDataFileService.class);
        when(data.snapshotAuthorized(eq("tenant"),eq("owner"),eq("tree-input"),any())).thenAnswer(inv->{
            Path target=inv.getArgument(3);Files.createDirectories(target);Files.write(target.resolve("source-project.json"),tree);
            return new PythonDataFileService.SnapshotFile("tree-input","source-project.json",tree.length,hash);
        });
        workspaces=new PythonOperationWorkspace(properties,data,json);
        params=Map.of(NativeProjectSnapshot.PARAMETER,Map.of("fileId","tree-input","sha256",hash,"manifestHash",manifest.get("sha256")));
    }
    Path path(String operation){return workspaces.path("tenant","owner","run","task",operation,1);}
    @Test void bindsCompleteProjectToTaskAndVerifiesItOnEverySnapshotRead()throws Exception {
        Path target=path("valid");
        workspaces.prepare(target,"main.py","print(1)",params,"tenant","owner",List.of("tree-input"),null,"run","task");
        Path code=target.resolve("scripts/native-project/src/calc.py");assertThat(Files.readString(code)).isEqualTo("print(1)\n");
        assertThat(workspaces.readSnapshot(target).inputs()).hasSize(1);
        Files.writeString(code,"print(2)\n");
        assertThatThrownBy(()->workspaces.readSnapshot(target)).hasMessageContaining("checksum mismatch");
    }
    @Test void deniesWrongTaskMissingInputAndDisabledOrUnmanagedPaths(){
        assertThatThrownBy(()->workspaces.prepare(path("wrong"),"main.py","print(1)",params,"tenant","owner",List.of("tree-input"),null,"run","other"))
            .hasMessageContaining("CODE_SNAPSHOT_HASH_MISMATCH");
        assertThatThrownBy(()->workspaces.prepare(path("missing"),"main.py","print(1)",params,"tenant","owner",List.of(),null,"run","task"))
            .hasMessageContaining("not authorized");
        assertThatThrownBy(()->workspaces.prepare(path("unmanaged"),"main.py","print(1)",params,"tenant","owner",List.of("tree-input")))
            .hasMessageContaining("managed task");
        properties.setNativeCodingEnabled(false);
        assertThatThrownBy(()->workspaces.prepare(path("disabled"),"main.py","print(1)",params,"tenant","owner",List.of("tree-input"),null,"run","task"))
            .hasMessageContaining("enabled");
    }
    @Test void legacySnapshotCannotAcquireAnUnboundCodeMount()throws Exception {
        Path target=path("legacy");workspaces.prepare(target,"main.py","print(1)",Map.of(),"tenant","owner",List.of());
        Files.createDirectory(target.resolve("scripts/native-project"));
        assertThatThrownBy(()->workspaces.readSnapshot(target)).hasMessageContaining("Unbound");
    }
}
