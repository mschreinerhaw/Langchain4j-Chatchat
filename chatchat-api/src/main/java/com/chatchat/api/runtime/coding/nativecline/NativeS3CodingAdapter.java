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
    private NativeTaskDispatch dispatch;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.chatchat.api.runtime.coding.workspace.CodingWorkspaceService> studioWorkspace;
    public boolean temporalScheduling(){return dispatch!=null;}
    @org.springframework.beans.factory.annotation.Autowired(required=false) public void setDispatch(NativeTaskDispatch value){dispatch=value;}
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
    private final Set<String> stopWakeups=ConcurrentHashMap.newKeySet();
    private final Set<String> stopsActive=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor stopWorkers=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(64),r->{var t=new Thread(r,"native-stop-reconciliation");t.setDaemon(true);return t;});
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->{var t=new Thread(r,"native-s3-control");t.setDaemon(true);return t;});
    @jakarta.annotation.PreDestroy public void close(){shuttingDown=true;workers.shutdownNow();stopWorkers.shutdownNow();}
    public NativeS3CodingAdapter(DurableCodingTaskService tasks,NativeSiloTaskService inputs,SiloCodingWorkspaceService workspace,
            NativeS3Settings settings,NativeS3Evidence evidence,CodingInvocationContext identity,ObjectMapper json,JdbcTemplate jdbc,
            McpLicenseEntitlementPort license,PlatformTransactionManager manager){
        this.tasks=tasks;this.inputs=inputs;this.workspace=workspace;this.settings=settings;this.evidence=evidence;this.identity=identity;this.json=json;this.jdbc=jdbc;this.license=license;
    }
    private static long remaining(long limit,long used){return limit<0?-1:Math.max(0,limit-used);}
    private static String key(KernelDataScope s,String task){return s.tenantId()+"\0"+s.userId()+"\0"+s.runId()+"\0"+task;}
    private <T>T worker(KernelDataScope s,Supplier<T> action){return identity.as(s,Set.of(READ,WORKER,EVIDENCE,STOP,SUBMIT,RECOVER),action);}
    @Override public CodingTaskOutcome submit(CodingTaskContract c){
        inputs.requireAuthorized(c);settings.profile((String)inputs.requireAuthorized(c).get("selectedModel"));
        if(!license.codingDispatchEntitlement().licenseValid())throw new SecurityException("Coding dispatch entitlement required");
        try{var existing=tasks.get(c.scope(),c.taskId());if(existing.status().terminal())return existing;}
        catch(CodingContractException absent){if(absent.error().code()!=CodingErrorCode.NOT_FOUND)throw absent;}
        if((dispatch!=null)!=c.taskId().startsWith("temporal-native-"))throw new IllegalStateException("Task belongs to its original scheduler; drain or explicitly migrate it, never fall back to another execution loop");
        if(dispatch!=null){dispatch.start(c);return tasks.submit(c);}
        settings.client.requireNativeIdentity();
        var accepted=tasks.submit(c);if(!accepted.status().terminal())schedule(c);return accepted;
    }
    private void schedule(CodingTaskContract c){if((dispatch!=null)!=c.taskId().startsWith("temporal-native-"))throw new IllegalStateException("Original task scheduler must reconcile this request");if(dispatch!=null){dispatch.start(c);return;}String key=key(c.scope(),c.taskId());if(active.putIfAbsent(key,true)!=null)return;
        try{workers.execute(()->{stopWakeups.remove(key);try{worker(c.scope(),()->{execute(c);return null;});}finally{
            active.remove(key);
            // A cancellation arriving while the controller exits must not lose
            // its wakeup. The next pass reads the durable stop fence before any
            // dispatch; it never starts another Agent loop.
            if(stopWakeups.remove(key)&&!shuttingDown)schedule(c);
        }});}catch(RejectedExecutionException busy){active.remove(key);}
    }
    /** Projection is a discovery index only. Every candidate is re-authorized
     * against the authoritative contract and journal before ownership is taken. */
    @Scheduled(initialDelay=15000,fixedDelay=15000) public void recover(){
        recoverStops();
        if(dispatch!=null)return;
        try{for(var row:jdbc.queryForList("select tenant_id,user_id,run_id,task_id from runtime_coding_task_view where status in ('CREATED','RUNNING') and task_id like 'native-%' order by updated_at limit 64")){
            var scope=new KernelDataScope(row.get("tenant_id").toString(),row.get("user_id").toString(),null,null,row.get("run_id").toString(),null,Map.of());
            try{worker(scope,()->{var c=tasks.contract(scope,row.get("task_id").toString());if(NativeSiloTaskService.supports(c)&&!tasks.get(scope,c.taskId()).status().terminal()){inputs.requireAuthorized(c);schedule(c);}return null;});}catch(RuntimeException unavailable){log.debug("Native recovery candidate unavailable: {}",row.get("task_id"));}
        }}catch(RuntimeException unavailable){/* Existing SQL/source recovery will retry; no execution is invented. */}
    }
    private void recoverStops(){
        try{for(var row:jdbc.queryForList("select tenant_id,user_id,run_id,task_id from runtime_coding_task_view "
                +"where status in ('CREATED','RUNNING') and task_id like 'temporal-native-%' "
                +"and summary_json like '%\"stopPending\":true%' order by updated_at limit 64")){
            var scope=new KernelDataScope(row.get("tenant_id").toString(),row.get("user_id").toString(),null,null,row.get("run_id").toString(),null,Map.of());
            String task=row.get("task_id").toString();
            try{worker(scope,()->{var result=tasks.get(scope,task);
                if(!result.status().terminal()&&result.executionControl().stopReason()!=null)queueStop(scope,task);
                return null;});}
            catch(RuntimeException unavailable){log.debug("Native stop candidate unavailable: {}",task);}
        }}catch(RuntimeException unavailable){log.debug("Native stop discovery unavailable",unavailable);}
    }
    private void queueStop(KernelDataScope scope,String task){
        String id=key(scope,task);
        if(!stopsActive.add(id))return;
        try{stopWorkers.execute(()->{
            try{stopManaged(scope,task,"Durable Native stop reconciliation");}
            catch(RuntimeException failure){log.warn("Native stop still unresolved runId={} taskId={} errorType={}",
                scope.runId(),task,failure.getClass().getSimpleName());}
            finally{stopsActive.remove(id);}
        });}catch(RejectedExecutionException busy){stopsActive.remove(id);}
    }
    @Override public CodingTaskOutcome get(KernelDataScope scope,String task){return tasks.get(scope,task);}
    @Override public CodingTaskOutcome resume(KernelDataScope scope,String task){
        identity.require(scope,RECOVER);var c=tasks.contract(scope,task);inputs.requireAuthorized(c);
        var current=tasks.get(scope,task);
        if(current.status().terminal())throw new IllegalStateException("A terminal task is immutable; re-execute with a new request ID");
        if(current.executionControl().stopReason()!=null&&task.startsWith("temporal-native-"))queueStop(scope,task);
        else schedule(c);
        return tasks.get(scope,task);
    }
    @Override public Subscription subscribe(KernelDataScope s,String t,long after,Observer observer){return tasks.subscribe(s,t,after,observer);}
    @Override public CodingTaskOutcome cancel(KernelDataScope s,String task,String reason){
        identity.require(s,STOP);var c=tasks.contract(s,task);var prior=tasks.get(s,task);if(prior.status().terminal())return prior;
        var state=workspace.get(s,task);
        CodingTaskOutcome requested;
        try{requested=evidence.atomic(c,()->{workspace.cancel(s,task,state.epoch());return tasks.cancel(s,task,reason);});}
        catch(IllegalStateException sealed){if(tasks.get(s,task).status().terminal())return tasks.get(s,task);throw sealed;}
        if(dispatch!=null)try{dispatch.cancel(s,task);}catch(RuntimeException unavailable){log.warn("Temporal cancellation delivery pending for {}",task);}
        if(c.taskId().startsWith("temporal-native-")){queueStop(s,task);return requested;}
        stopWakeups.add(key(s,task));schedule(c);return requested;
    }
    private static final Duration LEASE_DURATION=Duration.ofMinutes(3);
    private static final Duration LEASE_RENEW_INTERVAL=Duration.ofSeconds(30);
    private static final class Run {
        final CodingTaskContract c;
        final CodingJournal.Lease lease;
        final NativeExecutionControl control;
        Instant renewAt;

        Run(CodingTaskContract c,CodingJournal.Lease lease,NativeExecutionControl control){
            this.c=c;
            this.lease=lease;
            this.control=control;
            this.renewAt=lease.leaseUntil().minus(LEASE_DURATION).plus(LEASE_RENEW_INTERVAL);
        }
    }

    private void maintainLease(Run r){
        if(Instant.now().isBefore(r.renewAt))return;
        // Renewals preserve owner and fencing token. The journal checks the
        // current expiry on every write, including after a takeover.
        var renewed=tasks.renew(r.c.scope(),r.c.taskId(),r.lease,LEASE_DURATION,outcome(r).revision());
        r.renewAt=renewed.leaseUntil().minus(LEASE_DURATION).plus(LEASE_RENEW_INTERVAL);
        if(r.control!=null){
            var binding=state(r,BINDING);
            if(binding!=null)r.control.heartbeat("RUNNING",binding.path("nodeId").asText("default"),
                binding.path("sessionId").asText(),binding.path("executionEpoch").asText());
        }
    }
    private CodingTaskOutcome outcome(Run r){return tasks.get(r.c.scope(),r.c.taskId());}
    private JsonNode state(Run r,String key){assertCurrent(r);var value=tasks.privateState(r.c.scope(),r.c.taskId(),key);return value==null||value.isNull()?null:value;}
    private void assertCurrent(Run r){var lease=tasks.ownership(r.c.scope(),r.c.taskId());if(lease.fencingToken()!=r.lease.fencingToken()||!Objects.equals(lease.owner(),r.lease.owner())||!lease.leaseUntil().isAfter(Instant.now()))throw new IllegalStateException("Stale Native execution fence");}
    private <T>T guarded(Run r,Supplier<T> work){return evidence.atomic(r.c,()->{assertCurrent(r);return work.get();});}
    private NativeS3NodeClient client(Run r){var b=state(r,BINDING);return settings.client(b==null?settings.nodeId():b.path("nodeId").asText("default"));}
    public CodingTaskOutcome runManaged(KernelDataScope scope,String task,NativeExecutionControl control){return worker(scope,()->{settings.client(settings.nodeId()).requireNativeIdentity();var c=inputs.prepared(scope,task);tasks.submit(c);execute(c,control);var result=tasks.get(scope,task);if(!result.status().terminal())throw new IllegalStateException("Native execution remains unresolved");return result;});}
    public CodingTaskOutcome stopManaged(KernelDataScope scope,String task,String reason){return worker(scope,()->{
        var current=tasks.get(scope,task);if(current.status().terminal())return current;
        boolean workflowFailure=reason.startsWith("Temporal execution failed");
        if(!workflowFailure&&current.executionControl().stopReason()==null){
            cancel(scope,task,reason);current=tasks.get(scope,task);if(current.status().terminal())return current;
        }
        var c=tasks.contract(scope,task);var lease=tasks.ownership(scope,task);
        // Once the business stop fence is durable, cleanup may share the
        // current lease to stop processes; it can never dispatch another task.
        var now=Instant.now();
        if(!lease.leaseUntil().isAfter(now))lease=tasks.claim(scope,task,owner,Duration.ofMinutes(3),current.revision());
        else if(lease.leaseUntil().isBefore(now.plusSeconds(60)))
            lease=tasks.renew(scope,task,lease,Duration.ofMinutes(3),current.revision());
        if(workflowFailure&&current.executionControl().stopReason()==null){var held=lease;guarded(new Run(c,held,null),()->{tasks.requestFailureStop(scope,task,held,tasks.get(scope,task).revision(),reason);var ws=workspace.get(scope,task);workspace.failActive(scope,task,ws.epoch(),reason);return null;});}
        stop(new Run(c,lease,new NativeExecutionControl(){public String owner(){return NativeS3CodingAdapter.this.owner;}public int attempt(){return 1;}public String workflowId(){return "stop";}public void heartbeat(String p,String n,String s,String e){}}),false,reason);
        var result=tasks.get(scope,task);if(!result.status().terminal())throw new IllegalStateException("Native stop is not reconciled");return result;
    });}

    private void save(Run r,String key,Object value){var old=tasks.privateState(r.c.scope(),r.c.taskId(),key);tasks.privateState(r.c.scope(),r.c.taskId(),r.lease,key,old,json.valueToTree(value));}
    private void execute(CodingTaskContract c){execute(c,null);}
    private void execute(CodingTaskContract c,NativeExecutionControl control){
        Run run=null;
        try{
            var existing=tasks.get(c.scope(),c.taskId());if(existing.status().terminal())return;
            var held=tasks.ownership(c.scope(),c.taskId());
            String controller=control==null?owner:control.owner();
            while(control!=null&&!controller.equals(held.owner())&&held.leaseUntil().isAfter(Instant.now())){control.heartbeat("RECOVERING",settings.nodeId(),null,null);Thread.sleep(500);held=tasks.ownership(c.scope(),c.taskId());}
            existing=tasks.get(c.scope(),c.taskId());if(existing.status().terminal())return;
            var lease=controller.equals(held.owner())&&held.leaseUntil().isAfter(Instant.now())?held:tasks.claim(c.scope(),c.taskId(),controller,LEASE_DURATION,existing.revision());
            run=new Run(c,lease,control);
            tasks.enforceDeadline(c.scope(),c.taskId(),run.lease,outcome(run).revision());
            if(outcome(run).executionControl().stopReason()!=null){stop(run,false,"Cancellation requested or deadline reached");return;}
            if(!license.codingDispatchEntitlement().licenseValid())throw new SecurityException("Coding dispatch entitlement required");
            var binding=state(run,BINDING);
            if(binding==null){binding=binding(run);save(run,BINDING,binding);}
            else if(control!=null&&control.attempt()>1){recoverBinding(run);binding=state(run,BINDING);}
            String grant=binding.path("grant").asText(),session=binding.path("sessionId").asText();
            var inputFiles=json.convertValue(binding.path("inputs"),new com.fasterxml.jackson.core.type.TypeReference<List<NativeS3NodeClient.Input>>(){});
            // Stable grant/body/key make allocation retry safe. An unknown model
            // message is never blindly replayed after a Java process restart.
            try{client(run).status(grant,session);}catch(NativeS3NodeClient.NodeException absent){if(absent.status()!=404||state(run,"native-s3:message-intent")!=null)throw absent;client(run).create(grant,inputFiles,"create-"+session);}
            for(;;){
                if(control!=null){var b=state(run,BINDING);control.heartbeat("RUNNING",b.path("nodeId").asText("default"),b.path("sessionId").asText(),b.path("executionEpoch").asText());}
                var current=outcome(run);if(current.status().terminal())return;
                tasks.enforceDeadline(c.scope(),c.taskId(),run.lease,current.revision());
                if(outcome(run).executionControl().stopReason()!=null){stop(run,false,"Task stopped by Runtime");return;}
                maintainLease(run);
                poll(run,"core");poll(run,"provider");
                var remote=client(run).status(grant,session);
                if(remote.path("native").hasNonNull("sessionId")&&remote.path("native").hasNonNull("epoch")){
                    var currentBinding=state(run,BINDING);var nativeState=remote.path("native");
                    if(currentBinding.hasNonNull("receiptSessionId")&&(!currentBinding.path("receiptSessionId").equals(nativeState.path("sessionId"))||!currentBinding.path("receiptEpoch").equals(nativeState.path("epoch"))))throw new IllegalStateException("Native receipt Session changed without execution recovery");
                    if(!currentBinding.hasNonNull("receiptSessionId")){var bound=(ObjectNode)currentBinding.deepCopy();bound.set("receiptSessionId",nativeState.get("sessionId"));bound.set("receiptEpoch",nativeState.get("epoch"));save(run,BINDING,bound);}
                }
                String nativeStatus=remote.path("native").path("status").asText();
                if(Set.of("START_FAILED","RECOVERABLE").contains(remote.path("workerState").asText()))throw new IllegalStateException("Native Worker requires recovery; no replacement model loop was started");
                if("STOPPED".equals(remote.path("workerState").asText())&&!remote.hasNonNull("delivery"))
                    throw new IllegalStateException("Native Worker stopped without a committed delivery");
                if(!dispatchObjective(run,remote,grant,session)
                        || !dispatchRepair(run,remote,grant,session)){
                    Thread.sleep(500);
                    continue;
                }
                if("turn_completed".equals(nativeStatus)||remote.hasNonNull("delivery")){
                    poll(run,"core");poll(run,"provider");
                    if(deliver(run,remote))return;
                }
                Thread.sleep(500);
            }
        }catch(CodingContractException lost){if(control!=null)throw lost;if(shuttingDown||Thread.currentThread().isInterrupted())return;log.warn("Native task {} control rejected: {}",c.taskId(),lost.getMessage());if(run!=null)try{if(!outcome(run).status().terminal())stop(run,true,"Native control fence or contract rejected");}catch(RuntimeException unknown){recordControlFailure(run,unknown);}}
        catch(InterruptedException closing){Thread.currentThread().interrupt();if(control!=null)throw new IllegalStateException("Temporal controller interrupted",closing);/* Java restart leaves a durable binding, never a second Agent. */}
        catch(Exception failed){if(control!=null&&!(failed instanceof DeliveryRejected))throw failed instanceof RuntimeException re?re:new IllegalStateException(failed);if(shuttingDown||Thread.currentThread().isInterrupted())return;log.warn("Native task {} failed",c.taskId(),failed);if(run!=null)try{stop(run,true,failed.getMessage()==null?failed.getClass().getSimpleName():failed.getMessage());}catch(RuntimeException unknown){recordControlFailure(run,unknown);}}
    }
    private boolean dispatchObjective(Run r,JsonNode remote,String grant,String session){
        if(state(r,"native-s3:message-ack")!=null||remote.hasNonNull("delivery"))return true;
        var intent=state(r,"native-s3:message-intent");
        boolean ready="READY".equals(remote.path("workerState").asText());
        if(intent==null){
            if(!ready)return false;
            intent=json.valueToTree(Map.of("messageId","objective-"+session,
                "text",instruction(r),"persistedAt",Instant.now().toString()));
            save(r,"native-s3:message-intent",intent);
        }else if(!remote.path("native").path("messageReceipts").asBoolean()){
            // Previous releases omit the capability. Continue observing actual work,
            // but never replay an uncertain legacy dispatch on an idle worker.
            if(ready)throw new IllegalStateException("NATIVE_MESSAGE_RECEIPT_UNAVAILABLE");
            return true;
        }
        var receipt=sendWithReceipt(r,remote,grant,session,intent.path("messageId").asText(),
            intent.hasNonNull("text")?intent.path("text").asText():instruction(r),ready,"native-s3:objective-send-attempts");
        if(receipt==null)return false;
        save(r,"native-s3:message-ack",receipt);
        log.info("Native objective acknowledged runId={} taskId={} sessionId={} messageId={}",
            r.c.scope().runId(),r.c.taskId(),session,intent.path("messageId").asText());
        return true;
    }
    private JsonNode sendWithReceipt(Run r,JsonNode remote,String grant,String session,String key,String text,
                                     boolean ready,String attemptsKey){
        var node=client(r);
        if(remote.path("native").path("messageReceipts").asBoolean()){
            var observed=node.messageReceipt(grant,session,key);
            if(!key.equals(observed.path("messageId").asText()))throw new IllegalStateException("Native message receipt binding differs");
            if(observed.hasNonNull("receipt"))return observed.get("receipt");
        }
        if(!ready)return null;
        var attempts=state(r,attemptsKey);
        int count=attempts==null?0:attempts.path("count").asInt();
        if(count>=3)throw new IllegalStateException("NATIVE_MESSAGE_DISPATCH_EXHAUSTED");
        save(r,attemptsKey,Map.of("count",count+1));
        assertCurrent(r);
        try{return node.message(grant,session,text,key);}
        catch(NativeS3NodeClient.TransportException unknown){return null;}
        catch(NativeS3NodeClient.NodeException rejected){if(rejected.status()>=500)return null;throw rejected;}
    }
    private boolean dispatchRepair(Run r,JsonNode remote,String grant,String session){
        var pending=state(r,"native-s3:repair-intent");
        if(pending==null)return true;
        String version=pending.path("version").asText();
        var current=state(r,"native-s3:delivery-version");
        if(current!=null&&!version.equals(current.path("version").asText())){
            save(r,"native-s3:repair-intent",null);
            return true;
        }
        var attempts=state(r,"native-s3:repair-send:"+version);
        if(attempts!=null&&!remote.path("native").path("messageReceipts").asBoolean())
            throw new IllegalStateException("NATIVE_REPAIR_RECEIPT_UNAVAILABLE");
        var receipt=sendWithReceipt(r,remote,grant,session,"delivery-rejection-"+version,pending.path("text").asText(),
            "turn_completed".equals(remote.path("native").path("status").asText()),"native-s3:repair-send:"+version);
        if(receipt==null)return false;
        int next=Integer.parseInt(version.substring(1))+1;
        var acknowledged=(ObjectNode)pending.deepCopy();
        acknowledged.put("version","v"+next);
        acknowledged.set("ack",receipt);
        acknowledged.remove("text");
        save(r,"native-s3:delivery-version",acknowledged);
        save(r,"native-s3:repair-intent",null);
        return false; // The remote status predates repair dispatch; refresh it.
    }
    private JsonNode binding(Run r){
        var c=r.c;var frozen=inputs.requireAuthorized(c);var ws=workspace.get(c.scope(),c.taskId());var policy=workspace.transferPolicy(c.scope(),c.taskId(),ws.epoch());
        var files=inputs.inputCapabilities(c).stream().map(i->new NativeS3NodeClient.Input(i.input().path(),i.input().size(),i.input().sha256(),i.input().objectVersion(),i.url())).toList();
        String session="native-"+UUID.randomUUID();var grant=new LinkedHashMap<String,Object>();
        grant.put("version",2);grant.put("tenantId",c.scope().tenantId());grant.put("userId",c.scope().userId());grant.put("taskId",c.taskId());grant.put("sessionId",session);grant.put("workspaceRef","project");
        grant.put("modelProfile",settings.profile(frozen.get("selectedModel").toString()));if(settings.dynamicModels)grant.put("runtimeModel",settings.descriptor(frozen.get("selectedModel").toString()));grant.put("budgetPolicy",Map.of("status","DEFERRED_STAGE_2_REVIEW"));
        if(NativeSiloTaskService.studio(c)){
            var used=outcome(r).usage().consumed();
            grant.put("studioBudget",Map.of("modelCalls",remaining(c.budget().maxModelCalls(),used.modelCalls()),
                "modelTokens",remaining(c.budget().maxModelTokens(),used.modelTokens())));
        }
        grant.put("toolPermissions",c.authorization().allowedTools());grant.put("dataPermissions",List.of());grant.put("networkPolicy","broker-only");grant.put("expiresAt",c.budget().deadlineAt().toEpochMilli());grant.put("runtimeProfile","native-python-silo-v1");
        if(!c.budget().hasDeadline())grant.put("userManagedDeadline",true);
        grant.put("deliveryBinding",Map.of("tenantId",c.scope().tenantId(),"taskId",c.taskId(),"runId",c.scope().runId(),"inputSnapshotVersion",ws.snapshotVersion()));
        grant.put("runtimeTools",c.authorization().allowedTools().stream().filter(t->Set.of("agent_skill_read","workspace_metadata","workspace_validate").contains(t)||com.chatchat.api.runtime.coding.knowledge.CodingInternetAccess.NATIVE_TOOLS.contains(t)||com.chatchat.api.runtime.coding.knowledge.CodingDocumentSearchAccess.NATIVE_TOOLS.contains(t)).toList());
        grant.put("s3",Map.of("origin",policy.get("origin"),"inputPrefix",policy.get("inputPrefix"),"outputPrefix",policy.get("outputPrefix"),"inputManifestSha256",client(r).inputManifestHash(files)));
        return json.valueToTree(Map.of("sessionId",session,"grant",client(r).signGrant(grant,settings.issuerKey),"inputs",files,"transportEpoch",ws.epoch(),"nodeId",settings.nodeId(),"executionEpoch",UUID.randomUUID().toString(),"workflowId",r.control==null?"":r.control.workflowId(),"fencingToken",r.lease.fencingToken()));
    }
    private void recoverBinding(Run r){
        var prior=state(r,BINDING);boolean restart;
        try{var remote=client(r).status(prior.path("grant").asText(),prior.path("sessionId").asText());restart=Set.of("RECOVERABLE","START_FAILED","STOPPED").contains(remote.path("workerState").asText())&&!remote.hasNonNull("delivery");}
        catch(NativeS3NodeClient.NodeException error){if(error.status()!=404&&error.status()<500)throw error;restart=true;}
        catch(IllegalStateException unavailable){if(unavailable.getCause()==null)throw unavailable;restart=true;}
        if(!restart)return; // Observe the original live Session.
        if(state(r,"native-s3:message-intent")!=null)
            throw new IllegalStateException("NATIVE_DISPATCH_REQUIRES_STOP_RECONCILIATION");
        r.control.heartbeat("RECOVERING",prior.path("nodeId").asText("default"),prior.path("sessionId").asText(),prior.path("executionEpoch").asText());
        var stopped=settings.recovery(prior.path("nodeId").asText("default")).recoveryStop(prior.path("grant").asText(),prior.path("sessionId").asText());
        var proof=stopped.path("stopProof");
        if(!prior.path("sessionId").equals(stopped.path("sessionId"))||!workerStopped(stopped)||!proof.path("allocationFenced").asBoolean())throw new IllegalStateException("Old Native allocation has not been fenced and stopped");
        if(proof.hasNonNull("containerId"))reconcile(r,stopReference(r,stopped));
        else if(!proof.path("neverAllocated").asBoolean()||tasks.hasStartedOperations(r.c.scope(),r.c.taskId()))throw new IllegalStateException("Unallocated stop cannot reconcile actual starts");
        guarded(r,()->{
            save(r,"native-s3:retired:"+prior.path("sessionId").asText(),prior);
            workspace.restartStoppedExecution(r.c.scope(),r.c.taskId(),prior.path("transportEpoch").asText());
            for(String channel:List.of("core","provider")){
                save(r,"native-s3:read:"+channel,Map.of("sequence",0));
                save(r,"native-s3:cursor:"+channel,Map.of("sequence",0,"sha256",""));
            }
            for(String key:List.of("native-s3:message-intent","native-s3:message-ack","native-s3:delivery-version"))save(r,key,null);
            save(r,BINDING,binding(r));return null;
        });
    }
    private String instruction(Run r){
        if(NativeSiloTaskService.studio(r.c))return r.c.objective().instruction()
            +"\nKeep input/ read-only. Create delivery_manifest.json with schemaVersion coding_node_delivery_selection.v1 "
            +"and artifacts [{path:\"output/studio-proposal.json\",type:\"RESULT\"}]. Runtime validates proposed files before review. "
            +"Finish using the native completion action.";
        var frozen=inputs.requireAuthorized(r.c);var ws=workspace.get(r.c.scope(),r.c.taskId());
        return r.c.objective().instruction()+"\n\nRuntime delivery contract (not analysis instructions): Read the authorized original files at these real workspace paths: "+ws.inputs().stream().map(SiloCodingWorkspaceService.Input::path).toList()
            +". Runtime Internet admission: "+r.c.runtimeContext().getOrDefault("internetAvailability",Map.of("status","NOT_ADMITTED"))+"; authorized optional Runtime MCP tools: "+r.c.authorization().allowedTools().stream().filter(t->t.equals("agent_skill_read")||com.chatchat.api.runtime.coding.knowledge.CodingInternetAccess.NATIVE_TOOLS.contains(t)||com.chatchat.api.runtime.coding.knowledge.CodingDocumentSearchAccess.NATIVE_TOOLS.contains(t)).map(t->"runtime__"+t).toList()+". An unavailable capability is an evidence gap, not an empty public dataset; direct worker networking is not granted. input/ is read-only. Choose meaningful source names and any project structure. Use native tools and local Python when computation is needed; no remote Sandbox. Inspect real execution errors and repair your own code. Do not invent data or evidence. "
            +NativeVisualReport.instruction(r.c.runtimeContext())
            +(!com.chatchat.common.runtime.coding.VisualReportMode.active(r.c.runtimeContext())
                &&frozen.get("artifactTypes") instanceof List<?> requested&&requested.contains("REPORT")
                ?"\nVisual report mode is off. Deliver plain Markdown text and tables supported by the data. Do not generate HTML, figures, or links to local artifacts.\n":"")
            +"Deliver the requested artifact types: "+frozen.get("artifactTypes")+". Create delivery_manifest.json with schemaVersion coding_node_delivery_selection.v1 and artifacts [{path,type}]. Each path is relative to /workspace and identifies an existing file you choose. Allowed types: SOURCE, REPORT, RESULT, CHART, TEST, AUXILIARY. Do not include input/, logs/, evidence/, .git/ or the selection manifest itself. "
            +(frozen.get("resultSchema") instanceof Map<?,?> schema&&!schema.isEmpty()?"The declared RESULT JSON must match this structural schema (an object or an array of matching objects): "+new String(evidence.bytes(schema),StandardCharsets.UTF_8)+". ":"")
            +"Run and inspect your project and actual deliverables before ending the turn. Runtime, not the Agent, verifies hashes, object versions and acceptance.";
    }
    private long cursor(Run r,String channel){var n=state(r,"native-s3:read:"+channel);return n==null?0:n.path("sequence").asLong();}
    private void poll(Run r,String channel){
        maintainLease(r);
        var b=state(r,BINDING);long after=cursor(r,channel);for(int page=0;page<16;page++){
            maintainLease(r);
            var response=channel.equals("core")?client(r).observations(b.path("grant").asText(),b.path("sessionId").asText(),after,128):client(r).receipts(b.path("grant").asText(),b.path("sessionId").asText(),after,128);
            var events=response.path("events");if(events.isEmpty())return;
            var raw=evidence.archive(r.c,channel+"-events","native-"+channel+"-events.json",evidence.bytes(response));
            for(var event:events){
                String type=event.path("eventType").asText();
                if(Set.of("native.event","model.text.delta","model.usage").contains(type))continue;
                maintainLease(r);
                observe(r,channel,event,raw.id());
            }
            maintainLease(r);
            after=response.path("nextEventId").asLong();if(outcome(r).executionControl().stopReason()==null)save(r,"native-s3:read:"+channel,Map.of("sequence",after));if(!response.path("hasMore").asBoolean())return;
        }
    }
    private void observe(Run r,String channel,JsonNode event,String rawAsset){
        String type=event.path("eventType").asText();rawAsset=evidence.archive(r.c,channel+"-event-"+event.path("eventId").asText(),"native-"+channel+"-receipt.json",evidence.bytes(event)).id();JsonNode p=channel.equals("provider")?event:event.path("payload");
        String operation=null,group=null,telemetryId=null;CodingJournal.OperationKind kind=null;Counters actual=null;boolean complete=false,metered=false;
        var projected=new LinkedHashMap<String,Object>();projected.put("nativeEventType",type);projected.put("sessionId",state(r,BINDING).path("sessionId").asText());projected.put("detailAssetId",rawAsset);var execution=state(r,BINDING);for(String field:List.of("nodeId","executionEpoch","workflowId","fencingToken"))if(execution.has(field))projected.put(field,json.convertValue(execution.get(field),Object.class));
        String normalized="OBSERVATION",summary=type;
        if(channel.equals("provider")&&type.startsWith("model.request.")){
            operation="model-"+p.path("requestId").asText();kind=CodingJournal.OperationKind.MODEL;complete=type.equals("model.request.completed");long tokens=p.path("usage").path("totalTokens").asLong();
            actual=new Counters(1,tokens,0,0,0,0,0,"0");metered=complete&&p.path("usage").path("totalTokens").isNumber();
            normalized=complete?"MODEL_REQUEST_COMPLETED":"MODEL_REQUEST_STARTED";summary=p.path("providerModel").asText(p.path("model").asText());group="model";telemetryId=p.path("requestId").asText();
            for(String field:List.of("requestId","model","providerModel","usage","durationMs","requestContext","requestedReasoningEffort","reasoningEffort","httpStatus","errorCode","errorType"))if(p.hasNonNull(field))projected.put(field,json.convertValue(p.get(field),Object.class));
        }else if(channel.equals("provider")){
            summary=type+": "+p.path("tool").asText(p.path("name").asText(p.path("url").asText()));
            for(String field:List.of("tool","requestId","isError","skillId","name","version","source","bytes","url","sha256","size","path","assetId","retrieved_at","files"))if(p.hasNonNull(field))projected.put(field,json.convertValue(p.get(field),Object.class));
        }else if(type.startsWith("tool.")){
            String tool=p.path("toolName").asText();operation="tool-"+evidence.hash(p.path("toolCallId")).substring(0,32);kind=CodingJournal.OperationKind.TOOL;
            complete=!type.equals("tool.started");actual=new Counters(0,0,1,0,0,0,0,"0");projected.put("toolCallId",p.path("toolCallId").asText());
            // These metadata callbacks reserve their calls in the same Runtime journal before dispatch.
            if(NativeSiloTaskService.studio(r.c)&&tool.matches("(?:runtime__|tenant_data__)?workspace_(?:metadata|validate)"))
                operation=null;
            normalized=type.equals("tool.failed")?"ERROR_OCCURRED":type.equals("tool.started")?"TOOL_STARTED":Set.of("editor","apply_patch").contains(tool)?"FILE_CHANGED":"TOOL_COMPLETED";summary=tool;
        }else if(type.startsWith("command.")&&Set.of("command.started","command.completed").contains(type)){
            String command=p.path("command").path("executable").asText()+" "+p.path("command").path("args").toString();
            if(!command.matches("(?is).*(?:python|pytest).*"))return;
            operation="cmd-"+p.path("recordId").asText();kind=CodingJournal.OperationKind.CODE;complete=type.equals("command.completed");actual=new Counters(0,0,0,1,0,0,p.path("stdout").path("size").asLong()+p.path("stderr").path("size").asLong(),"0");
            normalized=complete&&p.path("exitCode").asInt(-1)!=0?"ERROR_OCCURRED":complete?"COMMAND_COMPLETED":"COMMAND_STARTED";summary=command;projected.put("command",command);if(p.hasNonNull("exitCode"))projected.put("exitCode",p.path("exitCode").asInt());
            group="execution";telemetryId=p.path("recordId").asText();
        }
        var proof=new LinkedHashMap<String,Object>();proof.put("taskId",r.c.taskId());proof.put("sessionId",state(r,BINDING).path("sessionId").asText());proof.put("operationId",operation==null?"observation":operation);proof.put("stopped",complete);proof.put("source",channel.equals("provider")?(type.startsWith("model.request.")?"PROVIDER_HTTP_OBSERVATION":"RUNTIME_CAPABILITY_OBSERVATION"):"NATIVE_ROOT_HOST_OBSERVATION");proof.put("nativeEventId",event.path("eventId").asText());proof.put("rawAssetId",rawAsset);proof.put("sha256",evidence.hash(event));
        String ref=evidence.register(r.c,NativeS3ExecutionProofVerifier.TYPE,state(r,BINDING).path("sessionId").asText()+":"+channel+":"+event.path("eventId").asText(),proof,List.of(r.c.inputSnapshotRef()));
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
        maintainLease(r);
        tasks.recordNativeObservation(r.c.scope(),r.c.taskId(),r.lease,outcome(r).revision(),new DurableCodingTaskService.NativeObservation(channel,event.path("eventId").asLong(),event.path("eventId").asText(),evidence.hash(event),ref,normalized,summary.substring(0,Math.min(2048,summary.length())),projected,usage,group,telemetryId,telemetry));
        if("execution".equals(group)&&complete&&outcome(r).executionControl().stopReason()==null)linkRepair(r,telemetry);
    }
    /** Derive diagnostics from the actual captured process streams. A shell
     * wrapper's zero exit code is retained, including when it masks Python. */
    private void enrichExecution(Run r,ObjectNode record){
        var b=state(r,BINDING);String id=record.path("recordId").asText();
        for(String stream:List.of("stdout","stderr")){
            var meta=record.path(stream);if(!meta.has("sha256"))continue;
            maintainLease(r);
            byte[] bytes=client(r).executionContent(b.path("grant").asText(),b.path("sessionId").asText(),id,stream,null,meta.path("size").asLong(),meta.path("sha256").asText());
            var asset=evidence.archive(r.c,"execution-"+id+"-"+stream,"command-"+id+"-"+stream+".txt",bytes);record.put(stream+"AssetId",asset.id());
            String output=new String(bytes,StandardCharsets.UTF_8);
            if(output.contains("Traceback (most recent call last):")){
                var match=java.util.regex.Pattern.compile("(?m)^([\\w.]+(?:Error|Exception)):[^\\r\\n]*").matcher(output);
                while(match.find())record.put("exceptionSummary",match.group().substring(0,Math.min(500,match.group().length())));
            }
        }
        record.put("pythonExceptionWithZeroShellExit",record.hasNonNull("exceptionSummary")&&record.path("exitCode").asInt(-1)==0);
        for(var source:record.path("sourceSnapshots")){
            maintainLease(r);
            byte[] bytes=client(r).executionContent(b.path("grant").asText(),b.path("sessionId").asText(),id,"source",source.path("path").asText(),source.path("size").asLong(),source.path("sha256").asText());
            var asset=evidence.archive(r.c,"source-"+id+"-"+source.path("sha256").asText(),source.path("path").asText().replace('/','_'),bytes);((ObjectNode)source).put("assetId",asset.id());
        }
    }
    private void linkRepair(Run r,JsonNode success){
        if(success.path("exitCode").asInt(-1)!=0||success.hasNonNull("exceptionSummary")
                ||success.path("sourceSnapshots").isEmpty())return;
        long end=success.path("nativeEventId").asLong();
        long edit=tasks.latestNativeEditBefore(r.c.scope(),r.c.taskId(),end);
        if(edit==0)return;
        for(var failed:tasks.nativeTelemetry(r.c.scope(),r.c.taskId(),"execution")){
            maintainLease(r);
            if(!failed.hasNonNull("exceptionSummary")&&failed.path("exitCode").asInt(0)==0)continue;
            long start=failed.path("nativeEventId").asLong();if(start>=edit||!failed.path("command").equals(success.path("command")))continue;
            var changed=new ArrayList<Map<String,Object>>();
            for(var before:failed.path("sourceSnapshots"))for(var after:success.path("sourceSnapshots"))if(before.path("path").equals(after.path("path"))&&!before.path("sha256").equals(after.path("sha256")))changed.add(Map.of("path",before.path("path").asText(),"beforeSha256",before.path("sha256").asText(),"afterSha256",after.path("sha256").asText(),"beforeAssetId",before.path("assetId").asText(),"afterAssetId",after.path("assetId").asText()));
            if(changed.isEmpty())continue;
            var repair=new LinkedHashMap<String,Object>();repair.put("verified",true);repair.put("failureEventId",failed.path("nativeEventId").asText());repair.put("editEventId",Long.toString(edit));repair.put("executionEventId",success.path("nativeEventId").asText());repair.put("exceptionSummary",failed.path("exceptionSummary").asText());repair.put("changedSources",changed);repair.put("failureStdoutAssetId",failed.path("stdoutAssetId").asText());repair.put("failureStderrAssetId",failed.path("stderrAssetId").asText());repair.put("rerunStdoutAssetId",success.path("stdoutAssetId").asText());repair.put("verification","CHANGED_SOURCE_SNAPSHOT_AND_SAME_COMMAND_RERUN_WITHOUT_OBSERVED_EXCEPTION");
            save(r,"native-s3:repair:"+failed.path("recordId").asText(),repair);
        }
    }
    private boolean deliver(Run r,JsonNode remote){
        var b=state(r,BINDING);String grant=b.path("grant").asText(),session=b.path("sessionId").asText();var ws=workspace.get(r.c.scope(),r.c.taskId());
        String version=state(r,"native-s3:delivery-version")==null?"v1":state(r,"native-s3:delivery-version").path("version").asText();
        JsonNode manifest;
        if(remote.hasNonNull("delivery"))manifest=remote.get("delivery");else{
            JsonNode preview;
            try{preview=client(r).preview(grant,session,version);}catch(NativeS3NodeClient.NodeException invalid){if(invalid.validationFeedback()==null)throw invalid;feedback(r,version,invalid.validationFeedback(),invalid.candidateSha256());return false;}
            String rejection=validateCandidate(r,preview);
            if(rejection!=null){
                var inventory=java.util.stream.StreamSupport.stream(preview.path("artifacts").spliterator(),false)
                    .map(a->List.of(a.path("path").asText(),a.path("type").asText(),a.path("sha256").asText()))
                    .sorted(Comparator.comparing(a->String.join("\u0000",a))).toList();
                feedback(r,version,rejection,evidence.hash(inventory));return false;
            }
            manifest=client(r).seal(grant,session,version);
        }
        // Drain genuine receipts after seal; no synthetic final tool events.
        poll(r,"core");poll(r,"provider");remote=client(r).status(grant,session);
        if(!remote.path("stopProof").path("running").isBoolean()||remote.path("stopProof").path("running").asBoolean(true))throw new IllegalStateException("Native Executor stop not confirmed");
        String stopRef=stopReference(r,remote);reconcile(r,stopRef);
        ws=workspace.get(r.c.scope(),r.c.taskId());JsonNode upload=remote.path("uploadReceipt");
        if("RUNNING".equals(ws.status())){
            var delivery=json.convertValue(manifest,SiloCodingWorkspaceService.Delivery.class);if(!b.path("receiptSessionId").asText().equals(delivery.sessionId())||!b.path("receiptEpoch").asText().equals(delivery.epoch()))throw new SecurityException("Delivery from a superseded Native receipt Session");var allocation=ws;var plans=guarded(r,()->workspace.plan(r.c.scope(),r.c.taskId(),allocation.epoch(),delivery));
            var plan=new LinkedHashMap<String,Object>();plan.put("manifestSha256",evidence.hash(manifest));plan.put("artifacts",plans);plan.putAll(workspace.metadataUploadPlan(r.c.scope(),r.c.taskId(),ws.epoch()));
            upload=client(r).upload(grant,session,json.valueToTree(plan));
            var uploaded=upload;guarded(r,()->workspace.commit(r.c.scope(),r.c.taskId(),allocation.epoch(),json.convertValue(uploaded.path("artifacts"),new com.fasterxml.jackson.core.type.TypeReference<List<SiloCodingWorkspaceService.Uploaded>>(){})));
        }
        ws=workspace.get(r.c.scope(),r.c.taskId());if("RECEIVED_PENDING_BUSINESS_VALIDATION".equals(ws.status())){var verifying=ws;ws=guarded(r,()->workspace.verifyDeliveryIntegrity(r.c.scope(),r.c.taskId(),verifying.epoch()));}
        var meta=upload.path("evidence");byte[] journal=workspace.transferEvidence(r.c.scope(),r.c.taskId(),ws.epoch(),"evidence",meta.path("objectVersion").asText(),meta.path("size").asLong());
        if(!UserAssetService.hash(journal).equals(meta.path("sha256").asText()))throw new IllegalStateException("Native Silo journal hash differs");
        archiveExecutions(r);
        var finalRemote=remote;var stateBeforeCommit=ws;
        guarded(r,()->{
            if(!"COMPLETED".equals(stateBeforeCommit.status()))workspace.finalizeSealed(r.c.scope(),r.c.taskId(),stateBeforeCommit.epoch(),stateBeforeCommit.deliveryHash(),journal,
                ignored->new SiloCodingWorkspaceService.StopObservation(finalRemote.path("stopProof").path("containerId").asText(),true,"NATIVE_EXECUTOR_OBSERVATION"));
            commit(r,stopRef);if(outcome(r).status()!=Status.COMPLETED)throw new IllegalStateException("Runtime stop/deadline prevents delivery completion");return null;
        });
        try{client(r).accept(grant,session,evidence.hash(manifest),true,outcome(r).validationRecordRef());}catch(RuntimeException ackUnknown){evidence.archive(r.c,"node-acceptance-ack","node-acceptance-ack.json",evidence.bytes(Map.of("acknowledgement","UNKNOWN","runtimeAcceptanceCommitted",true)));}return true;
    }
    private static boolean matches(Map<String,Object> schema,Map<String,Object> row){try{com.chatchat.agents.orchestration.analysis.graph.WorkspaceResultSchema.validate(row,schema);return true;}catch(IllegalArgumentException mismatch){return false;}}
    private String validateCandidate(Run r,JsonNode preview){
        if(NativeSiloTaskService.studio(r.c))return validateStudio(r,preview);
        var frozen=inputs.requireAuthorized(r.c);var types=new HashSet<String>();preview.path("artifacts").forEach(a->types.add(a.path("type").asText()));
        if(!types.containsAll((List<?>)frozen.get("artifactTypes")))return "Missing declared artifact types: "+frozen.get("artifactTypes");
        if(com.chatchat.common.runtime.coding.VisualReportMode.active(r.c.runtimeContext())
                && !NativeVisualReport.hasSupportedDelivery(preview.path("artifacts")))
            return "Deliver one HTML report or a Markdown report with the supported findings and data limitations.";
        var schema=(Map<String,Object>)frozen.getOrDefault("resultSchema",Map.of());if(schema.isEmpty())return null;
        var b=state(r,BINDING);for(var a:preview.path("artifacts"))if(a.path("type").asText().equals("RESULT"))try{
            var data=json.readTree(client(r).previewContent(b.path("grant").asText(),b.path("sessionId").asText(),preview,a.path("path").asText()));
            if(data==null||data.isNull())continue;
            var rows=json.convertValue(data.isArray()?data:json.createArrayNode().add(data),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});

            if(!rows.isEmpty()&&rows.stream().allMatch(row->matches(schema,row)))return null;
        }catch(java.io.IOException|IllegalArgumentException invalid){/* Try another declared RESULT. Transport and integrity failures must propagate. */}
        return "No declared RESULT JSON matches the frozen output schema: "+new String(evidence.bytes(schema),StandardCharsets.UTF_8);
    }
    private static final String STUDIO_PROPOSAL="output/studio-proposal.json";
    private String validateStudio(Run r,JsonNode preview){
        var artifacts=preview.path("artifacts");
        var item=artifacts.path(0);
        if(artifacts.size()!=1||!STUDIO_PROPOSAL.equals(item.path("path").asText())||!"RESULT".equals(item.path("type").asText())
                ||item.path("size").asLong(-1)<1||item.path("size").asLong()>r.c.outputContract().maxResultBytes())
            return "Deliver only the bounded Studio proposal JSON as RESULT at "+STUDIO_PROPOSAL;
        var binding=state(r,BINDING);
        byte[] bytes=client(r).previewContent(binding.path("grant").asText(),binding.path("sessionId").asText(),preview,STUDIO_PROPOSAL);
        String hash=UserAssetService.hash(bytes);
        if(!hash.equals(item.path("sha256").asText()))throw new SecurityException("Native Studio preview hash differs");
        String key="native-s3:studio-review:"+hash;
        if(evidence.exists(r.c,evidence.reference(r.c,"CODING_WORKSPACE_FILE_INDEX",key)))return null;
        try {
            var proposal=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .forType(new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}).<Map<String,Object>>readValue(bytes);
            var review=studioWorkspace.getObject().reviewNativeResult(r.c,proposal);
            var validation=(Map<?,?>)review.get("validation");
            if(!Boolean.TRUE.equals(validation.get("valid"))){
                String details=Objects.toString(validation.get("diagnostics"),"Studio validation rejected the proposal");
                return details.substring(0,Math.min(4000,details.length()));
            }
            evidence.register(r.c,"CODING_WORKSPACE_FILE_INDEX",key,
                json.convertValue(review,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}),List.of(r.c.inputSnapshotRef()));
            return null;
        } catch(com.chatchat.api.datascience.sql.SqlGlotToolchain.UnconfirmedStopException unknown){throw unknown;}
        catch(java.io.IOException|IllegalArgumentException|org.springframework.web.server.ResponseStatusException rejected){
            return "Invalid Studio proposal: "+Objects.toString(rejected.getMessage(),"review failed").substring(0,
                Math.min(2000,Objects.toString(rejected.getMessage(),"review failed").length()));
        }
    }

    private static final class DeliveryRejected extends IllegalStateException {
        private DeliveryRejected(){super("OUTPUT_CONTRACT_VIOLATION: Native delivery repair limit reached");}
    }
    private void feedback(Run r,String version,String rejection,String candidateHash){
        String fingerprint=evidence.hash(Map.of("reason",rejection,"candidate",Objects.toString(candidateHash,"")));
        var previous=state(r,"native-s3:delivery-version");
        int repeats=previous!=null&&fingerprint.equals(previous.path("fingerprint").asText())
            ?previous.path("repeats").asInt()+1:1;
        var receipt=evidence.archive(r.c,"delivery-rejection-"+version,"delivery-rejection.json",
            evidence.bytes(Map.of("deliveryVersion",version,"reason",rejection,"fingerprint",fingerprint,
                "source","JAVA_DELIVERY_CONTRACT_VALIDATION")));
        int round=Integer.parseInt(version.substring(1));
        long studioRepairs=NativeSiloTaskService.studio(r.c)
            ? ((Number)((Map<?,?>)((Map<?,?>)inputs.requireAuthorized(r.c).get("studio")).get("sqlRepairPolicy")).get("maxRepairAttempts")).longValue():-1;
        if(repeats>=3||round>=5||studioRepairs>=0&&round>studioRepairs)
            throw new DeliveryRejected();
        save(r,"native-s3:repair-intent",Map.of("version",version,"fingerprint",fingerprint,"repeats",repeats,
            "receiptAssetId",receipt.id(),"text","Actual Runtime delivery contract rejection: "+rejection
                +". Correct the deliverable and its selection manifest in this same session, then finish again."));
    }
    private static boolean workerStopped(JsonNode remote){
        var running=remote.path("stopProof").path("running");
        return running.isBoolean()&&!running.booleanValue();
    }
    private String stopReference(Run r,JsonNode remote){
        var proof=remote.path("stopProof");if(!proof.path("containerId").asText().matches("[a-f0-9]{64}")||!workerStopped(remote))throw new IllegalStateException("Actual scoped Executor stop proof required");
        String session=state(r,BINDING).path("sessionId").asText();
        // The independent recovery proof additionally fences allocation. Keep it
        // under its own key because prior physical-stop evidence is immutable.
        String key=(proof.path("allocationFenced").asBoolean()?"recovery-stop-":"stop-")+session;
        return evidence.register(r.c,NativeS3ExecutionProofVerifier.STOP_TYPE,key,Map.of("taskId",r.c.taskId(),"sessionId",session,"workerStopped",true,"containerId",proof.path("containerId").asText(),"stopProof",json.convertValue(proof,Map.class)),List.of(r.c.inputSnapshotRef()));
    }
    private void reconcile(Run r,String proof){for(String operation:tasks.unresolvedOperations(r.c.scope(),r.c.taskId()))tasks.reconcileStopped(r.c.scope(),r.c.taskId(),r.lease,outcome(r).revision(),operation,proof);}
    private void archiveExecutions(Run r){
        var b=state(r,BINDING);var list=client(r).executions(b.path("grant").asText(),b.path("sessionId").asText());
        for(var record:list.path("executions")){
            if(!record.path("command").toString().matches("(?is).*(?:python|pytest).*"))continue;
            String id=record.path("recordId").asText();if(!record.has("stdout"))continue;
            for(String stream:List.of("stdout","stderr")){
                var meta=record.path(stream);byte[] bytes=client(r).executionContent(b.path("grant").asText(),b.path("sessionId").asText(),id,stream,null,meta.path("size").asLong(),meta.path("sha256").asText());
                evidence.archive(r.c,"execution-"+id+"-"+stream,"command-"+id+"-"+stream+".txt",bytes);
            }
            for(var source:record.path("sourceSnapshots")){
                maintainLease(r);
            byte[] bytes=client(r).executionContent(b.path("grant").asText(),b.path("sessionId").asText(),id,"source",source.path("path").asText(),source.path("size").asLong(),source.path("sha256").asText());
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
        if(NativeSiloTaskService.studio(r.c)){
            commitStudio(r,ws,assetRows,stopRef);
            return;
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
    private void commitStudio(Run r,SiloCodingWorkspaceService.State ws,List<Map<String,Object>> assets,String stopRef){
        if(assets.size()!=1||!STUDIO_PROPOSAL.equals(assets.get(0).get("path"))||!"RESULT".equals(assets.get(0).get("type")))
            throw new SecurityException("Native Studio result differs from the reviewed proposal");
        var file=assets.get(0);
        String result=evidence.reference(r.c,"CODING_WORKSPACE_FILE_INDEX","native-s3:studio-review:"+file.get("sha256"));
        if(!evidence.exists(r.c,result))
            throw new SecurityException("No Studio review matches the sealed proposal");
        String code=evidence.register(r.c,"CODING_NATIVE_SOURCE_INVENTORY","studio-code",Map.of("taskId",r.c.taskId(),"artifacts",assets),List.of(result));
        String validation=evidence.register(r.c,"CODING_WORKSPACE_VALIDATION","studio-validation",
            Map.of("taskId",r.c.taskId(),"status","INCONCLUSIVE","structuralChecksPassed",true,"codeExecuted",false,
                "deliveryHash",ws.deliveryHash(),"reviewRef",result),List.of(result,stopRef));
        String bundle=evidence.bundle(r.c,List.of(r.c.inputSnapshotRef(),code,stopRef,result,validation));
        var prior=outcome(r);var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        tasks.commitOutcome(r.c.scope(),r.c.taskId(),r.lease,prior.revision(),new CodingTaskOutcome(
            prior.schemaVersion(),prior.taskId(),prior.scope(),prior.contractSha256(),Status.COMPLETED,
            prior.revision()+1,prior.lastEventSequence()+1,prior.createdAt(),at,prior.startedAt(),at,false,null,
            "Studio source proposals parsed and staged for review",List.of("No code or database statements executed"),
            prior.inputSnapshotRef(),code,stopRef,result,validation,bundle,ValidationStatus.INCONCLUSIVE,
            prior.reproducibility(),prior.usage(),new ExecutionControl(null,null,true,false),
            prior.parentTaskId(),prior.supersedesTaskId(),Map.of()));
    }
    private void stop(Run r,boolean failed,String reason){
        var ownership=tasks.ownership(r.c.scope(),r.c.taskId());
        if(ownership.fencingToken()!=r.lease.fencingToken()||!Objects.equals(ownership.owner(),r.lease.owner())||!ownership.leaseUntil().isAfter(Instant.now()))throw new IllegalStateException("Stale Java controller cannot cancel a Native session");
        var b=state(r,BINDING);if(b==null){var prior=outcome(r);if(prior.executionControl().stopReason()!=null)tasks.finishStoppedWithoutExecution(r.c.scope(),r.c.taskId(),prior.revision());return;}
        var ws=workspace.get(r.c.scope(),r.c.taskId());if(!Set.of("COMPLETED","CANCELLED","FAILED").contains(ws.status()))workspace.cancel(r.c.scope(),r.c.taskId(),ws.epoch());
        String nodeId=b.path("nodeId").asText("default");
        JsonNode remote;
        try{
            if(!settings.hasLiveNode(nodeId))remote=recoveryStop(r,b,nodeId);
            else {
                remote=client(r).status(b.path("grant").asText(),b.path("sessionId").asText());
                if(!workerStopped(remote)){
                    client(r).cancel(b.path("grant").asText(),b.path("sessionId").asText());
                    remote=client(r).status(b.path("grant").asText(),b.path("sessionId").asText());
                }
            }
        }
        catch(NativeS3NodeClient.NodeException absent){
            // Host persists the Session before allocating a Worker; objective dispatch is
            // preceded by our durable message intent. A missing Session alone is NOT proof.
            if(absent.status()!=404)throw absent;
            if(state(r,"native-s3:message-intent")==null&&!tasks.hasStartedOperations(r.c.scope(),r.c.taskId())
                    &&outcome(r).executionControl().stopReason()!=null){
                tasks.finishStoppedWithoutExecution(r.c.scope(),r.c.taskId(),outcome(r).revision());return;
            }
            remote=recoveryStop(r,b,nodeId);
        }
        catch(NativeS3NodeClient.TransportException unavailable){
            if(!settings.hasLiveNode(nodeId)||Thread.currentThread().isInterrupted())throw unavailable;
            remote=recoveryStop(r,b,nodeId);
        }
        if(remote.path("stopProof").path("neverAllocated").asBoolean()
                &&state(r,"native-s3:message-intent")==null&&!tasks.hasStartedOperations(r.c.scope(),r.c.taskId())){
            tasks.finishStoppedWithoutExecution(r.c.scope(),r.c.taskId(),outcome(r).revision());return;
        }
        long stopDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while(!workerStopped(remote)&&System.nanoTime()<stopDeadline){
            try{Thread.sleep(200);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Stop reconciliation interrupted",interrupted);}
            long remaining=stopDeadline-System.nanoTime();
            if(remaining<=0)break;
            try{remote=client(r).stopStatus(b.path("grant").asText(),b.path("sessionId").asText(),Duration.ofNanos(remaining));}
            catch(NativeS3NodeClient.TransportException unknown){
                if(System.nanoTime()>=stopDeadline)break;
                throw unknown;
            }
        }
        if(!workerStopped(remote))throw new IllegalStateException("STOP_RECONCILIATION_TIMEOUT");
        String proof=stopReference(r,remote);reconcile(r,proof);
        var stopped=outcome(r);
        if(stopped.status().terminal())return;
        // Finalize a durable stop while the verified proof and lease are current.
        // Optional historical telemetry can take longer than the control lease.
        if(stopped.executionControl().stopReason()!=null){
            tasks.finishStoppedAfterReconciliation(r.c.scope(),r.c.taskId(),r.lease,stopped.revision());return;
        }
        try{poll(r,"core");poll(r,"provider");}catch(RuntimeException unavailable){
            try{evidence.archive(r.c,"stop-telemetry-unavailable","stop-telemetry-unavailable.json",
                evidence.bytes(Map.of("workerStopVerified",true,"telemetryAvailable",false)));}
            catch(RuntimeException archiveUnavailable){/* The verified stop proof is already durable. */}
        }
        var prior=outcome(r);if(prior.status().terminal())return;
        if(prior.executionControl().stopReason()!=null){tasks.finishStoppedAfterReconciliation(r.c.scope(),r.c.taskId(),r.lease,prior.revision());return;}
        String failureSummary=reason;
        if("Native Worker stopped without a committed delivery".equals(reason)){
            failureSummary=tasks.nativeTelemetry(r.c.scope(),r.c.taskId(),"model").stream()
                .filter(receipt->receipt.hasNonNull("finishedAt"))
                .max(Comparator.comparing(receipt->receipt.path("finishedAt").asText()))
                .filter(receipt->"FAILED".equals(receipt.path("status").asText()))
                .map(NativeS3CodingAdapter::modelFailureSummary).orElse(reason);
        }
        var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);var proposal=new CodingTaskOutcome(prior.schemaVersion(),prior.taskId(),prior.scope(),prior.contractSha256(),Status.FAILED,prior.revision()+1,prior.lastEventSequence()+1,prior.createdAt(),at,prior.startedAt(),at,false,
            reason.startsWith("OUTPUT_CONTRACT_VIOLATION")?CodingErrorCode.OUTPUT_CONTRACT_VIOLATION:CodingErrorCode.EXECUTION_FAILED,
            failureSummary.substring(0,Math.min(2048,failureSummary.length())),List.of("Native execution failed; original receipts and partial artifacts are retained"),prior.inputSnapshotRef(),null,proof,null,null,null,ValidationStatus.NOT_RUN,prior.reproducibility(),prior.usage(),new ExecutionControl(null,null,true,false),null,null,Map.of());
        tasks.commitOutcome(r.c.scope(),r.c.taskId(),r.lease,prior.revision(),proposal);
    }
    private static String modelFailureSummary(JsonNode receipt) {
        return switch(receipt.path("errorCode").asText("")){
            case "UPSTREAM_TIMEOUT" -> "模型上游连接或响应超时，分析未交付";
            case "UPSTREAM_DNS_FAILURE" -> "模型上游地址解析失败，分析未交付";
            case "UPSTREAM_TLS_FAILURE" -> "模型上游 TLS 握手失败，分析未交付";
            default -> {
                int status=receipt.path("httpStatus").asInt(0);
                yield status>=400 ? "模型上游返回 HTTP "+status+"，分析未交付"
                    : status==0 ? "模型请求未收到上游 HTTP 响应，分析未交付"
                    : "模型上游响应未完整接收，分析未交付";
            }
        };
    }
    private JsonNode recoveryStop(Run r,JsonNode binding,String nodeId){
        assertCurrent(r);
        var remote=settings.recovery(nodeId).recoveryStop(binding.path("grant").asText(),binding.path("sessionId").asText());
        if(!binding.path("sessionId").equals(remote.path("sessionId"))
                ||!remote.path("stopProof").path("allocationFenced").asBoolean()||!workerStopped(remote))
            throw new IllegalStateException("NATIVE_RECOVERY_STOP_UNVERIFIED");
        return remote;
    }
    private void recordControlFailure(Run r,Exception failure){
        try{var data=Map.of("message","Native transport/stop reconciliation unavailable","exceptionType",failure.getClass().getSimpleName(),
            "reasonCode","STOP_RECONCILIATION_TIMEOUT".equals(failure.getMessage())?"STOP_RECONCILIATION_TIMEOUT":"NATIVE_CONTROL_UNAVAILABLE");var file=evidence.archive(r.c,"control-error","native-control-error.json",evidence.bytes(data));
            tasks.recordWorkerProgress(r.c.scope(),r.c.taskId(),r.lease,outcome(r).revision(),System.currentTimeMillis(),"SESSION_TIMING",null,evidence.hash(data),Map.of("normalizedType","ERROR_OCCURRED","summary","Native transport outcome unknown; stop must be reconciled","detailAssetId",file.id(),"source","JAVA_NATIVE_CONTROL"));
        }catch(RuntimeException ignored){/* Durable state stays nonterminal when stop is unknown. */}
    }
}
