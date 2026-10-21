package com.chatchat.api.assets;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RuntimePythonAssetArchiverTest {
    @Test void stoppedFailedExecutionArchivesDiagnosticsWithoutClaimingValidResults()throws Exception{
        var archive=mock(RuntimeAssetArchive.class);var python=mock(McpPythonControlPlanePort.class);var json=new ObjectMapper();
        var scope=new KernelDataScope("tenant","alice",null,null,"run",null,Map.of());byte[] log="ValueError: invalid input".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] manifest=json.writeValueAsBytes(Map.of("artifacts",List.of(Map.of("channel","logs","fileName","stderr.txt","size",log.length,"sha256",UserAssetService.hash(log)))));
        when(python.artifact("tenant","alice","run","task","operation","manifest","manifest.json")).thenReturn(manifest);
        when(python.artifact("tenant","alice","run","task","operation","logs","stderr.txt")).thenReturn(log);
        var operation=new McpPythonControlPlanePort.OperationView("operation","FAILED","sandbox",1,false,true,1,"codehash","digest",UserAssetService.hash(manifest),"failure");
        new RuntimePythonAssetArchiver(archive,python,json).stopped(scope,"task",operation);
        verify(archive).save(scope,"task",UserAssetService.Kind.LOG,"execution:operation:logs:stderr.txt","stderr.txt",log);
        verify(archive).context(eq(scope),eq("task"),eq("execution-stop:operation"),argThat(value->"NOT_EVALUATED".equals(value.get("resultValidity"))&&"FAILED".equals(value.get("status"))));
    }
    @Test void runningAndHashMismatchedExecutionsCannotPublish(){
        var archive=mock(RuntimeAssetArchive.class);var python=mock(McpPythonControlPlanePort.class);var scope=new KernelDataScope("tenant","alice",null,null,"run",null,Map.of());
        var service=new RuntimePythonAssetArchiver(archive,python,new ObjectMapper());
        service.stopped(scope,"task",new McpPythonControlPlanePort.OperationView("operation","RUNNING","sandbox",1,false,false,null,"code","image",null,null));
        verifyNoInteractions(python,archive);
        when(python.artifact(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(new byte[]{1});
        assertThatThrownBy(()->service.stopped(scope,"task",new McpPythonControlPlanePort.OperationView("operation","FAILED","sandbox",1,false,true,1,"code","image","a".repeat(64),null))).isInstanceOf(SecurityException.class);
        verify(archive,never()).save(any(),anyString(),any(),anyString(),anyString(),any());
    }
}
