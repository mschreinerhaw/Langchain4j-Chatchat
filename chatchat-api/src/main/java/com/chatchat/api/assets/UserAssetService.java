package com.chatchat.api.assets;

import com.chatchat.common.kernel.KernelDataScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** SQL owns identity, quotas and lifecycle. Object keys and versions never come from a request. */
@Service
@ConditionalOnProperty(prefix="chatchat.assets", name="enabled", havingValue="true")
public class UserAssetService {
    public enum Kind { DOCUMENT, DATASET, CODE, LOG, ARTIFACT, CONTEXT }
    public record Owner(String tenant, String user) {
        public Owner {
            if (tenant == null || tenant.isBlank() || tenant.length()>128 || user == null || user.isBlank() || user.length()>128)
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated asset owner required");
        }
        String id() { return hash((tenant+"\0"+user).getBytes(StandardCharsets.UTF_8)); }
        public static Owner of(KernelDataScope scope) { return new Owner(scope.tenantId(),scope.userId()); }
    }
    public record File(String id, String kind, String fileName, String contentType, String sha256, long size,
                       String state, String taskId, String runId, String sourceRef, long createdAt) { }
    public record Space(long quotaBytes, long usedBytes) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate createTx;
    private final AssetBlobStore blobs;
    private final AssetStorageProperties policy;
    public UserAssetService(JdbcTemplate jdbc, PlatformTransactionManager manager, AssetBlobStore blobs, AssetStorageProperties policy) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.blobs=blobs; this.policy=policy;
        this.createTx=new TransactionTemplate(manager);
        createTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        if(policy.getMaxFileBytes()<1 || policy.getMaxFileBytes()>256L*1024*1024 || policy.getUserQuotaBytes()<policy.getMaxFileBytes())
            throw new IllegalArgumentException("Invalid asset limits");
    }
    private void ensure(Owner owner) {
        if (!jdbc.queryForList("select id from runtime_user_workspace where id=?",owner.id()).isEmpty()) return;
        try { createTx.executeWithoutResult(s -> jdbc.update("insert into runtime_user_workspace(id,tenant_id,user_id,quota_bytes,used_bytes,created_at) values(?,?,?,?,?,?)",
            owner.id(),owner.tenant(),owner.user(),policy.getUserQuotaBytes(),0,System.currentTimeMillis())); }
        catch(DuplicateKeyException concurrent) { /* The concurrent owner's creation won. */ }
    }
    public Space space(Owner owner) {
        ensure(owner);
        return jdbc.queryForObject("select quota_bytes,used_bytes from runtime_user_workspace where id=?",
            (rs,n)->new Space(rs.getLong(1),rs.getLong(2)),owner.id());
    }
    public List<File> list(Owner owner, Kind kind, String task, int limit) {
        ensure(owner);
        return jdbc.query("select * from runtime_workspace_file where workspace_id=? and state<>'DELETED' "
            +"and (?='' or kind=?) and (?='' or task_id=?) order by created_at desc,id limit ?",
            UserAssetService::file, owner.id(),kind==null?"":kind.name(),kind==null?"":kind.name(),
            task==null?"":task,task==null?"":task,Math.max(1,Math.min(limit,100)));
    }
    public File upload(Owner owner, String name, byte[] data) {
        String type=documentType(name,data);
        ensure(owner);
        long deleted=jdbc.queryForObject("select count(*) from runtime_workspace_file where workspace_id=? and kind='DOCUMENT' and task_id='' and file_name=? and sha256=? and state='DELETED'",
            Long.class,owner.id(),name,hash(data));
        return store(owner,Kind.DOCUMENT,"","",deleted==0?"upload":"upload:"+deleted,name,type,data);
    }
    public File store(Owner owner, Kind kind, String task, String run, String source, String name, String type, byte[] data) {
        Objects.requireNonNull(data); Objects.requireNonNull(kind);
        if (data.length>policy.getMaxFileBytes()) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Asset exceeds file limit");
        name=name(name); task=bounded(task,256); run=bounded(run,256); source=bounded(source,1024);
        if(type==null || !type.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) type="application/octet-stream";
        ensure(owner);
        String digest=hash(data);
        String id=hash((owner.id()+"\0"+kind+"\0"+task+"\0"+run+"\0"+source+"\0"+name+"\0"+digest).getBytes(StandardCharsets.UTF_8));
        String key="tenants/"+hash(owner.tenant().getBytes(StandardCharsets.UTF_8))+"/users/"+owner.id()+"/"
            +switch(kind){case DOCUMENT->"documents";case DATASET->"datasets";case CONTEXT->"context";case ARTIFACT->"artifacts";default->"tasks";}
            +"/"+id+"/"+digest;
        final String fileName=name, contentType=type,taskId=task,runId=run,sourceRef=source;
        // A reserved STAGED row survives network failure. Repeating the same binding resumes it without recharging quota.
        tx.executeWithoutResult(s->{
            Space space=lockSpace(owner);
            var rows=rows(owner,id);
            if(!rows.isEmpty()) {
                if("DELETED".equals(rows.get(0).get("state"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Deleted asset identity cannot be reused");
                return;
            }
            if(space.usedBytes()>space.quotaBytes()-data.length) throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,"User asset quota exceeded");
            jdbc.update("insert into runtime_workspace_file(id,workspace_id,kind,file_name,content_type,object_key,sha256,size_bytes,state,task_id,run_id,source_ref,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,owner.id(),kind.name(),fileName,contentType,key,digest,data.length,"STAGED",taskId,runId,sourceRef,System.currentTimeMillis());
            jdbc.update("update runtime_user_workspace set used_bytes=used_bytes+? where id=?",data.length,owner.id());
        });
        return tx.execute(s->{
            lockSpace(owner); var row=requireRow(owner,id);
            if("AVAILABLE".equals(row.get("state"))) return get(owner,id);
            if(!"STAGED".equals(row.get("state"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Asset publication is not active");
            var stored=blobs.put(key,data,contentType,digest);
            // Verify a read of the exact immutable version before making it visible to tasks.
            verify(blobs.get(key,stored.versionId(),data.length),digest,data.length);
            jdbc.update("update runtime_workspace_file set object_version=?,state='AVAILABLE' where id=?",stored.versionId(),id);
            return get(owner,id);
        });
    }
    public File get(Owner owner,String id) {
        var rows=jdbc.query("select * from runtime_workspace_file where workspace_id=? and id=? and state<>'DELETED'",UserAssetService::file,owner.id(),id);
        if(rows.isEmpty()) throw missing(); return rows.get(0);
    }
    public byte[] read(Owner owner,String id) {
        var row=requireRow(owner,id);
        if(!"AVAILABLE".equals(row.get("state"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Asset is not available");
        byte[] data=blobs.get((String)row.get("object_key"),(String)row.get("object_version"),((Number)row.get("size_bytes")).longValue());
        verify(data,(String)row.get("sha256"),((Number)row.get("size_bytes")).longValue());return data;
    }
    /** The worker's trusted contract is the authority; user-wide listing cannot authorize a task input. */
    public byte[] readTask(KernelDataScope scope,String task,String id) {
        Owner owner=Owner.of(scope); File file=get(owner,id);
        if(task==null || task.isBlank() || !task.equals(file.taskId()) || !Objects.equals(scope.runId(),file.runId()))
            throw new SecurityException("Asset is outside the current task manifest");
        return read(owner,id);
    }
    public void copyTask(KernelDataScope scope,String task,String id,java.io.OutputStream output)throws java.io.IOException {
        Owner owner=Owner.of(scope);File file=get(owner,id);
        if(task==null || task.isBlank() || !task.equals(file.taskId()) || !Objects.equals(scope.runId(),file.runId()))throw new SecurityException("Asset is outside the current task manifest");
        var row=requireRow(owner,id);if(!"AVAILABLE".equals(file.state()))throw new IllegalStateException("Task asset is not available");
        try{
            var digest=MessageDigest.getInstance("SHA-256");
            var target=new java.security.DigestOutputStream(output,digest);
            blobs.copy((String)row.get("object_key"),(String)row.get("object_version"),file.size(),target);
            if(!HexFormat.of().formatHex(digest.digest()).equals(file.sha256()))throw new java.io.IOException("Task asset hash differs from registry");
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public void delete(Owner owner,String id) {
        tx.executeWithoutResult(s->{
            lockSpace(owner); var found=rows(owner,id); if(found.isEmpty())throw missing();
            var row=found.get(0); if("DELETED".equals(row.get("state")))return;
            // Task inputs, code and evidence are immutable retained records, not user-deletable scratch files.
            if(!"".equals(row.get("task_id")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Task assets are retained with their execution evidence");
            String version=(String)row.get("object_version");
            if(version!=null)blobs.delete((String)row.get("object_key"),version);
            jdbc.update("update runtime_workspace_file set state='DELETED',deleted_at=? where id=?",System.currentTimeMillis(),id);
            jdbc.update("update runtime_user_workspace set used_bytes=used_bytes-? where id=?",row.get("size_bytes"),owner.id());
        });
    }
    private Space lockSpace(Owner owner) {
        return jdbc.queryForObject("select quota_bytes,used_bytes from runtime_user_workspace where id=? for update",
            (rs,n)->new Space(rs.getLong(1),rs.getLong(2)),owner.id());
    }
    private List<Map<String,Object>> rows(Owner owner,String id) {
        return jdbc.queryForList("select * from runtime_workspace_file where workspace_id=? and id=?",owner.id(),id);
    }
    private Map<String,Object> requireRow(Owner owner,String id) {
        var found=rows(owner,id);if(found.isEmpty() || "DELETED".equals(found.get(0).get("state")))throw missing();return found.get(0);
    }
    private static File file(java.sql.ResultSet rs,int index)throws java.sql.SQLException {
        return new File(rs.getString("id"),rs.getString("kind"),rs.getString("file_name"),rs.getString("content_type"),rs.getString("sha256"),
            rs.getLong("size_bytes"),rs.getString("state"),rs.getString("task_id"),rs.getString("run_id"),rs.getString("source_ref"),rs.getLong("created_at"));
    }
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Asset not found");}
    private static String bounded(String value,int max){if(value==null || value.length()>max || value.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid asset binding");return value;}
    private static String name(String value){
        if(value==null || value.isBlank() || value.length()>255 || value.contains("/") || value.contains("\\") || value.chars().anyMatch(c->c<32) || value.equals(".") || value.equals(".."))
            throw new IllegalArgumentException("Invalid asset file name");return value;
    }
    private static String documentType(String file,byte[] bytes) {
        name(file);String ext=file.substring(file.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        String type=switch(ext){case "pdf"->"application/pdf";case "docx"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";case "csv"->"text/csv";
            case "parquet"->"application/vnd.apache.parquet";case "txt","md"->"text/plain";case "json","jsonl"->"application/json";
            default->throw new IllegalArgumentException("Unsupported user document format");};
        if(bytes.length==0)throw new IllegalArgumentException("Empty document");
        if("pdf".equals(ext) && !new String(bytes,0,Math.min(5,bytes.length),StandardCharsets.US_ASCII).equals("%PDF-"))throw new IllegalArgumentException("Invalid PDF signature");
        if(Set.of("docx","xlsx").contains(ext) && (bytes.length<4 || bytes[0]!='P' || bytes[1]!='K'))throw new IllegalArgumentException("Invalid Office container");
        if("parquet".equals(ext) && (bytes.length<8 || !new String(bytes,0,4,StandardCharsets.US_ASCII).equals("PAR1")
            || !new String(bytes,bytes.length-4,4,StandardCharsets.US_ASCII).equals("PAR1")))throw new IllegalArgumentException("Invalid Parquet signature");
        return type;
    }
    static void verify(byte[] data,String hash,long size){if(data.length!=size || !hash(data).equals(hash))throw new IllegalStateException("Asset integrity check failed");}
    public static String hash(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(Exception e){throw new IllegalStateException(e);}}
}
