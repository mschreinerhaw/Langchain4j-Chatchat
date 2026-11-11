package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.evidence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Immutable Silo bytes + existing Runtime evidence lineage; no new store. */
@Service
@ConditionalOnProperty(prefix="chatchat.runtime.coding.unified",name="enabled",havingValue="true")
public final class NativeS3Evidence {
    public final UserAssetService assets;
    private final JdbcRuntimeEvidenceStore store;
    private final ObjectMapper json;
    public NativeS3Evidence(UserAssetService assets,JdbcRuntimeEvidenceStore store,ObjectMapper json){this.assets=assets;this.store=store;this.json=json;}
    public byte[] bytes(Object value){try{return json.writeValueAsBytes(value);}catch(Exception e){throw new IllegalArgumentException("Invalid Native evidence",e);}}
    public String hash(Object value){return UserAssetService.hash(bytes(value));}
    public <T>T atomic(CodingTaskContract contract,java.util.function.Supplier<T> action){return store.atomicExecutionCheckpoints(contract.scope(),action);}
    public UserAssetService.File archive(CodingTaskContract c,String purpose,String name,byte[] data){
        return assets.store(UserAssetService.Owner.of(c.scope()),UserAssetService.Kind.LOG,c.taskId(),c.scope().runId(),"native-s3:"+purpose+":"+UserAssetService.hash(data),name,"application/octet-stream",data);
    }
    public String register(CodingTaskContract c,String type,String key,Map<String,Object> payload,List<String> parents){
        String id="coding:native:"+UserAssetService.hash((c.scope().tenantId()+"\0"+c.scope().userId()+"\0"+c.scope().runId()+"\0"+c.taskId()+"\0"+type+"\0"+key).getBytes(StandardCharsets.UTF_8));
        String sha=store.contentHash(payload);var existing=store.find(c.scope(),id);
        if(existing.isPresent()){if(!sha.equals(existing.get().contentSha256()))throw new IllegalStateException("Immutable Native evidence conflict");return "runtime-evidence:"+id;}
        store.register(new EvidenceRecord(null,id,c.scope(),type,"NativeS3CodingAdapter",sha,payload,null,System.currentTimeMillis(),Map.of()),
            new EvidenceLineage(null,id,c.scope(),parents.stream().map(NativeS3Evidence::id).toList(),c.taskId(),"NativeS3CodingAdapter",null,Map.of()));
        return "runtime-evidence:"+id;
    }
    public static String id(String ref){return ref.startsWith("runtime-evidence:")?ref.substring("runtime-evidence:".length()):ref;}
    public String bundle(CodingTaskContract c,List<String> members){
        String id="coding:native-bundle:"+hash(members);store.createSnapshot(c.scope(),id,members.stream().map(NativeS3Evidence::id).toList(),Map.of("taskId",c.taskId(),"profile",NativeSiloTaskService.PROFILE,"businessEvaluated",false));return "runtime-evidence:"+id;
    }
}
