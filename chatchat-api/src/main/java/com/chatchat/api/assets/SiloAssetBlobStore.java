package com.chatchat.api.assets;

import io.minio.*;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="chatchat.assets", name="enabled", havingValue="true")
public final class SiloAssetBlobStore implements AssetBlobStore {
    private final MinioClient client;
    private final String bucket;
    public SiloAssetBlobStore(AssetStorageProperties settings) {
        bucket = settings.getBucket();
        var http = new okhttp3.OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS).build();
        client = MinioClient.builder().endpoint(settings.getEndpoint()).region(settings.getRegion())
            .credentials(secret(settings.getAccessKeyFile(), settings.getAccessKey()),
                secret(settings.getSecretKeyFile(), settings.getSecretKey())).httpClient(http).build();
    }
    private static String secret(String file, String value) {
        try {
            String result = file == null || file.isBlank() ? value : Files.readString(Path.of(file)).strip();
            if (result == null || result.isBlank()) throw new IllegalStateException("Asset storage credentials required");
            return result;
        } catch (IOException e) { throw new IllegalStateException("Asset credential file unavailable", e); }
    }
    @Override public Stored put(String key, byte[] bytes, String type, String hash) {
        try (var input = new ByteArrayInputStream(bytes)) {
            var stored = client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                .stream(input, bytes.length, -1).contentType(type).userMetadata(Map.of("sha256", hash)).build());
            if (stored.versionId() == null || stored.versionId().isBlank() || "null".equals(stored.versionId()))
                throw new IllegalStateException("Asset bucket must have object versioning enabled");
            return new Stored(stored.versionId());
        } catch (Exception e) { throw new IllegalStateException("Asset object publication failed", e); }
    }
    @Override public byte[] get(String key, String version, long limit) {
        if (limit < 0 || limit > Integer.MAX_VALUE - 1) throw new IllegalArgumentException("Invalid object size bound");
        try (var input = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).versionId(version).build())) {
            byte[] data = input.readNBytes((int)limit + 1);
            if (data.length > limit) throw new IllegalStateException("Asset object exceeds registered length");
            return data;
        } catch (Exception e) { throw new IllegalStateException("Asset object read failed", e); }
    }
    @Override public void delete(String key, String version) {
        try { client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).versionId(version).build()); }
        catch (Exception e) { throw new IllegalStateException("Asset object deletion failed", e); }
    }
    @Override public void copy(String key,String version,long limit,OutputStream output)throws IOException {
        try(var input=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).versionId(version).build())){
            byte[] buffer=new byte[65536];long size=0;int count;
            while((count=input.read(buffer))!=-1){if(count>limit-size)throw new IOException("Asset exceeds registered length");output.write(buffer,0,count);size+=count;}
            if(size!=limit)throw new IOException("Asset length differs from registry");
        }catch(IOException failure){throw failure;}catch(Exception failure){throw new IOException("Asset object read failed",failure);}
    }
}
