package com.chatchat.api.assets;

/** Private object transport. Only the asset service may turn authorized IDs into object keys. */
public interface AssetBlobStore {
    record Stored(String versionId) { }
    record ObjectInfo(String versionId,long size) { }
    record ObjectPart(String key,String versionId) { }
    Stored put(String key, byte[] bytes, String contentType, String sha256);
    byte[] get(String key, String versionId, long maxBytes);
    default void copy(String key,String versionId,long maxBytes,java.io.OutputStream output)throws java.io.IOException {
        output.write(get(key,versionId,maxBytes));
    }
    void delete(String key, String versionId);
    /** Machine-only, short-lived capabilities for exact task-generated objects. */
    default String presignRead(String key,String version,int seconds){throw new UnsupportedOperationException("Silo direct transfer unavailable");}
    default String presignWrite(String key,int seconds){throw new UnsupportedOperationException("Silo direct transfer unavailable");}
    default ObjectInfo stat(String key,String version){throw new UnsupportedOperationException("Silo direct transfer unavailable");}
    default Stored compose(String key,java.util.List<ObjectPart> parts){throw new UnsupportedOperationException("Silo compose unavailable");}
}
