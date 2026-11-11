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
public final class SiloAssetBlobStore implements AssetBlobStore, AutoCloseable {
    private final MinioClient client;
    private final okhttp3.OkHttpClient http;
    private final String bucket;
    public SiloAssetBlobStore(AssetStorageProperties settings) {
        bucket = settings.getBucket();
        http = new okhttp3.OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS).build();
        client = MinioClient.builder().endpoint(settings.getEndpoint()).region(settings.getRegion())
            .credentials(secret(settings.getAccessKeyFile(), settings.getAccessKey()),
                secret(settings.getSecretKeyFile(), settings.getSecretKey())).httpClient(http).build();
    }
    /** Release this store's HTTP resources after its owner has stopped work.
     * In particular, standalone acceptance fixtures must return normally rather
     * than force JVM exit to bypass the connection pool's idle lifetime.
     */
    @Override public void close(){
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
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
        } catch (io.minio.errors.ErrorResponseException e) {
            var error=e.errorResponse();
            if(error!=null&&(java.util.Set.of("XMinioStorageFull","StorageFull","InsufficientStorage").contains(error.code())
                    || "Storage backend has reached its minimum free drive threshold. Please delete a few objects to proceed.".equals(error.message())))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.INSUFFICIENT_STORAGE,
                    "ARTIFACT_STORAGE_FULL: 本次文件发布被存储容量保护拒绝。请释放存储空间后核对当前任务状态，再恢复原请求；此错误不代表业务数据不存在。",e);
            throw new IllegalStateException("Asset object publication failed",e);
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
    private static int expiry(int seconds){if(seconds<1||seconds>900)throw new IllegalArgumentException("Task capability expiry must be 1..900 seconds");return seconds;}
    @Override public String presignRead(String key,String version,int seconds){
        if(version==null||version.isBlank()||"null".equals(version))throw new IllegalArgumentException("Immutable object version required");
        try{return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(io.minio.http.Method.GET)
            .bucket(bucket).object(key).versionId(version).expiry(expiry(seconds)).build());}
        catch(Exception failure){throw new IllegalStateException("Silo input capability unavailable",failure);}
    }
    @Override public String presignWrite(String key,int seconds){
        try{return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(io.minio.http.Method.PUT)
            .bucket(bucket).object(key).expiry(expiry(seconds)).build());}
        catch(Exception failure){throw new IllegalStateException("Silo output capability unavailable",failure);}
    }
    @Override public ObjectInfo stat(String key,String version){
        if(version==null||version.isBlank()||"null".equals(version))throw new IllegalArgumentException("Immutable object version required");
        try{var info=client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).versionId(version).build());return new ObjectInfo(info.versionId(),info.size());}
        catch(Exception failure){throw new IllegalStateException("Silo object metadata unavailable",failure);}
    }
    @Override public Stored compose(String key,java.util.List<ObjectPart> parts){
        if(parts==null||parts.isEmpty()||parts.size()>10000)throw new IllegalArgumentException("Invalid Silo part count");
        try{
            var sources=new java.util.ArrayList<ComposeSource>();
            for(var part:parts){if(part.versionId()==null||part.versionId().isBlank()||"null".equals(part.versionId()))throw new IllegalArgumentException("Immutable part version required");
                sources.add(ComposeSource.builder().bucket(bucket).object(part.key()).versionId(part.versionId()).build());}
            var result=client.composeObject(ComposeObjectArgs.builder().bucket(bucket).object(key).sources(sources).build());
            if(result.versionId()==null||"null".equals(result.versionId()))throw new IllegalStateException("Silo object versioning required");
            return new Stored(result.versionId());
        }catch(Exception failure){throw new IllegalStateException("Silo part composition failed",failure);}
    }
    @Override public void copy(String key,String version,long limit,OutputStream output)throws IOException {
        try(var input=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).versionId(version).build())){
            byte[] buffer=new byte[65536];long size=0;int count;
            while((count=input.read(buffer))!=-1){if(count>limit-size)throw new IOException("Asset exceeds registered length");output.write(buffer,0,count);size+=count;}
            if(size!=limit)throw new IOException("Asset length differs from registry");
        }catch(IOException failure){throw failure;}catch(Exception failure){throw new IOException("Asset object read failed",failure);}
    }
}
