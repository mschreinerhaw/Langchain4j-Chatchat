package com.chatchat.api.assets;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Retains stopped execution diagnostics, including failed/cancelled runs, without declaring result validity. */
@Service
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
public class RuntimePythonAssetArchiver {
    private final RuntimeAssetArchive archive;private final McpPythonControlPlanePort python;private final ObjectMapper json;
    public RuntimePythonAssetArchiver(RuntimeAssetArchive archive,McpPythonControlPlanePort python,ObjectMapper json){this.archive=archive;this.python=python;this.json=json;}
    public void stopped(KernelDataScope scope,String task,McpPythonControlPlanePort.OperationView operation){
        if(!operation.executorStopped() || operation.manifestHash()==null)return;
        String source="execution:"+operation.operationId()+":";
        if(archive.read(scope,task,source+"manifest:manifest.json").isPresent())return;
        byte[] manifest=python.artifact(scope.tenantId(),scope.userId(),scope.runId(),task,operation.operationId(),"manifest","manifest.json");
        if(!UserAssetService.hash(manifest).equals(operation.manifestHash()))throw new SecurityException("Stopped execution manifest hash mismatch");
        try{
            var parsed=json.readTree(manifest);if(!parsed.path("artifacts").isArray())throw new IllegalStateException("Invalid stopped execution manifest");
            Set<String> seen=new HashSet<>();
            for(var item:parsed.path("artifacts")){
                String channel=item.path("channel").asText(),name=item.path("fileName").asText();
                if(!Set.of("results","validation","logs").contains(channel) || name.isBlank() || !seen.add(channel+"/"+name))throw new SecurityException("Invalid stopped artifact identity");
                byte[] bytes=python.artifact(scope.tenantId(),scope.userId(),scope.runId(),task,operation.operationId(),channel,name);
                if(bytes.length!=item.path("size").asLong(-1) || !UserAssetService.hash(bytes).equals(item.path("sha256").asText()))throw new SecurityException("Stopped artifact hash mismatch");
                archive.save(scope,task,"logs".equals(channel)?UserAssetService.Kind.LOG:UserAssetService.Kind.ARTIFACT,source+channel+":"+name,name,bytes);
            }
            archive.context(scope,task,"execution-stop:"+operation.operationId(),Map.of("operationId",operation.operationId(),
                "status",operation.status(),"executorStopped",true,"manifestSha256",operation.manifestHash(),"resultValidity","NOT_EVALUATED"));
            archive.save(scope,task,UserAssetService.Kind.LOG,source+"manifest:manifest.json","manifest.json",manifest);
        }catch(java.io.IOException invalid){throw new IllegalStateException("Invalid stopped execution manifest",invalid);}
    }
}
