package com.chatchat.mcpserver.python;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.coding.execution.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.RuntimeExecutionCheckpointPort;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.TaskScheduler;

/** Native correlation/protocol adapter over the EXISTING Java journal. It never executes Agent code or chooses tools. */
public final class NativeOperationAuthority {
    final JsonNode cfg;final ObjectMapper json;final McpPythonControlPlanePort python;
    final DurableCodingTaskService tasks;final CodingPythonExecutionBridge bridge;
    final McpPythonExecutionProofVerifier pythonProof;
    final ThreadLocal<KernelDataScope> caller=new ThreadLocal<>();
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final Set<String> TERMINAL=Set.of("COMPLETED","FAILED","CANCELLED");
    record Call(KernelDataScope scope,String task){}
    <T> T revisionRetry(java.util.concurrent.Callable<T> action)throws Exception {for(int n=0;;n++){try{return action.call();}catch(CodingContractException e){if(n>=7||e.error().code()!=CodingErrorCode.INVALID_STATE_TRANSITION||!"Coding task revision conflict".equals(e.getMessage()))throw e;}}}
    NativeOperationAuthority(JsonNode cfg,RuntimeExecutionCheckpointPort store,McpPythonControlPlanePort python,ObjectMapper mapper,TaskScheduler scheduler){
        this.cfg=cfg;this.python=python;json=mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);pythonProof=new McpPythonExecutionProofVerifier(python,json);
        tasks=new DurableCodingTaskService(store,json,Clock.systemUTC(),(scope,action)->{
            var current=caller.get();if(current==null||!same(scope,current))throw new SecurityException("Trusted scoped authority required");
        },(contract,outcome)->false,(scope,task,op,handle,ref)->{
            if(handle.startsWith("mcp-python:"))return pythonProof.confirmsStopped(scope,task,op,handle,ref);
            try{return verifiedRemote(new Call(scope,task),op,ref);}catch(Exception unavailable){return false;}
        },Runnable::run,scheduler);
        bridge=new CodingPythonExecutionBridge(tasks,python,json);
    }
    static boolean same(KernelDataScope a,KernelDataScope b){return a.tenantId().equals(b.tenantId())&&a.userId().equals(b.userId())&&a.runId().equals(b.runId());}
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    byte[] bytes(Object value){try{return json.writeValueAsBytes(value);}catch(Exception e){throw new IllegalArgumentException(e);}}
    String canonicalHash(JsonNode value){return hash(bytes(json.convertValue(value,Object.class)));}
    String signed(JsonNode value){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(cfg.path("dispatchSecret").asText().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(bytes(json.convertValue(value,Object.class))));}catch(Exception e){throw new IllegalStateException(e);}}
    Call call(JsonNode body){var i=body.path("identity");var t=i.path("tenantId").asText();var u=i.path("userId").asText();var r=i.path("sessionId").asText();var task=i.path("taskId").asText();
        for(String v:List.of(t,u,r,task))if(!v.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))throw new SecurityException("Invalid task scope");
        var scope=new KernelDataScope(t,u,null,null,r,null,Map.of());caller.set(scope);return new Call(scope,task);
    }
    CodingTaskContract contract(Call c,JsonNode body){return new CodingTaskContract(CodingProtocol.VERSION,c.task,c.scope,
        new CodingTaskContract.Objective("Native external-operation durability and cancellation",List.of("execution receipts"),List.of("stage2:verified-stop")),"stage2:immutable-input",List.of(),"stage2:isolated-workspace",
        new CodingTaskContract.Authorization("stage2:trusted-task-grant",List.of("python_execute","long_mcp"),List.of(),List.of(),List.of(),CodingTaskContract.NetworkPolicy.NONE,List.of(),List.of(CodingTaskContract.SideEffect.CODE_EXECUTION)),
        // Existing journal requires counters. These fixed test ceilings are NOT model-budget integration.
        new CodingTaskContract.Budget("native-"+c.task,Instant.ofEpochMilli(body.path("identity").path("expiresAt").asLong()),0,0,256,128,"0","USD",1000000000,0,100000000),
        cfg.path("pythonEnvironment").asText(),"stage2:receipt-validation",new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,"stage2:result","stage2:receipt",List.of("logs/receipt.json"),List.of("application/json"),1000,1000000,List.of()),Map.of());}
    CodingJournal.Lease lease(Call c){var value=tasks.ownership(c.scope,c.task);if(!Instant.now().isBefore(value.leaseUntil()))value=CodingTaskRevisions.mutate(tasks,c.scope,c.task,r->tasks.claim(c.scope,c.task,"native-reconciler",Duration.ofSeconds(20),r));return value;}
    JsonNode state(Call c,String key){return tasks.privateState(c.scope,c.task,key);}
    void put(Call c,String key,JsonNode old,JsonNode next){var l=lease(c);if(old==null)tasks.privateState(c.scope,c.task,l,key,null,next);else tasks.reconcileNativeState(c.scope,c.task,l,key,old,next);}
    JsonNode binding(Call c,String op){if(!op.matches("cline-[0-9a-f]{64}"))throw new SecurityException("Invalid operation ID");var value=state(c,"native:"+op);if(value==null)throw new SecurityException("Operation not admitted in this scope");return value;}
    void update(Call c,String op,java.util.function.Consumer<ObjectNode> change){for(int n=0;;n++){var old=binding(c,op);ObjectNode next=old.deepCopy();change.accept(next);try{put(c,"native:"+op,old,next);return;}catch(CodingContractException conflict){if(n>=7||!conflict.getMessage().contains("CAS conflict"))throw conflict;}}}
    void active(Call c,String epoch){var s=state(c,"native:session");var l=tasks.ownership(c.scope,c.task);if(s==null||!s.path("active").asBoolean()||!s.path("epoch").asText().equals(epoch)||s.path("fencingToken").asLong()!=l.fencingToken()||!Instant.now().isBefore(l.leaseUntil())||tasks.get(c.scope,c.task).executionControl().stopReason()!=null)throw new SecurityException("Expired, inactive or old native Epoch");}
    JsonNode session(Call c,JsonNode body){
        tasks.submit(contract(c,body));var old=state(c,"native:session");String epoch=body.path("epoch").asText();if(epoch.isBlank())throw new SecurityException("Native epoch required");
        if(old!=null){active(c,epoch);return old;}
        var l=lease(c);var next=json.createObjectNode().put("epoch",epoch).put("active",true).put("fencingToken",l.fencingToken());put(c,"native:session",null,next);return next;
    }
    JsonNode bind(Call c,JsonNode body){String epoch=body.path("epoch").asText();active(c,epoch);
        String nativeSession=body.path("nativeSessionId").asText(),nativeCall=body.path("nativeToolCallId").asText(),tool=body.path("toolName").asText();
        if(nativeSession.isBlank()||nativeCall.isBlank()||nativeCall.length()>512||!Set.of("long_mcp","python_execute").contains(tool))throw new SecurityException("Native call identity/tool missing");
        String op="cline-"+hash(bytes(List.of(c.scope.tenantId(),c.scope.userId(),c.scope.runId(),c.task,nativeSession,nativeCall,tool)));
        String fingerprint=canonicalHash(body.path("arguments"));var existing=state(c,"native:"+op);
        if(existing!=null){if(!fingerprint.equals(existing.path("argumentsHash").asText())||!epoch.equals(existing.path("epoch").asText()))throw new SecurityException("Immutable native operation conflict");return existing;}
        var b=json.createObjectNode().put("operationId",op).put("nativeSessionId",nativeSession).put("nativeToolCallId",nativeCall).put("toolName",tool).put("epoch",epoch).put("argumentsHash",fingerprint).put("state","PENDING").put("fencingToken",tasks.ownership(c.scope,c.task).fencingToken());
        b.set("arguments",body.path("arguments"));b.set("identity",body.path("identity"));if(tool.equals("python_execute"))b.put("childOperationId","cline-"+hash((op+"/python").getBytes(StandardCharsets.UTF_8)));
        put(c,"native:"+op,null,b);return b;
    }
    JsonNode remote(String path,Object body)throws Exception{var request=HttpRequest.newBuilder(URI.create(cfg.path("remoteControlUrl").asText()+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+cfg.path("remoteControlSecret").asText()).header("content-type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(bytes(body))).build();var response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());if(response.statusCode()!=200)throw new IllegalStateException("Remote control unavailable: "+response.statusCode());return json.readTree(response.body());}
    ObjectNode remoteIdentity(Call c,String op){var b=json.createObjectNode();b.set("identity",binding(c,op).path("identity"));b.put("operationId",op);b.put("fencingToken",lease(c).fencingToken());return b;}
    boolean verifiedRemote(Call c,String op,String ref)throws Exception{var b=binding(c,op);var remote=remote("/get",remoteIdentity(c,op));var receipt=remote.path("receipt");
        if(!TERMINAL.contains(remote.path("state").asText())||!c.scope.userId().equals(receipt.path("identity").path("userId").asText())||!c.scope.runId().equals(receipt.path("identity").path("sessionId").asText())||b.path("fencingToken").asLong()!=receipt.path("fencingToken").asLong()||!b.path("epoch").asText().equals(receipt.path("epoch").asText())||!b.path("toolName").asText().equals(receipt.path("toolName").asText()))return false;
        if(!remote.path("executorStopped").asBoolean()||!receipt.path("stopConfirmed").asBoolean()||!op.equals(receipt.path("operationId").asText())||!b.path("argumentsHash").asText().equals(receipt.path("argumentsHash").asText())||!c.scope.tenantId().equals(receipt.path("identity").path("tenantId").asText())||!c.task.equals(receipt.path("identity").path("taskId").asText())||!ref.equals("native-mcp-receipt:"+canonicalHash(receipt)))return false;
        if(b.has("childOperationId")){String child=b.path("childOperationId").asText();var value=python.get(c.scope.tenantId(),c.scope.userId(),c.scope.runId(),c.task,child);if(!value.executorStopped())return false;byte[] raw=python.artifact(c.scope.tenantId(),c.scope.userId(),c.scope.runId(),c.task,child,"logs","receipt.json");String handle="mcp-python:"+hash(String.join("\0",c.scope.tenantId(),c.scope.userId(),c.scope.runId(),c.task,child).getBytes(StandardCharsets.UTF_8));if(!pythonProof.confirmsStopped(c.scope,c.task,child,handle,"mcp-python-receipt:"+hash(raw)))return false;}
        return true;
    }
    JsonNode reconcile(Call c,String op)throws Exception{
        var b=binding(c,op);JsonNode observed;
        try{observed=remote("/get",remoteIdentity(c,op));}catch(Exception unavailable){if(!TERMINAL.contains(b.path("state").asText()))update(c,op,n->n.put("state","UNKNOWN"));return binding(c,op);}
        if(observed.path("executorStopped").asBoolean()&&observed.path("receipt").isObject()){
            String proof="native-mcp-receipt:"+canonicalHash(observed.path("receipt"));
            if(!verifiedRemote(c,op,proof)){update(c,op,n->n.put("state","UNKNOWN").put("failure","Remote receipt validation failed"));return binding(c,op);}
            if(b.has("childOperationId")&&tasks.startedCodeOperations(c.scope,c.task).contains(b.path("childOperationId").asText()))revisionRetry(()->bridge.reconcile(c.scope,c.task,lease(c),b.path("childOperationId").asText()));
            if(tasks.unresolvedOperations(c.scope,c.task).contains(op)||tasks.verifiedOperation(c.scope,c.task,op).isPresent())CodingTaskRevisions.mutate(tasks,c.scope,c.task,r->tasks.reconcileStopped(c.scope,c.task,lease(c),r,op,proof));
            String target=switch(observed.path("state").asText()){case "COMPLETED"->"COMPLETED";case "CANCELLED"->"CANCELLED";default->"FAILED";};
            update(c,op,n->{if(TERMINAL.contains(n.path("state").asText())&&!n.path("state").asText().equals(target)){n.put("state","UNKNOWN").put("failure","Conflicting terminal receipt");return;}n.put("state",target).put("stopProofRef",proof);n.set("receipt",observed.path("receipt"));});
        }else if(!TERMINAL.contains(b.path("state").asText())){String next=b.path("state").asText().equals("CANCEL_REQUESTED")?"CANCEL_REQUESTED":observed.path("state").asText().equals("RUNNING")?"RUNNING":"UNKNOWN";update(c,op,n->n.put("state",next));}
        return binding(c,op);
    }
    JsonNode dispatch(Call c,JsonNode body)throws Exception{
        String epoch=body.path("epoch").asText();active(c,epoch);String op=body.path("operationId").asText();var b=binding(c,op);
        if(!epoch.equals(b.path("epoch").asText())||!canonicalHash(body.path("rpc").path("params").path("arguments")).equals(b.path("argumentsHash").asText()))throw new SecurityException("Native operation arguments/Epoch mismatch");
        var l=tasks.ownership(c.scope,c.task);var reservation=new CodingTaskOutcome.Counters(0,0,1,0,0,0,1048576,"0");
        var reserved=CodingTaskRevisions.mutate(tasks,c.scope,c.task,r->tasks.reserve(c.scope,c.task,l,r,op,b.path("argumentsHash").asText(),CodingJournal.OperationKind.TOOL,reservation));if(!reserved.admitted())throw new SecurityException("Existing journal admission denied");
        var intent=CodingTaskRevisions.mutate(tasks,c.scope,c.task,r->tasks.startIntent(c.scope,c.task,l,r,op,"native-mcp:"+op));
        update(c,op,n->n.put("dispatchIntent",true));
        if(!intent.dispatchIntentCreated()){var current=reconcile(c,op);var cached=current.get("mcpResponse");if(cached==null)throw new IllegalStateException("OPERATION_QUERY_ONLY: no repeat dispatch");return json.createObjectNode().set("mcpResponse",cached);}
        ObjectNode rpc=body.path("rpc").deepCopy();ObjectNode meta=json.createObjectNode();meta.set("binding",b);meta.put("signature",signed(b));((ObjectNode)rpc.path("params")).set("_meta",meta);
        try{
            var request=HttpRequest.newBuilder(URI.create(cfg.path("mcpUrl").asText())).timeout(Duration.ofSeconds(180)).header("Authorization","Bearer "+cfg.path("mcpToken").asText()).header("content-type","application/json").header("accept","application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofByteArray(bytes(rpc))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());if(response.statusCode()!=200)throw new IllegalStateException("MCP remote HTTP "+response.statusCode());
            var actual=json.readTree(response.body());update(c,op,n->n.set("mcpResponse",actual));var view=reconcile(c,op);var result=json.createObjectNode();result.set("operation",view);result.set("mcpResponse",actual);return result;
        }catch(Exception lost){if(!TERMINAL.contains(binding(c,op).path("state").asText()))update(c,op,n->n.put("state","UNKNOWN").put("failure","Dispatch/response lost; query-only recovery"));throw lost;}
    }
    JsonNode child(Call c,JsonNode body,String method)throws Exception{
        String op=body.path("operationId").asText();var b=binding(c,op);if(!b.path("toolName").asText().equals("python_execute"))throw new SecurityException("Not a Python operation");String child=b.path("childOperationId").asText();var l=lease(c);
        if(method.equals("start")){
            active(c,b.path("epoch").asText());String source=b.path("arguments").path("source").asText();
            if(!b.path("dispatchIntent").asBoolean())throw new SecurityException("Parent dispatch intent required before Python start");
            // start() can throw a revision conflict only BEFORE external dispatch. Its existing
            // durable startIntent makes all later invocations query-only. Never retry transport errors.
            var value=revisionRetry(()->bridge.start(c.scope,tasks.contract(c.scope,c.task),l,child,cfg.path("pythonEnvironment").asText(),"native-stage2","analysis.py",source,Map.of(),"{}",Map.of(),new CodingTaskOutcome.Counters(0,0,1,1,0,0,1048576,"0")));return json.valueToTree(value);
        }
        var value=method.equals("cancel")?python.cancel(c.scope.tenantId(),c.scope.userId(),c.scope.runId(),c.task,child,l.fencingToken()):python.get(c.scope.tenantId(),c.scope.userId(),c.scope.runId(),c.task,child);
        var result=(ObjectNode)json.valueToTree(value);
        if(value.executorStopped()&&value.manifestHash()!=null){result.set("receipt",json.readTree(python.artifact(c.scope.tenantId(),c.scope.userId(),c.scope.runId(),c.task,child,"logs","receipt.json")));result.put("receiptSha256",hash(python.artifact(c.scope.tenantId(),c.scope.userId(),c.scope.runId(),c.task,child,"logs","receipt.json")));}
        return result;
    }
    JsonNode cancel(Call c,String op)throws Exception{
        var current=reconcile(c,op);if(TERMINAL.contains(current.path("state").asText()))return current;
        update(c,op,n->n.put("state","CANCEL_REQUESTED"));remote("/cancel",remoteIdentity(c,op));return reconcile(c,op);
    }
    JsonNode route(String path,JsonNode body)throws Exception{
        var c=call(body);String op=body.path("operationId").asText();
        return switch(path){
            case "/session"->session(c,body);
            case "/heartbeat"->{active(c,body.path("epoch").asText());var l=tasks.ownership(c.scope,c.task);CodingTaskRevisions.mutate(tasks,c.scope,c.task,r->tasks.renew(c.scope,c.task,l,Duration.ofSeconds(20),r));yield json.valueToTree(tasks.ownership(c.scope,c.task));}
            case "/bind"->bind(c,body);
            case "/binding"->binding(c,op);
            case "/remote/binding"->binding(c,op);
            case "/resolve"->{String candidate="cline-"+hash(bytes(List.of(c.scope.tenantId(),c.scope.userId(),c.scope.runId(),c.task,body.path("nativeSessionId").asText(),body.path("nativeToolCallId").asText(),body.path("toolName").asText())));var b=binding(c,candidate);active(c,body.path("epoch").asText());if(!b.path("argumentsHash").asText().equals(canonicalHash(body.path("arguments"))))throw new SecurityException("Native arguments differ");yield b;}
            case "/dispatch"->dispatch(c,body);
            case "/get"->reconcile(c,op);
            case "/cancel"->cancel(c,op);
            case "/list"->{var result=json.createArrayNode();for(var id:tasks.nativeOperationStates(c.scope,c.task).keySet())result.add(reconcile(c,id));yield result;}
            case "/invalidate"->{tasks.cancel(c.scope,c.task,"Native worker invalidated; remote reconciliation required");var old=state(c,"native:session");if(old!=null){ObjectNode next=old.deepCopy();next.put("active",false);put(c,"native:session",old,next);}yield json.createObjectNode().put("active",false);}
            case "/finish"->{if(body.path("localStopProof").path("running").asBoolean(true)||body.path("localStopProof").path("containerId").asText().isBlank())throw new SecurityException("Trusted gateway Worker stop proof required");for(var b:tasks.nativeOperationStates(c.scope,c.task).values()){if(!TERMINAL.contains(b.path("state").asText())||!b.has("stopProofRef"))throw new SecurityException("Native operation still unresolved");}yield json.valueToTree(CodingTaskRevisions.mutate(tasks,c.scope,c.task,r->tasks.finishStoppedAfterReconciliation(c.scope,c.task,lease(c),r)));}
            case "/rotate"->{var l=tasks.ownership(c.scope,c.task);if(Instant.now().isBefore(l.leaseUntil()))throw new SecurityException("Wait for old lease expiry; no fence bypass");var next=lease(c);yield json.valueToTree(next);}
            case "/python/start"->child(c,body,"start");case "/python/get"->child(c,body,"get");case "/python/cancel"->child(c,body,"cancel");
            default->throw new SecurityException("Unknown authority route");
        };
    }
    void listen()throws Exception{var server=HttpServer.create(new InetSocketAddress("0.0.0.0",cfg.path("port").asInt(18502)),64);server.setExecutor(Executors.newFixedThreadPool(16));server.createContext("/",exchange->{
        try{String path=exchange.getRequestURI().getPath();if(path.equals("/health/live")&&exchange.getRequestMethod().equals("GET")){byte[] response=bytes(Map.of("live",true));exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);return;}String secret=cfg.path(path.startsWith("/python/")||path.equals("/remote/binding")?"mcpSecret":"gatewaySecret").asText();byte[] actual=Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"),"").getBytes(StandardCharsets.UTF_8),expected=("Bearer "+secret).getBytes(StandardCharsets.UTF_8);if(!MessageDigest.isEqual(actual,expected))throw new SecurityException("Authority credential rejected");
            byte[] raw=exchange.getRequestBody().readNBytes(1048577);if(raw.length>1048576)throw new SecurityException("Authority body too large");byte[] response=bytes(route(path,json.readTree(raw)));exchange.getResponseHeaders().set("content-type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
        }catch(Exception e){byte[] response=bytes(Map.of("error",e.getClass().getSimpleName(),"message",Objects.toString(e.getMessage(),"Authority failure")));exchange.getResponseHeaders().set("content-type","application/json");exchange.sendResponseHeaders(e instanceof SecurityException?409:503,response.length);exchange.getResponseBody().write(response);}finally{caller.remove();exchange.close();}
    });server.start();System.out.println("Native operation authority ready; existing Java journal and managed execution only");}
}
