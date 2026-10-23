package com.chatchat.api.runtime.coding.data;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.evidence.EvidenceLineage;
import com.chatchat.common.runtime.evidence.EvidenceRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Exports only a selected DatasetHandle already delivered to the current Analysis Loop. */
@Service
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
public final class CodingInputSnapshotService {
    private static final String TYPE = "CODING_INPUT_SNAPSHOT";
    private static final String FILE_NAME = "input.jsonl";
    private final JdbcRuntimeEvidenceStore evidence;
    private final McpPythonControlPlanePort python;
    private final CodingTaskAccess access;
    private final ObjectMapper mapper;
    private CodingInputFilePublisher filePublisher;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setFilePublisher(CodingInputFilePublisher value){this.filePublisher=value;}
    public Map<String,Object> withInputContext(Map<String,Object> configuration,KernelDataScope scope,String task,Snapshot input) {
        var description=describe(scope,task,input);var result=new LinkedHashMap<String,Object>(configuration);
        if(description.containsKey("inputManifest"))result.put("inputFiles",Map.of("schemaVersion",
            com.chatchat.common.runtime.coding.CodingInputManifest.VERSION,"format",description.get("format"),
            "delivery","SHARED_DIRECTORY","manifestFileId",input.fileId(),"manifestSha256",input.sha256()));
        return com.chatchat.common.runtime.coding.CodingRuntimeContext.validate(result);
    }
    public Snapshot captureConfigured(KernelDataScope scope,String task,List<Dataset> authorized,String source,String decision,
            String workspace,long maxRows,long maxBytes,Runnable guard) {
        if(filePublisher!=null && filePublisher.enabled())return filePublisher.capture(scope,task,authorized,source,decision,workspace,maxRows,maxBytes,guard);
        return maxRows>100000||maxBytes>16L*1024*1024?captureChunked(scope,task,authorized,source,decision,workspace,maxRows,maxBytes,guard)
            :captureJsonl(scope,task,authorized,source,decision,workspace,maxRows,maxBytes);
    }
    public Snapshot captureForTask(CodingTaskContract contract,List<Dataset> authorized,String source,long maxRows,long maxBytes) {
        if(contract.runtimeContext().get("inputFiles") instanceof Map<?,?> frozen) {
            if(filePublisher==null || !filePublisher.enabled() || !filePublisher.format().equals(frozen.get("format")))
                throw new IllegalStateException("Task input format configuration changed; original task cannot be reinterpreted");
            return filePublisher.capture(contract.scope(),contract.taskId(),authorized,source,contract.authorization().decisionRef(),contract.workspaceRef(),
                maxRows,maxBytes,()->{},String.valueOf(frozen.get("format")));
        }
        return captureJsonl(contract.scope(),contract.taskId(),authorized,source,contract.authorization().decisionRef(),contract.workspaceRef(),maxRows,maxBytes);
    }

    public record Chunk(String fileId, String fileName, String sha256, long rowCount, long size) { }
    public record Snapshot(String reference, String fileId, String fileName, String sha256,
                           long rowCount, long size, String sourceDatasetRef, List<Chunk> chunks) {
        public Snapshot { chunks=chunks==null?List.of():List.copyOf(chunks); }
        public Snapshot(String reference,String fileId,String fileName,String sha256,long rowCount,long size,String sourceDatasetRef) {
            this(reference,fileId,fileName,sha256,rowCount,size,sourceDatasetRef,List.of());
        }
        public boolean chunked(){return !chunks.isEmpty();}
    }
    public static List<Chunk> mountedFiles(Snapshot input) {
        var files=new ArrayList<Chunk>();
        files.add(new Chunk(input.fileId(),input.fileName(),input.sha256(),input.rowCount(),input.size()));
        files.addAll(input.chunks());
        return List.copyOf(files);
    }

    public CodingInputSnapshotService(JdbcRuntimeEvidenceStore evidence,
            McpPythonControlPlanePort python, CodingTaskAccess access, ObjectMapper mapper) {
        this.evidence = evidence; this.python = python; this.access = access;
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /** Authorized structural projection; no raw records, SQL, endpoint or source credentials. */
    public Map<String,Object> describe(KernelDataScope scope, String taskId, Snapshot input) {
        access.require(scope, CodingTaskAccess.Action.READ);
        String ref=input.reference().substring("runtime-evidence:".length());
        var record=evidence.find(scope,ref).orElseThrow(()->new SecurityException("Input description is unavailable"));
        if (!TYPE.equals(record.evidenceType()) || !taskId.equals(record.payload().get("taskId"))
                || !evidence.contentHash(record.payload()).equals(record.contentSha256())
                || !input.sha256().equals(record.payload().get("sha256")))
            throw new SecurityException("Input description is not bound to the task");
        var description = new LinkedHashMap<String,Object>();
        description.put("schemaJson",record.payload().get("schemaJson"));
        description.put("rowCount",input.rowCount());
        description.put("size",input.size());
        description.put("sha256",input.sha256());
        description.put("fileId",input.fileId());
        description.put("format","JSON_LINES");
        if(record.payload().get("inputManifest") instanceof Map<?,?> manifest) {
            description.put("format",manifest.get("format"));
            description.put("inputManifest",manifest);
            description.put("delivery","SHARED_DIRECTORY");
        }
        description.put("encoding","UTF-8");
        description.put("qualitySummary",record.payload().getOrDefault("qualitySummary",Map.of()));
        if(record.payload().get("inputVersions") instanceof List<?> versions) {
            var projected=new ArrayList<Map<String,Object>>();
            for(Object version:versions)if(version instanceof Map<?,?> fields) {
                var item=new LinkedHashMap<String,Object>();
                for(String key:List.of("datasetReference","contentSha256","rowCount","schemaCoverage"))
                    if(fields.get(key) instanceof String || fields.get(key) instanceof Number)item.put(key,fields.get(key));
                if(fields.get("fieldSchema") instanceof Map<?,?> schema) {
                    var safe=new TreeMap<String,List<String>>();
                    for(var field:schema.entrySet())if(safe.size()<256 && field.getKey() instanceof String name
                            && field.getValue() instanceof java.util.Collection<?> types) {
                        var allowed=types.stream().map(String::valueOf).filter(type->java.util.Set.of(
                            "STRING","NUMBER","BOOLEAN","NULL","OBJECT","ARRAY","BINARY","POJO").contains(type)).sorted().toList();
                        if(!allowed.isEmpty())safe.put(name,allowed);
                    }
                    item.put("fieldSchema",safe);
                }
                item.put("fieldDefinitions",CodingFieldDefinitions.sanitize(fields.get("fieldDefinitions")));
                item.put("sourceDefinition",CodingFieldDefinitions.sanitizeSource(fields.get("sourceDefinition")));
                projected.add(Map.copyOf(item));
            }
            description.put("inputVersions",List.copyOf(projected));
        }
        if(input.chunked())description.put("chunks",input.chunks());
        return Map.copyOf(description);
    }

    /** Only the leased coding worker can observe its explicitly authorized immutable input. */
    public Map<String,Object> observeForCoding(CodingTaskContract contract) {
        access.require(contract.scope(),CodingTaskAccess.Action.WORKER);
        if (!contract.metadataRefs().contains(CodingInputObservation.POLICY_REF))
            throw new SecurityException("Coding input observation was not declared by this task");
        var input=requireAuthorized(contract);
        var description=describe(contract.scope(),contract.taskId(),input);
        if(filePublisher==null || !description.containsKey("inputManifest"))
            throw new IllegalStateException("Coding input observation requires versioned immutable files");
        var observation=new LinkedHashMap<String,Object>(filePublisher.observe(contract.scope(),input,
            description.containsKey("inputVersions")));
        observation.put("snapshotRef",input.reference());observation.put("inputSha256",input.sha256());
        return java.util.Collections.unmodifiableMap(observation);
    }

    /** Caller supplies the current run's trusted dataset collection, never a model-supplied handle. */
    public Snapshot capture(KernelDataScope scope, String taskId, List<Dataset> authorizedDatasets,
            String sourceDatasetRef, String decisionRef, String workspaceRef,
            long maxRows, long maxBytes) {
        access.require(scope, CodingTaskAccess.Action.SUBMIT);
        if(filePublisher!=null && filePublisher.enabled())return filePublisher.capture(scope,taskId,authorizedDatasets,
            sourceDatasetRef,decisionRef,workspaceRef,maxRows,maxBytes,()->{});
        return captureJsonl(scope,taskId,authorizedDatasets,sourceDatasetRef,decisionRef,workspaceRef,maxRows,maxBytes);
    }
    private Snapshot captureJsonl(KernelDataScope scope,String taskId,List<Dataset> authorizedDatasets,
            String sourceDatasetRef,String decisionRef,String workspaceRef,long maxRows,long maxBytes) {
        access.require(scope, CodingTaskAccess.Action.SUBMIT);
        if (scope == null || scope.tenantId() == null || scope.userId() == null || scope.runId() == null
                || taskId == null || taskId.isBlank() || sourceDatasetRef == null || sourceDatasetRef.isBlank()
                || decisionRef == null || decisionRef.isBlank() || workspaceRef == null || workspaceRef.isBlank()
                || maxRows < 0 || maxBytes < 1 || maxBytes > 64L * 1024 * 1024)
            throw new IllegalArgumentException("Bounded authorized Coding snapshot request is required");
        Dataset selected = Objects.requireNonNull(authorizedDatasets).stream()
            .filter(item -> sourceDatasetRef.equals(item.reference())).findFirst()
            .orElseThrow(() -> new SecurityException("Dataset was not delivered to this Analysis Loop"));
        if (!selected.handle().recordCountExact() || selected.recordCount() > maxRows)
            throw new IllegalArgumentException("Dataset has no bounded exact row count");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        final long[] rows = {0};
        TreeMap<String, TreeSet<String>> schema = new TreeMap<>();
        TreeMap<String, Long> present = new TreeMap<>(), nulls = new TreeMap<>();
        selected.handle().scan(1_000, page -> {
            for (Map<String, Object> row : page.rows()) {
                if (++rows[0] > maxRows) throw new IllegalArgumentException("Dataset row limit exceeded");
                try {
                    mapper.valueToTree(row).properties().forEach(field -> {
                        schema.computeIfAbsent(field.getKey(), ignored -> new TreeSet<>())
                            .add(field.getValue().getNodeType().name());
                        present.merge(field.getKey(),1L,Long::sum);
                        if(field.getValue().isNull())nulls.merge(field.getKey(),1L,Long::sum);
                    });
                    byte[] encoded = mapper.writeValueAsBytes(row);
                    if ((long) output.size() + encoded.length + 1 > maxBytes)
                        throw new IllegalArgumentException("Dataset byte limit exceeded");
                    output.write(encoded); output.write('\n');
                } catch (java.io.IOException invalid) {
                    throw new IllegalStateException("Dataset snapshot serialization failed", invalid);
                }
            }
        });
        if (rows[0] != selected.recordCount())
            throw new IllegalStateException("Dataset changed during snapshot capture");
        byte[] bytes = output.toByteArray();
        // A blank JSONL line represents zero records while remaining uploadable
        // through file transports that reject zero-byte multipart files.
        if (bytes.length == 0) bytes = new byte[]{'\n'};
        String schemaJson;
        try { schemaJson = mapper.writeValueAsString(schema); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("Input schema cannot be encoded", invalid); }
        String hash = sha(bytes);
        String identity = scope.tenantId() + "\0" + scope.userId() + "\0" + scope.runId()
            + "\0" + taskId + "\0" + sourceDatasetRef + "\0" + decisionRef
            + "\0" + workspaceRef + "\0" + hash;
        String digest = sha(identity.getBytes(StandardCharsets.UTF_8));
        String fileId = "c" + digest.substring(0, 63);
        String evidenceId = "coding:input:" + digest;
        Map<String, Object> payload = new java.util.LinkedHashMap<>(Map.of("taskId", taskId, "sourceDatasetRef", sourceDatasetRef,
            "decisionRef", decisionRef, "workspaceRef", workspaceRef, "fileId", fileId,
            "fileName", FILE_NAME, "sha256", hash, "rowCount", rows[0], "size", bytes.length,
            "schemaJson", schemaJson));
        var quality = new LinkedHashMap<String,Object>();
        quality.put("coverage","FULL_SNAPSHOT_STRUCTURAL_ONLY");
        quality.put("rowCount",rows[0]);
        var fields = new LinkedHashMap<String,Object>();
        for(var field:schema.entrySet()) fields.put(field.getKey(),Map.of(
            "observedTypes",List.copyOf(field.getValue()),"missingCount",rows[0]-present.getOrDefault(field.getKey(),0L),
            "nullCount",nulls.getOrDefault(field.getKey(),0L)));
        quality.put("fields",fields);
        payload.put("qualitySummary",quality);
        if(selected.analysisContext().containsKey("inputVersions"))payload.put("inputVersions",selected.analysisContext().get("inputVersions"));
        var stored = python.uploadDataFile(scope.tenantId(), scope.userId(), fileId, FILE_NAME, hash, bytes);
        if (stored == null || !"AVAILABLE".equals(stored.status()) || !hash.equals(stored.fileHash())
                || stored.fileSize() != bytes.length || !fileId.equals(stored.id()))
            throw new IllegalStateException("MCP did not confirm immutable Coding input content");
        evidence.register(new EvidenceRecord(null, evidenceId, scope, TYPE,
                "CodingInputSnapshotService", evidence.contentHash(payload), payload,
                null, System.currentTimeMillis(), Map.of()),
            new EvidenceLineage(null, evidenceId, scope, List.of(), taskId,
                "CodingInputSnapshotService", null, Map.of()));
        return new Snapshot("runtime-evidence:" + evidenceId, fileId, FILE_NAME,
            hash, rows[0], bytes.length, sourceDatasetRef);
    }

    /** Bounded-memory opt-in: one immutable MCP file per chunk plus a content-addressed manifest. */
    public Snapshot captureChunked(KernelDataScope scope,String taskId,List<Dataset> authorizedDatasets,
            String sourceDatasetRef,String decisionRef,String workspaceRef,long maxRows,long maxBytes,Runnable guard) {
        access.require(scope,CodingTaskAccess.Action.SUBMIT);
        if(filePublisher!=null && filePublisher.enabled())return filePublisher.capture(scope,taskId,authorizedDatasets,
            sourceDatasetRef,decisionRef,workspaceRef,maxRows,maxBytes,guard);
        if(maxRows<1||maxRows>1_000_000||maxBytes<1||maxBytes>256L*1024*1024)
            throw new IllegalArgumentException("Chunked snapshot exceeds deployment limits");
        Dataset selected=Objects.requireNonNull(authorizedDatasets).stream()
            .filter(item->sourceDatasetRef.equals(item.reference())).findFirst()
            .orElseThrow(()->new SecurityException("Dataset was not delivered to this Analysis Loop"));
        if(!selected.handle().recordCountExact()||selected.recordCount()>maxRows)
            throw new IllegalArgumentException("Dataset exceeds exact row ceiling");
        final int chunkLimit=4*1024*1024;
        var chunks=new ArrayList<Chunk>();
        var buffer=new ByteArrayOutputStream(chunkLimit);
        var digest=shaDigest();
        final long[] rows={0},bytes={0},chunkRows={0};
        TreeMap<String,TreeSet<String>> schema=new TreeMap<>();
        TreeMap<String,Long> present=new TreeMap<>(),nulls=new TreeMap<>();
        selected.handle().scan(1000,page->{
            guard.run();
            for(var row:page.rows()) {
                if(++rows[0]>maxRows)throw new IllegalArgumentException("Snapshot row ceiling exceeded");
                byte[] encoded;
                try {encoded=mapper.writeValueAsBytes(row);}catch(java.io.IOException invalid){throw new IllegalStateException("Snapshot encoding failed",invalid);}
                if(encoded.length+1>chunkLimit)throw new IllegalArgumentException("One row exceeds chunk ceiling");
                if(bytes[0]+encoded.length+1>maxBytes)throw new IllegalArgumentException("Snapshot byte ceiling exceeded");
                if(buffer.size()+encoded.length+1>chunkLimit) {
                    chunks.add(uploadChunk(scope,taskId,sourceDatasetRef,chunks.size(),buffer.toByteArray(),chunkRows[0],guard));
                    buffer.reset();chunkRows[0]=0;
                }
                mapper.valueToTree(row).properties().forEach(field->{
                    schema.computeIfAbsent(field.getKey(),key->new TreeSet<>()).add(field.getValue().getNodeType().name());
                    present.merge(field.getKey(),1L,Long::sum);
                    if(field.getValue().isNull())nulls.merge(field.getKey(),1L,Long::sum);
                });
                buffer.writeBytes(encoded);buffer.write('\n');digest.update(encoded);digest.update((byte)'\n');
                bytes[0]+=encoded.length+1;chunkRows[0]++;
            }
        });
        guard.run();
        if(rows[0]!=selected.recordCount())throw new IllegalStateException("Dataset changed during chunked scan");
        if(buffer.size()>0||chunks.isEmpty())chunks.add(uploadChunk(scope,taskId,sourceDatasetRef,chunks.size(),buffer.toByteArray(),chunkRows[0],guard));
        if(chunks.size()>64)throw new IllegalArgumentException("Snapshot exceeds 64 chunk ceiling");
        String contentHash=HexFormat.of().formatHex(digest.digest());
        String identity=scope.tenantId()+"\0"+scope.userId()+"\0"+scope.runId()+"\0"+taskId+"\0"
            +sourceDatasetRef+"\0"+decisionRef+"\0"+workspaceRef+"\0"+contentHash;
        String id=sha(identity.getBytes(StandardCharsets.UTF_8));
        String fileId="c"+id.substring(0,63), evidenceId="coding:input:"+id;
        var manifest=Map.of("format","runtime-coding-jsonl-chunks.v1","contentSha256",contentHash,
            "rowCount",rows[0],"size",bytes[0],"chunks",List.copyOf(chunks));
        byte[] manifestBytes;
        try {manifestBytes=mapper.writeValueAsBytes(manifest);}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}
        String manifestHash=sha(manifestBytes);
        upload(scope,fileId,"manifest.json",manifestHash,manifestBytes,guard);
        String schemaJson;
        try {schemaJson=mapper.writeValueAsString(schema);}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}
        var fields=new LinkedHashMap<String,Object>();
        for(var field:schema.entrySet())fields.put(field.getKey(),Map.of("observedTypes",List.copyOf(field.getValue()),
            "missingCount",rows[0]-present.getOrDefault(field.getKey(),0L),"nullCount",nulls.getOrDefault(field.getKey(),0L)));
        var quality=Map.of("coverage","FULL_SNAPSHOT_STRUCTURAL_ONLY","rowCount",rows[0],"fields",fields);
        var payload=new LinkedHashMap<String,Object>();
        payload.put("taskId",taskId);payload.put("sourceDatasetRef",sourceDatasetRef);
        payload.put("decisionRef",decisionRef);payload.put("workspaceRef",workspaceRef);
        payload.put("fileId",fileId);payload.put("fileName","manifest.json");payload.put("sha256",manifestHash);
        payload.put("rowCount",rows[0]);payload.put("size",manifestBytes.length);payload.put("contentSha256",contentHash);
        payload.put("dataSize",bytes[0]);payload.put("schemaJson",schemaJson);payload.put("qualitySummary",quality);
        payload.put("chunks",mapper.convertValue(chunks,new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){}));
        evidence.register(new EvidenceRecord(null,evidenceId,scope,TYPE,"CodingInputSnapshotService",
            evidence.contentHash(payload),payload,null,System.currentTimeMillis(),Map.of()),
            new EvidenceLineage(null,evidenceId,scope,List.of(),taskId,"CodingInputSnapshotService",null,Map.of()));
        return new Snapshot("runtime-evidence:"+evidenceId,fileId,"manifest.json",manifestHash,
            rows[0],manifestBytes.length,sourceDatasetRef,chunks);
    }
    private Chunk uploadChunk(KernelDataScope scope,String taskId,String source,int index,byte[] content,long rows,Runnable guard) {
        if(index>=64)throw new IllegalArgumentException("Snapshot exceeds 64 chunk ceiling");
        String hash=sha(content);
        String id=sha((scope.tenantId()+"\0"+scope.userId()+"\0"+scope.runId()+"\0"+taskId+"\0"+source+"\0"+index+"\0"+hash).getBytes(StandardCharsets.UTF_8));
        String fileId="c"+id.substring(0,63);
        upload(scope,fileId,FILE_NAME,hash,content,guard);
        return new Chunk(fileId,FILE_NAME,hash,rows,content.length);
    }
    private void upload(KernelDataScope scope,String fileId,String name,String hash,byte[] bytes) {
        upload(scope,fileId,name,hash,bytes,()->{});
    }
    private void upload(KernelDataScope scope,String fileId,String name,String hash,byte[] bytes,Runnable guard) {
        RuntimeException last=null;
        for(int attempt=0;attempt<3;attempt++)try {
            guard.run();
            var stored=python.uploadDataFile(scope.tenantId(),scope.userId(),fileId,name,hash,bytes);
            if(stored==null||!"AVAILABLE".equals(stored.status())||!hash.equals(stored.fileHash())
                ||stored.fileSize()!=bytes.length||!fileId.equals(stored.id()))throw new IllegalStateException("MCP chunk receipt mismatch");
            return;
        }catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}
        catch(RuntimeException failed){last=failed;}
        throw new IllegalStateException("MCP chunk upload failed after bounded retries",last);
    }
    private static MessageDigest shaDigest() {
        try{return MessageDigest.getInstance("SHA-256");}catch(Exception impossible){throw new IllegalStateException(impossible);}
    }

    /** Resolves the one immutable input that a Coding task may mount. */
    public Snapshot requireAuthorized(CodingTaskContract contract) {
        access.require(contract.scope(), CodingTaskAccess.Action.READ);
        KernelDataScope scope = contract.scope();
        String ref = contract.inputSnapshotRef();
        if (!ref.startsWith("runtime-evidence:coding:input:"))
            throw new SecurityException("Coding input is not a trusted DatasetHandle snapshot");
        EvidenceRecord record = evidence.find(scope, ref.substring("runtime-evidence:".length()))
            .orElseThrow(() -> new SecurityException("Coding input snapshot is not accessible"));
        Map<String, Object> value = record.payload();
        if (!TYPE.equals(record.evidenceType()) || !evidence.contentHash(value).equals(record.contentSha256())
                || !contract.taskId().equals(value.get("taskId"))
                || !contract.authorization().decisionRef().equals(value.get("decisionRef"))
                || !contract.workspaceRef().equals(value.get("workspaceRef")))
            throw new SecurityException("Coding contract is not bound to the authorized input snapshot");
        String fileId = text(value, "fileId"), fileName = text(value, "fileName");
        String hash = text(value, "sha256"), source = text(value, "sourceDatasetRef");
        List<Chunk> chunks=List.of();
        if(value.get("chunks") instanceof List<?> stored)
            chunks=stored.stream().map(item->mapper.convertValue(item,Chunk.class)).toList();
        var mounted=new ArrayList<String>();mounted.add(fileId);chunks.forEach(item->mounted.add(item.fileId()));
        // Authorization references are canonically sorted by the public contract; file read
        // order belongs to the immutable manifest, not to the permission list.
        if (!contract.authorization().allowedDataRefs().equals(mounted.stream().sorted().toList())
                || !(FILE_NAME.equals(fileName) && chunks.isEmpty() || "manifest.json".equals(fileName) && !chunks.isEmpty())
                || !hash.matches("[0-9a-f]{64}"))
            throw new SecurityException("Coding input file authorization differs from its snapshot");
        Object frozen=contract.runtimeContext().get("inputFiles");
        if(value.get("inputManifest") instanceof Map<?,?> manifest) {
            if(!(frozen instanceof Map<?,?> input) || !fileId.equals(input.get("manifestFileId"))
                    || !hash.equals(input.get("manifestSha256")) || !manifest.get("format").equals(input.get("format")))
                throw new SecurityException("Task input capability is not bound to its snapshot");
        }else if(frozen!=null)throw new SecurityException("Legacy input cannot claim a new format");
        var snapshot=new Snapshot(ref, fileId, fileName, hash,
            ((Number) value.get("rowCount")).longValue(), ((Number) value.get("size")).longValue(), source,chunks);
        if(filePublisher!=null && value.containsKey("inputManifest"))filePublisher.materialize(contract,snapshot);
        return snapshot;
    }

    private static String text(Map<String, Object> value, String key) {
        Object result = value.get(key);
        if (!(result instanceof String string) || string.isBlank())
            throw new SecurityException("Coding snapshot field is missing: " + key);
        return string;
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
