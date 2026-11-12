package com.chatchat.api.runtime.coding.monitor;

import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Rebuildable query projection of the existing Coding journal, in its database.
 * It cannot create, finish, cancel or resume a task. No prompts/code/log bodies
 * are copied into the list. Failures and backlogs never block an Agent call. */
@Service
@ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
public final class CodingMonitorProjection implements DurableCodingTaskService.ProjectionListener {
    private record Update(CodingTaskContract contract,CodingTaskOutcome outcome,List<CodingExecutionEvent> events){}
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final JdbcRuntimeEvidenceStore source;
    private final ConcurrentLinkedQueue<Update> queue=new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued=new AtomicInteger();private volatile boolean lagging;
    private String backfillCursor="";private long backfillFinishedAt;
    public CodingMonitorProjection(JdbcTemplate jdbc,ObjectMapper json,DurableCodingTaskService tasks,JdbcRuntimeEvidenceStore source){
        this.jdbc=jdbc;this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);this.source=source;tasks.setProjectionListener(this);
    }
    @PostConstruct void schema(){
        jdbc.execute("create table if not exists runtime_coding_task_view (id varchar(64) primary key,tenant_id varchar(128) not null,user_id varchar(128) not null,run_id varchar(256) not null,task_id varchar(256) not null,task_name varchar(256) not null,status varchar(32) not null,created_at bigint not null,updated_at bigint not null,revision bigint not null,summary_json text not null)");
        jdbc.execute("create index if not exists idx_coding_view_owner_time on runtime_coding_task_view(tenant_id,user_id,created_at,id)");
        jdbc.execute("create table if not exists runtime_coding_event_view (view_id varchar(64) not null,sequence bigint not null,occurred_at bigint not null,native_type varchar(128),normalized_type varchar(64) not null,event_json text not null,primary key(view_id,sequence))");
    }
    @Override public void committed(CodingTaskContract contract,CodingTaskOutcome outcome,List<CodingExecutionEvent> events){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()){
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){enqueue(contract,outcome,events);}
            });return;
        }
        enqueue(contract,outcome,events);
    }
    private void enqueue(CodingTaskContract contract,CodingTaskOutcome outcome,List<CodingExecutionEvent> events){
        if(queued.incrementAndGet()>1024){queued.decrementAndGet();lagging=true;return;}
        queue.add(new Update(contract,outcome,events));
    }
    @Scheduled(fixedDelay=500) public synchronized void flush(){
        int processed=0;Update update;
        while(processed++<128&&(update=queue.peek())!=null){
            try{write(update);queue.poll();queued.decrementAndGet();}
            catch(RuntimeException unavailable){lagging=true;return;}
        }
        if(queue.isEmpty())lagging=false;
    }
    /** Backfill/rebuild from existing owner journals, including after a missed
     * projection notification. This never starts or recovers an execution. */
    @Scheduled(fixedDelay=2000,initialDelay=2000) public synchronized void backfill(){
        if(backfillFinishedAt>0&&System.currentTimeMillis()-backfillFinishedAt<300000)return;
        var rows=jdbc.queryForList("select p.id,p.tenant_id,p.user_id,p.run_id from runtime_evidence_partition p join runtime_evidence_entry e on e.partition_id=p.id where p.id>? and e.entry_kind='EXEC_CHECKPOINT' and e.record_json like ? order by p.id limit 64",backfillCursor,"%\"key\":\"coding.tasks.v1\"%");
        for(var row:rows){
            try{
                var scope=new KernelDataScope(row.get("tenant_id").toString(),row.get("user_id").toString(),null,null,row.get("run_id").toString(),null,Map.of());
                var text=source.readExecutionCheckpoint(scope,DurableCodingTaskService.CHECKPOINT_KEY);
                if(text.isPresent())for(var task:json.readTree(text.get()).path("tasks")){
                    var contract=json.treeToValue(task.path("contract"),CodingTaskContract.class);
                    var outcome=json.treeToValue(task.path("outcome"),CodingTaskOutcome.class);
                    var events=new ArrayList<CodingExecutionEvent>();for(var event:task.path("events"))events.add(json.treeToValue(event,CodingExecutionEvent.class));
                    write(new Update(contract,outcome,events));
                }
                backfillCursor=row.get("id").toString();
            }catch(Exception unavailable){lagging=true;return;}
        }
        if(rows.size()<64){backfillCursor="";backfillFinishedAt=System.currentTimeMillis();}
    }
    private void write(Update update){
        var contract=update.contract();var outcome=update.outcome();var scope=contract.scope();String id=id(scope.tenantId(),scope.userId(),scope.runId(),contract.taskId());
        for(var event:update.events()){
            var view=event(event);String encoded=encode(view);
            try{jdbc.update("insert into runtime_coding_event_view(view_id,sequence,occurred_at,native_type,normalized_type,event_json) values(?,?,?,?,?,?)",
                id,event.sequence(),event.occurredAt().toEpochMilli(),view.get("nativeEventType"),view.get("type"),encoded);}
            catch(org.springframework.dao.DuplicateKeyException duplicate){
                String prior=jdbc.queryForObject("select event_json from runtime_coding_event_view where view_id=? and sequence=?",String.class,id,event.sequence());
                if(!encoded.equals(prior))throw new IllegalStateException("Monitor event source identity conflict");
            }
        }
        String name=contract.objective().instruction().split("\n",2)[0];if(name.length()>256)name=name.substring(0,256);
        var summary=new LinkedHashMap<String,Object>();summary.put("taskId",contract.taskId());summary.put("runId",scope.runId());summary.put("name",name);
        summary.put("status",outcome.status());summary.put("createdAt",outcome.createdAt());summary.put("startedAt",outcome.startedAt());summary.put("updatedAt",outcome.updatedAt());summary.put("endedAt",outcome.endedAt());
        summary.put("canStop",!outcome.status().terminal());
        summary.put("stopPending",!outcome.status().terminal()&&(outcome.cancellationRequested()||outcome.executionControl().stopReason()!=null));
        summary.put("modelRequests",outcome.usage().consumed().modelCalls());summary.put("tokens",outcome.usage().meteringComplete()?outcome.usage().consumed().modelTokens():null);
        summary.put("confirmedTokenLowerBound",outcome.usage().consumed().modelTokens());
        var providerRows=jdbc.queryForList("select event_json from runtime_coding_event_view where view_id=? and normalized_type='MODEL_REQUEST_COMPLETED'",id);
        if(!providerRows.isEmpty()){
            long tokens=0;boolean complete=providerRows.size()==outcome.usage().consumed().modelCalls();
            for(var row:providerRows){Object raw=decode(row.get("event_json")).get("usage");
                if(raw instanceof Map<?,?> usage&&usage.get("inputTokens") instanceof Number input&&usage.get("outputTokens") instanceof Number output)tokens=Math.addExact(tokens,Math.addExact(input.longValue(),output.longValue()));
                else complete=false;
            }
            summary.put("tokens",complete?tokens:null);summary.put("confirmedTokenLowerBound",tokens);
        }
        summary.put("validationStatus",outcome.validationStatus());summary.put("lastSequence",outcome.lastEventSequence());summary.put("sourceRevision",outcome.revision());
        summary.put("source","DURABLE_CODING_JOURNAL");summary.put("lastError",outcome.reasonCode());
        var latest=jdbc.queryForList("select event_json from runtime_coding_event_view where view_id=? order by sequence desc limit 1",id);
        summary.put("currentAction",latest.isEmpty()?outcome.summary():decode(latest.get(0).get("event_json")).get("summary"));
        int changed=jdbc.update("update runtime_coding_task_view set task_name=?,status=?,updated_at=?,revision=?,summary_json=? where id=? and revision<=?",
            name,outcome.status().name(),outcome.updatedAt().toEpochMilli(),outcome.revision(),encode(summary),id,outcome.revision());
        if(changed==0&&jdbc.queryForObject("select count(*) from runtime_coding_task_view where id=?",Integer.class,id)==0)
            try{jdbc.update("insert into runtime_coding_task_view(id,tenant_id,user_id,run_id,task_id,task_name,status,created_at,updated_at,revision,summary_json) values(?,?,?,?,?,?,?,?,?,?,?)",
                id,scope.tenantId(),scope.userId(),scope.runId(),contract.taskId(),name,outcome.status().name(),outcome.createdAt().toEpochMilli(),outcome.updatedAt().toEpochMilli(),outcome.revision(),encode(summary));}
            catch(org.springframework.dao.DuplicateKeyException concurrent){/* A newer journal projection won. */}
    }
    public Map<String,Object> list(String tenant,String user,String query,String status,long from,long to,int page,int size){
        size=Math.max(1,Math.min(size,100));page=Math.max(0,page);
        String where=" where tenant_id=? and user_id=? and lower(task_name) like ? and (?='' or status=?) and created_at>=? and created_at<=?";
        Object[] args={tenant,user,"%"+Objects.toString(query,"").toLowerCase(Locale.ROOT)+"%",Objects.toString(status,""),Objects.toString(status,""),from,to};
        var parameters=new ArrayList<>(Arrays.asList(args));parameters.add(size);parameters.add(Math.multiplyExact(page,size));
        var items=jdbc.queryForList("select summary_json from runtime_coding_task_view"+where+" order by created_at desc,id limit ? offset ?",parameters.toArray()).stream().map(r->decode(r.get("summary_json"))).toList();
        return Map.of("items",items,"total",jdbc.queryForObject("select count(*) from runtime_coding_task_view"+where,Long.class,args),"page",page,"size",size,"projectionLagging",lagging);
    }
    public List<Map<String,Object>> events(String tenant,String user,String run,String task,long after,int limit){
        return jdbc.queryForList("select event_json from runtime_coding_event_view where view_id=? and sequence>? order by sequence limit ?",id(tenant,user,run,task),after,Math.max(1,Math.min(limit,256))).stream().map(row->decode(row.get("event_json"))).toList();
    }
    public static Map<String,Object> event(CodingExecutionEvent event){
        var payload=event.payload();var result=new LinkedHashMap<String,Object>();
        result.put("eventId",event.eventId());result.put("sequence",event.sequence());result.put("occurredAt",event.occurredAt());result.put("taskId",event.taskId());result.put("runId",event.scope().runId());
        result.put("nativeEventType",payload.getOrDefault("nativeEventType",event.type().name()));
        result.put("nativeEventId",payload.get("nativeEventId"));result.put("sessionId",payload.get("sessionId"));result.put("toolCallId",payload.get("toolCallId"));
        result.put("type",payload.getOrDefault("normalizedType",switch(event.type()){
            case TASK_STARTED->"TASK_STARTED";case EXECUTION_STARTED->event.operationId()!=null&&event.operationId().startsWith("model-")?"OBSERVATION":"COMMAND_STARTED";case EXECUTION_FINISHED->event.operationId()!=null&&event.operationId().startsWith("model-")?"OBSERVATION":"COMMAND_COMPLETED";
            case CODE_COMMITTED->"FILE_CHANGED";case VALIDATION_RECORDED->"DELIVERY_VALIDATED";
            case TASK_FINISHED->event.status()==CodingTaskOutcome.Status.COMPLETED?"TASK_COMPLETED":event.status()==CodingTaskOutcome.Status.CANCELLED?"TASK_CANCELLED":"TASK_FAILED";
            default->"OBSERVATION";}));
        result.put("status",event.status());result.put("summary",Objects.toString(payload.getOrDefault("summary",event.summary()),""));result.put("evidenceRefs",event.evidenceRefs());
        for(String key:List.of("detailAssetId","nativeEvidenceRef","requestId","model","providerModel","usage","durationMs","exitCode","command","path","fileSha256","errorSummary","repairEvidence","source"))
            if(payload.containsKey(key)){Object value=payload.get(key);result.put(key,value instanceof String text&&text.length()>4096?text.substring(0,4096):value);}
        return Collections.unmodifiableMap(result);
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid monitor projection",e);}}
    private Map<String,Object> decode(Object value){try{return json.readValue(value.toString(),new com.fasterxml.jackson.core.type.TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("Invalid monitor projection",e);}}
    private static String id(String tenant,String user,String run,String task){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((tenant+"\0"+user+"\0"+run+"\0"+task).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
