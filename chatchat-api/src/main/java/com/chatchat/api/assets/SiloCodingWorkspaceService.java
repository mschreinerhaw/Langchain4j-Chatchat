package com.chatchat.api.assets;

import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Opt-in Coding Node transport. SQL checkpoints fence runs; Silo owns all bytes.
 * No model prompts, code editing, repair strategy or business-success inference.
 * Capabilities are returned only to the trusted Node adapter, never public APIs.
 */
@Service
@ConditionalOnProperty(prefix="chatchat.runtime.coding.unified",name="enabled",havingValue="true")
public class SiloCodingWorkspaceService {
    public static final int CHUNK=8*1024*1024;
    public record Input(String assetId,String path,long size,String sha256,String objectVersion) { }
    public record InputCapability(Input input,String url) { }
    public record Artifact(String path,String type,long size,String sha256) { }
    public record Receipt(String eventId,String eventType,String sessionId,String epoch) { }
    public record Delivery(String schemaVersion,String taskId,String runId,String tenantId,String inputSnapshotVersion,
            String deliveryVersion,String sessionId,String epoch,String executionStatus,String acceptanceStatus,
            List<Artifact> artifacts,List<Receipt> nativeReceipts) { }
    public record PartCapability(int index,String url) { }
    public record UploadPlan(String path,List<PartCapability> parts) { }
    public record UploadedPart(int index,long size,String sha256,String objectVersion) { }
    public record Uploaded(String path,List<UploadedPart> parts,long size,String sha256) { }
    public record State(String taskId,String runId,String tenantId,String userId,String epoch,String snapshotVersion,
            String status,List<Input> inputs,String deliveryHash,Delivery delivery,List<String> assetIds) { }
    private final UserAssetService assets;
    private final AssetBlobStore blobs;
    private final AssetStorageProperties limits;
    private final JdbcRuntimeEvidenceStore checkpoints;
    private final ObjectMapper json;
    public SiloCodingWorkspaceService(UserAssetService assets,AssetBlobStore blobs,AssetStorageProperties limits,
            JdbcRuntimeEvidenceStore checkpoints,ObjectMapper json){
        this.assets=assets;this.blobs=blobs;this.limits=limits;this.checkpoints=checkpoints;this.json=json;
    }
    private static String key(String task){if(task==null||!task.matches("[A-Za-z0-9_-]{1,96}"))throw new IllegalArgumentException("Invalid task identity");return "coding-node-workspace:"+task;}
    private static void owner(KernelDataScope scope){UserAssetService.Owner.of(scope);if(scope.runId()==null)throw new SecurityException("Run identity required");}
    private State decode(String value){try{return json.readValue(value,State.class);}catch(Exception e){throw new IllegalStateException("Workspace checkpoint invalid",e);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Workspace cannot be encoded",e);}}
    public State get(KernelDataScope scope,String task){owner(scope);var state=decode(checkpoints.readExecutionCheckpoint(scope,key(task)).orElseThrow());
        if(!Objects.equals(scope.tenantId(),state.tenantId())||!Objects.equals(scope.userId(),state.userId())||!Objects.equals(scope.runId(),state.runId())||!task.equals(state.taskId()))throw new SecurityException("Workspace owner mismatch");return state;}
    private State running(KernelDataScope scope,String task,String epoch){var state=get(scope,task);if(!Objects.equals(epoch,state.epoch())||!"RUNNING".equals(state.status()))throw new IllegalStateException("Task fence is closed or stale: status="+state.status()+", epochMatches="+Objects.equals(epoch,state.epoch()));return state;}
    public State create(KernelDataScope scope,String task,List<String> fileIds){
        owner(scope);key(task);
        if(fileIds==null||fileIds.isEmpty()||fileIds.size()>64||new HashSet<>(fileIds).size()!=fileIds.size())throw new IllegalArgumentException("Explicit unique authorized inputs required");
        var owner=UserAssetService.Owner.of(scope);var inputs=new ArrayList<Input>();
        // Reserve an immutable allocation before object I/O so concurrent retries
        // cannot bind a task to different input/output object spaces.
        for(String file:fileIds)assets.get(owner,file);
        var prior=checkpoints.readExecutionCheckpoint(scope,key(task));
        if(prior.isPresent()){
            var existing=get(scope,task);
            var originalIds=existing.inputs().stream().map(i->assets.get(owner,i.assetId()).sourceRef()).toList();
            var expected=fileIds.stream().map(id->"coding-node-input:"+id+":"+existing.epoch()).toList();
            var legacy=fileIds.stream().map(id->"coding-node-input:"+id).toList();
            if(!originalIds.equals(expected)&&!originalIds.equals(legacy))throw new IllegalStateException("Immutable task input conflict");
            return existing;
        }
        String epochKey=key(task)+":allocation";
        checkpoints.compareAndSetExecutionCheckpoint(scope,epochKey,null,UUID.randomUUID().toString());
        String epoch=checkpoints.readExecutionCheckpoint(scope,epochKey).orElseThrow();
        // Authorization precedes object access. Preserve original physical bytes.
        for(int i=0;i<fileIds.size();i++){
            var original=assets.get(owner,fileIds.get(i));byte[] bytes=assets.read(owner,original.id());
            String digest=UserAssetService.hash(bytes);
            String objectKey=taskPrefix(scope.tenantId(),scope.userId(),task,scope.runId(),epoch)+"/input/"+i+"/"+digest;
            var copy=assets.storeTaskInput(owner,task,scope.runId(),"coding-node-input:"+original.id()+":"+epoch,original.fileName(),original.contentType(),bytes,objectKey);
            inputs.add(new Input(copy.id(),"input/data/"+i+"/"+copy.fileName(),copy.size(),copy.sha256(),assets.taskInputVersion(scope,task,copy.id())));
        }
        String snapshot=UserAssetService.hash(encode(inputs).getBytes(StandardCharsets.UTF_8));
        var state=new State(task,scope.runId(),scope.tenantId(),scope.userId(),epoch,snapshot,"RUNNING",List.copyOf(inputs),null,null,List.of());
        if(!checkpoints.compareAndSetExecutionCheckpoint(scope,key(task),null,encode(state))){var existing=get(scope,task);if(!snapshot.equals(existing.snapshotVersion()))throw new IllegalStateException("Immutable task input conflict");return existing;}
        return state;
    }
    public List<InputCapability> inputCapabilities(KernelDataScope scope,String task,String epoch){
        var state=running(scope,task,epoch);return state.inputs().stream().map(input->new InputCapability(input,assets.taskInputCapability(scope,task,input.assetId(),900))).toList();
    }
    public State cancel(KernelDataScope scope,String task,String epoch){
        get(scope,task);
        return decode(checkpoints.mutateExecutionCheckpoint(scope,key(task),value->{var s=decode(value);if(!Objects.equals(s.epoch(),epoch))throw new IllegalStateException("Stale task cancellation");
            if("CANCELLED".equals(s.status()))return value;
            if(!Set.of("RUNNING","RECEIVED_PENDING_BUSINESS_VALIDATION","BUSINESS_VALIDATED_PENDING_SEAL","DELIVERY_VALIDATED_PENDING_SEAL").contains(s.status()))throw new IllegalStateException("Delivery already committed before cancellation");
            return encode(new State(s.taskId(),s.runId(),s.tenantId(),s.userId(),s.epoch(),s.snapshotVersion(),"CANCELLED",s.inputs(),s.deliveryHash(),s.delivery(),s.assetIds()));}));
    }
    public State failActive(KernelDataScope scope,String task,String epoch,String reason){
        get(scope,task);if(reason==null||reason.isBlank()||reason.length()>1024)throw new IllegalArgumentException("Explicit failure reason required");
        return decode(checkpoints.mutateExecutionCheckpoint(scope,key(task),value->{var s=decode(value);
            if(!epoch.equals(s.epoch())||!Set.of("RUNNING","RECEIVED_PENDING_BUSINESS_VALIDATION","BUSINESS_VALIDATED_PENDING_SEAL").contains(s.status()))throw new IllegalStateException("Failure cannot replace a terminal or stale task");
            var log=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.LOG,task,scope.runId(),"coding-node-failure:"+epoch,"task-failure.json","application/json",encode(Map.of("reason",reason,"taskId",task,"epoch",epoch)).getBytes(StandardCharsets.UTF_8));
            var ids=new ArrayList<>(s.assetIds());ids.add(log.id());return encode(new State(s.taskId(),s.runId(),s.tenantId(),s.userId(),s.epoch(),s.snapshotVersion(),"FAILED",s.inputs(),s.deliveryHash(),s.delivery(),ids));}));
    }
    public UserAssetService.File archiveNativeEvidence(KernelDataScope scope,String task,String epoch,byte[] journal){
        var state=get(scope,task);
        if(!epoch.equals(state.epoch())||state.delivery()==null)throw new IllegalStateException("Native evidence binding unavailable");
        try{
            var document=json.readTree(journal);var events=document.path("events");if(!events.isArray()||events.isEmpty())throw new IllegalArgumentException("Native event journal required");
            var receipts=new HashMap<String,String>();for(var event:events){
                if(!state.delivery().sessionId().equals(event.path("sessionId").asText())||!state.delivery().epoch().equals(event.path("epoch").asText()))throw new SecurityException("Native evidence session mismatch");
                receipts.put(event.path("eventId").asText(),event.path("eventType").asText());
            }
            for(var receipt:state.delivery().nativeReceipts())if(!receipt.eventType().equals(receipts.get(receipt.eventId())))throw new SecurityException("Declared native receipt missing from actual journal");
        }catch(IOException failure){throw new IllegalArgumentException("Invalid native journal",failure);}
        return assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.LOG,task,scope.runId(),"coding-node-native-journal:"+state.deliveryHash(),"native-events.json","application/json",journal);
    }
    static String deliveryPath(String path){
        if(path==null||path.isBlank()||path.length()>768||path.contains("\\")||path.chars().anyMatch(c->c<32)||path.startsWith("/")||path.contains(":"))throw new IllegalArgumentException("Invalid artifact path");
        var parts=path.split("/",-1);for(String part:parts)if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("Invalid artifact path");
        if(Set.of("input","logs","evidence",".git").contains(parts[0])||path.equals("delivery_manifest.json"))throw new IllegalArgumentException("Reserved artifact path");return path;
    }
    private void validate(State state,Delivery delivery){
        if(delivery==null||!"coding_node_delivery.v1".equals(delivery.schemaVersion())||!state.taskId().equals(delivery.taskId())||!state.runId().equals(delivery.runId())
                ||!state.tenantId().equals(delivery.tenantId())||!state.snapshotVersion().equals(delivery.inputSnapshotVersion())
                ||delivery.deliveryVersion()==null||!delivery.deliveryVersion().matches("[A-Za-z0-9_-]{1,96}")||delivery.sessionId()==null||delivery.epoch()==null
                ||!"NATIVE_TURN_COMPLETED".equals(delivery.executionStatus())||!"PENDING_RUNTIME_VALIDATION".equals(delivery.acceptanceStatus()))throw new SecurityException("Delivery binding mismatch");
        if(delivery.nativeReceipts()==null||delivery.nativeReceipts().stream().noneMatch(r->"turn.completed".equals(r.eventType())&&delivery.sessionId().equals(r.sessionId())&&delivery.epoch().equals(r.epoch())))throw new IllegalArgumentException("Native completion receipt required");
        if(delivery.artifacts()==null||delivery.artifacts().isEmpty()||delivery.artifacts().size()>1000)throw new IllegalArgumentException("Invalid artifact inventory");
        var paths=new HashSet<String>();long total=0;
        for(var a:delivery.artifacts()){
            if(!paths.add(deliveryPath(a.path()))||!Set.of("SOURCE","REPORT","CHART","RESULT","TEST","AUXILIARY").contains(a.type())||a.size()<0||a.size()>limits.getMaxFileBytes()||a.sha256()==null||!a.sha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid artifact metadata");
            total=Math.addExact(total,a.size());if(total>limits.getUserQuotaBytes())throw new IllegalArgumentException("Delivery exceeds user quota");
        }
    }
    private static String taskPrefix(String tenant,String user,String task,String run,String epoch){return "tenants/"+UserAssetService.hash(tenant.getBytes(StandardCharsets.UTF_8))+"/users/"+new UserAssetService.Owner(tenant,user).id()+"/tasks/coding-node/"+UserAssetService.hash((task+"\0"+run).getBytes(StandardCharsets.UTF_8))+"/"+epoch;}
    private String prefix(State state){return taskPrefix(state.tenantId(),state.userId(),state.taskId(),state.runId(),state.epoch())+"/output/"+state.deliveryHash();}
    /** Machine-only frozen task roots. No storage URL or credential is public. */
    public Map<String,String> transferPolicy(KernelDataScope scope,String task,String epoch){
        var state=running(scope,task,epoch);var endpoint=java.net.URI.create(limits.getEndpoint());
        String root="/"+limits.getBucket()+"/"+taskPrefix(state.tenantId(),state.userId(),state.taskId(),state.runId(),state.epoch());
        return Map.of("origin",endpoint.getScheme()+"://"+endpoint.getRawAuthority(),"inputPrefix",root+"/input/","outputPrefix",root+"/output/");
    }
    public Map<String,String> metadataUploadPlan(KernelDataScope scope,String task,String epoch){
        var state=running(scope,task,epoch);if(state.delivery()==null)throw new IllegalStateException("Bound sealed delivery required");
        return Map.of("manifestUrl",blobs.presignWrite(prefix(state)+"/delivery_manifest.json",900),"evidenceUrl",blobs.presignWrite(prefix(state)+"/native-events.json",900));
    }
    public byte[] transferEvidence(KernelDataScope scope,String task,String epoch,String kind,String version,long size){
        var state=get(scope,task);if(!epoch.equals(state.epoch())||state.delivery()==null||!Set.of("manifest","evidence").contains(kind))throw new SecurityException("Task metadata binding required");
        return blobs.get(prefix(state)+(kind.equals("manifest")?"/delivery_manifest.json":"/native-events.json"),version,size);
    }
    private String artifactKey(State state,Artifact a){return prefix(state)+"/"+UserAssetService.hash(a.path().getBytes(StandardCharsets.UTF_8));}
    public List<UploadPlan> plan(KernelDataScope scope,String task,String epoch,Delivery delivery){
        var current=running(scope,task,epoch);validate(current,delivery);String hash=UserAssetService.hash(encode(delivery).getBytes(StandardCharsets.UTF_8));
        String versionKey=key(task)+":version:"+UserAssetService.hash(delivery.deliveryVersion().getBytes(StandardCharsets.UTF_8));
        if(!checkpoints.compareAndSetExecutionCheckpoint(scope,versionKey,null,hash)&&!hash.equals(checkpoints.readExecutionCheckpoint(scope,versionKey).orElseThrow()))throw new IllegalStateException("Delivery version is immutable; cannot rebind different content");
        var state=decode(checkpoints.mutateExecutionCheckpoint(scope,key(task),value->{var s=decode(value);
            if(!"RUNNING".equals(s.status())||!epoch.equals(s.epoch()))throw new IllegalStateException("Task fence closed");
            if(s.deliveryHash()!=null&&!hash.equals(s.deliveryHash()))throw new IllegalStateException("Delivery version is immutable");
            return encode(new State(s.taskId(),s.runId(),s.tenantId(),s.userId(),s.epoch(),s.snapshotVersion(),s.status(),s.inputs(),hash,delivery,s.assetIds()));}));
        return delivery.artifacts().stream().map(a->{var parts=new ArrayList<PartCapability>();for(int n=0;n<Math.max(1,(a.size()+CHUNK-1)/CHUNK);n++)parts.add(new PartCapability(n,blobs.presignWrite(artifactKey(state,a)+"/parts/"+n,900)));return new UploadPlan(a.path(),parts);}).toList();
    }
    private record Verified(Artifact artifact,String key,String version) { }
    public record BusinessValidation(String profile,boolean passed,Map<String,Object> facts) { }
    public record VerifiedTask(State state,Map<String,byte[]> inputs,Map<String,byte[]> artifacts) { }
    @FunctionalInterface public interface BusinessVerifier { BusinessValidation verify(VerifiedTask task); }
    public record StopObservation(String containerId,boolean stopped,String proofSource) { }
    @FunctionalInterface public interface StopVerifier { StopObservation observe(State task); }
    /** Verifier is selected by Java, never serialized from a model/Node request.
     * Re-read the immutable Silo versions rather than executing generated source.
     */
    public State verifyBusiness(KernelDataScope scope,String task,String epoch,BusinessVerifier verifier){
        return evaluateBusiness(scope,task,epoch,verifier);
    }
    /** Generic Runtime acceptance: immutable objects, delivery identity and
     * actual Native completion. No business meaning or numeric claim is judged. */
    public State verifyDeliveryIntegrity(KernelDataScope scope,String task,String epoch){
        return decode(checkpoints.mutateExecutionCheckpoint(scope,key(task),value->{var s=decode(value);
            if(!scope.tenantId().equals(s.tenantId())||!scope.userId().equals(s.userId())||!scope.runId().equals(s.runId())||!epoch.equals(s.epoch()))throw new SecurityException("Delivery validation owner/fence mismatch");
            if("DELIVERY_VALIDATED_PENDING_SEAL".equals(s.status()))return value;
            if(!"RECEIVED_PENDING_BUSINESS_VALIDATION".equals(s.status()))throw new IllegalStateException("No verified uploaded delivery");
            validate(s,s.delivery());
            var file=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.LOG,task,scope.runId(),"coding-node-delivery-validation:"+s.deliveryHash(),"delivery-validation.json","application/json",
                encode(Map.of("profile","artifact-integrity-v1","deliveryHash",s.deliveryHash(),"objectIntegrityVerified",true,"businessEvaluated",false)).getBytes(StandardCharsets.UTF_8));
            var ids=new ArrayList<>(s.assetIds());ids.add(file.id());
            return encode(new State(s.taskId(),s.runId(),s.tenantId(),s.userId(),s.epoch(),s.snapshotVersion(),"DELIVERY_VALIDATED_PENDING_SEAL",s.inputs(),s.deliveryHash(),s.delivery(),ids));
        }));
    }
    public State verifyBusinessCandidate(KernelDataScope scope,String task,String epoch,BusinessVerifier verifier){
        return evaluateBusiness(scope,task,epoch,verifier);
    }
    private State evaluateBusiness(KernelDataScope scope,String task,String epoch,BusinessVerifier verifier){
        var state=get(scope,task);
        if(!epoch.equals(state.epoch())||!"RECEIVED_PENDING_BUSINESS_VALIDATION".equals(state.status()))throw new IllegalStateException("No current delivery awaiting business validation");
        var input=new LinkedHashMap<String,byte[]>();for(var f:state.inputs())input.put(f.path(),assets.readTask(scope,task,f.assetId()));
        var output=new LinkedHashMap<String,byte[]>();
        for(int i=0;i<state.delivery().artifacts().size();i++)output.put(state.delivery().artifacts().get(i).path(),assets.readTask(scope,task,state.assetIds().get(i)));
        var validation=Objects.requireNonNull(verifier.verify(new VerifiedTask(state,Collections.unmodifiableMap(input),Collections.unmodifiableMap(output))));
        if(validation.profile()==null||validation.profile().isBlank()||validation.facts()==null)throw new IllegalArgumentException("Explicit business validation evidence required");
        byte[] evidence=encode(validation).getBytes(StandardCharsets.UTF_8);
        return decode(checkpoints.mutateExecutionCheckpoint(scope,key(task),value->{var s=decode(value);
            if(!epoch.equals(s.epoch())||!"RECEIVED_PENDING_BUSINESS_VALIDATION".equals(s.status())||!Objects.equals(state.deliveryHash(),s.deliveryHash()))throw new IllegalStateException("Business validation fence changed");
            var file=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.LOG,task,scope.runId(),"coding-node-business-validation:"+s.deliveryHash(),"business-validation.json","application/json",evidence);
            var ids=new ArrayList<>(s.assetIds());ids.add(file.id());
            return encode(new State(s.taskId(),s.runId(),s.tenantId(),s.userId(),s.epoch(),s.snapshotVersion(),validation.passed()?"BUSINESS_VALIDATED_PENDING_SEAL":"BUSINESS_VALIDATION_FAILED",s.inputs(),s.deliveryHash(),s.delivery(),ids));}));
    }
    /** Continue the SAME live Native session after a real validation receipt.
     * Retain the failed delivery; no new Agent or synthetic tool result is made.
     * Cancelled/completed tasks cannot be reopened by this operation.
     */
    public State continueAfterValidationFailure(KernelDataScope scope,String task,String epoch,String failedDeliveryHash){
        var state=get(scope,task);
        if(!epoch.equals(state.epoch())||!"BUSINESS_VALIDATION_FAILED".equals(state.status())||!Objects.equals(failedDeliveryHash,state.deliveryHash()))throw new IllegalStateException("No current failed delivery eligible for feedback");
        String archived=encode(state),historyKey=key(task)+":failed-delivery:"+state.deliveryHash();
        if(!checkpoints.compareAndSetExecutionCheckpoint(scope,historyKey,null,archived)&&!archived.equals(checkpoints.readExecutionCheckpoint(scope,historyKey).orElseThrow()))throw new IllegalStateException("Failed delivery archive conflict");
        return decode(checkpoints.mutateExecutionCheckpoint(scope,key(task),value->{
            if(!archived.equals(value))throw new IllegalStateException("Task changed before validation feedback");
            return encode(new State(state.taskId(),state.runId(),state.tenantId(),state.userId(),state.epoch(),state.snapshotVersion(),"RUNNING",state.inputs(),null,null,List.of()));}));
    }
    /** A trusted control adapter supplies the actual Executor observation. A
     * model/user JSON body cannot implement StopVerifier or authorize finality.
     */
    public State finalizeSealed(KernelDataScope scope,String task,String epoch,String deliveryHash,byte[] nativeJournal,StopVerifier stopVerifier){
        var state=get(scope,task);
        if(!epoch.equals(state.epoch())||!Set.of("BUSINESS_VALIDATED_PENDING_SEAL","DELIVERY_VALIDATED_PENDING_SEAL").contains(state.status())||!Objects.equals(deliveryHash,state.deliveryHash()))throw new IllegalStateException("No validated delivery awaiting seal");
        archiveNativeEvidence(scope,task,epoch,nativeJournal);
        try{
            var events=json.readTree(nativeJournal).path("events");boolean sealed=false;
            for(var e:events)if("delivery.sealed".equals(e.path("eventType").asText())&&e.path("payload").path("localStopConfirmed").asBoolean(false))sealed=true;
            if(!sealed)throw new IllegalStateException("Native worker seal receipt missing");
        }catch(IOException failure){throw new IllegalArgumentException("Invalid native seal evidence",failure);}
        var proof=Objects.requireNonNull(stopVerifier.observe(state));
        if(!proof.stopped()||proof.containerId()==null||!proof.containerId().matches("[a-f0-9]{64}")||proof.proofSource()==null||proof.proofSource().isBlank())throw new IllegalStateException("Executor stop not confirmed");
        byte[] evidence=encode(proof).getBytes(StandardCharsets.UTF_8);
        return decode(checkpoints.mutateExecutionCheckpoint(scope,key(task),value->{var s=decode(value);
            if(!epoch.equals(s.epoch())||!Set.of("BUSINESS_VALIDATED_PENDING_SEAL","DELIVERY_VALIDATED_PENDING_SEAL").contains(s.status())||!Objects.equals(deliveryHash,s.deliveryHash()))throw new IllegalStateException("Cancelled or superseded delivery cannot finalize");
            var receipt=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.LOG,task,scope.runId(),"coding-node-executor-stop:"+deliveryHash,"executor-stop.json","application/json",evidence);
            var ids=new ArrayList<>(s.assetIds());ids.add(receipt.id());return encode(new State(s.taskId(),s.runId(),s.tenantId(),s.userId(),s.epoch(),s.snapshotVersion(),"COMPLETED",s.inputs(),s.deliveryHash(),s.delivery(),ids));}));
    }
    private void verifyObject(String key,String version,long size,String expected){
        if(blobs.stat(key,version).size()!=size)throw new IllegalStateException("Silo object length mismatch");
        try{var digest=MessageDigest.getInstance("SHA-256");blobs.copy(key,version,size,new DigestOutputStream(OutputStream.nullOutputStream(),digest));
            if(!HexFormat.of().formatHex(digest.digest()).equals(expected))throw new IllegalStateException("Silo object SHA-256 mismatch");}
        catch(IOException|NoSuchAlgorithmException failure){throw new IllegalStateException("Silo verification failed",failure);}
    }
    public State commit(KernelDataScope scope,String task,String epoch,List<Uploaded> uploaded){
        var existing=get(scope,task);if(!Objects.equals(existing.epoch(),epoch))throw new IllegalStateException("Stale delivery run");
        if("RECEIVED_PENDING_BUSINESS_VALIDATION".equals(existing.status()))return existing;
        var state=running(scope,task,epoch);if(state.delivery()==null||uploaded==null||uploaded.size()!=state.delivery().artifacts().size())throw new IllegalArgumentException("Complete upload receipts required");
        var byPath=new HashMap<String,Uploaded>();for(var u:uploaded)if(byPath.put(u.path(),u)!=null)throw new IllegalArgumentException("Duplicate upload receipt");
        var verified=new ArrayList<Verified>();
        for(var a:state.delivery().artifacts()){
            var u=byPath.get(a.path());int count=(int)Math.max(1,(a.size()+CHUNK-1)/CHUNK);
            if(u==null||u.size()!=a.size()||!a.sha256().equals(u.sha256())||u.parts()==null||u.parts().size()!=count)throw new IllegalArgumentException("Upload receipt mismatch");
            var parts=new ArrayList<AssetBlobStore.ObjectPart>();String base=artifactKey(state,a);
            for(int i=0;i<count;i++){var p=u.parts().get(i);long length=Math.min(CHUNK,Math.max(0,a.size()-(long)i*CHUNK));
                if(p.index()!=i||p.size()!=length||p.sha256()==null||!p.sha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid part receipt");
                String partKey=base+"/parts/"+i;verifyObject(partKey,p.objectVersion(),length,p.sha256());parts.add(new AssetBlobStore.ObjectPart(partKey,p.objectVersion()));}
            // Compose uses immutable part versions; single/empty files need no multipart copy.
            String objectKey=count==1?parts.get(0).key():base+"/complete";
            String version=count==1?parts.get(0).versionId():blobs.compose(objectKey,parts).versionId();
            verifyObject(objectKey,version,a.size(),a.sha256());verified.add(new Verified(a,objectKey,version));
        }
        return decode(checkpoints.mutateExecutionCheckpoint(scope,key(task),value->{var s=decode(value);
            if("RECEIVED_PENDING_BUSINESS_VALIDATION".equals(s.status())&&Objects.equals(s.deliveryHash(),state.deliveryHash()))return value;
            if(!"RUNNING".equals(s.status())||!epoch.equals(s.epoch())||!Objects.equals(state.deliveryHash(),s.deliveryHash()))throw new IllegalStateException("Cancelled or superseded delivery cannot register artifacts");
            var ids=new ArrayList<String>();for(var v:verified){var a=v.artifact();String name=a.path().substring(a.path().lastIndexOf('/')+1);
                var kind=Set.of("SOURCE","TEST").contains(a.type())?UserAssetService.Kind.CODE:UserAssetService.Kind.ARTIFACT;
                var file=assets.registerTaskObject(UserAssetService.Owner.of(scope),kind,task,scope.runId(),"coding-node:"+s.delivery().deliveryVersion()+":"+a.path(),name,"application/octet-stream",v.key(),v.version(),a.size(),a.sha256());ids.add(file.id());}
            var manifest=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.LOG,task,scope.runId(),"coding-node-delivery-manifest:"+s.deliveryHash(),"delivery_manifest.json","application/json",encode(s.delivery()).getBytes(StandardCharsets.UTF_8));ids.add(manifest.id());
            return encode(new State(s.taskId(),s.runId(),s.tenantId(),s.userId(),s.epoch(),s.snapshotVersion(),"RECEIVED_PENDING_BUSINESS_VALIDATION",s.inputs(),s.deliveryHash(),s.delivery(),ids));}));
    }
}
