package com.chatchat.api.assets;

import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Called only after existing Runtime contract/access checks. No storage authority is sent to workers. */
@Service
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
public class RuntimeAssetArchive {
    private final UserAssetService assets;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public RuntimeAssetArchive(UserAssetService assets,JdbcTemplate jdbc,ObjectMapper json){this.assets=assets;this.jdbc=jdbc;this.json=json;}
    public UserAssetService.File save(KernelDataScope scope,String task,UserAssetService.Kind kind,String source,String name,byte[] bytes){
        if(scope.runId()==null || task==null || task.isBlank())throw new SecurityException("Task-bound asset scope required");
        return assets.store(UserAssetService.Owner.of(scope),kind,task,scope.runId(),source,name,"application/octet-stream",bytes);
    }
    public Optional<byte[]> read(KernelDataScope scope,String task,String source){
        var ids=jdbc.queryForList("select id from runtime_workspace_file where workspace_id=? and task_id=? and run_id=? and source_ref=? and state='AVAILABLE' order by created_at,id",
            String.class,UserAssetService.Owner.of(scope).id(),task,scope.runId(),source);
        if(ids.size()>1)throw new IllegalStateException("Immutable task asset binding changed");
        return ids.isEmpty()?Optional.empty():Optional.of(assets.readTask(scope,task,ids.get(0)));
    }
    public boolean copy(KernelDataScope scope,String task,String source,java.io.OutputStream output)throws java.io.IOException {
        var ids=jdbc.queryForList("select id from runtime_workspace_file where workspace_id=? and task_id=? and run_id=? and source_ref=? and state='AVAILABLE'",
            String.class,UserAssetService.Owner.of(scope).id(),task,scope.runId(),source);
        if(ids.size()>1)throw new IllegalStateException("Immutable task asset binding changed");
        if(ids.isEmpty())return false;assets.copyTask(scope,task,ids.get(0),output);return true;
    }
    public void context(KernelDataScope scope,String task,String source,Map<String,Object> context){
        try{save(scope,task,UserAssetService.Kind.CONTEXT,source,"analysis-context.json",json.writeValueAsBytes(context));}
        catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid analysis context",invalid);}
    }
    public void code(KernelDataScope scope,String task,String source,String name,String code){
        save(scope,task,UserAssetService.Kind.CODE,source,name,code.getBytes(StandardCharsets.UTF_8));
    }
}
