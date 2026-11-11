package com.chatchat.api.assets;

import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

/** Explicit transport component probe: generated binary bytes, zero model calls.
 * No Native receipt, analysis result or successful business task is fabricated.
 */
final class SiloTransportAcceptanceFixture {
    private static final int LENGTH=16*1024*1024+31,CHUNK=8*1024*1024;
    static void run(String action,Path root,JsonNode config,KernelDataScope scope,String task,
            UserAssetService assets,AssetBlobStore blobs,ObjectMapper json)throws Exception {
        if(!config.path("transportProbeOnly").asBoolean(false))throw new SecurityException("Explicit transport-only fixture configuration required");
        Path planFile=root.resolve("transport-plan.private.json");
        if(action.equals("prepare-transport-probe")){
            if(Files.exists(planFile))throw new IllegalStateException("Transport probe already prepared; retain original evidence");
            byte[] bytes=new byte[LENGTH];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*31+7);
            String digest=UserAssetService.hash(bytes);
            var source=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.DATASET,task,scope.runId(),"transport-component-input-v1","transport-probe.bin","application/octet-stream",bytes);
            String key="tenants/"+UserAssetService.hash(scope.tenantId().getBytes(java.nio.charset.StandardCharsets.UTF_8))+"/users/"+UserAssetService.Owner.of(scope).id()+"/tasks/transport-probe/"+source.id()+"/"+digest;
            var parts=new ArrayList<Map<String,Object>>();for(int i=0;i<(LENGTH+CHUNK-1)/CHUNK;i++)parts.add(Map.of("index",i,"url",blobs.presignWrite(key+"/parts/"+i,900)));
            var input=Map.of("path","input/data/transport-probe.bin","size",LENGTH,"sha256",digest,"objectVersion",assets.taskInputVersion(scope,task,source.id()),"url",assets.taskInputCapability(scope,task,source.id(),900));
            Files.writeString(planFile,json.writeValueAsString(Map.of("schemaVersion","silo_transport_component_probe.v1","componentTestOnly",true,"input",input,"artifact",Map.of("path","output/transport-probe.bin","type","AUXILIARY","size",LENGTH,"sha256",digest),"parts",parts,"outputKey",key)),StandardOpenOption.CREATE_NEW);
        }else if(action.equals("verify-transport-probe")){
            var plan=json.readTree(Files.readAllBytes(planFile));var uploaded=json.readTree(Files.readAllBytes(root.resolve("transport-upload-receipt.private.json")));
            String key=plan.path("outputKey").asText(),digest=plan.path("artifact").path("sha256").asText();
            if(!plan.path("componentTestOnly").asBoolean()||!uploaded.path("path").asText().equals("output/transport-probe.bin")||uploaded.path("size").asInt()!=LENGTH||!digest.equals(uploaded.path("sha256").asText())||uploaded.path("parts").size()!=3)throw new SecurityException("Transport receipt binding mismatch");
            var parts=new ArrayList<AssetBlobStore.ObjectPart>();var verified=new ArrayList<Map<String,Object>>();
            for(int i=0;i<3;i++){
                var receipt=uploaded.path("parts").get(i);int size=Math.min(CHUNK,LENGTH-i*CHUNK);String partKey=key+"/parts/"+i,version=receipt.path("objectVersion").asText();
                if(receipt.path("index").asInt(-1)!=i||receipt.path("size").asInt(-1)!=size)throw new SecurityException("Transport part order/size mismatch");
                var stat=blobs.stat(partKey,version);byte[] bytes=blobs.get(partKey,version,size);
                if(stat.size()!=size||bytes.length!=size||!UserAssetService.hash(bytes).equals(receipt.path("sha256").asText()))throw new SecurityException("Silo immutable part integrity mismatch");
                verified.add(Map.of("index",i,"size",size,"sha256",UserAssetService.hash(bytes),"objectVersion",version));parts.add(new AssetBlobStore.ObjectPart(partKey,version));
            }
            String complete=key+"/complete";var stored=blobs.compose(complete,parts);var stat=blobs.stat(complete,stored.versionId());byte[] bytes=blobs.get(complete,stored.versionId(),LENGTH);
            if(stat.size()!=LENGTH||bytes.length!=LENGTH||!digest.equals(UserAssetService.hash(bytes)))throw new SecurityException("Silo composed object integrity mismatch");
            for(int i=0;i<bytes.length;i++)if(bytes[i]!=(byte)(i*31+7))throw new SecurityException("Independent byte-pattern check failed");
            var proof=new LinkedHashMap<String,Object>();proof.put("schemaVersion","silo_transport_component_result.v1");proof.put("passed",true);proof.put("size",LENGTH);proof.put("sha256",digest);proof.put("parts",verified);proof.put("composedKey",complete);proof.put("composedVersion",stored.versionId());proof.put("fullReadbackVerified",true);proof.put("independentBytePatternVerified",true);proof.put("modelCalls",0);proof.put("businessTaskAcceptance",false);
            byte[] evidence=json.writeValueAsBytes(proof);var archived=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.LOG,task,scope.runId(),"transport-component-verification:"+digest,"transport-component-verification.json","application/json",evidence);
            if(!Arrays.equals(assets.readTask(scope,task,archived.id()),evidence))throw new SecurityException("Transport validation evidence readback failed");
            proof.put("verificationAssetId",archived.id());Files.writeString(root.resolve("transport-verification.private.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(proof),StandardOpenOption.CREATE_NEW);
        }else throw new IllegalArgumentException("Unknown transport probe action");
    }
}
