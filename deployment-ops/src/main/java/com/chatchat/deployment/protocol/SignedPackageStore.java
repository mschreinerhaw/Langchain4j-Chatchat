package com.chatchat.deployment.protocol;

import com.chatchat.deployment.docker.*;
import com.chatchat.deployment.execution.StateFiles;
import com.chatchat.deployment.storage.StorageGuard;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.math.BigInteger;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static com.chatchat.deployment.protocol.InstallationManifest.require;

/** Resumable signed offline media. Payloads are regular files under the data mount. */
public final class SignedPackageStore implements AutoCloseable {
    private final StateFiles files;
    private final Path trust;
    private final DockerClient docker;
    private final java.util.concurrent.locks.ReentrantLock mutations;
    private final Set<String> importing=ConcurrentHashMap.newKeySet();
    private final Map<String,String> blobFingerprints=new ConcurrentHashMap<>();
    private final ExecutorService workers=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"jdk-package-verifier");t.setDaemon(true);return t;});
    public SignedPackageStore(Path root,Path trust,DockerClient docker)throws IOException{
        this(root,trust,docker,new java.util.concurrent.locks.ReentrantLock(true));
    }
    public SignedPackageStore(Path root,Path trust,DockerClient docker,java.util.concurrent.locks.ReentrantLock mutations)throws IOException{
        this.mutations=mutations;
        files=new StateFiles(root);this.trust=trust;this.docker=docker;Files.createDirectories(files.path("uploads"));Files.createDirectories(files.path("blobs"));
        for(JsonNode value:files.list("uploads"))if(value.path("status").asText().equals("verifying")){ObjectNode meta=(ObjectNode)value;meta.put("status","uploading");meta.put("message","Verification interrupted; recommit this complete upload");files.write("uploads/"+meta.path("id").asText()+".json",meta);}
    }
    public boolean busy(){return !importing.isEmpty();}
    public void free(long bytes)throws IOException{require(bytes>=0&&bytes<=Files.getFileStore(files.path(".")).getUsableSpace()-1024L*1024*1024,"Insufficient staging disk space; business images and volumes are never cleaned automatically");}
    private Path uploadFile(String id){require(id!=null&&id.matches("[a-f0-9]{32}"),"Invalid upload ID");return files.path("uploads/"+id);}
    public synchronized ObjectNode create(JsonNode spec)throws IOException{
        long size=spec.path("size").asLong();require(size>0&&size<=16L*1024*1024*1024,"Package size must be at most 16 GiB");String digest=spec.path("sha256").asText();require(digest.matches("[a-f0-9]{64}"),"SHA-256 required");free(size);
        String id=StateFiles.id();ObjectNode meta=StateFiles.JSON.createObjectNode().put("id",id).put("name",spec.path("name").asText("package.zip")).put("size",size).put("sha256",digest).put("status","uploading");
        files.write("uploads/"+id+".json",meta);Files.createFile(uploadFile(id));Files.setPosixFilePermissions(uploadFile(id),PosixFilePermissions.fromString("rw-------"));return status(id);
    }
    public synchronized ObjectNode status(String id)throws IOException{Path file=uploadFile(id);ObjectNode meta=(ObjectNode)files.read("uploads/"+id+".json");meta.put("offset",Files.exists(file)?Files.size(file):meta.path("size").asLong());return meta;}
    public synchronized ObjectNode chunk(String id,long offset,byte[] bytes)throws IOException{
        ObjectNode meta=status(id);require(meta.path("status").asText().equals("uploading"),"Upload already committed");require(meta.path("offset").asLong()==offset,"Offset changed; query upload and resume");require(bytes.length>0&&bytes.length<=4*1024*1024&&offset+bytes.length<=meta.path("size").asLong(),"Invalid upload chunk");free(bytes.length);StorageGuard.verifyRuntime();
        try(OutputStream out=Files.newOutputStream(uploadFile(id),StandardOpenOption.APPEND)){out.write(bytes);}return status(id);
    }
    public synchronized ObjectNode commit(String id)throws IOException{
        ObjectNode meta=status(id);
        require(meta.path("offset").asLong()==meta.path("size").asLong(),"Upload incomplete");require(importing.add(id),"Package already verifying");meta.put("status","verifying");files.write("uploads/"+id+".json",meta);
        workers.submit(()->{boolean locked=false;try{mutations.lockInterruptibly();locked=true;verify(id);}catch(Exception error){try{ObjectNode failed=status(id);failed.put("status","failed").put("message",error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());files.write("uploads/"+id+".json",failed);}catch(IOException ignored){}}finally{if(locked)mutations.unlock();importing.remove(id);}});return meta;
    }
    public static String sha(Path path)throws IOException{try(InputStream input=Files.newInputStream(path)){try{MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] block=new byte[1024*1024];for(int count;(count=input.read(block))!=-1;){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Offline verification interrupted; recommit to resume");hash.update(block,0,count);}return HexFormat.of().formatHex(hash.digest());}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static byte[] metadata(ZipFile zip,String name)throws IOException{ZipEntry entry=zip.getEntry(name);require(entry!=null&&entry.getSize()>=0&&entry.getSize()<=2*1024*1024,"Missing or oversized "+name);try(InputStream in=zip.getInputStream(entry)){return in.readNBytes(2*1024*1024+1);}}
    private JsonNode signingKey(JsonNode signature,byte[] checksums)throws Exception{
        String id=InstallationManifest.name(signature.path("keyId").asText());JsonNode key=StateFiles.JSON.readTree(Files.readAllBytes(trust.resolve(id+".json")));
        BigInteger n=new BigInteger(key.path("n").asText(),16),e=new BigInteger(key.path("e").asText(),16);require(n.bitLength()>=2048&&e.compareTo(BigInteger.valueOf(65537))>=0,"Weak signing key");
        Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n,e)));verifier.update(checksums);require(verifier.verify(Base64.getDecoder().decode(signature.path("signature").asText())),"Invalid package signature");return key;
    }
    private void authorize(JsonNode manifest,JsonNode key){
        if(key.has("allowedPackages")){boolean found=false;for(JsonNode name:key.path("allowedPackages"))found|=name.asText().equals(manifest.path("name").asText());require(found,"Signer not authorized for this package");}
        if(manifest.has("runtimeInstaller")){boolean found=false;for(JsonNode capability:key.path("capabilities"))found|=capability.asText().equals("runtime-os-native/v1");require(found,"Native installer requires a dedicated trusted capability");}
    }
    private void verify(String id)throws Exception{
        ObjectNode meta=status(id);Path archive=uploadFile(id);String digest=meta.path("sha256").asText();String base="packages/"+digest+"/";
        if(Files.exists(archive))require(sha(archive).equals(digest),"Offline package SHA-256 mismatch");
        if(Files.isRegularFile(files.path(base+"receipt.json"))){JsonNode manifest=verifyStored(digest);ensureImages(digest,manifest);verified(id);return;}
        require(Files.isRegularFile(archive,LinkOption.NOFOLLOW_LINKS),"Upload staging unavailable; upload the offline media again");
        try(ZipFile zip=new ZipFile(archive.toFile())){
            List<? extends ZipEntry> entries=Collections.list(zip.entries());require(entries.size()<=4096,"Too many package members");Set<String> names=new HashSet<>();long expanded=0;
            for(ZipEntry entry:entries){String name=InstallationManifest.member(entry.getName());require(!entry.isDirectory()&&names.add(name)&&!Set.of("receipt.json","retired.json").contains(name),"Duplicate, directory or reserved archive member");require(entry.getSize()>=0&&entry.getSize()<=32L*1024*1024*1024,"Oversized payload");expanded=Math.addExact(expanded,entry.getSize());require(expanded<=32L*1024*1024*1024,"Inflated package too large");}
            byte[] checkBytes=metadata(zip,"checksums.json");JsonNode checks=StateFiles.JSON.readTree(checkBytes),signature=StateFiles.JSON.readTree(metadata(zip,"signature.json"));JsonNode key=signingKey(signature,checkBytes);
            Set<String> expected=new HashSet<>(names);expected.remove("signature.json");expected.remove("checksums.json");Set<String> declared=new HashSet<>();checks.fieldNames().forEachRemaining(declared::add);require(checks.isObject()&&expected.equals(declared),"Signature must cover every payload");checks.fields().forEachRemaining(entry->require(entry.getValue().asText().matches("[a-f0-9]{64}"),"Invalid payload checksum"));
            JsonNode manifest=StateFiles.JSON.readTree(metadata(zip,"manifest.json"));require(sha(metadata(zip,"manifest.json")).equals(checks.path("manifest.json").asText()),"Manifest checksum mismatch");InstallationManifest.validate(manifest,declared);authorize(manifest,key);
            for(JsonNode prior:catalog())require(!prior.path("name").asText().equals(manifest.path("name").asText())||!prior.path("version").asText().equals(manifest.path("version").asText())||prior.path("digest").asText().equals(digest),"Immutable version already exists; publish another version");
            long needed=0;for(ZipEntry entry:entries)if(!entry.getName().startsWith("images/")||verifiedBlob(checks.path(entry.getName()).asText(),entry.getSize())==null)needed=Math.addExact(needed,entry.getSize());free(needed);
            for(ZipEntry entry:entries){StorageGuard.verifyRuntime();Path destination=files.path(base+entry.getName());Files.createDirectories(destination.getParent());require(!Files.isSymbolicLink(destination)&&destination.getParent().toRealPath().startsWith(files.path(".").toRealPath()),"Symlink payload forbidden");
                String checksum=checks.path(entry.getName()).asText();Path cached=entry.getName().startsWith("images/")?verifiedBlob(checksum,entry.getSize()):null;
                if(cached!=null){Path alias=destination.resolveSibling("link-"+StateFiles.id());try{Files.createLink(alias,cached);Files.move(alias,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(alias);}blobFingerprints.put(checksum,blobVector(cached));continue;}
                Path temporary=Files.createTempFile(destination.getParent(),"payload-",".tmp");
                try{
                    try(InputStream in=zip.getInputStream(entry);OutputStream out=Files.newOutputStream(temporary,StandardOpenOption.TRUNCATE_EXISTING)){Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));byte[] block=new byte[1024*1024];long count=0;for(int n;(n=in.read(block))!=-1;){count+=n;require(count<=entry.getSize(),"Oversized ZIP member");out.write(block,0,n);}require(count==entry.getSize(),"Truncated ZIP member");}
                    if(checks.has(entry.getName()))require(sha(temporary).equals(checksum),"Payload checksum mismatch: "+entry.getName());
                    Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    if(entry.getName().startsWith("images/")){Path blob=files.path("blobs/"+checksum);if(!Files.exists(blob,LinkOption.NOFOLLOW_LINKS))Files.createLink(blob,destination);else verifiedBlob(checksum,entry.getSize());blobFingerprints.put(checksum,blobVector(blob));}
                }finally{Files.deleteIfExists(temporary);}
            }
            Set<String> loaded=new HashSet<>();for(JsonNode image:manifest.path("images")){
                Path imageArchive=files.path(base+image.path("archive").asText());
                try{resolveFromArchive(imageArchive,image.path("id").asText());}
                catch(IOException unavailable){if(loaded.add(imageArchive.toString()))loadImageArchive(imageArchive);resolveFromArchive(imageArchive,image.path("id").asText());}
            }
            for(JsonNode prior:catalog())require(!prior.path("name").asText().equals(manifest.path("name").asText())||!prior.path("version").asText().equals(manifest.path("version").asText())||prior.path("digest").asText().equals(digest),"Immutable version already exists; publish another version");
            ObjectNode receipt=StateFiles.JSON.createObjectNode().put("keyId",signature.path("keyId").asText()).put("verifiedAt",StateFiles.now());receipt.set("checksums",checks);files.write(base+"receipt.json",receipt);verified(id);
        }
    }
    private JsonNode verifyStored(String digest)throws Exception {
        Path directory=folder(digest);require(Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS),"Invalid stored package directory");
        for(String name:List.of("checksums.json","signature.json","receipt.json","manifest.json")){Path path=directory.resolve(name);require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)<=2*1024*1024,"Missing or oversized stored package metadata");}
        byte[] inventory=Files.readAllBytes(directory.resolve("checksums.json"));JsonNode checks=StateFiles.JSON.readTree(inventory),signature=StateFiles.JSON.readTree(Files.readAllBytes(directory.resolve("signature.json"))),receipt=StateFiles.JSON.readTree(Files.readAllBytes(directory.resolve("receipt.json")));JsonNode key=signingKey(signature,inventory);
        require(checks.isObject()&&checks.size()>0&&checks.size()<=4094&&checks.equals(receipt.path("checksums"))&&signature.path("keyId").equals(receipt.path("keyId")),"Stored verification receipt differs from signed inventory");
        Set<String> names=new HashSet<>();var entries=checks.fields();while(entries.hasNext()){
            var entry=entries.next();String name=InstallationManifest.member(entry.getKey()),checksum=entry.getValue().asText();require(checksum.matches("[a-f0-9]{64}")&&names.add(name),"Invalid stored payload inventory");Path payload=directory.resolve(name);require(Files.isRegularFile(payload,LinkOption.NOFOLLOW_LINKS)&&payload.toRealPath().startsWith(directory.toRealPath()),"Stored payload unavailable; restore the offline package cache");
            Path blob=name.startsWith("images/")?verifiedBlob(checksum,Files.size(payload)):null;
            if(blob==null||!Files.isSameFile(blob,payload))require(sha(payload).equals(checksum),"Stored payload checksum mismatch; restore the verified offline package cache");
        }
        JsonNode manifest=StateFiles.JSON.readTree(Files.readAllBytes(directory.resolve("manifest.json")));InstallationManifest.validate(manifest,names);authorize(manifest,key);return manifest;
    }
    private String blobVector(Path blob)throws IOException{BasicFileAttributes attributes=Files.readAttributes(blob,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);String change;try{change=String.valueOf(Files.getAttribute(blob,"unix:ctime",LinkOption.NOFOLLOW_LINKS));}catch(UnsupportedOperationException unsupported){change=attributes.creationTime().toString();}return attributes.fileKey()+":"+attributes.size()+":"+attributes.lastModifiedTime()+":"+change;}
    private Path verifiedBlob(String checksum,long size)throws IOException{
        require(checksum.matches("[a-f0-9]{64}"),"Invalid image archive identity");Path blob=files.path("blobs/"+checksum);if(!Files.exists(blob,LinkOption.NOFOLLOW_LINKS))return null;require(Files.isRegularFile(blob,LinkOption.NOFOLLOW_LINKS)&&Files.size(blob)==size,"Invalid cached image archive");String vector=blobVector(blob);if(!vector.equals(blobFingerprints.get(checksum)))require(sha(blob).equals(checksum),"Cached image archive is corrupt");blobFingerprints.put(checksum,vector);return blob;
    }
    private void ensureImages(String digest,JsonNode manifest)throws IOException{
        Set<String> loaded=new HashSet<>();JsonNode checks=StateFiles.JSON.readTree(Files.readAllBytes(folder(digest).resolve("receipt.json"))).path("checksums");for(JsonNode image:manifest.path("images")){
            String archive=image.path("archive").asText();Path path=folder(digest).resolve(InstallationManifest.member(archive));
            try{resolveFromArchive(path,image.path("id").asText());}
            catch(IOException unavailable){if(loaded.add(archive)){require(sha(path).equals(checks.path(archive).asText()),"Stored image archive changed");loadImageArchive(path);}resolveFromArchive(path,image.path("id").asText());}
        }
    }
    private final Map<String,Map<String,Set<String>>> archiveIdentities=new ConcurrentHashMap<>();
    private String resolveFromArchive(Path archive,String signedId)throws IOException{
        try{if(docker.call("GET","/images/"+signedId+"/json").path("Id").asText().equals(signedId))return signedId;}catch(IOException missing){}
        String vector=archive.toString()+":"+blobVector(archive);Map<String,Set<String>> identities=archiveIdentities.get(vector);
        if(identities==null){identities=ArchiveImageIdentities.read(archive);archiveIdentities.put(vector,identities);}
        for(String candidate:identities.getOrDefault(signedId,Set.of()))try{if(docker.call("GET","/images/"+candidate+"/json").path("Id").asText().equals(candidate))return candidate;}catch(IOException missing){}
        throw new IOException("Signed image unavailable in Docker: "+signedId);
    }
    public String resolvedImageId(String digest,String signedId)throws IOException{
        JsonNode manifest=packageManifest(digest);
        for(JsonNode image:manifest.path("images"))if(image.path("id").asText().equals(signedId)){
            String archive=InstallationManifest.member(image.path("archive").asText());Path path=folder(digest).resolve(archive);
            String checksum=StateFiles.JSON.readTree(Files.readAllBytes(folder(digest).resolve("receipt.json"))).path("checksums").path(archive).asText();
            Path verified=verifiedBlob(checksum,Files.size(path));require(verified!=null&&Files.isSameFile(verified,path),"Image archive verification cache unavailable");
            return resolveFromArchive(path,signedId);
        }
        throw new IOException("Unsigned image identity");
    }

    private void verified(String id)throws IOException{ObjectNode meta=status(id);meta.put("status","verified");meta.remove("message");files.write("uploads/"+id+".json",meta);Files.deleteIfExists(uploadFile(id));meta.put("stagingReclaimed",true).put("stagingReclaimedAt",StateFiles.now());files.write("uploads/"+id+".json",meta);}
    public Path folder(String digest){require(digest!=null&&digest.matches("[a-f0-9]{64}"),"Invalid package identity");return files.path("packages/"+digest);}
    public JsonNode packageManifest(String digest)throws IOException{
        Path folder=folder(digest);JsonNode receipt=StateFiles.JSON.readTree(Files.readAllBytes(folder.resolve("receipt.json")));Path manifest=folder.resolve("manifest.json");require(sha(manifest).equals(receipt.path("checksums").path("manifest.json").asText()),"Stored manifest changed");return StateFiles.JSON.readTree(Files.readAllBytes(manifest));
    }
    public byte[] asset(String digest,String name)throws IOException{Path file=folder(digest).resolve(InstallationManifest.member(name));JsonNode checks=StateFiles.JSON.readTree(Files.readAllBytes(folder(digest).resolve("receipt.json"))).path("checksums");require(checks.has(name)&&Files.size(file)<=16*1024*1024,"Unsigned or oversized config asset");byte[] bytes=Files.readAllBytes(file);require(sha(bytes).equals(checks.path(name).asText()),"Config asset changed");return bytes;}
    public boolean retired(String digest){return Files.exists(folder(digest).resolve("retired.json"));}
    public ObjectNode retire(String digest,String actor)throws IOException{packageManifest(digest);ObjectNode value=StateFiles.JSON.createObjectNode().put("retired",true).put("actor",actor);files.write("packages/"+digest+"/retired.json",value);return value;}
    public ArrayNode catalog()throws IOException{
        ArrayNode result=StateFiles.JSON.createArrayNode();Path root=files.path("packages");if(!Files.isDirectory(root))return result;
        try(var directories=Files.list(root)){for(Path path:directories.sorted().toList()){String digest=path.getFileName().toString();if(!digest.matches("[a-f0-9]{64}")||!Files.exists(path.resolve("receipt.json"))||retired(digest))continue;JsonNode manifest=packageManifest(digest);ObjectNode entry=StateFiles.JSON.createObjectNode().put("digest",digest).put("components",manifest.path("components").size());for(String key:List.of("name","version","description","parameters","platform","requirements"))if(manifest.has(key))entry.set(key,manifest.get(key));entry.set("installationProtocol",manifest);entry.put("keyId",StateFiles.JSON.readTree(Files.readAllBytes(path.resolve("receipt.json"))).path("keyId").asText());result.add(entry);}}return result;
    }
    public void loadImageArchive(Path path)throws IOException{
        JsonNode manifest=StateFiles.JSON.readTree(TarArchive.entry(path,"manifest.json",1024*1024));require(manifest.isArray()&&manifest.size()>0,"Docker-save archive required");Map<String,Set<String>> identities=ArchiveImageIdentities.read(path);Map<String,String> tags=new HashMap<>();for(JsonNode image:docker.call("GET","/images/json"))for(JsonNode tag:image.path("RepoTags"))tags.put(tag.asText(),image.path("Id").asText());
        for(JsonNode image:manifest){String config=InstallationManifest.member(image.path("Config").asText());String identity="sha256:"+sha(TarArchive.entry(path,config,16*1024*1024));for(JsonNode tag:image.path("RepoTags"))require(!tags.containsKey(tag.asText())||(tags.get(tag.asText()).equals(identity)||identities.getOrDefault(identity,Set.of()).contains(tags.get(tag.asText()))),"Archive would overwrite an existing image tag");}
        try(InputStream input=Files.newInputStream(path);DockerClient.Response response=docker.request("POST","/images/load?quiet=1",input,Files.size(path),"application/x-tar",Duration.ofMinutes(30))){require(response.status<300,"Docker rejected image import");byte[] bytes=response.body.readNBytes(4*1024*1024+1);require(bytes.length<=4*1024*1024,"Oversized Docker load response");for(String line:new String(bytes,java.nio.charset.StandardCharsets.UTF_8).split("\n"))if(!line.isBlank()){JsonNode record=StateFiles.JSON.readTree(line);require(!record.has("error"),"Docker image import failed: "+record.path("error").asText());}}
    }
    public void close(){workers.shutdownNow();try{workers.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
}
