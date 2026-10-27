package com.chatchat.api.runtime.coding.data;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.CodingInputManifest;
import com.chatchat.common.runtime.coding.CodingInputManifest.*;
import com.chatchat.common.runtime.evidence.*;
import com.chatchat.platform.runtime.coding.CodingSharedFiles;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Format/storage extension of the existing DatasetHandle capture. No data retrieval or model authority. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
public final class CodingInputFilePublisher {
    private com.chatchat.api.assets.RuntimeAssetArchive assets;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setAssets(com.chatchat.api.assets.RuntimeAssetArchive value){assets=value;}
    public void materialize(com.chatchat.common.runtime.coding.CodingTaskContract contract,CodingInputSnapshotService.Snapshot input){
        if(assets==null)return;
        var store=new CodingSharedFiles(Path.of(policy.getSharedRoot()));
        for(var file:CodingInputSnapshotService.mountedFiles(input)){
            try{store.verify(contract.scope().tenantId(),contract.scope().userId(),file.fileId(),file.fileName(),file.sha256(),file.size());}
            catch(IOException unavailable){
                var bytes=assets.read(contract.scope(),contract.taskId(),"input:"+file.fileId()).orElseThrow(()->new IllegalStateException("Archived task input is unavailable",unavailable));
                if(bytes.length!=file.size() || !com.chatchat.api.assets.UserAssetService.hash(bytes).equals(file.sha256()))throw new SecurityException("Archived input differs from task manifest");
                Path staging=null;
                try{
                    staging=store.staging(contract.scope().tenantId(),contract.scope().userId());Path part=staging.resolve(file.fileName());Files.write(part,bytes);
                    store.publish(contract.scope().tenantId(),contract.scope().userId(),file.fileId(),file.fileName(),part,file.sha256(),file.size());
                    var receipt=python.registerSharedDataFile(contract.scope().tenantId(),contract.scope().userId(),file.fileId(),file.fileName(),file.sha256(),file.size());
                    if(receipt==null || !"AVAILABLE".equals(receipt.status()) || !file.fileId().equals(receipt.id()) || !file.sha256().equals(receipt.fileHash()) || file.size()!=receipt.fileSize())throw new IllegalStateException("Restored input receipt mismatch");
                }catch(IOException failure){throw new IllegalStateException("Archived task input cannot be staged",failure);}
                finally{if(staging!=null)try{Files.deleteIfExists(staging.resolve(file.fileName()));Files.deleteIfExists(staging);}catch(IOException ignored){}}
            }
        }
    }
    private final CodingInputFilesProperties policy;
    private final McpPythonControlPlanePort python;
    private final JdbcRuntimeEvidenceStore evidence;
    private final CodingTaskAccess access;
    private final ObjectMapper json;
    public CodingInputFilePublisher(CodingInputFilesProperties policy,McpPythonControlPlanePort python,
            JdbcRuntimeEvidenceStore evidence,CodingTaskAccess access,ObjectMapper json) {
        this.policy=policy;this.python=python;this.evidence=evidence;this.access=access;
        this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    public boolean enabled(){return policy.isEnabled();}
    public Map<String,Object> observe(KernelDataScope scope, CodingInputSnapshotService.Snapshot input, boolean batched) {
        access.require(scope,CodingTaskAccess.Action.WORKER);
        access.require(scope,CodingTaskAccess.Action.READ);
        if (!enabled()) throw new IllegalStateException("Immutable input observation is unavailable");
        var store=new CodingSharedFiles(Path.of(policy.getSharedRoot()));
        var observation=new CodingInputObservation(json,input.sourceDatasetRef(),batched);
        CodingInputRows.scan(input,file->{
            try { return Files.readAllBytes(store.verify(scope.tenantId(),scope.userId(),file.fileId(),
                file.fileName(),file.sha256(),file.size())); }
            catch(IOException invalid){throw new SecurityException("Input observation integrity check failed",invalid);}
        },json,observation::accept);
        return observation.snapshot();
    }
    public String format(){return policy.getFormat()==null?"":policy.getFormat().name();}
    public CodingInputSnapshotService.Snapshot capture(KernelDataScope scope,String task,List<Dataset> authorized,
            String source,String decision,String workspace,long maxRows,long maxBytes,Runnable guard) {
        return capture(scope,task,authorized,source,decision,workspace,maxRows,maxBytes,guard,null);
    }
    public CodingInputSnapshotService.Snapshot capture(KernelDataScope scope,String task,List<Dataset> authorized,
            String source,String decision,String workspace,long maxRows,long maxBytes,Runnable guard,String requiredFormat) {
        access.require(scope,CodingTaskAccess.Action.SUBMIT);
        String sharedRoot=policy.getSharedRoot();Format format=policy.getFormat();int partRows=policy.getPartRows();
        if(!enabled() || sharedRoot==null || sharedRoot.isBlank() || format==null || format==Format.JSON_LINES
                || partRows<1 || partRows>10000 || maxRows<0 || maxRows>1000000 || maxBytes<1 || maxBytes>256L*1024*1024)
            throw new IllegalArgumentException("Invalid bounded shared input policy");
        if(requiredFormat!=null && !format.name().equals(requiredFormat))throw new IllegalStateException("Frozen task input format changed");
        var capabilities=python.inputCapabilities();
        if(capabilities==null || !capabilities.contains("SHARED_DIRECTORY") || !capabilities.contains(format.name()))
            throw new IllegalStateException("Python control plane has not negotiated this shared input format");
        Dataset dataset=authorized.stream().filter(d->source.equals(d.reference())).findFirst()
            .orElseThrow(()->new SecurityException("Dataset was not delivered to this Analysis Loop"));
        if(!dataset.handle().recordCountExact() || dataset.recordCount()>maxRows)
            throw new IllegalArgumentException("Exact bounded dataset required");
        var store=new CodingSharedFiles(Path.of(sharedRoot));Path staging=null;
        try {
            staging=store.staging(scope.tenantId(),scope.userId());Path spool=staging.resolve("capture.jsonl");
            var fields=new TreeMap<String,Field>();long[] count={0},logicalBytes={0};
            try(var output=Files.newBufferedWriter(spool,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
                dataset.handle().scan(1000,page->{guard.run();for(var row:page.rows()) {
                    if(++count[0]>maxRows)throw new IllegalArgumentException("Snapshot row ceiling exceeded");
                    if(row.size()>256)throw new IllegalArgumentException("Snapshot column ceiling exceeded");
                    row.forEach((name,value)->fields.computeIfAbsent(name,k->new Field()).observe(value));
                    if(fields.size()>256)throw new IllegalArgumentException("Snapshot column ceiling exceeded");
                    try {
                        String encoded=json.writeValueAsString(row);long bytes=encoded.getBytes(StandardCharsets.UTF_8).length+1L;
                        if(bytes>4*1024*1024 || (logicalBytes[0]+=bytes)>maxBytes)throw new IllegalArgumentException("Snapshot logical byte ceiling exceeded");
                        output.write(encoded);output.write('\n');
                    }catch(IOException invalid){throw new java.io.UncheckedIOException(invalid);}
                }});
            }
            guard.run();if(count[0]!=dataset.recordCount())throw new IllegalStateException("Dataset changed during capture");
            String logicalHash=CodingSharedFiles.hash(spool);var columns=new ArrayList<Column>();
            fields.forEach((name,field)->columns.add(field.column(name,"c"+columns.size(),count[0])));
            var schema=new LinkedHashMap<String,Object>();schema.put("schemaVersion",CodingInputManifest.VERSION);
            schema.put("columns",columns);schema.put("format",format);schema.put("encoding","UTF-8");
            schema.put("presenceColumns",columns.stream().collect(java.util.stream.Collectors.toMap(Column::storageName,CodingTableCodec::presence)));
            schema.put("csv",Map.of("delimiter",",","quote","\"","escape","DOUBLE_QUOTE","nullToken",CodingTableCodec.CSV_NULL,
                "missingToken",CodingTableCodec.CSV_MISSING,"literalBackslash","PREFIX_BACKSLASH"));
            schema.put("sourceDatasetRef",source);
            if(dataset.analysisContext().get("schema") instanceof Map<?,?> declared && !declared.isEmpty())
                schema.put("declaredSourceSchema",declared);
            String binding=scope.tenantId()+"\0"+scope.userId()+"\0"+scope.runId()+"\0"+task+"\0"+source+"\0"+decision+"\0"+workspace;
            Path schemaPath=staging.resolve("schema.json");Files.write(schemaPath,json.writeValueAsBytes(schema));
            var chunks=new ArrayList<CodingInputSnapshotService.Chunk>();var schemaFile=publish(store,scope,task,binding,schemaPath,0,guard);
            chunks.add(schemaFile);var parts=new ArrayList<Part>();
            try(var reader=Files.newBufferedReader(spool,StandardCharsets.UTF_8)) {
                var rows=new ArrayList<JsonNode>();long buffered=0;String line;
                while((line=reader.readLine())!=null) {
                    guard.run();long size=line.getBytes(StandardCharsets.UTF_8).length+1L;
                    if(!rows.isEmpty() && (rows.size()>=partRows || buffered+size>4*1024*1024)) {
                        encodePart(store,scope,task,binding,staging,format,columns,rows,parts,chunks,guard);rows.clear();buffered=0;
                    }
                    rows.add(json.readTree(line));buffered+=size;
                }
                if(!rows.isEmpty() || parts.isEmpty())encodePart(store,scope,task,binding,staging,format,columns,rows,parts,chunks,guard);
            }
            if(chunks.size()>64)throw new IllegalArgumentException("Input exceeds 64 mounted auxiliary files");
            var manifest=new CodingInputManifest(CodingInputManifest.VERSION,source,format,schemaFile.fileId(),
                logicalHash,count[0],logicalBytes[0],columns,parts);
            Path manifestPath=staging.resolve("manifest.json");Files.write(manifestPath,json.writeValueAsBytes(manifest));
            long stored=Files.size(manifestPath)+chunks.stream().mapToLong(CodingInputSnapshotService.Chunk::size).sum();
            if(stored>maxBytes)throw new IllegalArgumentException("Snapshot physical byte ceiling exceeded");
            // Publish the manifest last. Only committed evidence can authorize a ready task.
            var root=publish(store,scope,task,binding,manifestPath,count[0],guard);
            String evidenceId="coding:input:"+root.fileId().substring(1);
            var payload=new LinkedHashMap<String,Object>();payload.put("taskId",task);payload.put("sourceDatasetRef",source);
            payload.put("decisionRef",decision);payload.put("workspaceRef",workspace);payload.put("fileId",root.fileId());
            payload.put("fileName",root.fileName());payload.put("sha256",root.sha256());payload.put("rowCount",count[0]);payload.put("size",root.size());
            payload.put("schemaJson",json.writeValueAsString(schema));payload.put("inputManifest",json.convertValue(manifest,Map.class));
            payload.put("chunks",json.convertValue(chunks,List.class));payload.put("delivery","SHARED_DIRECTORY");
            payload.put("qualitySummary",Map.of("coverage","FULL_SNAPSHOT_STRUCTURAL_ONLY","rowCount",count[0],"fields",columns));
            if(dataset.analysisContext().containsKey("inputVersions"))payload.put("inputVersions",dataset.analysisContext().get("inputVersions"));
            Map<String,Object> committed=json.convertValue(payload,new com.fasterxml.jackson.core.type.TypeReference<>(){});
            evidence.register(new EvidenceRecord(null,evidenceId,scope,"CODING_INPUT_SNAPSHOT","CodingInputSnapshotService",
                evidence.contentHash(committed),committed,null,System.currentTimeMillis(),Map.of()),
                new EvidenceLineage(null,evidenceId,scope,List.of(),task,"CodingInputSnapshotService",null,Map.of()));
            return new CodingInputSnapshotService.Snapshot("runtime-evidence:"+evidenceId,root.fileId(),root.fileName(),root.sha256(),count[0],root.size(),source,chunks);
        } catch(IOException invalid){throw new IllegalStateException("Shared input publication failed",invalid);}
        finally {
            if(staging!=null)try(var files=Files.list(staging)){for(var file:files.toList())Files.deleteIfExists(file);Files.deleteIfExists(staging);}
            catch(IOException cleanup){/* Unpublished staging is never authorized; retained for operator reconciliation. */}
        }
    }
    private void encodePart(CodingSharedFiles store,KernelDataScope scope,String task,String binding,Path staging,Format format,List<Column> columns,
            List<JsonNode> rows,List<Part> parts,List<CodingInputSnapshotService.Chunk> chunks,Runnable guard) throws IOException {
        if(parts.size()>=63)throw new IllegalArgumentException("Input part ceiling exceeded");
        String extension=format==Format.PARQUET?"parquet":"csv";
        Path path=staging.resolve(String.format(Locale.ROOT,"part-%05d.%s",parts.size(),extension));
        CodingTableCodec.write(path,format,columns,rows,json);
        var file=publish(store,scope,task,binding,path,rows.size(),guard);chunks.add(file);
        parts.add(new Part(file.fileId(),file.fileName(),file.sha256(),file.rowCount(),file.size()));
    }
    private CodingInputSnapshotService.Chunk publish(CodingSharedFiles store,KernelDataScope scope,String task,String binding,Path path,long rows,Runnable guard) throws IOException {
        guard.run();String hash=CodingSharedFiles.hash(path);long size=Files.size(path);
        String id="c"+digest(binding+"\0"+path.getFileName()+"\0"+hash).substring(0,63);
        if(assets!=null)assets.save(scope,task,com.chatchat.api.assets.UserAssetService.Kind.DATASET,"input:"+id,path.getFileName().toString(),Files.readAllBytes(path));
        store.publish(scope.tenantId(),scope.userId(),id,path.getFileName().toString(),path,hash,size);
        var receipt=python.registerSharedDataFile(scope.tenantId(),scope.userId(),id,path.getFileName().toString(),hash,size);
        if(receipt==null || !"AVAILABLE".equals(receipt.status()) || !id.equals(receipt.id()) || !hash.equals(receipt.fileHash()) || size!=receipt.fileSize())
            throw new IllegalStateException("Shared input receipt mismatch");
        return new CodingInputSnapshotService.Chunk(id,path.getFileName().toString(),hash,rows,size);
    }
    private static String digest(String text) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static final class Field {
        long present,nulls;Type type;int integerDigits,scale;
        void observe(Object value) {
            present++;if(value==null){nulls++;return;}
            Type found=value instanceof Boolean?Type.BOOLEAN:value instanceof BigDecimal||value instanceof BigInteger?Type.DECIMAL:
                value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long?Type.INT64:
                value instanceof Float||value instanceof Double?Type.FLOAT64:value instanceof CharSequence?Type.STRING:Type.JSON;
            if(found==Type.FLOAT64 && !Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException("Non-finite number in input");
            if(found==Type.DECIMAL || found==Type.INT64) {
                BigDecimal decimal=new BigDecimal(value.toString());scale=Math.max(scale,Math.max(0,decimal.scale()));
                integerDigits=Math.max(integerDigits,Math.max(0,decimal.precision()-decimal.scale()));
            }
            if(type==null)type=found;
            else if(type!=found) {
                if(Set.of(Type.INT64,Type.DECIMAL).contains(type)&&Set.of(Type.INT64,Type.DECIMAL).contains(found))type=Type.DECIMAL;
                else throw new IllegalArgumentException("Mixed input types require an explicit registered schema");
            }
        }
        Column column(String name,String storage,long rows) {
            Type effective=type==null?Type.NULL:type;int precision=effective==Type.DECIMAL?Math.max(1,integerDigits+scale):0;
            return new Column(name,storage,effective,precision,effective==Type.DECIMAL?scale:0,rows-present,nulls);
        }
    }
}
