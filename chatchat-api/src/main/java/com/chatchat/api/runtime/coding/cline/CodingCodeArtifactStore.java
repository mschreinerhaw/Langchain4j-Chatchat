package com.chatchat.api.runtime.coding.cline;

import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.evidence.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Immutable code submissions are private artifacts, not repeated event or model payloads. */
@Component
@ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
public final class CodingCodeArtifactStore {
    private com.chatchat.api.assets.RuntimeAssetArchive assets;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setAssets(com.chatchat.api.assets.RuntimeAssetArchive value){assets=value;}
    private final JdbcRuntimeEvidenceStore evidence;
    public CodingCodeArtifactStore(JdbcRuntimeEvidenceStore evidence) { this.evidence = evidence; }
    public String commit(CodingTaskContract contract, String operation, String source, String expectedHash) {
        return commit(contract,operation,source,expectedHash,null);
    }
    public String commit(CodingTaskContract contract, String operation, String source, String expectedHash,
                         com.chatchat.common.runtime.coding.CodingScript script) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (!operation.matches("cline-[0-9a-f]{64}") || bytes.length == 0 || bytes.length > 256000)
            throw new IllegalArgumentException("Invalid code artifact identity or size");
        String hash = sha(bytes);
        if (expectedHash != null && !expectedHash.equals(hash)) throw new IllegalArgumentException("Code hash mismatch");
        String id = id(contract.taskId(), operation);
        String fileName=script==null?com.chatchat.common.runtime.coding.CodingScript.LEGACY_FILE:script.fileName();
        var existing=find(contract.scope(),contract.taskId(),operation);
        if(existing.isPresent()) {
            if(!hash.equals(existing.get().get("sha256"))||!fileName.equals(existing.get().get("fileName")))throw new SecurityException("Code operation is immutable");
            return hash;
        }
        if(assets!=null)assets.code(contract.scope(),contract.taskId(),"code:"+operation,fileName,source);
        var payload = new LinkedHashMap<String,Object>(Map.of("taskId",contract.taskId(),"operationId",operation,"fileName",fileName,
            "sha256",hash,"sourceBytes",bytes.length,"content",source));
        if(script!=null)payload.put("script",script);
        String input = contract.inputSnapshotRef().replaceFirst("^runtime-evidence:", "");
        evidence.register(new EvidenceRecord(null,id,contract.scope(),"CODING_CODE_SUBMISSION","ClineCodingAgent",
                evidence.contentHash(payload),payload,null,System.currentTimeMillis(),Map.of()),
            new EvidenceLineage(null,id,contract.scope(),List.of(input),contract.taskId(),"ClineCodingAgent",operation,Map.of()));
        return hash;
    }
    public Optional<Map<String,Object>> find(KernelDataScope scope, String task, String operation) {
        return evidence.find(scope,id(task,operation)).map(record -> {
            if (!"CODING_CODE_SUBMISSION".equals(record.evidenceType()) || !task.equals(record.payload().get("taskId"))
                    || !operation.equals(record.payload().get("operationId"))) throw new SecurityException("Code identity mismatch");
            return record.payload();
        });
    }
    private static String id(String task,String operation) { return "coding:source:"+sha((task+"\0"+operation).getBytes(StandardCharsets.UTF_8)); }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
