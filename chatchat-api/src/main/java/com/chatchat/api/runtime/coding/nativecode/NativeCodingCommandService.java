package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.coding.execution.CodingPythonExecutionBridge;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Freezes admitted native commands through the existing Python managed control plane. No shell on the API host. */
@Service
@ConditionalOnProperty(prefix="chatchat.coding.native",name="enabled",havingValue="true")
public final class NativeCodingCommandService {
    private final DurableCodingTaskService tasks;
    private final NativeCodingWorkspaceService workspace;
    private final CodingPythonExecutionBridge bridge;
    private final McpPythonControlPlanePort python;
    private final ObjectMapper json;
    public NativeCodingCommandService(DurableCodingTaskService tasks,NativeCodingWorkspaceService workspace,
            CodingPythonExecutionBridge bridge,McpPythonControlPlanePort python,ObjectMapper json){
        this.tasks=tasks;this.workspace=workspace;this.bridge=bridge;this.python=python;this.json=json;
    }
    public Map<String,Object> execute(CodingTaskContract c,CodingJournal.Lease lease,Map<String,Object> request){
        tasks.requireWorkerLease(c.scope(),c.taskId(),lease,false);workspace.requireAuthorized(c);
        String identity=Objects.toString(request.get("operationId"),"");
        int split=identity.lastIndexOf(":command:");
        if(split<1)throw new SecurityException("Native command identity required");
        String call=identity.substring(0,split),indexText=identity.substring(split+9);
        if(!indexText.matches("[1-9][0-9]?")||Integer.parseInt(indexText)>16)throw new SecurityException("Invalid command ordinal");
        int index=Integer.parseInt(indexText)-1;var intent=workspace.intent(c,call);
        if(!"run_commands".equals(intent.path("tool").asText()))throw new SecurityException("Native terminal admission required");
        String inputJson=intent.path("inputJson").asText();
        Object command=request.get("command");
        if(!(command instanceof String||command instanceof Map<?,?>)||inputJson.getBytes(StandardCharsets.UTF_8).length>64000)
            throw new SecurityException("Invalid or oversized native command intent");
        String commandHash=commandHash(command);
        JsonNode raw=parse(inputJson.getBytes(StandardCharsets.UTF_8));
        // Fast refusal for the ordinary string shape. The Sandbox independently
        // reruns the SDK normalizer for object commands and coalesced heredocs.
        if(raw.path("commands").isArray()&&raw.path("commands").size()==1&&raw.path("commands").get(0).isTextual()
                &&(index!=0||!command.equals(raw.path("commands").get(0).asText())))throw new SecurityException("Command differs from the actual model tool request");
        String operation=operation(c,identity),key="native-command-input:"+operation;
        var prepared=tasks.privateState(c.scope(),c.taskId(),key);
        if(prepared==null){
            var current=workspace.current(c);
            var manifest=NativeCodeWorkspace.verify(c.scope(),c.taskId(),map(request.get("manifest")),files(request.get("files")));
            if(!manifest.get("sha256").equals(map(current.get("manifest")).get("sha256")))throw new SecurityException("Command must freeze current source");
            if(c.budget().maxCodeExecutions()<1||c.budget().maxCpuMillis()<1)throw new SecurityException("No command budget authorized");
            long timeout=Math.min(300000,Math.max(1,c.budget().maxCpuMillis()/c.budget().maxCodeExecutions()));
            timeout=Math.min(timeout,Math.max(1,Duration.between(Instant.now(),c.budget().deadlineAt()).toMillis()));
            var spec=Map.of("toolInputJson",inputJson,"commandOrdinal",index+1,"commandHash",commandHash,
                "intentHash",intent.path("intentHash").asText(),"manifestHash",manifest.get("sha256"),"timeoutMs",timeout);
            String encoded=Base64.getEncoder().encodeToString(bytes(spec));
            String helperHash=helperHash();
            String source="import subprocess, hashlib\nfrom pathlib import Path\n"
                +"if hashlib.sha256(Path('/workspace/scripts/native_command.mjs').read_bytes()).hexdigest() != '"+helperHash+"':\n"
                +"    raise RuntimeError('NATIVE_EXECUTOR_VERSION_CHANGED')\n"
                +"raise SystemExit(subprocess.call(['node', '/workspace/scripts/native_command.mjs', '"+encoded+"']))\n";
            byte[] tree=bytes(current);String fileHash=NativeCodeWorkspace.sha(tree),fileId="ncode-"+fileHash.substring(0,56);
            var allocation=tasks.allocateScript(c.scope(),c.taskId(),lease,operation);
            var value=new LinkedHashMap<String,Object>();value.put("operationId",operation);value.put("toolCallId",call);value.put("modelOperationId",intent.path("modelOperationId").asText());
            value.put("manifestHash",manifest.get("sha256"));value.put("commandHash",commandHash);value.put("intentHash",intent.path("intentHash").asText());value.put("commandOrdinal",index+1);
            value.put("source",source);value.put("sourceHash",NativeCodeWorkspace.sha(source.getBytes(StandardCharsets.UTF_8)));
            value.put("fileId",fileId);value.put("fileHash",fileHash);value.put("treeSource","native-code:"+manifest.get("sha256"));
            value.put("timeoutMs",timeout);value.put("fileName",allocation.fileName());
            value.put("executorHelperSha256",helperHash);
            tasks.privateState(c.scope(),c.taskId(),lease,key,null,json.valueToTree(value));prepared=tasks.privateState(c.scope(),c.taskId(),key);
        }
        if(!prepared.path("commandHash").asText().equals(commandHash)
                ||!prepared.path("manifestHash").asText().equals(map(request.get("manifest")).get("sha256")))throw new SecurityException("Command replay identity differs");
        // A stop proof is the only basis for replay; a lost start acknowledgment never causes redispatch.
        var proof=tasks.verifiedOperation(c.scope(),c.taskId(),operation);
        if(proof.isEmpty()){
            var current=workspace.current(c);
            if(!prepared.path("manifestHash").asText().equals(map(current.get("manifest")).get("sha256")))throw new SecurityException("Native code changed before dispatch");
            byte[] tree=bytes(current);
            if(!NativeCodeWorkspace.sha(tree).equals(prepared.path("fileHash").asText()))throw new SecurityException("Frozen source archive differs");
            python.uploadDataFile(c.scope().tenantId(),c.scope().userId(),prepared.path("fileId").asText(),"source-project.json",prepared.path("fileHash").asText(),tree);
            tasks.bindScriptHash(c.scope(),c.taskId(),lease,operation,prepared.path("sourceHash").asText());
            var parameters=Map.<String,Object>of("_runtime_native_project",Map.of("fileId",prepared.path("fileId").asText(),"sha256",prepared.path("fileHash").asText(),"manifestHash",prepared.path("manifestHash").asText()));
            var reservation=new CodingTaskOutcome.Counters(0,0,0,1,prepared.path("timeoutMs").asLong(),0,
                c.budget().maxOutputBytes()/c.budget().maxCodeExecutions(),"0");
            McpPythonControlPlanePort.OperationView started;
            synchronized(workspace.ioLock(c)){
                tasks.requireWorkerLease(c.scope(),c.taskId(),lease,false);
                if(tasks.verifiedOperation(c.scope(),c.taskId(),NativeCodingWorkspaceService.operation(c,call)).isPresent())
                    throw new SecurityException("Native command tool has already stopped");
                started=bridge.start(c.scope(),c,lease,operation,c.environmentRef(),"native-project",prepared.path("fileName").asText(),
                    prepared.path("source").asText(),parameters,"{}",Map.of(prepared.path("fileId").asText(),prepared.path("fileHash").asText()),reservation);
            }
            while(!started.executorStopped()){
                if(!Instant.now().isBefore(c.budget().deadlineAt())){bridge.cancelExecution(c.scope(),c.taskId(),operation);throw new IllegalStateException("Native execution deadline; stop requires reconciliation");}
                tasks.requireWorkerLease(c.scope(),c.taskId(),lease,false);
                try{Thread.sleep(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Native command callback interrupted; reconcile existing operation",interrupted);}
                started=bridge.get(c.scope(),c.taskId(),operation);
            }
            bridge.reconcile(c.scope(),c.taskId(),lease,operation);
        }
        return receipt(c,lease,operation,prepared,true);
    }
    /** Reads one independently stopped operation; never starts code or changes its workspace. */
    public Map<String,Object> receipt(CodingTaskContract c,CodingJournal.Lease lease,String operation,JsonNode prepared,boolean remember){
        var proof=tasks.verifiedOperation(c.scope(),c.taskId(),operation).orElseThrow(()->new SecurityException("Command stop proof is absent"));
        var stopped=bridge.get(c.scope(),c.taskId(),operation);
        if(!stopped.executorStopped()||stopped.exitCode()==null||!prepared.path("sourceHash").asText().equals(stopped.codeHash()))throw new SecurityException("Command execution binding differs");
        var environment=c.runtimeContext().get("pythonEnvironment") instanceof Map<?,?> value?value:Map.of();
        if(!Objects.equals(environment.get("imageDigest"),stopped.imageDigest()))throw new SecurityException("Command image differs from task snapshot");
        byte[] artifact;
        try{artifact=python.artifact(c.scope().tenantId(),c.scope().userId(),c.scope().runId(),c.taskId(),operation,"results","native-command.json");}
        catch(RuntimeException unavailable){
            var missing=new LinkedHashMap<String,Object>();missing.put("operationId",operation);missing.put("manifestHash",prepared.path("manifestHash").asText());
            missing.put("commandHash",prepared.path("commandHash").asText());missing.put("executorStopped",true);missing.put("stopProofRef",proof.stopProofRef());
            missing.put("exitCode",stopped.exitCode());missing.put("resultAvailable",false);missing.put("diagnosticCode","NATIVE_COMMAND_RESULT_UNAVAILABLE");
            missing.put("stdout",bridge.stdout(c.scope(),c.taskId(),operation));missing.put("stderr",bridge.stderr(c.scope(),c.taskId(),operation));
            remember(c,lease,missing,remember);return missing;
        }
        if(artifact.length>c.budget().maxOutputBytes())throw new SecurityException("Native terminal artifact exceeds task output cap");
        JsonNode result=parse(artifact);
        if(!prepared.path("manifestHash").asText().equals(result.path("manifestHash").asText())
                ||!prepared.path("commandHash").asText().equals(result.path("commandHash").asText())
                ||!prepared.path("intentHash").asText().equals(result.path("intentHash").asText())
                ||prepared.path("commandOrdinal").asInt()!=result.path("commandOrdinal").asInt(-1)
                ||!result.path("exitCode").canConvertToInt()||result.path("exitCode").asInt()!=stopped.exitCode()
                ||!"0.0.93".equals(result.path("sdkVersion").asText())
                ||!result.path("stdout").isTextual()||!result.path("stderr").isTextual())throw new SecurityException("Native terminal result differs from stopped process");
        var response=map(result);response.put("operationId",operation);response.put("stopProofRef",proof.stopProofRef());response.put("executorStopped",true);
        response.put("imageDigest",stopped.imageDigest());response.put("sourceHash",stopped.codeHash());
        response.put("executorHelperSha256",prepared.path("executorHelperSha256").asText());
        response.put("resultArtifactSha256",NativeCodeWorkspace.sha(artifact));
        response.put("resultAvailable",true);
        for(String stream:List.of("stdout","stderr")){
            String text=(String)response.get(stream);response.put(stream+"Bytes",text.getBytes(StandardCharsets.UTF_8).length);
            if(text.length()>8192){response.put(stream,text.substring(text.length()-8192));response.put("truncated",true);response.put(stream+"TailOnly",true);}
        }
        remember(c,lease,response,remember);
        return response;
    }
    private void remember(CodingTaskContract c,CodingJournal.Lease lease,Map<String,Object> response,boolean enabled){
        if(enabled){var previous=tasks.privateState(c.scope(),c.taskId(),"native-last-execution");
            tasks.privateState(c.scope(),c.taskId(),lease,"native-last-execution",previous,json.valueToTree(response));}
    }
    public static String operation(CodingTaskContract c,String identity){return "cline-"+NativeCodeWorkspace.sha((c.scope().tenantId()+"\0"+c.scope().userId()+"\0"+c.scope().runId()+"\0"+c.taskId()+"\0"+identity).getBytes(StandardCharsets.UTF_8));}
    private static String commandHash(Object command){return command instanceof String text?NativeCodeWorkspace.sha(text.getBytes(StandardCharsets.UTF_8)):CodingCanonicalHash.sha256Value(command);}
    public Map<String,Object> cancel(CodingTaskContract c){
        workspace.requireAuthorized(c);var requested=new ArrayList<String>();
        for(String operation:tasks.startedCodeOperations(c.scope(),c.taskId()))if(tasks.privateState(c.scope(),c.taskId(),"native-command-input:"+operation)!=null
                &&tasks.verifiedOperation(c.scope(),c.taskId(),operation).isEmpty()){
            bridge.cancelExecution(c.scope(),c.taskId(),operation);requested.add(operation);
        }
        return Map.of("cancellationRequested",requested);
    }
    private JsonNode parse(byte[] value){try{return json.readTree(value);}catch(Exception invalid){throw new SecurityException("Invalid native command evidence",invalid);}}
    private String helperHash(){try(var source=getClass().getResourceAsStream("/python/native_command.mjs")){
        if(source==null)throw new SecurityException("Pinned native executor helper unavailable");return NativeCodeWorkspace.sha(source.readAllBytes());
    }catch(java.io.IOException invalid){throw new IllegalStateException("Cannot pin native executor helper",invalid);}}
    private byte[] bytes(Object value){try{return json.writeValueAsBytes(value);}catch(Exception invalid){throw new IllegalArgumentException(invalid);}}
    private Map<String,Object> map(Object value){return json.convertValue(value,new TypeReference<LinkedHashMap<String,Object>>(){});}
    private Map<String,String> files(Object value){return NativeCodeWorkspace.files(json.convertValue(value,new TypeReference<LinkedHashMap<String,String>>(){}));}
}
