package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.coding.execution.CodingPythonExecutionBridge;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.RuntimeExecutionCheckpointPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

/** Deterministic unit boundary test using the REAL Runtime journal. The MCP port
 * is a counting fixture, never remote-operation or business acceptance evidence. */
public final class NativeProgressAdmissionProbe {
    static final class Checkpoints implements RuntimeExecutionCheckpointPort {
        private String value;
        public synchronized Optional<String> readExecutionCheckpoint(KernelDataScope s,String k){return Optional.ofNullable(value);}
        public synchronized boolean compareAndSetExecutionCheckpoint(KernelDataScope s,String k,String old,String next){if(!Objects.equals(value,old))return false;value=next;return true;}
        public synchronized String mutateExecutionCheckpoint(KernelDataScope s,String k,java.util.function.UnaryOperator<String> f){return value=f.apply(value);}
    }
    static void check(boolean ok){if(!ok)throw new AssertionError("Native progress admission regression");}
    public static void main(String[] args)throws Exception {
        var scope=new KernelDataScope("unit-tenant","unit-owner","unit-request","unit-session","unit-run",null,Map.of());
        var json=new ObjectMapper().findAndRegisterModules();
        var tasks=new DurableCodingTaskService(new Checkpoints(),json,Clock.systemUTC(),(s,a)->{check(scope.equals(s));},(s,r)->true,null,Runnable::run,new ConcurrentTaskScheduler());
        var contract=new CodingTaskContract("runtime_os.coding.v1","unit-task",scope,
            new CodingTaskContract.Objective("Unit test only",List.of("report"),List.of("criteria:unit")),"snapshot:unit",List.of("metadata:unit"),"workspace:unit",
            new CodingTaskContract.Authorization("decision:unit",List.of("python_execute"),List.of("unit-file"),List.of("input:unit"),List.of("output:unit"),CodingTaskContract.NetworkPolicy.NONE,List.of(),List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
            new CodingTaskContract.Budget("budget:unit",Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),5,10000,10,3,"10","USD",10000,1048576,10000),"unit-env","validation:unit",
            new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,"schema:unit","schema:validation",List.of("rows.jsonl"),List.of("application/x-ndjson"),10,10000,List.of(CodingTaskContract.RequiredEvidence.values())));
        tasks.submit(contract);var lease=tasks.claim(scope,contract.taskId(),"cline-adapter:unit",Duration.ofMinutes(5),tasks.get(scope,contract.taskId()).revision());
        String op="cline-"+"a".repeat(64),source="print(1)\n",hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var script=tasks.allocateScript(scope,contract.taskId(),lease,op);tasks.bindScriptHash(scope,contract.taskId(),lease,op,hash);
        var injected=new AtomicBoolean();var starts=new AtomicInteger();var queries=new AtomicInteger();
        var environment=new McpPythonControlPlanePort.EnvironmentView("unit-env","unit","unit","unit-image","3.11","1","1m","1m","1m","1000:1000","NONE",null,"[]",30,false,1,"PUBLISHED");
        var operation=new McpPythonControlPlanePort.OperationView(op,"RUNNING","unit-container",lease.fencingToken(),false,false,null,hash,"sha256:"+"c".repeat(64),null,null);
        var port=(McpPythonControlPlanePort)Proxy.newProxyInstance(NativeProgressAdmissionProbe.class.getClassLoader(),new Class[]{McpPythonControlPlanePort.class},(proxy,method,values)->switch(method.getName()){
            case "environment" -> {
                if(injected.compareAndSet(false,true))tasks.recordWorkerProgress(scope,contract.taskId(),lease,tasks.get(scope,contract.taskId()).revision(),1,"TOOL_STARTED",null,"b".repeat(64),Map.of("tool","read_files"));
                yield environment;
            }
            case "start" -> {starts.incrementAndGet();yield operation;}
            case "get" -> {queries.incrementAndGet();yield operation;}
            default -> throw new UnsupportedOperationException(method.getName());
        });
        var bridge=new CodingPythonExecutionBridge(tasks,port,json);
        var reservation=new CodingTaskOutcome.Counters(0,0,1,1,100,0,1000,"0");
        check(bridge.start(scope,contract,lease,op,"unit-env","asset:unit",script.fileName(),source,Map.of(),"{}",Map.of("unit-file","d".repeat(64)),reservation)==operation);
        check(injected.get()&&starts.get()==1);
        bridge.start(scope,contract,lease,op,"unit-env","asset:unit",script.fileName(),source,Map.of(),"{}",Map.of("unit-file","d".repeat(64)),reservation);
        check(starts.get()==1&&queries.get()==1);
        var wrongFence=new CodingJournal.Lease(lease.owner(),lease.leaseUntil(),lease.fencingToken()+1);
        try{bridge.start(scope,contract,wrongFence,"cline-"+"e".repeat(64),"unit-env","asset:unit",script.fileName(),source,Map.of(),"{}",Map.of("unit-file","d".repeat(64)),reservation);throw new AssertionError("Fenced dispatch accepted");}
        catch(CodingContractException expected){check(starts.get()==1);}
        System.out.println("Native progress/CAS race, duplicate dispatch, fencing: 3 checks PASS (real Journal; unit MCP fixture)");
    }
}
