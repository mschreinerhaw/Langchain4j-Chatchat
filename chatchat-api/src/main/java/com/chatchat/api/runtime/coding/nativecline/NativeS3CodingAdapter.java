package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.assets.*;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.evidence.CodingSchemaResultVerifier;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.license.McpLicenseEntitlementPort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.coding.CodingTaskOutcome.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.*;

/** Native execution adapter over the existing Runtime journal, lease and Silo.
 * Cline alone owns planning, edits and error repair. Java checks only the frozen
 * output contract, actual receipts, object integrity and cancellation fences.
 */
@Service
@ConditionalOnProperty(prefix="chatchat.runtime.coding.unified",name="enabled",havingValue="true")
public final class NativeS3CodingAdapter implements CodingAgentSPI {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(NativeS3CodingAdapter.class);
    private static final String BINDING="native-s3:binding";
    private final DurableCodingTaskService tasks;
    private final NativeSiloTaskService inputs;
    private final SiloCodingWorkspaceService workspace;
    private final NativeS3Settings settings;
    private final NativeS3Evidence evidence;
    private final CodingInvocationContext identity;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final McpLicenseEntitlementPort license;
    private final String owner="native-s3-java-"+UUID.randomUUID();
    // HttpClient wraps interrupted IO; Java shutdown must not cancel a live Native session.
    private volatile boolean shuttingDown;
    private final ConcurrentMap<String,Boolean> active=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->{var t=new Thread(r,"native-s3-control");t.setDaemon(true);return t;});
    @jakarta.annotation.PreDestroy public void close(){shuttingDown=true;workers.shutdownNow();}
    public NativeS3CodingAdapter(DurableCodingTaskService tasks,NativeSiloTaskService inputs,SiloCodingWorkspaceService workspace,
            NativeS3Settings settings,NativeS3Evidence evidence,CodingInvocationContext identity,ObjectMapper json,JdbcTemplate jdbc,
            McpLicenseEntitlementPort license,PlatformTransactionManager manager){
        this.tasks=tasks;this.inputs=inputs;this.workspace=workspace;this.settings=settings;this.evidence=evidence;this.identity=identity;this.json=json;this.jdbc=jdbc;this.license=license;
    }
    private static String key(KernelDataScope s,String task){return s.tenantId()+"\0"+s.userId()+"\0"+s.runId()+"\0"+task;}
    private <T>T worker(KernelDataScope s,Supplier<T> action){return identity.as(s,Set.of(READ,WORKER,EVIDENCE,STOP,SUBMIT,RECOVER),action);}
    @Override public CodingTaskOutcome submit(CodingTaskContract c){
        inputs.requireAuthorized(c);settings.profile((String)inputs.requireAuthorized(c).get("selectedModel"));
        if(!license.codingDispatchEntitlement().licenseValid())throw new SecurityException("Coding dispatch entitlement required");
        settings.client.requireNativeIdentity();
        var accepted=tasks.submit(c);if(!accepted.status().terminal())schedule(c);return accepted;
    }
    private void schedule(CodingTaskContract c){String key=key(c.scope(),c.taskId());if(active.putIfAbsent(key,true)!=null)return;
        try{workers.execute(()->{try{worker(c.scope(),()->{execute(c);return null;});}finally{active.remove(key);}});}catch(RejectedExecutionException busy){active.remove(key);}
    }
    /** Projection is a discovery index only. Every candidate is re-authorized
     * against the authoritative contract and journal before ownership is taken. */
    @Scheduled(initialDelay=15000,fixedDelay=15000) public void recover(){
        try{for(var row:jdbc.queryForList("select tenant_id,user_id,run_id,task_id from runtime_coding_task_view where status in ('CREATED','RUNNING') and task_id like 'native-%' order by updated_at limit 64")){
            var scope=new KernelDataScope(row.get("tenant_id").toString(),row.get("user_id").toString(),null,null,row.get("run_id").toString(),null,Map.of());
            try{worker(scope,()->{var c=tasks.contract(scope,row.get("task_id").toString());if(NativeSiloTaskService.supports(c)&&!tasks.get(scope,c.taskId()).status().terminal()){inputs.requireAuthorized(c);schedule(c);}return null;});}catch(RuntimeException unavailable){log.debug("Native recovery candidate unavailable: {}",row.get("task_id"));}
        }}catch(RuntimeException unavailable){/* Existing SQL/source recovery will retry; no execution is invented. */}
    }
    @Override public CodingTaskOutcome get(KernelDataScope scope,String task){return tasks.get(scope,task);}
    @Override public CodingTaskOutcome resume(KernelDataScope scope,String task){
        identity.require(scope,RECOVER);var c=tasks.contract(scope,task);inputs.requireAuthorized(c);
        if(tasks.get(scope,task).status().terminal())throw new IllegalStateException("A terminal task is immutable; re-execute with a new request ID");schedule(c);return tasks.get(scope,task);
    }
    @Override public Subscription subscribe(KernelDataScope s,String t,long after,Observer observer){return tasks.subscribe(s,t,after,observer);}
    @Override public CodingTaskOutcome cancel(KernelDataScope s,String task,String reason){
        identity.require(s,STOP);var c=tasks.contract(s,task);var prior=tasks.get(s,task);if(prior.status().terminal())return prior;
        var state=workspace.get(s,task);
        CodingTaskOutcome requested;
        try{requested=evidence.atomic(c,()->{workspace.cancel(s,task,state.epoch());return tasks.cancel(s,task,reason);});}
        catch(IllegalStateException sealed){if(tasks.get(s,task).status().terminal())return tasks.get(s,task);throw sealed;}
        var binding=tasks.privateState(s,task,BINDING);
        if(binding!=null)try{settings.client.cancel(binding.path("grant").asText(),binding.path("sessionId").asText());}catch(RuntimeException unknown){/* Worker reconciles actual stop. */}
        schedule(c);return requested;
    }
    private record Run(CodingTaskContract c,CodingJournal.Lease lease){}
    private CodingTaskOutcome outcome(Run r){return tasks.get(r.c.scope(),r.c.taskId());}
    private JsonNode state(Run r,String key){return tasks.privateState(r.c.scope(),r.c.taskId(),key);}
    private void save(Run r,String key,Object value){var old=state(r,key);tasks.privateState(r.c.scope(),r.c.taskId(),r.lease,key,old,json.valueToTree(value));}
    private void execute(CodingTaskContract c){
        Run run=null;
        try{
            var existing=tasks.get(c.scope(),c.taskId());if(existing.status().terminal())return;
            var held=tasks.ownership(c.scope(),c.taskId());
            var lease=owner.equals(held.owner())&&held.leaseUntil().isAfter(Instant.now())?held:tasks.claim(c.scope(),c.taskId(),owner,Duration.ofMinutes(3),existing.revision());
            run=new Run(c,lease);
            if(outcome(run).executionControl().stopReason()!=null){stop(run,false,"Cancellation requested");return;}
            if(!license.codingDispatchEntitlement().licenseValid())throw new SecurityException("Coding dispatch entitlement required");
            var binding=state(run,BINDING);
            if(binding==null){binding=binding(run);save(run,BINDING,binding);}
            String grant=binding.path("grant").asText(),session=binding.path("sessionId").asText();
            var inputFiles=json.convertValue(binding.path("inputs"),new com.fasterxml.jackson.core.type.TypeReference<List<NativeS3NodeClient.Input>>(){});
            // Stable grant/body/key make allocation retry safe. An unknown model
            // message is never blindly replayed after a Java process restart.
            try{settings.client.status(grant,session);}catch(NativeS3NodeClient.NodeException absent){if(absent.status()!=404)throw absent;settings.client.create(grant,inputFiles,"create-"+session);}
            Instant renewAt=Instant.now().plusSeconds(30);
            for(;;){
                var current=outcome(run);if(current.status().terminal())return;
                tasks.enforceDeadline(c.scope(),c.taskId(),run.lease,current.revision());
                if(outcome(run).executionControl().stopReason()!=null){stop(run,false,"Task stopped by Runtime");return;}
                if(Instant.now().isAfter(renewAt)){run=new Run(c,tasks.renew(c.scope(),c.taskId(),run.lease,Duration.ofMinutes(3),outcome(run).revision()));renewAt=Instant.now().plusSeconds(30);}
                poll(run,"core");poll(run,"provider");
                var remote=settings.client.status(grant,session);String nativeStatus=remote.path("native").path("status").asText();
                if(Set.of("START_FAILED","RECOVERABLE").contains(remote.path("workerState").asText()))throw new IllegalStateException("Native Worker requires recovery; no replacement model loop was started");
                if(state(run,"native-s3:message-intent")==null&&"READY".equals(remote.path("workerState").asText())){
                    save(run,"native-s3:message-intent",Map.of("messageId","objective-"+session,"persistedAt",Instant.now().toString()));
                    settings.client.message(grant,session,instruction(run),"objective-"+session);save(run,"native-s3:message-ack",Map.of("acknowledged",true));
                }
                if("turn_completed".equals(nativeStatus)||remote.hasNonNull("delivery")){
                    poll(run,"core");poll(run,"provider");
                    if(deliver(run,remote))return;
                }
                if("STOPPED".equals(remote.path("workerState").asText()))throw new IllegalStateException("Native Worker stopped without a committed delivery");
                Thread.sleep(500);
            }
        }catch(CodingContractException lost){if(shuttingDown||Thread.currentThread().isInterrupted())return;log.warn("Native task {} control rejected: {}",c.taskId(),lost.getMessage());if(run!=null)try{if(!outcome(run).status().terminal())stop(run,true,"Native control fence or contract rejected");}catch(RuntimeException unknown){recordControlFailure(run,unknown);}}
        catch(InterruptedException closing){Thread.currentThread().interrupt();/* Java restart leaves a durable binding, never a second Agent. */}
        catch(Exception failed){if(shuttingDown||Thread.currentThread().isInterrupted())return;log.warn("Native task {} failed",c.taskId(),failed);if(run!=null)try{stop(run,true,failed.getMessage()==null?failed.getClass().getSimpleName():failed.getMessage());}catch(RuntimeException unknown){recordControlFailure(run,unknown);}}
    }
    private JsonNode binding(Run r){
        var c=r.c;var frozen=inputs.requireAuthorized(c);var ws=workspace.get(c.scope(),c.taskId());var policy=workspace.transferPolicy(c.scope(),c.taskId(),ws.epoch());
        var files=inputs.inputCapabilities(c).stream().map(i->new NativeS3NodeClient.Input(i.input().path(),i.input().size(),i.input().sha256(),i.input().objectVersion(),i.url())).toList();
        String session="native-"+UUID.randomUUID();var grant=new LinkedHashMap<String,Object>();
        grant.put("version",2);grant.put("tenantId",c.scope().tenantId());grant.put("userId",c.scope().userId());grant.put("taskId",c.taskId());grant.put("sessionId",session);grant.put("workspaceRef","project");
        grant.put("modelProfile",settings.profile(frozen.get("selectedModel").toString()));if(settings.dynamicModels)grant.put("runtimeModel",settings.descriptor(frozen.get("selectedModel").toString()));grant.put("budgetPolicy",Map.of("status","DEFERRED_STAGE_2_REVIEW"));
        grant.put("toolPermissions",c.authorization().allowedTools());grant.put("dataPermissions",List.of());grant.put("networkPolicy","broker-only");grant.put("expiresAt",c.budget().deadlineAt().toEpochMilli());grant.put("runtimeProfile","native-python-silo-v1");
        grant.put("deliveryBinding",Map.of("tenantId",c.scope().tenantId(),"taskId",c.taskId(),"runId",c.scope().runId(),"inputSnapshotVersion",ws.snapshotVersion()));
        grant.put("runtimeTools",c.authorization().allowedTools().stream().filter(t->com.chatchat.api.runtime.coding.knowledge.CodingInternetAccess.NATIVE_TOOLS.contains(t)||com.chatchat.api.runtime.coding.knowledge.CodingDocumentSearchAccess.NATIVE_TOOLS.contains(t)).toList());
        grant.put("s3",Map.of("origin",policy.get("origin"),"inputPrefix",policy.get("inputPrefix"),"outputPrefix",policy.get("outputPrefix"),"inputManifestSha256",settings.client.inputManifestHash(files)));
        return json.valueToTree(Map.of("sessionId",session,"grant",settings.client.signGrant(grant,settings.issuerKey),"inputs",files,"transportEpoch",ws.epoch()));
    }
    private String instruction(Run r){
        var frozen=inputs.requireAuthorized(r.c);var ws=workspace.get(r.c.scope(),r.c.taskId());
        return r.c.objective().instruction()+"\n\nRuntime delivery contract (not analysis instructions): Read the authorized original files at these real workspace paths: "+ws.inputs().stream().map(SiloCodingWorkspaceService.Input::path).toList()
            +". Runtime Internet admission: "+r.c.runtimeContext().getOrDefault("internetAvailability",Map.of("status","NOT_ADMITTED"))+"; authorized optional Runtime MCP tools: "+r.c.authorization().allowedTools().stream().filter(t->com.chatchat.api.runtime.coding.knowledge.CodingInternetAccess.NATIVE_TOOLS.contains(t)||com.chatchat.api.runtime.coding.knowledge.CodingDocumentSearchAccess.NATIVE_TOOLS.contains(t)).map(t->"runtime__"+t).toList()+". An unavailable capability is an evidence gap, not an empty public dataset; direct worker networking is not granted. input/ is read-only. Choose meaningful source names and any project structure. Use native tools and local Python when computation is needed; no remote Sandbox. Inspect real execution errors and repair your own code. Do not invent data or evidence. "
            +NativeVisualReport.instruction(r.c.runtimeContext())
            +"Deliver the requested artifact types: "+frozen.get("artifactTypes")+". Create delivery_manifest.json with schemaVersion coding_node_delivery_selection.v1 and artifacts [{path,type}]. Each path is relative to /workspace and identifies an existing file you choose. Allowed types: SOURCE, REPORT, RESULT, CHART, TEST, AUXILIARY. Do not include input/, logs/, evidence/, .git/ or the selection manifest itself. "
            +(frozen.get("resultSchema") instanceof Map<?,?> schema&&!schema.isEmpty()?"The declared RESULT JSON must match this structural schema (an object or an array of matching objects): "+new String(evidence.bytes(schema),StandardCharsets.UTF_8)+". ":"")
            +"Run and inspect your project and actual deliverables before ending the turn. Runtime, not the Agent, verifies hashes, object versions and acceptance.";
    }
    private long cursor(Run r,String channel){var n=state(r,"native-s3:read:"+channel);return n==null?0:n.path("sequence").asLong();}
    private void poll(Run r,String channel){
        var b=state(r,BINDING);long after=cursor(r,channel);for(int page=0;page<16;page++){
            var response=channel.equals("core")?settings.client.observations(b.path("grant").asText(),b.path("sessionId").asText(),after,128):settings.client.receipts(b.path("grant").asText(),b.path("sessionId").asText(),after,128);
            var events=response.path("events");if(events.isEmpty())return;
            var raw=evidence.archive(r.c,channel+"-events","native-"+channel+"-events.json",evidence.bytes(response));
            for(var event:events){String type=event.path("eventType").asText();if(Set.of("native.event","model.text.delta","model.usage").contains(type))continue;observe(r,channel,event,raw.id());}
            after=response.path("nextEventId").asLong();if(outcome(r).executionControl().stopReason()==null)save(r,"native-s3:read:"+channel,Map.of("sequence",after));if(!response.path("hasMore").asBoolean())return;
        }
    }
    private void observe(Run r,String channel,JsonNode event,String rawAsset){
        String type=event.path("eventType").asText();rawAsset=evidence.archive(r.c,channel+"-event-"+event.path("eventId").asText(),"native-"+channel+"-receipt.json",evidence.bytes(event)).id();JsonNode p=channel.equals("provider")?event:event.path("payload");
        String operation=null,group=null,telemetryId=null;CodingJournal.OperationKind kind=null;Counters actual=null;boolean complete=false,metered=false;
        var projected=new LinkedHashMap<String,Object>();projected.put("nativeEventType",type);projected.put("sessionId",state(r,BINDING).path("sessionId").asText());projected.put("detailAssetId",rawAsset);
        String normalized="OBSERVATION",summary=type;
        if(channel.equals("provider")&&type.startsWith("model.request.")){
            operation="model-"+p.path("requestId").asText();kind=CodingJournal.OperationKind.MODEL;complete=type.equals("model.request.completed");long tokens=p.path("usage").path("totalTokens").asLong();
            actual=new Counters(1,tokens,0,0,0,0,0,"0");metered=complete&&p.path("usage").path("totalTokens").isNumber();
            normalized=complete?"MODEL_REQUEST_COMPLETED":"MODEL_REQUEST_STARTED";summary=p.path("providerModel").asText(p.path("model").asText());group="model";telemetryId=p.path("requestId").asText();
            for(String field:List.of("requestId","model","providerModel","usage","durationMs"))if(p.hasNonNull(field))projected.put(field,json.convertValue(p.get(field),Object.class));
        }else if(channel.equals("provider")){
            summary=type+": "+p.path("tool").asText(p.path("name").asText(p.path("url").asText()));
            for(String field:List.of("tool","requestId","isError","skillId","name","version","source","bytes","url","sha256","size","path","assetId","retrieved_at","files"))if(p.hasNonNull(field))projected.put(field,json.convertValue(p.get(field),Object.class));
        }else if(type.startsWith("tool.")){
            String tool=p.path("toolName").asText();operation="tool-"+evidence.hash(p.path("toolCallId")).substring(0,32);kind=CodingJournal.OperationKind.TOOL;
            complete=!type.equals("tool.started");actual=new Counters(0,0,1,0,0,0,0,"0");projected.put("toolCallId",p.path("toolCallId").asText());
            normalized=type.equals("tool.failed")?"ERROR_OCCURRED":type.equals("tool.started")?"TOOL_STARTED":Set.of("editor","apply_patch").contains(tool)?"FILE_CHANGED":"TOOL_COMPLETED";summary=tool;
        }else if(type.startsWith("command.")&&Set.of("command.started","command.completed").contains(type)){
            String command=p.path("command").path("executable").asText()+" "+p.path("command").path("args").toString();
            if(!command.matches("(?is).*(?:python|pytest).*"))return;
            operation="cmd-"+p.path("recordId").asText();kind=CodingJournal.OperationKind.CODE;complete=type.equals("command.completed");actual=new Counters(0,0,0,1,0,0,p.path("stdout").path("size").asLong()+p.path("stderr").path("size").asLong(),"0");
            normalized=complete&&p.path("exitCode").asInt(-1)!=0?"ERROR_OCCURRED":complete?"COMMAND_COMPLETED":"COMMAND_STARTED";summary=command;projected.put("command",command);if(p.hasNonNull("exitCode"))projected.put("exitCode",p.path("exitCode").asInt());
            group="execution";telemetryId=p.path("recordId").asText();
        }
        var proof=new LinkedHashMap<String,Object>();proof.put("taskId",r.c.taskId());proof.put("sessionId",state(r,BINDING).path("sessionId").asText());proof.put("operationId",operation==null?"observation":operation);proof.put("stopped",complete);proof.put("source",channel.equals("provider")?(type.startsWith("model.request.")?"PROVIDER_HTTP_OBSERVATION":"RUNTIME_CAPABILITY_OBSERVATION"):"NATIVE_ROOT_HOST_OBSERVATION");proof.put("nativeEventId",event.path("eventId").asText());proof.put("rawAssetId",rawAsset);proof.put("sha256",evidence.hash(event));
        String ref=evidence.register(r.c,NativeS3ExecutionProofVerifier.TYPE,channel+":"+event.path("eventId").asText(),proof,List.of(r.c.inputSnapshotRef()));
        if(operation!=null)projected.put("operationId",operation);
        if(p.hasNonNull("toolName"))projected.put("tool",p.path("toolName").asText());
        for(String field:List.of("status","startedAt","finishedAt","durationMs"))if(p.hasNonNull(field))projected.put(field,json.convertValue(p.get(field),Object.class));
        if(event.hasNonNull("timestamp"))projected.put("nativeOccurredAt",event.path("timestamp").asText());
        JsonNode telemetry=group==null?null:p.deepCopy();
        if("execution".equals(group)){
            var commandRecord=(ObjectNode)telemetry;commandRecord.put("command",summary);commandRecord.put("detailAssetId",rawAsset);commandRecord.put("nativeEventId",event.path("eventId").asText());
            if(complete){enrichExecution(r,commandRecord);if(commandRecord.hasNonNull("exceptionSummary")){normalized="ERROR_OCCURRED";summary=commandRecord.path("exceptionSummary").asText();}}
        }
        if("FILE_CHANGED".equals(normalized)&&outcome(r).executionControl().stopReason()==null)save(r,"native-s3:edit:"+event.path("eventId").asText(),Map.of("nativeEventId",event.path("eventId").asText(),"detailAssetId",rawAsset));
        var usage=operation==null?null:new DurableCodingTaskService.NativeUsage(operation,kind,actual,complete,metered);
        tasks.recordNativeObservation(r.c.scope(),r.c.taskId(),r.lease,outcome(r).revision(),new DurableCodingTaskService.NativeObservation(channel,event.path("eventId").asLong(),event.path("eventId").asText(),evidence.hash(event),ref,normalized,summary.substring(0,Math.min(2048,summary.length())),projected,usage,group,telemetryId,telemetry));
        if("execution".equals(group)&&complete&&outcome(r).executionControl().stopReason()==null)linkRepair(r,telemetry);
    }
    /** Derive diagnostics from the actual captured process streams. A shell
     * wrapper's zero exit code is retained, including when it masks Python. */
    private void enrichExecution(Run r,ObjectNode record){
        var b=state(r,BINDING);String id=record.path("recordId").asText();
        for(String stream:List.of("stdout","stderr")){
            var meta=record.path(stream);if(!meta.has("sha256"))continue;
            byte[] bytes=settings.client.executionContent(b.path("grant").asText(),b.path("sessionId").asText(),id,stream,null,meta.path("size").asLong(),meta.path("sha256").asText());
            var asset=evidence.archive(r.c,"execution-"+id+"-"+stream,"command-"+id+"-"+stream+".txt",bytes);record.put(stream+"AssetId",asset.id());
            String output=new String(bytes,StandardCharsets.UTF_8);
            if(output.contains("Traceback (most recent call last):")){
                var match=java.util.regex.Pattern.compile("(?m)^([\\w.]+(?:Error|Exception)):[^\\r\\n]*").matcher(output);
                while(match.find())record.put("exceptionSummary",match.group().substring(0,Math.min(500,match.group().length())));
            }
        }
        record.put("pythonExceptionWithZeroShellExit",record.hasNonNull("exceptionSummary")&&record.path("exitCode").asInt(-1)==0);
        for(var source:record.path("sourceSnapshots")){
            byte[] bytes=settings.client.executionContent(b.path("grant").asText(),b.path("sessionId").asText(),id,"source",source.path("path").asText(),source.path("size").asLong(),source.path("sha256").asText());
            var asset=evidence.archive(r.c,"source-"+id+"-"+source.path("sha256").asText(),source.path("path").asText().replace('/','_'),bytes);((ObjectNode)source).put("assetId",asset.id());
        }
    }
    private void linkRepair(Run r,JsonNode success){
        if(success.path("exitCode").asInt(-1)!=0||success.hasNonNull("exceptionSummary"))return;
        long end=success.path("nativeEventId").asLong();
        for(var failed:tasks.nativeTelemetry(r.c.scope(),r.c.taskId(),"execution")){
            if(!failed.hasNonNull("exceptionSummary")&&failed.path("exitCode").asInt(0)==0)continue;
            long start=failed.path("nativeEventId").asLong();if(start>=end||!failed.path("command").equals(success.path("command")))continue;
            var changed=new ArrayList<Map<String,Object>>();
            for(var before:failed.path("sourceSnapshots"))for(var after:success.path("sourceSnapshots"))if(before.path("path").equals(after.path("path"))&&!before.path("sha256").equals(after.path("sha256")))changed.add(Map.of("path",before.path("path").asText(),"beforeSha256",before.path("sha256").asText(),"afterSha256",after.path("sha256").asText(),"beforeAssetId",before.path("assetId").asText(),"afterAssetId",after.path("assetId").asText()));
            if(changed.isEmpty())continue;
            JsonNode edit=null;for(long seq=start+1;seq<end;seq++){var candidate=state(r,"native-s3:edit:"+seq);if(candidate!=null)edit=candidate;}
            if(edit==null)continue;
            var repair=new LinkedHashMap<String,Object>();repair.put("verified",true);repair.put("failureEventId",failed.path("nativeEventId").asText());repair.put("editEventId",edit.path("nativeEventId").asText());repair.put("executionEventId",success.path("nativeEventId").asText());repair.put("exceptionSummary",failed.path("exceptionSummary").asText());repair.put("changedSources",changed);repair.put("failureStdoutAssetId",failed.path("stdoutAssetId").asText());repair.put("failureStderrAssetId",failed.path("stderrAssetId").asText());repair.put("rerunStdoutAssetId",success.path("stdoutAssetId").asText());repair.put("verification","CHANGED_SOURCE_SNAPSHOT_AND_SAME_COMMAND_RERUN_WITHOUT_OBSERVED_EXCEPTION");
            save(r,"native-s3:repair:"+failed.path("recordId").asText(),repair);
        }
    }
    private boolean deliver(Run r,JsonNode remote){
        var b=state(r,BINDING);String grant=b.path("grant").asText(),session=b.path("sessionId").asText();var ws=workspace.get(r.c.scope(),r.c.taskId());
        String version=state(r,"native-s3:delivery-version")==null?"v1":state(r,"native-s3:delivery-version").path("version").asText();
        JsonNode manifest;
        if(remote.hasNonNull("delivery"))manifest=remote.get("delivery");else{
            JsonNode preview;
            try{preview=settings.client.preview(grant,session,version);}catch(NativeS3NodeClient.NodeException invalid){if(invalid.validationFeedback()==null)throw invalid;feedback(r,version,invalid.validationFeedback());return false;}
            String rejection=validateCandidate(r,preview);
            if(rejection!=null){feedback(r,version,rejection);return false;}
            manifest=settings.client.seal(grant,session,version);
        }
        // Drain genuine receipts after seal; no synthetic final tool events.
        poll(r,"core");poll(r,"provider");remote=settings.client.status(grant,session);
        if(!remote.path("stopProof").path("running").isBoolean()||remote.path("stopProof").path("running").asBoolean(true))throw new IllegalStateException("Native Executor stop not confirmed");
        String stopRef=stopReference(r,remote);reconcile(r,stopRef);
        ws=workspace.get(r.c.scope(),r.c.taskId());JsonNode upload=remote.path("uploadReceipt");
        if("RUNNING".equals(ws.status())){
            var delivery=json.convertValue(manifest,SiloCodingWorkspaceService.Delivery.class);var plans=workspace.plan(r.c.scope(),r.c.taskId(),ws.epoch(),delivery);
            var plan=new LinkedHashMap<String,Object>();plan.put("manifestSha256",evidence.hash(manifest));plan.put("artifacts",plans);plan.putAll(workspace.metadataUploadPlan(r.c.scope(),r.c.taskId(),ws.epoch()));
            upload=settings.client.upload(grant,session,json.valueToTree(plan));
            workspace.commit(r.c.scope(),r.c.taskId(),ws.epoch(),json.convertValue(upload.path("artifacts"),new com.fasterxml.jackson.core.type.TypeReference<List<SiloCodingWorkspaceService.Uploaded>>(){}));
        }
        ws=workspace.get(r.c.scope(),r.c.taskId());if("RECEIVED_PENDING_BUSINESS_VALIDATION".equals(ws.status()))ws=workspace.verifyDeliveryIntegrity(r.c.scope(),r.c.taskId(),ws.epoch());
        var meta=upload.path("evidence");byte[] journal=workspace.transferEvidence(r.c.scope(),r.c.taskId(),ws.epoch(),"evidence",meta.path("objectVersion").asText(),meta.path("size").asLong());
        if(!UserAssetService.hash(journal).equals(meta.path("sha256").asText()))throw new IllegalStateException("Native Silo journal hash differs");
        archiveExecutions(r);
        var finalRemote=remote;var stateBeforeCommit=ws;
        evidence.atomic(r.c,()->{
            if(!"COMPLETED".equals(stateBeforeCommit.status()))workspace.finalizeSealed(r.c.scope(),r.c.taskId(),stateBeforeCommit.epoch(),stateBeforeCommit.deliveryHash(),journal,
                ignored->new SiloCodingWorkspaceService.StopObservation(finalRemote.path("stopProof").path("containerId").asText(),true,"NATIVE_EXECUTOR_OBSERVATION"));
            commit(r,stopRef);if(outcome(r).status()!=Status.COMPLETED)throw new IllegalStateException("Runtime stop/deadline prevents delivery completion");return null;
        });
        try{settings.client.accept(grant,session,evidence.hash(manifest),true,outcome(r).validationRecordRef());}catch(RuntimeException ackUnknown){evidence.archive(r.c,"node-acceptance-ack","node-acceptance-ack.json",evidence.bytes(Map.of("acknowledgement","UNKNOWN","runtimeAcceptanceCommitted",true)));}return true;
    }
    private static boolean matches(Map<String,Object> schema,Map<String,Object> row){try{com.chatchat.agents.orchestration.analysis.graph.WorkspaceResultSchema.validate(row,schema);return true;}catch(IllegalArgumentException mismatch){return false;}}
    private String validateCandidate(Run r,JsonNode preview){
        var frozen=inputs.requireAuthorized(r.c);var types=new HashSet<String>();preview.path("artifacts").forEach(a->types.add(a.path("type").asText()));
        if(!types.containsAll((List<?>)frozen.get("artifactTypes")))return "Missing declared artifact types: "+frozen.get("artifactTypes");
        if(com.chatchat.common.runtime.coding.VisualReportMode.active(r.c.runtimeContext())){
            long entries=java.util.stream.StreamSupport.stream(preview.path("artifacts").spliterator(),false).filter(a->"REPORT".equals(a.path("type").asText())&&a.path("path").asText().toLowerCase(java.util.Locale.ROOT).endsWith(".html")).count();
            if(entries!=1)return "Interactive report delivery requires exactly one HTML entry with type REPORT and its local dependencies in the delivery selection. Inspect and repair the deliverables.";
        }
        var schema=(Map<String,Object>)frozen.getOrDefault("resultSchema",Map.of());if(schema.isEmpty())return null;
        var b=state(r,BINDING);for(var a:preview.path("artifacts"))if(a.path("type").asText().equals("RESULT"))try{
            var data=json.readTree(settings.client.previewContent(b.path("grant").asText(),b.path("sessionId").asText(),preview,a.path("path").asText()));
            var rows=json.convertValue(data.isArray()?data:json.createArrayNode().add(data),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});

            if(!rows.isEmpty()&&rows.stream().allMatch(row->matches(schema,row)))return null;
        }catch(Exception invalid){/* Try another declared RESULT; never manufacture values. */}
        return "No declared RESULT JSON matches the frozen output schema: "+new String(evidence.bytes(schema),StandardCharsets.UTF_8);
    }
    private void feedback(Run r,String version,String rejection){
        var receipt=evidence.archive(r.c,"delivery-rejection-"+version,"delivery-rejection.json",evidence.bytes(Map.of("deliveryVersion",version,"reason",rejection,"source","JAVA_DELIVERY_CONTRACT_VALIDATION")));
        int next=Integer.parseInt(version.substring(1))+1;save(r,"native-s3:delivery-version",Map.of("version","v"+next,"rejectedVersion",version,"receiptAssetId",receipt.id()));
        var b=state(r,BINDING);settings.client.message(b.path("grant").asText(),b.path("sessionId").asText(),"Actual Runtime delivery contract rejection: "+rejection+". Correct the deliverable and its selection manifest in this same session, then finish again.","delivery-rejection-"+version);
    }
    private String stopReference(Run r,JsonNode remote){
        var proof=remote.path("stopProof");if(!proof.path("containerId").asText().matches("[a-f0-9]{64}")||proof.path("running").asBoolean(true))throw new IllegalStateException("Actual scoped Executor stop proof required");
        return evidence.register(r.c,NativeS3ExecutionProofVerifier.STOP_TYPE,"stop",Map.of("taskId",r.c.taskId(),"sessionId",state(r,BINDING).path("sessionId").asText(),"workerStopped",true,"containerId",proof.path("containerId").asText(),"stopProof",json.convertValue(proof,Map.class)),List.of(r.c.inputSnapshotRef()));
    }
    private void reconcile(Run r,String proof){for(String operation:tasks.unresolvedOperations(r.c.scope(),r.c.taskId()))tasks.reconcileStopped(r.c.scope(),r.c.taskId(),r.lease,outcome(r).revision(),operation,proof);}
    private void archiveExecutions(Run r){
        var b=state(r,BINDING);var list=settings.client.executions(b.path("grant").asText(),b.path("sessionId").asText());
        for(var record:list.path("executions")){
            if(!record.path("command").toString().matches("(?is).*(?:python|pytest).*"))continue;
            String id=record.path("recordId").asText();if(!record.has("stdout"))continue;
            for(String stream:List.of("stdout","stderr")){
                var meta=record.path(stream);byte[] bytes=settings.client.executionContent(b.path("grant").asText(),b.path("sessionId").asText(),id,stream,null,meta.path("size").asLong(),meta.path("sha256").asText());
                evidence.archive(r.c,"execution-"+id+"-"+stream,"command-"+id+"-"+stream+".txt",bytes);
            }
            for(var source:record.path("sourceSnapshots")){
                byte[] bytes=settings.client.executionContent(b.path("grant").asText(),b.path("sessionId").asText(),id,"source",source.path("path").asText(),source.path("size").asLong(),source.path("sha256").asText());
                evidence.archive(r.c,"source-"+id+"-"+source.path("sha256").asText(),source.path("path").asText().replace('/','_'),bytes);
            }
        }
    }
    private void commit(Run r,String stopRef){
        var ws=workspace.get(r.c.scope(),r.c.taskId());var rows=new ArrayList<Map<String,Object>>();String report="";
        var assetRows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<ws.delivery().artifacts().size();i++){
            var a=ws.delivery().artifacts().get(i);String asset=ws.assetIds().get(i);assetRows.add(Map.of("path",a.path(),"type",a.type(),"assetId",asset,"sha256",a.sha256(),"size",a.size()));
            if(a.type().equals("REPORT")&&a.path().endsWith(".md")&&report.isEmpty())report=new String(evidence.assets.readTask(r.c.scope(),r.c.taskId(),asset),StandardCharsets.UTF_8);
        }
        var schema=(Map<String,Object>)inputs.requireAuthorized(r.c).getOrDefault("resultSchema",Map.of());
        if(!schema.isEmpty())for(var a:assetRows)if(a.get("type").equals("RESULT"))try{
            var document=json.readTree(evidence.assets.readTask(r.c.scope(),r.c.taskId(),a.get("assetId").toString()));
            var decoded=json.convertValue(document.isArray()?document:json.createArrayNode().add(document),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});
            if(!decoded.isEmpty()&&decoded.stream().allMatch(row->matches(schema,row))){rows.addAll(decoded);break;}
        }catch(Exception rejected){/* No fallback to fabricated structured data. */}
        if(!schema.isEmpty()&&rows.isEmpty())throw new IllegalStateException("Sealed RESULT no longer matches its validated schema");
        if(rows.isEmpty())rows.add(Map.of("reportMarkdown",report,"artifacts",assetRows,"businessEvaluated",false));
        byte[] rowBytes=evidence.bytes(rows);var rowAsset=evidence.archive(r.c,"result-rows","delivery-results.json",rowBytes);
        String code=evidence.register(r.c,"CODING_NATIVE_SOURCE_INVENTORY","code",Map.of("taskId",r.c.taskId(),"artifacts",assetRows.stream().filter(a->a.get("type").equals("SOURCE")||a.get("type").equals("TEST")).toList()),List.of(r.c.inputSnapshotRef()));
        String result=evidence.register(r.c,"CODING_STRUCTURED_DATASET","result",Map.of("taskId",r.c.taskId(),"delivery","NATIVE_SILO_ROWS","assetId",rowAsset.id(),"artifactSha256",rowAsset.sha256(),"size",rowAsset.size(),"rowCount",rows.size(),"schemaJson","{}"),List.of(r.c.inputSnapshotRef(),stopRef));
        String validation=evidence.register(r.c,"CODING_COMPUTATION_VALIDATION","validation",Map.of("taskId",r.c.taskId(),"status","INCONCLUSIVE","structuralChecksPassed",true,"businessEvaluated",false,"profile","artifact-integrity-v1","deliveryHash",ws.deliveryHash()),List.of(result,stopRef));
        String bundle=evidence.bundle(r.c,List.of(r.c.inputSnapshotRef(),code,stopRef,result,validation));
        var prior=outcome(r);var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);var proposed=new CodingTaskOutcome(prior.schemaVersion(),prior.taskId(),prior.scope(),prior.contractSha256(),Status.COMPLETED,prior.revision()+1,prior.lastEventSequence()+1,prior.createdAt(),at,prior.startedAt(),at,false,null,
            "Native delivery completed; Silo versions and SHA-256 verified",List.of("Delivery/structural acceptance only; business meaning is not evaluated by Runtime"),prior.inputSnapshotRef(),code,stopRef,result,validation,bundle,ValidationStatus.INCONCLUSIVE,
            new Reproducibility(ReproducibilityLevel.CONDITIONAL,List.of("Model generation is not deterministic"),List.of(r.c.environmentRef())),prior.usage(),new ExecutionControl(null,null,true,false),prior.parentTaskId(),prior.supersedesTaskId(),Map.of());
        tasks.commitOutcome(r.c.scope(),r.c.taskId(),r.lease,prior.revision(),proposed);
    }
    private void stop(Run r,boolean failed,String reason){
        var ownership=tasks.ownership(r.c.scope(),r.c.taskId());
        if(ownership.fencingToken()!=r.lease.fencingToken()||!Objects.equals(ownership.owner(),r.lease.owner())||!ownership.leaseUntil().isAfter(Instant.now()))throw new IllegalStateException("Stale Java controller cannot cancel a Native session");
        var b=state(r,BINDING);if(b==null){var prior=outcome(r);if(prior.executionControl().stopReason()!=null)tasks.finishStoppedWithoutExecution(r.c.scope(),r.c.taskId(),prior.revision());return;}
        var ws=workspace.get(r.c.scope(),r.c.taskId());if(!Set.of("COMPLETED","CANCELLED","FAILED").contains(ws.status()))workspace.cancel(r.c.scope(),r.c.taskId(),ws.epoch());
        JsonNode remote;
        try{settings.client.cancel(b.path("grant").asText(),b.path("sessionId").asText());remote=settings.client.status(b.path("grant").asText(),b.path("sessionId").asText());}
        catch(NativeS3NodeClient.NodeException absent){
            // Host persists the Session before allocating a Worker; objective dispatch is
            // preceded by our durable message intent. A missing Session alone is NOT proof.
            if(absent.status()!=404||state(r,"native-s3:message-intent")!=null
                    ||tasks.hasStartedOperations(r.c.scope(),r.c.taskId())
                    ||outcome(r).executionControl().stopReason()==null)throw absent;
            tasks.finishStoppedWithoutExecution(r.c.scope(),r.c.taskId(),outcome(r).revision());return;
        }
        for(int attempt=0;attempt<150&&!remote.path("stopProof").path("running").isBoolean();attempt++){
            try{Thread.sleep(200);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Stop reconciliation interrupted",interrupted);}
            remote=settings.client.status(b.path("grant").asText(),b.path("sessionId").asText());
        }
        String proof=stopReference(r,remote);reconcile(r,proof);
        // A missing historical telemetry file must not discard an actual Executor stop proof.
        try{poll(r,"core");poll(r,"provider");}catch(RuntimeException unavailable){
            try{evidence.archive(r.c,"stop-telemetry-unavailable","stop-telemetry-unavailable.json",
                evidence.bytes(Map.of("workerStopVerified",true,"telemetryAvailable",false)));}
            catch(RuntimeException archiveUnavailable){/* The verified stop proof is already durable. */}
        }
        var prior=outcome(r);if(prior.status().terminal())return;
        if(prior.executionControl().stopReason()!=null){tasks.finishStoppedAfterReconciliation(r.c.scope(),r.c.taskId(),r.lease,prior.revision());return;}
        var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);var proposal=new CodingTaskOutcome(prior.schemaVersion(),prior.taskId(),prior.scope(),prior.contractSha256(),Status.FAILED,prior.revision()+1,prior.lastEventSequence()+1,prior.createdAt(),at,prior.startedAt(),at,false,CodingErrorCode.EXECUTION_FAILED,
            reason.substring(0,Math.min(2048,reason.length())),List.of("Native execution failed; original receipts and partial artifacts are retained"),prior.inputSnapshotRef(),null,proof,null,null,null,ValidationStatus.NOT_RUN,prior.reproducibility(),prior.usage(),new ExecutionControl(null,null,true,false),null,null,Map.of());
        tasks.commitOutcome(r.c.scope(),r.c.taskId(),r.lease,prior.revision(),proposal);
    }
    private void recordControlFailure(Run r,Exception failure){
        try{var data=Map.of("message","Native transport/stop reconciliation unavailable","exceptionType",failure.getClass().getSimpleName());var file=evidence.archive(r.c,"control-error","native-control-error.json",evidence.bytes(data));
            tasks.recordWorkerProgress(r.c.scope(),r.c.taskId(),r.lease,outcome(r).revision(),System.currentTimeMillis(),"SESSION_TIMING",null,evidence.hash(data),Map.of("normalizedType","ERROR_OCCURRED","summary","Native transport outcome unknown; stop must be reconciled","detailAssetId",file.id(),"source","JAVA_NATIVE_CONTROL"));
        }catch(RuntimeException ignored){/* Durable state stays nonterminal when stop is unknown. */}
    }
}
