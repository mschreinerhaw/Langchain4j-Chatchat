package com.chatchat.api.runtime.coding.data;
import com.chatchat.common.runtime.coding.CodingInputManifest;
import com.chatchat.platform.runtime.coding.CodingSharedFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Verified format decoder for execution provenance and explicitly declared bounded Coding observations. */
public final class CodingInputRows {
    private CodingInputRows() { }
    public static void scan(CodingInputSnapshotService.Snapshot snapshot,
            Function<CodingInputSnapshotService.Chunk,byte[]> fetch,ObjectMapper json,Consumer<Map<String,Object>> consumer) {
        try {
            var root=new CodingInputSnapshotService.Chunk(snapshot.fileId(),snapshot.fileName(),snapshot.sha256(),snapshot.rowCount(),snapshot.size());
            byte[] bytes=checked(root,fetch);var document=snapshot.chunked()?json.readTree(bytes):null;
            if(document!=null && CodingInputManifest.VERSION.equals(document.path("schemaVersion").asText())) {
                var manifest=json.treeToValue(document,CodingInputManifest.class);
                if(!manifest.sourceDatasetRef().equals(snapshot.sourceDatasetRef()) || manifest.rowCount()!=snapshot.rowCount())
                    throw new SecurityException("Input manifest dataset binding mismatch");
                Map<String,CodingInputSnapshotService.Chunk> files=new HashMap<>();
                for(var file:snapshot.chunks())if(files.put(file.fileId(),file)!=null)throw new SecurityException("Duplicate snapshot file");
                var schema=files.remove(manifest.schemaFileId());
                if(schema==null || !"schema.json".equals(schema.fileName()))throw new SecurityException("Input schema missing");
                var described=json.readTree(checked(schema,fetch));
                var schemaColumns=json.convertValue(described.path("columns"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<CodingInputManifest.Column>>(){});
                if(!manifest.columns().equals(schemaColumns) || !manifest.schemaVersion().equals(described.path("schemaVersion").asText())
                        || !manifest.format().name().equals(described.path("format").asText()))
                    throw new SecurityException("Input schema differs from manifest");
                for(var original:manifest.nativeFiles()) {
                    var file=files.remove(original.fileId());
                    if(file==null || !file.fileName().equals(original.fileName()) || !file.sha256().equals(original.sha256())
                            || file.size()!=original.sizeBytes() || file.rowCount()!=0)
                        throw new SecurityException("Native input file binding mismatch");
                    checked(file,fetch);
                }
                for(var part:manifest.parts()) {
                    var file=files.remove(part.fileId());
                    if(file==null || !file.fileName().equals(part.fileName()) || !file.sha256().equals(part.sha256())
                            || file.rowCount()!=part.rowCount() || file.size()!=part.sizeBytes())
                        throw new SecurityException("Input part binding mismatch");
                    Path temp=Files.createTempFile("coding-input-verifier-","."+manifest.format().name().toLowerCase(Locale.ROOT));
                    try {
                        Files.write(temp,checked(file,fetch));long[] count={0};
                        CodingTableCodec.read(temp,manifest,json,row->{
                            if(++count[0]>part.rowCount())throw new SecurityException("Input row count exceeded");consumer.accept(row);
                        });
                        if(count[0]!=part.rowCount())throw new SecurityException("Input row count mismatch");
                    }finally{Files.deleteIfExists(temp);}
                }
                if(!files.isEmpty())throw new SecurityException("Unbound input files");
            } else {
                var files=snapshot.chunked()?snapshot.chunks():List.of(root);
                for(var file:files) {
                    byte[] content=snapshot.chunked()?checked(file,fetch):bytes;
                    for(String line:new String(content,java.nio.charset.StandardCharsets.UTF_8).split("\\R"))if(!line.isBlank()) {
                        var row=json.readTree(line);if(!row.isObject())throw new SecurityException("Input JSONL row is not an object");
                        consumer.accept(json.convertValue(row,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
                    }
                }
            }
        }catch(java.io.IOException invalid){throw new IllegalStateException("Immutable input cannot be decoded",invalid);}
    }
    private static byte[] checked(CodingInputSnapshotService.Chunk file,Function<CodingInputSnapshotService.Chunk,byte[]> fetch) {
        byte[] bytes=fetch.apply(file);
        try {
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if(!hash.equals(file.sha256()) || file.size()!=bytes.length)throw new SecurityException("Immutable input bytes changed");
            return bytes;
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
