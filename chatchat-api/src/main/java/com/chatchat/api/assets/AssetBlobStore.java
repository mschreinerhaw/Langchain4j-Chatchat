package com.chatchat.api.assets;

/** Private object transport. Only the asset service may turn authorized IDs into object keys. */
public interface AssetBlobStore {
    record Stored(String versionId) { }
    Stored put(String key, byte[] bytes, String contentType, String sha256);
    byte[] get(String key, String versionId, long maxBytes);
    default void copy(String key,String versionId,long maxBytes,java.io.OutputStream output)throws java.io.IOException {
        output.write(get(key,versionId,maxBytes));
    }
    void delete(String key, String versionId);
}
