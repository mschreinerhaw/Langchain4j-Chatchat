package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.assets.RuntimeAssetArchive;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.runtime.coding.cline.ClineModelReceiptStore;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Runtime governance and Silo storage only. All code editing belongs to SDK executors. */
@Service
@ConditionalOnProperty(prefix="chatchat.coding.native",name="enabled",havingValue="true")
public final class NativeCodingWorkspaceService {
    public static final String INPUT_TYPE="CODING_NATIVE_PROJECT_INPUT",TOOL_TYPE="CODING_NATIVE_TOOL_RECEIPT";
    private static final String CURRENT="native-workspace";
    private final DurableCodingTaskService tasks;
    private final JdbcRuntimeEvidenceStore evidence;
    private final RuntimeAssetArchive assets;
    private final ClineModelReceiptStore models;
    private final ObjectMapper json;
    private final java.util.concurrent.ConcurrentHashMap<String,Object> ioLocks=new java.util.concurrent.ConcurrentHashMap<>();
    public NativeCodingWorkspaceService(DurableCodingTaskService tasks,JdbcRuntimeEvidenceStore evidence,
            RuntimeAssetArchive assets,ClineModelReceiptStore models,ObjectMapper json){
        this.tasks=tasks;this.evidence=evidence;this.assets=assets;this.models=models;this.json=json;
    }
    public Map<String,Object> seed(KernelDataScope scope,String task,Map<String,String> supplied){
        var files=NativeCodeWorkspace.files(supplied);var manifest=NativeCodeWorkspace.manifest(scope,task,files,0,null);
        String source=source(manifest);saveTree(scope,task,source,manifest,files,"INITIAL",null);
        return Map.of("source",source,"manifest",manifest);
    }
    public Map<String,Object> requireAuthorized(CodingTaskContract c){
        tasks.get(c.scope(),c.taskId()).validateAgainst(c);
        return requireSnapshot(c);
    }
    public Map<String,Object> requireSnapshot(CodingTaskContract c){
        if(!NativeCodeWorkspace.enabled(c)||!CodingProtocol.WORKSPACE_VERSION.equals(c.schemaVersion())
                ||c.authorization().networkPolicy()!=CodingTaskContract.NetworkPolicy.NONE
                ||!c.authorization().allowedSideEffects().containsAll(List.of(CodingTaskContract.SideEffect.CODE_EXECUTION,CodingTaskContract.SideEffect.WORKSPACE_WRITE)))
            throw new SecurityException("Native project authority required");
        var record=evidence.find(c.scope(),id(c.inputSnapshotRef())).orElseThrow(()->new SecurityException("Native project input unavailable"));
        if(!INPUT_TYPE.equals(record.evidenceType())||!c.taskId().equals(record.payload().get("taskId"))
                ||!evidence.contentHash(record.payload()).equals(record.contentSha256()))throw new SecurityException("Native project input binding differs");
        return record.payload();
    }
    public Map<String,Object> open(CodingTaskContract c,CodingJournal.Lease lease){synchronized(ioLock(c)){return openLocked(c,lease);}}
    private Map<String,Object> openLocked(CodingTaskContract c,CodingJournal.Lease lease){
        tasks.requireWorkerLease(c.scope(),c.taskId(),lease,false);
        var input=requireAuthorized(c);var current=tasks.privateState(c.scope(),c.taskId(),CURRENT);
        if(current==null){
            var initial=map(input.get("initial"));readTree(c,initial);
            tasks.privateState(c.scope(),c.taskId(),lease,CURRENT,null,json.valueToTree(initial));current=tasks.privateState(c.scope(),c.taskId(),CURRENT);
        }
        var pointer=map(current);var tree=readTree(c,pointer);var response=new LinkedHashMap<String,Object>(tree);
        response.put("modelId",input.getOrDefault("routingModelId",input.get("modelName")));response.put("providerId",input.getOrDefault("providerId","runtime"));
        if(input.get("editStrategy") instanceof String strategy&&!"auto".equals(strategy))response.put("editStrategy",strategy);
        response.put("requireExecution",true);
        if(input.get("acceptanceCommand") instanceof String command){response.put("acceptanceCommand",command);response.put("acceptanceCommandHashes",NativeCommandVerification.hashes(command));}
        var execution=tasks.privateState(c.scope(),c.taskId(),"native-last-execution");
        if(execution!=null)response.put("lastExecution",map(execution));
        var verification=tasks.privateState(c.scope(),c.taskId(),"native-last-verification");
        if(verification!=null)response.put("lastVerification",map(verification));
        return response;
    }
    /** Archive reads never resume a task or depend on external business-source refresh. */
    public Map<String,Object> current(CodingTaskContract c){
        requireAuthorized(c);var pointer=tasks.privateState(c.scope(),c.taskId(),CURRENT);
        return readTree(c,pointer==null?map(requireAuthorized(c).get("initial")):map(pointer));
    }
    public Map<String,Object> commit(CodingTaskContract c,CodingJournal.Lease lease,Map<String,Object> proposal){synchronized(ioLock(c)){return commitLocked(c,lease,proposal);}}
    private Map<String,Object> commitLocked(CodingTaskContract c,CodingJournal.Lease lease,Map<String,Object> proposal){
        tasks.requireWorkerLease(c.scope(),c.taskId(),lease,false);
        requireAuthorized(c);String kind=Objects.toString(proposal.get("kind"),"");
        var files=files(proposal.get("files"));var manifest=NativeCodeWorkspace.verify(c.scope(),c.taskId(),map(proposal.get("manifest")),files);
        JsonNode old=tasks.privateState(c.scope(),c.taskId(),CURRENT);if(old==null)throw new SecurityException("Native workspace not opened");
        var pointer=map(old);var previous=map(pointer.get("manifest"));
        if("EXECUTION_SNAPSHOT".equals(kind)){
            if(!manifest.get("sha256").equals(previous.get("sha256")))throw new SecurityException("Execution must freeze current code");
            readTree(c,pointer);return Map.of("manifestHash",manifest.get("sha256"));
        }
        String call=callId(proposal.get("operationId")),operation=operation(c,call);
        if(operation.equals(pointer.get("operationId"))&&manifest.get("sha256").equals(previous.get("sha256")))
            return Map.of("manifestHash",manifest.get("sha256"));
        if(!"EDIT".equals(kind)||!Objects.equals(manifest.get("parent"),previous.get("sha256"))
                ||((Number)manifest.get("version")).longValue()!=((Number)previous.get("version")).longValue()+1)
            throw new SecurityException("Code parent/version differs from current workspace");
        var intent=intent(c,call);
        if(evidence.find(c.scope(),"native-tool:"+operation+":stopped").isPresent())throw new SecurityException("Native edit tool has already stopped");
        if(!Set.of("editor","apply_patch").contains(intent.path("tool").asText()))throw new SecurityException("Code commit requires an admitted native edit");
        var initial=requireAuthorized(c);var priorFiles=files(readTree(c,pointer).get("files"));
        for(Object path:initial.get("readOnlyPaths") instanceof List<?> paths?paths:List.of())
            if(!Objects.equals(priorFiles.get(path.toString()),files.get(path.toString())))throw new SecurityException("PATH_READ_ONLY: "+path);
        var diff=map(proposal.get("nativeDiff"));
        if(!diff.containsKey("result")||!diff.containsKey("intentHash"))throw new SecurityException("Native diff proof required");
        String diffSource="native-diff:"+operation;
        assets.save(c.scope(),c.taskId(),UserAssetService.Kind.ARTIFACT,diffSource,"native-diff.json",bytes(diff));
        String source=source(manifest);saveTree(c.scope(),c.taskId(),source,manifest,files,operation,diffSource);
        var next=Map.<String,Object>of("source",source,"manifest",manifest,"operationId",operation,"diffSource",diffSource);
        tasks.privateState(c.scope(),c.taskId(),lease,CURRENT,old,json.valueToTree(next));
        return Map.of("manifestHash",manifest.get("sha256"));
    }
    public Map<String,Object> authorize(CodingTaskContract c,CodingJournal.Lease lease,Map<String,Object> request){
        tasks.requireWorkerLease(c.scope(),c.taskId(),lease,false);
        requireAuthorized(c);
        if(!"TOOL".equals(request.get("action"))){
            tasks.get(c.scope(),c.taskId()).validateAgainst(c);
            if(request.get("manifest") instanceof Map<?,?> manifest&&!Objects.equals(manifest.get("sha256"),map(current(c).get("manifest")).get("sha256")))
                throw new SecurityException("WORKSPACE_RELOAD_REQUIRED");
            return Map.of("authorized",true);
        }
        String tool=Objects.toString(request.get("tool"),""),call=callId(request.get("operationId"));
        if(!c.authorization().allowedTools().contains(tool)||!NativeCodeWorkspace.TOOLS.contains(tool))throw new SecurityException("Native tool is not authorized");
        String inputJson=Objects.toString(request.get("inputJson"),"");
        if(inputJson.length()>256000||!parse(inputJson.getBytes(StandardCharsets.UTF_8)).equals(map(request.get("input"))))throw new SecurityException("Native input representation differs");
        String digest=NativeCodeWorkspace.sha(inputJson.getBytes(StandardCharsets.UTF_8));
        if(!digest.equals(request.get("intentHash")))throw new SecurityException("Native tool intent hash mismatch");
        String operation=operation(c,call),key="native-tool:"+operation;
        var existing=tasks.privateState(c.scope(),c.taskId(),key);
        if(existing!=null){
            if(!tool.equals(existing.path("tool").asText())||!digest.equals(existing.path("intentHash").asText()))throw new SecurityException("Tool operation identity conflict");
        }else{
            String model=lastModel(c,call,tool,inputJson);
            var state=new LinkedHashMap<String,Object>();state.put("operationId",operation);state.put("toolCallId",call);state.put("tool",tool);
            state.put("intentHash",digest);state.put("modelOperationId",model);state.put("inputJson",inputJson);
            state.put("fencingToken",lease.fencingToken());
            state.put("workspaceHash",map(current(c).get("manifest")).get("sha256"));
            tasks.privateState(c.scope(),c.taskId(),lease,key,null,json.valueToTree(state));
            existing=tasks.privateState(c.scope(),c.taskId(),key);
        }
        var reserved=tasks.reserve(c.scope(),c.taskId(),lease,tasks.get(c.scope(),c.taskId()).revision(),operation,
            CodingCanonicalHash.sha256Value(map(existing)),CodingJournal.OperationKind.TOOL,count());
        if(!reserved.admitted())throw new IllegalStateException("Native tool budget denied");
        tasks.startIntent(c.scope(),c.taskId(),lease,reserved.outcome().revision(),operation,"runtime-native-tool:"+operation);
        return Map.of("authorized",true);
    }
    public Map<String,Object> finishTool(CodingTaskContract c,CodingJournal.Lease lease,String call,Map<String,Object> result){synchronized(ioLock(c)){return finishToolLocked(c,lease,call,result);}}
    private Map<String,Object> finishToolLocked(CodingTaskContract c,CodingJournal.Lease lease,String call,Map<String,Object> result){
        tasks.requireWorkerLease(c.scope(),c.taskId(),lease,true);
        var nativeIntent=intent(c,call);String operation=nativeIntent.path("operationId").asText();
        if("run_commands".equals(nativeIntent.path("tool").asText()))for(String code:tasks.startedCodeOperations(c.scope(),c.taskId())){
            var command=tasks.privateState(c.scope(),c.taskId(),"native-command-input:"+code);
            if(command!=null&&call.equals(command.path("toolCallId").asText())&&tasks.verifiedOperation(c.scope(),c.taskId(),code).isEmpty())
                throw new IllegalStateException("Native command is not independently stopped; retain tool reservation");
        }
        if(evidence.find(c.scope(),"native-tool:"+operation+":stopped").isPresent()){
            if(!replay(c,call).equals(result))throw new SecurityException("Native tool result is immutable");
            String proof="runtime-evidence:native-tool:"+operation+":stopped";
            tasks.reconcileStopped(c.scope(),c.taskId(),lease,tasks.get(c.scope(),c.taskId()).revision(),operation,proof);
            tasks.settle(c.scope(),c.taskId(),lease,tasks.get(c.scope(),c.taskId()).revision(),operation,count(),true);
            return Map.of("saved",true,"proofRef","runtime-evidence:native-tool:"+operation+":stopped");
        }
        String source="native-tool-result:"+operation;
        assets.save(c.scope(),c.taskId(),UserAssetService.Kind.ARTIFACT,source,"native-tool-result.json",bytes(result));
        String recordId="native-tool:"+operation+":stopped";
        var payload=Map.<String,Object>of("taskId",c.taskId(),"operationId",operation,"executionHandle","runtime-native-tool:"+operation,
            "executorStopped",true,"executorScope","SDK_NATIVE_CODE_IO","resultSource",source,
            "resultHash",NativeCodeWorkspace.sha(bytes(result)),"tool",nativeIntent.path("tool").asText());
        register(c,recordId,TOOL_TYPE,payload,List.of(id(c.inputSnapshotRef())),operation);
        String proof="runtime-evidence:"+recordId;
        tasks.reconcileStopped(c.scope(),c.taskId(),lease,tasks.get(c.scope(),c.taskId()).revision(),operation,proof);
        tasks.settle(c.scope(),c.taskId(),lease,tasks.get(c.scope(),c.taskId()).revision(),operation,count(),true);
        return Map.of("saved",true,"proofRef",proof);
    }
    public Map<String,Object> replay(CodingTaskContract c,String call){
        requireAuthorized(c);String operation=operation(c,call),recordId="native-tool:"+operation+":stopped";
        var record=evidence.find(c.scope(),recordId);
        if(record.isEmpty())return Map.of();
        if(!confirmsStopped(c.scope(),c.taskId(),operation,"runtime-native-tool:"+operation,"runtime-evidence:"+recordId))throw new SecurityException("Invalid native replay proof");
        byte[] bytes=assets.read(c.scope(),c.taskId(),Objects.toString(record.get().payload().get("resultSource"))).orElseThrow();
        if(!NativeCodeWorkspace.sha(bytes).equals(record.get().payload().get("resultHash")))throw new SecurityException("Native tool result changed");
        return parse(bytes);
    }
    public JsonNode intent(CodingTaskContract c,String call){
        requireAuthorized(c);String operation=operation(c,call);var state=tasks.privateState(c.scope(),c.taskId(),"native-tool:"+operation);
        if(state==null||!tasks.unresolvedOperations(c.scope(),c.taskId()).contains(operation)&&tasks.verifiedOperation(c.scope(),c.taskId(),operation).isEmpty())
            throw new SecurityException("Native tool has no durable start admission");return state;
    }
    private String lastModel(CodingTaskContract c,String call,String tool,String inputJson){
        var started=tasks.startedModelOperations(c.scope(),c.taskId());
        if(started.isEmpty())throw new SecurityException("Native tools require a real model invocation");
        String model=started.get(started.size()-1).operationId();
        var response=models.response(c.scope(),c.taskId(),model).orElseThrow(()->new SecurityException("Model response not committed"));
        if(!(response.get("toolCalls") instanceof List<?> calls))throw new SecurityException("Tool call not present in the actual model receipt");
        var matches=calls.stream().filter(v->v instanceof Map<?,?> value&&call.equals(value.get("id"))&&tool.equals(value.get("name"))).toList();
        if(matches.size()!=1)throw new SecurityException("Tool call identity differs from the actual model receipt");
        JsonNode expected=json.valueToTree(((Map<?,?>)matches.get(0)).get("input")),actual=json.valueToTree(parse(inputJson.getBytes(StandardCharsets.UTF_8)));
        if(!expected.equals((left,right)->left.isNumber()&&right.isNumber()?left.decimalValue().compareTo(right.decimalValue()):left.equals(right)?0:1,actual))
            throw new SecurityException("Native tool input differs from the actual model receipt");
        return model;
    }
    public List<Map<String,Object>> history(CodingTaskContract c){
        var tree=current(c);var result=new ArrayList<Map<String,Object>>();
        for(int i=0;i<65;i++){
            var manifest=map(tree.get("manifest"));var item=new LinkedHashMap<String,Object>();item.put("manifest",manifest);
            if(tree.containsKey("diffSource"))item.put("diffSource",tree.get("diffSource"));result.add(item);
            if(manifest.get("parent")==null)return List.copyOf(result);
            tree=readTree(c,Map.of("source","native-code:"+manifest.get("parent")));
        }
        throw new SecurityException("Native history lineage exceeded");
    }
    public Map<String,Object> diff(CodingTaskContract c,long version){
        var item=history(c).stream().filter(value->((Number)map(value.get("manifest")).get("version")).longValue()==version)
            .findFirst().orElseThrow(()->new IllegalArgumentException("Unknown native code version"));
        if(version==0)return Map.of("manifest",item.get("manifest"),"initial",true);
        String source=Objects.toString(item.get("diffSource"),"");
        if(!source.matches("native-diff:workspace-[0-9a-f]{64}"))throw new SecurityException("Native diff reference is invalid");
        return Map.of("manifest",item.get("manifest"),"nativeDiff",parse(assets.read(c.scope(),c.taskId(),source)
            .orElseThrow(()->new SecurityException("Native diff archive unavailable"))));
    }
    /** Only after a missing/ended SDK was fenced and its command operations independently reconciled. */
    public void recoverTools(CodingTaskContract c,CodingJournal.Lease lease,Map<String,Map<String,Object>> executionReceipts){
        synchronized(ioLock(c)){
            tasks.requireWorkerLease(c.scope(),c.taskId(),lease,true);
            for(String operation:tasks.unresolvedOperations(c.scope(),c.taskId())){
                var intent=tasks.privateState(c.scope(),c.taskId(),"native-tool:"+operation);if(intent==null)continue;
                String call=intent.path("toolCallId").asText();var result=replay(c,call);
                if(result.isEmpty()){
                    var commands=new ArrayList<Map<String,Object>>();
                    for(var entry:executionReceipts.entrySet()){
                        var prepared=tasks.privateState(c.scope(),c.taskId(),"native-command-input:"+entry.getKey());
                        if(prepared!=null&&call.equals(prepared.path("toolCallId").asText()))commands.add(entry.getValue());
                    }
                    result=Map.of("isError",true,"output",Map.of("error","SDK_TOOL_INTERRUPTED",
                        "message","The prior SDK stopped before its tool result was committed. Read the current project and observed execution receipts before continuing.",
                        "workspaceManifestHash",map(current(c).get("manifest")).get("sha256"),"observedExecutions",commands));
                }
                finishToolLocked(c,lease,call,result);
            }
        }
    }
    private Map<String,Object> readTree(CodingTaskContract c,Map<String,Object> pointer){
        String source=Objects.toString(pointer.get("source"),"");
        if(!source.matches("native-code:[0-9a-f]{64}"))throw new SecurityException("Invalid native source reference");
        var tree=parse(assets.read(c.scope(),c.taskId(),source).orElseThrow(()->new SecurityException("Native source archive unavailable")));
        var manifest=NativeCodeWorkspace.verify(c.scope(),c.taskId(),map(tree.get("manifest")),files(tree.get("files")));
        if(!source.equals(source(manifest)))throw new SecurityException("Native archive source mismatch");return tree;
    }
    private void saveTree(KernelDataScope scope,String task,String source,Map<String,Object> manifest,Map<String,String> files,String operation,String diff){
        var tree=new LinkedHashMap<String,Object>();tree.put("manifest",manifest);tree.put("files",files);tree.put("operationId",operation);
        if(diff!=null)tree.put("diffSource",diff);
        assets.code(scope,task,source,"source-project.json",CodingCanonicalHash.canonicalValueJson(tree));
    }
    public boolean confirmsStopped(KernelDataScope scope,String task,String operation,String handle,String ref){return NativeToolProofVerifier.valid(evidence,scope,task,operation,handle,ref);}
    private void register(CodingTaskContract c,String id,String type,Map<String,Object> payload,List<String> parents,String operation){
        evidence.register(new EvidenceRecord(null,id,c.scope(),type,"NativeCodingWorkspaceService",evidence.contentHash(payload),payload,null,System.currentTimeMillis(),Map.of()),
            new EvidenceLineage(null,id,c.scope(),parents,c.taskId(),"NativeCodingWorkspaceService",operation,Map.of()));
    }
    public static String operation(CodingTaskContract c,String call){return "workspace-"+NativeCodeWorkspace.sha((c.taskId()+"\0"+call).getBytes(StandardCharsets.UTF_8));}
    private static String callId(Object value){if(!(value instanceof String s)||s.isBlank()||s.length()>256)throw new SecurityException("Invalid native tool call ID");return s;}
    private static String source(Map<String,Object> manifest){return "native-code:"+manifest.get("sha256");}
    Object ioLock(CodingTaskContract c){return ioLocks.computeIfAbsent(CodingCanonicalHash.sha256Value(List.of(c.scope().tenantId(),c.scope().userId(),c.scope().runId(),c.taskId())),key->new Object());}
    private static String id(String ref){if(ref==null||!ref.startsWith("runtime-evidence:"))throw new SecurityException("Invalid native evidence reference");return ref.substring("runtime-evidence:".length());}
    private static CodingTaskOutcome.Counters count(){return new CodingTaskOutcome.Counters(0,0,1,0,0,0,0,"0");}
    private byte[] bytes(Object value){try{return json.writeValueAsBytes(value);}catch(Exception invalid){throw new IllegalArgumentException("Invalid native artifact",invalid);}}
    private Map<String,Object> parse(byte[] value){try{return json.readValue(value,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception invalid){throw new SecurityException("Invalid native archive",invalid);}}
    private Map<String,Object> map(Object value){return json.convertValue(value,new TypeReference<LinkedHashMap<String,Object>>(){});}
    private Map<String,String> files(Object value){return NativeCodeWorkspace.files(json.convertValue(value,new TypeReference<LinkedHashMap<String,String>>(){}));}
}
