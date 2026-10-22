package com.chatchat.mcpserver.python;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real filesystem snapshots and Python child processes; no fake execution success. */
class PythonScriptIsolationIntegrationTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    CodingTaskContract contract(String owner,String task) {
        var scope=new KernelDataScope("tenant",owner,null,null,"run-"+task,null,Map.of());
        return new CodingTaskContract(CodingProtocol.VERSION,task,scope,
            new CodingTaskContract.Objective("销售汇总",List.of("rows.jsonl"),List.of("criteria")),"input",List.of(),"workspace:"+task,
            new CodingTaskContract.Authorization("decision",List.of("python_execute"),List.of(),List.of(),List.of(),CodingTaskContract.NetworkPolicy.NONE,List.of(),List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
            new CodingTaskContract.Budget("budget:"+task,Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),10,1000,10,10,"1","USD",10000,100000000,100000),
            "environment","plan",new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,"schema","validation",List.of("rows.jsonl"),List.of("application/x-ndjson"),10,10000,List.of(CodingTaskContract.RequiredEvidence.values())));
    }
    PythonOperationWorkspace workspaces() {
        var props=new PythonRuntimeProperties();props.setWorkspaceRoot(root.toString());
        return new PythonOperationWorkspace(props,mock(PythonDataFileService.class),json);
    }
    int execute(Path workspace,CodingScript script) throws Exception {
        Path capture=workspace.resolve("scripts/_runtime_capture.py");
        try(var resource=getClass().getResourceAsStream("/python/runtime_capture.py")){Files.copy(resource,capture);}
        String python=System.getProperty("os.name").toLowerCase().contains("win")?"py":"python3";
        var process=new ProcessBuilder(python,capture.toString(),workspace.resolve("scripts").resolve(script.fileName()).toString(),
            workspace.resolve("output/logs/runtime-diagnostic.json").toString()).directory(root.toFile()).start();
        assertTrue(process.waitFor(30,TimeUnit.SECONDS));
        if(process.exitValue()!=0)assertTrue(process.getErrorStream().readAllBytes().length==0,"Child error must remain a sanitized diagnostic");
        return process.exitValue();
    }
    @Test void tenUsersAndTwoConcurrentSameBusinessTasksEachNeverOverwriteCodeOrResults() throws Exception {
        var workspaces=workspaces();var pool=Executors.newFixedThreadPool(10);var start=new CountDownLatch(1);
        var futures=new ArrayList<Future<Path>>();
        try {
            for(int user=0;user<10;user++)for(int task=0;task<2;task++){
                final String owner="user-"+user,id=owner+"-sales-"+task;
                futures.add(pool.submit(()->{
                    start.await();var contract=contract(owner,id);var script=CodingScript.allocate(contract,Instant.parse("2026-10-22T08:00:00Z"),1);
                    Path workspace=workspaces.path("tenant",owner,contract.scope().runId(),id,"op",1);
                    String output=workspace.resolve("output/results/rows.jsonl").toString().replace("\\","\\\\");
                    String source="from pathlib import Path\nPath('"+output+"').write_text('"+id+"')\n";
                    workspaces.prepare(workspace,script.fileName(),source,Map.of(),"tenant",owner,List.of(),script);
                    assertEquals(0,execute(workspace,script));
                    assertEquals(id,Files.readString(workspace.resolve("output/results/rows.jsonl")));
                    assertEquals(source,Files.readString(workspace.resolve("scripts").resolve(script.fileName())));
                    assertEquals(script,new PythonOperationWorkspace(newProps(),mock(PythonDataFileService.class),json).readSnapshot(workspace).script());
                    return workspace;
                }));
            }
            start.countDown();var paths=new HashSet<Path>();for(var future:futures)assertTrue(paths.add(future.get(60,TimeUnit.SECONDS)));
            assertEquals(20,paths.size());
        } finally {pool.shutdownNow();pool.awaitTermination(30,TimeUnit.SECONDS);}
    }
    PythonRuntimeProperties newProps(){var props=new PythonRuntimeProperties();props.setWorkspaceRoot(root.toString());return props;}
    @Test void syntaxFailureAndV2ExecutionPreserveV1AndRejectPathOrOwnerChanges() throws Exception {
        var workspaces=workspaces();var contract=contract("owner","sales");
        var v1=CodingScript.allocate(contract,Instant.now(),1);var v2=CodingScript.allocate(contract,Instant.now(),2);
        Path first=workspaces.path("tenant","owner",contract.scope().runId(),"sales","op1",1);
        Path second=workspaces.path("tenant","owner",contract.scope().runId(),"sales","op2",1);
        String broken="def broken(:\n    pass\n";
        workspaces.prepare(first,v1.fileName(),broken,Map.of(),"tenant","owner",List.of(),v1);
        assertNotEquals(0,execute(first,v1));
        var diagnostic=json.readTree(Files.readAllBytes(first.resolve("output/logs/runtime-diagnostic.json")));
        assertEquals("SyntaxError",diagnostic.path("exceptionType").asText(),diagnostic.toString());
        assertFalse(diagnostic.path("stack").isEmpty(),diagnostic.toString());
        assertEquals(v1.fileName(),diagnostic.path("stack").get(0).path("file").asText());
        workspaces.prepare(second,v2.fileName(),"print(42)",Map.of(),"tenant","owner",List.of(),v2);
        assertEquals(0,execute(second,v2));assertEquals(broken,Files.readString(first.resolve("scripts").resolve(v1.fileName())));
        assertThrows(SecurityException.class,()->workspaces.prepare(first,v1.fileName(),"changed",Map.of(),"tenant","owner",List.of(),v1));
        assertThrows(SecurityException.class,()->workspaces.prepare(first,v1.fileName(),broken,Map.of(),"tenant","intruder",List.of(),v1));
        for(String name:List.of("../evil.py","/tmp/evil.py","x\\evil.py","x..py"))
            assertThrows(IllegalArgumentException.class,()->workspaces.prepare(second,name,"print(1)",Map.of(),"tenant","owner",List.of()));
        assertThrows(IllegalArgumentException.class,()->new CodingScript(v2.fileName(),"/tmp/"+v2.fileName(),v2.workingDirectory(),2));
        assertThrows(IllegalArgumentException.class,()->new CodingScript(v2.fileName(),v2.scriptPath(),v2.workingDirectory(),1));
    }
}
