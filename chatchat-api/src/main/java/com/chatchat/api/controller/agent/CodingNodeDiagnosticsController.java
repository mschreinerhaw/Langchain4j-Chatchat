package com.chatchat.api.controller.agent;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Opt-in, read-only observation of an explicitly configured candidate.
 * Native persisted receipts remain the source. This does not create Runtime
 * tasks, change their state, or certify business acceptance. No control API or
 * task/provider/storage capability is returned to the browser.
 */
@RestController
@RequestMapping("/api/v1/coding-node-diagnostics")
@ConditionalOnProperty(prefix="chatchat.runtime.coding.diagnostics",name="enabled",havingValue="true")
public final class CodingNodeDiagnosticsController {
    private final Path root;
    private final ObjectMapper json;
    private record Task(Path registry,JsonNode record){}
    public CodingNodeDiagnosticsController(@Value("${chatchat.runtime.coding.diagnostics.root}")String root,ObjectMapper json){
        this.root=Path.of(root).toAbsolutePath().normalize();this.json=json;
    }
    private ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Candidate task is unavailable to this account");}
    private boolean owner(HttpServletRequest request,JsonNode row){
        return Objects.equals(request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID),row.path("contract").path("tenantId").asText())
            && Objects.equals(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID),row.path("contract").path("userId").asText());
    }
    private Path safe(Path path)throws IOException{
        Path actual=path.toRealPath();if(!actual.startsWith(root.toRealPath()))throw denied();return actual;
    }
    private JsonNode read(Path file)throws IOException{
        file=safe(file);if(Files.size(file)>4L*1024*1024)throw new IOException("Candidate metadata is too large");return json.readTree(Files.readAllBytes(file));
    }
    private List<Task> tasks(HttpServletRequest request)throws IOException{
        Path directory=root.resolve("state/workers");if(!Files.isDirectory(directory))return List.of();
        try(var files=Files.list(safe(directory))){
            var result=new ArrayList<Task>();
            for(var dir:files.limit(2048).toList()){
                if(!dir.getFileName().toString().matches("[a-f0-9-]{36}"))continue;
                Path file=dir.resolve("registry.json");if(!Files.isRegularFile(file))continue;
                JsonNode row=read(file);if(owner(request,row))result.add(new Task(file,row));
            }
            return result;
        }
    }
    private Task task(HttpServletRequest request,String run,String id)throws IOException{
        return tasks(request).stream().filter(t->id.equals(t.record.path("contract").path("taskId").asText())
            &&run.equals(t.record.path("contract").path("deliveryBinding").path("runId").asText())).findFirst().orElseThrow(this::denied);
    }
    private Path worker(Task task)throws IOException{return safe(root.resolve("workers").resolve(task.record.path("workerId").asText()));}
    private List<JsonNode> nativeEvents(Task task)throws IOException{
        String session=task.record.path("nativeSessionId").asText();if(!session.matches("[A-Za-z0-9_.:-]{1,200}"))return List.of();
        Path file=worker(task).resolve("state").resolve(session).resolve("events.jsonl");if(!Files.isRegularFile(file))return List.of();
        if(Files.size(safe(file))>128L*1024*1024)throw new IOException("Candidate journal exceeds observation limit");
        var rows=new ArrayList<JsonNode>();try(var reader=Files.newBufferedReader(file)){String line;while((line=reader.readLine())!=null){
            if(line.length()>4*1024*1024)continue;try{
                JsonNode event=json.readTree(line);
                // Keep original sequence numbers. Text streaming duplicates
                // the receipt events and is not Token usage or a tool action.
                if(!Set.of("native.event","model.text.delta").contains(event.path("eventType").asText()))rows.add(event);
            }catch(IOException partial){/* Last append may still be in flight. */}
        }}return rows;
    }
    private String status(Task task){
        var r=task.record;
        // Sealing deliberately revokes execution capabilities. That is not a
        // task cancellation and must not erase its completed Native delivery.
        if(r.path("delivery").path("artifacts").isArray())return "SEALED_PENDING_VALIDATION";
        if(r.path("workerState").asText().equals("START_FAILED"))return "FAILED";
        if(r.path("revoked").asBoolean())return "CANCELLED";
        if(r.path("workerState").asText().equals("RECOVERABLE"))return "RECOVERABLE";
        if(r.path("workerState").asText().equals("STOPPED"))return "PARTIAL"; // A sealed delivery is not business completion.
        return "RUNNING";
    }
    private List<Map<String,Object>> models(Task task)throws IOException{
        Path dir=root.resolve("runs").resolve(task.record.path("contract").path("sessionId").asText()).resolve("model");
        if(!Files.isDirectory(dir))return List.of();var result=new ArrayList<Map<String,Object>>();
        try(var files=Files.list(safe(dir))){for(var file:files.sorted().limit(4096).toList()){
            String name=file.getFileName().toString();if(!name.matches("[0-9]+-request\\.json"))continue;
            Path receipt=file.resolveSibling(name.replace("-request.json","-receipt.json"));var r=Files.isRegularFile(receipt)?read(receipt):json.createObjectNode();
            var row=new LinkedHashMap<String,Object>();row.put("requestId",task.record.path("contract").path("sessionId").asText()+":"+name.replace("-request.json",""));
            row.put("providerModel",r.path("providerModels").path(0).asText(null));row.put("status",r.isEmpty()?"STARTED":r.has("error")?"FAILED":"RECEIVED");row.put("durationMs",r.path("durationMs").numberValue());
            var usage=new LinkedHashMap<String,Object>();var raw=r.path("usage");
            usage.put("inputTokens",raw.path("prompt_tokens").numberValue());usage.put("outputTokens",raw.path("completion_tokens").numberValue());usage.put("totalTokens",raw.path("total_tokens").numberValue());
            usage.put("cacheReadTokens",raw.path("prompt_tokens_details").path("cached_tokens").numberValue());row.put("usage",usage);row.put("usageSource","PROVIDER_RECEIPT");result.add(row);
        }}return result;
    }
    private Map<String,Object> summary(Task task)throws IOException{
        var r=task.record;var m=new LinkedHashMap<String,Object>();m.put("taskId",r.path("contract").path("taskId").asText());
        m.put("runId",r.path("contract").path("deliveryBinding").path("runId").asText());m.put("name","Candidate "+m.get("taskId"));m.put("status",status(task));
        Path cases=root.resolve("acceptance");if(Files.isDirectory(cases))try(var dirs=Files.list(safe(cases))){
            for(var dir:dirs.limit(512).toList()){
                Path intent=dir.resolve("intent.json");if(!Files.isRegularFile(intent))continue;var value=read(intent);
                var grant=value.has("grant")?value.path("grant"):value.path("contract");
                if(grant.path("sessionId").equals(r.path("contract").path("sessionId"))){m.put("name",dir.getFileName().toString());break;}
            }
        }
        String created=r.path("createdAt").asText(null);
        if(created==null)created=Files.readAttributes(safe(task.registry.getParent()),java.nio.file.attribute.BasicFileAttributes.class).creationTime().toInstant().toString();
        var events=nativeEvents(task);String started=null;
        for(var event:events)if(event.path("eventType").asText().equals("task.started")){started=event.path("timestamp").asText(null);break;}
        m.put("createdAt",created);m.put("startedAt",started);m.put("currentAction",events.isEmpty()?r.path("taskState").asText():events.get(events.size()-1).path("eventType").asText());
        String stopped=r.path("stopProof").path("finishedAt").asText();
        if(!stopped.isBlank()&&!stopped.startsWith("0001"))m.put("endedAt",stopped);
        for(var event:events)if(type(event).equals("ERROR_OCCURRED")){
            String error=event.path("payload").path("error").asText("");
            for(var output:event.path("payload").path("output")){
                var match=java.util.regex.Pattern.compile("(?m)^[A-Za-z_][A-Za-z0-9_.]*(?:Error|Exception):[^\\r\\n]*").matcher(output.path("result").asText());
                while(match.find())error=match.group();
            }
            m.put("lastError",error.isEmpty()?event.path("payload").path("toolName").asText()+" failed":error.substring(0,Math.min(320,error.length())));
        }
        Path validation=root.resolve("acceptance").resolve(m.get("name").toString()).resolve("independent-local-verification.json");
        if(Files.isRegularFile(validation))m.put("fixtureValidationPassed",read(validation).path("passed").asBoolean());
        m.put("timeSource","GATEWAY_ALLOCATION_DIRECTORY_CREATION");
        var receipts=models(task);long tokens=0;boolean complete=!receipts.isEmpty();
        for(var receipt:receipts){Object value=((Map<?,?>)receipt.get("usage")).get("totalTokens");if(value instanceof Number n)tokens+=n.longValue();else complete=false;}
        m.put("modelRequests",receipts.size());m.put("tokens",complete?tokens:null);m.put("confirmedTokenLowerBound",tokens);m.put("diagnosticOnly",true);m.put("validationStatus","NOT_RUN");
        return m;
    }
    private String type(JsonNode event){String name=event.path("eventType").asText(),tool=event.path("payload").path("toolName").asText();
        if(name.equals("tool.completed")&&tool.equals("run_commands")&&event.path("payload").toString().contains("Traceback (most recent call last)"))return "ERROR_OCCURRED";
        return switch(name){case "model.iteration.started"->"MODEL_REQUEST_STARTED";case "model.iteration.completed"->"MODEL_REQUEST_COMPLETED";
            case "tool.started"->tool.equals("run_commands")?"COMMAND_STARTED":"TOOL_STARTED";
            case "tool.failed"->"ERROR_OCCURRED";case "tool.completed"->tool.equals("run_commands")?"COMMAND_COMPLETED":Set.of("editor","apply_patch").contains(tool)?"FILE_CHANGED":"TOOL_COMPLETED";
            default->"OBSERVATION";};
    }
    private JsonNode redact(JsonNode value,Task task)throws IOException{
        String text=json.writeValueAsString(value);var config=read(root.resolve("private/gateway.json"));
        for(JsonNode secret:List.of(config.path("signingKey"),config.path("executor").path("token"),task.record.path("apiToken"),task.record.path("brokerToken")))
            if(secret.isTextual()&&!secret.asText().isEmpty())text=text.replace(secret.asText(),"[REDACTED]");
        for(var profile:config.path("modelProfiles"))if(profile.path("apiKey").isTextual())text=text.replace(profile.path("apiKey").asText(),"[REDACTED]");
        return json.readTree(text);
    }
    @GetMapping("/tasks") public ApiResponse<?> list(HttpServletRequest request,@RequestParam(name="page",defaultValue="0")int page,@RequestParam(name="size",defaultValue="20")int size,
            @RequestParam(name="name",defaultValue="")String name,@RequestParam(name="status",defaultValue="")String status)throws IOException{
        if(page<0||page>10000||size<1||size>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows=new ArrayList<Map<String,Object>>();for(var t:tasks(request)){var row=summary(t);if((name.isEmpty()||row.get("name").toString().contains(name))&&(status.isEmpty()||status.equals(row.get("status"))))rows.add(json.convertValue(redact(json.valueToTree(row),t),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}
        Collections.reverse(rows);return ApiResponse.success(Map.of("items",rows.stream().skip((long)page*size).limit(size).toList(),"total",rows.size(),"diagnosticOnly",true,"projectionLagging",false));
    }
    @GetMapping("/tasks/{run}/{id}") public ApiResponse<?> detail(HttpServletRequest request,@PathVariable("run") String run,@PathVariable("id") String id)throws IOException{
        var t=task(request,run,id);var rows=nativeEvents(t);var out=summary(t);out.put("lastEventSequence",rows.isEmpty()?0:rows.get(rows.size()-1).path("eventId").asLong());
        var m=new LinkedHashMap<String,Object>();m.put("taskId",id);m.put("runId",run);m.put("name",out.get("name"));m.put("outcome",out);m.put("sessionId",t.record.path("contract").path("sessionId").asText());
        m.put("modelReceipts",models(t));m.put("executions",rows.stream().filter(e->e.path("eventType").asText().startsWith("tool.")&&e.path("payload").path("toolName").asText().equals("run_commands")).map(e->e.path("payload")).toList());
        m.put("repairs",List.of());m.put("diagnosticOnly",true);return ApiResponse.success(redact(json.valueToTree(m),t));
    }
    @GetMapping("/tasks/{run}/{id}/events") public ApiResponse<?> events(HttpServletRequest request,@PathVariable("run") String run,@PathVariable("id") String id,@RequestParam(name="afterSequence",defaultValue="0")long afterSequence,@RequestParam(name="limit",defaultValue="128")int limit)throws IOException{
        if(afterSequence<0||limit<1||limit>256)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);var t=task(request,run,id);var result=new ArrayList<Map<String,Object>>();
        for(var event:nativeEvents(t)){long seq=event.path("eventId").asLong();if(seq<=afterSequence)continue;var m=new LinkedHashMap<String,Object>();m.put("sequence",seq);m.put("eventId",event.path("eventId").asText());m.put("occurredAt",event.path("timestamp").asText());m.put("nativeEventType",event.path("eventType").asText());m.put("type",type(event));m.put("summary",event.path("payload").path("toolName").asText(event.path("eventType").asText()));m.put("nativeReceipt",redact(event,t));result.add(m);if(result.size()>=limit)break;}
        return ApiResponse.success(Map.of("events",result,"nextSequence",result.isEmpty()?afterSequence:result.get(result.size()-1).get("sequence")));
    }
    @GetMapping("/tasks/{run}/{id}/artifacts") public ApiResponse<?> artifacts(HttpServletRequest request,@PathVariable("run") String run,@PathVariable("id") String id)throws IOException{
        var t=task(request,run,id);var files=new ArrayList<Map<String,Object>>();for(var f:t.record.path("delivery").path("artifacts"))files.add(Map.of("id",hash(f.path("path").asText().getBytes(StandardCharsets.UTF_8)),"fileName",f.path("path").asText(),"kind",f.path("type").asText(),"size",f.path("size").asLong(),"sha256",f.path("sha256").asText(),"state",t.record.has("uploadReceipt")?"SILO_UPLOADED_NOT_JAVA_ACCEPTED":"SEALED_LOCAL"));
        return ApiResponse.success(Map.of("files",files,"validationStatus","NOT_RUN"));
    }
    @GetMapping("/tasks/{run}/{id}/artifacts/{asset}/content") public ResponseEntity<byte[]> content(HttpServletRequest request,@PathVariable("run") String run,@PathVariable("id") String id,@PathVariable("asset") String asset,@RequestParam(name="download",defaultValue="false")boolean download)throws Exception{
        var t=task(request,run,id);JsonNode found=null;for(var f:t.record.path("delivery").path("artifacts"))if(asset.equals(hash(f.path("path").asText().getBytes(StandardCharsets.UTF_8))))found=f;
        if(found==null)throw denied();Path workspace=worker(t).resolve("workspace").toRealPath(),file=safe(workspace.resolve(found.path("path").asText()));if(!file.startsWith(workspace))throw denied();
        if(Files.size(file)>8L*1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);byte[] bytes=Files.readAllBytes(file);
        if(bytes.length!=found.path("size").asLong()||!hash(bytes).equals(found.path("sha256").asText()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Artifact SHA-256 differs from sealed manifest");
        MediaType type=file.toString().endsWith(".png")?MediaType.IMAGE_PNG:MediaType.TEXT_PLAIN;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").contentType(download?MediaType.APPLICATION_OCTET_STREAM:type).body(bytes);
    }
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception failure){throw new IllegalStateException(failure);}}
}
