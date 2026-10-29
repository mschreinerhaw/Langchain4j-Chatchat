package com.chatchat.api.runtime.coding.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Task-local projection of an existing snapshot, not another data store or cache. */
final class CodingInputText {
    private final String reference;
    private final boolean batched;
    private final int offset, limit;
    private final ObjectMapper json;
    private final StringBuilder visible = new StringBuilder();
    private final ByteArrayOutputStream original = new ByteArrayOutputStream();
    private long characters;
    private int chunkIndex, chunkCount = -1;
    private String fileHash, fileName;
    private long fileSize;
    CodingInputText(String reference, boolean batched, int offset, int limit, ObjectMapper json) {
        this.reference=reference;this.batched=batched;this.offset=offset;this.limit=limit;this.json=json;
    }
    void accept(Map<String,Object> envelope) {
        if (batched && !reference.equals(envelope.get("datasetReference"))) return;
        Object value = batched ? envelope.get("record") : envelope;
        if (!(value instanceof Map<?,?> row)) throw new SecurityException("Invalid delivered record");
        if (Set.of("SOURCE_FILE","UNTRUSTED_HISTORICAL_CONTEXT").contains(Objects.toString(row.get("role"),""))
                && row.get("contentBase64") instanceof String encoded) {
            if (characters != 0 || !(row.get("chunkIndex") instanceof Number index) || index.intValue() != chunkIndex
                    || !(row.get("chunkCount") instanceof Number count) || count.intValue() < 1)
                throw new SecurityException("Original file chunks are incomplete or out of order");
            if (chunkIndex == 0) {
                chunkCount=count.intValue();fileHash=Objects.toString(row.get("sha256"),"");
                fileName=Objects.toString(row.get("fileName"),"");
                if (!(row.get("size") instanceof Number size)) throw new SecurityException("Missing file size");
                fileSize=size.longValue();
            }
            if (count.intValue()!=chunkCount || !fileHash.equals(row.get("sha256")) || !fileName.equals(row.get("fileName"))
                    || !(row.get("size") instanceof Number size) || size.longValue()!=fileSize)
                throw new SecurityException("Original file binding changed");
            byte[] bytes=Base64.getDecoder().decode(encoded);
            if (!com.chatchat.api.assets.UserAssetService.hash(bytes).equals(row.get("chunkSha256"))
                    || bytes.length > com.chatchat.api.assets.AssetAnalysisInputs.MAX_BYTES-original.size())
                throw new SecurityException("Original file chunk integrity or size differs");
            original.writeBytes(bytes);chunkIndex++;
            return;
        }
        if (chunkCount != -1) throw new SecurityException("Mixed original-file and record representations");
        try { append(json.writeValueAsString(row)+"\n"); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("Input record cannot be disclosed",invalid); }
    }
    private void append(String text) {
        int size=text.codePointCount(0,text.length());
        long start=Math.max(0,(long)offset-characters), end=Math.min(size,(long)offset+limit-characters);
        if(end>start)visible.append(text,text.offsetByCodePoints(0,(int)start),text.offsetByCodePoints(0,(int)end));
        characters+=size;
    }
    Map<String,Object> result(String snapshotHash) {
        if(chunkCount != -1) {
            byte[] bytes=originalBytes();
            if(!fileName.toLowerCase(Locale.ROOT).matches(".*\\.(txt|sql|md|markdown|log|csv|json|jsonl|xml|yaml|yml)$"))
                throw new IllegalArgumentException("Binary documents require an installed format reader in the controlled executor");
            try { append(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()); }
            catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("Text is not UTF-8; use the controlled executor with an explicit encoding",invalid);}
        }
        if(offset>characters)throw new IllegalArgumentException("Input offset exceeds text length");
        long next=Math.min(characters,(long)offset+limit);
        return Map.of("datasetReference",reference,"content",visible.toString(),"offset",offset,
            "nextOffset",next,"totalCharacters",characters,"eof",next==characters,
            "snapshotSha256",snapshotHash,"offsetUnit","UNICODE_CODE_POINTS","authority","UNTRUSTED_DATA_ONLY");
    }
    byte[] originalBytes() {
        if(chunkCount == -1)throw new IllegalArgumentException("Input is not an original file");
        byte[] bytes=original.toByteArray();
        if(chunkIndex!=chunkCount || bytes.length!=fileSize || !com.chatchat.api.assets.UserAssetService.hash(bytes).equals(fileHash))
            throw new SecurityException("Original file is incomplete or changed");
        return bytes;
    }
    String originalName() { return fileName; }
}
