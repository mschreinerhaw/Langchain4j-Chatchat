package com.chatchat.deployment.protocol;

import com.chatchat.deployment.execution.StateFiles;
import com.chatchat.deployment.storage.StorageGuard;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipFile;
import static com.chatchat.deployment.protocol.InstallationManifest.require;

/** Explicit local operator import; the running node still verifies the entire signed package. */
public final class LocalPackageImport {
    private LocalPackageImport() {}
    private static Path regular(String name)throws Exception {
        Path path=Path.of(name).toAbsolutePath().normalize();
        require(path.startsWith(Path.of("/mnt/disk01"))&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),"Offline media must be a regular file on /mnt/disk01");
        StorageGuard.managedDirectory(path.getParent());
        require(path.toRealPath().equals(path),"Offline media symlinks are forbidden");return path;
    }
    public static void run(String[] args)throws Exception {
        require(args.length>=3&&args.length%2==1,"Usage: --import-package FILE SHA256 [--image-cache DOCKER_SAVE_TAR]");
        Path archive=regular(args[1]);String digest=args[2];require(digest.matches("[a-f0-9]{64}"),"Publisher SHA-256 required");
        System.out.println("Checking complete offline package SHA-256...");
        require(SignedPackageStore.sha(archive).equals(digest),"Package SHA-256 mismatch");
        Path data=Path.of(System.getenv().getOrDefault("OPS_DATA_DIR","/mnt/disk01/deployment-ops/data")).toAbsolutePath().normalize();
        Path root=StorageGuard.managedDirectory(data.getParent().resolve("standalone"));Properties config=new Properties();
        try(var in=Files.newInputStream(root.resolve("instance.properties"))){config.load(in);}
        String token=config.getProperty("EXECUTOR_TOKEN");require(token!=null&&!token.isBlank(),"Initialize and start this JAR instance first");
        String port=System.getenv().getOrDefault("OPS_EXECUTOR_PORT","19242");require(port.matches("[0-9]{4,5}"),"Invalid local node port");
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        for(int option=3;option<args.length;option+=2){require(args[option].equals("--image-cache"),"Unknown local import option");Path cached=regular(args[option+1]);String hash=SignedPackageStore.sha(cached);boolean declared=false;
            try(ZipFile zip=new ZipFile(archive.toFile())){var member=zip.getEntry("checksums.json");require(member!=null&&member.getSize()<=2*1024*1024,"Missing bounded checksums");try(var in=zip.getInputStream(member)){JsonNode checks=StateFiles.JSON.readTree(in.readNBytes(2*1024*1024+1));var fields=checks.fields();while(fields.hasNext()){var field=fields.next();var entry=zip.getEntry(field.getKey());if(field.getKey().startsWith("images/")&&field.getValue().asText().equals(hash)&&entry!=null&&entry.getSize()==Files.size(cached))declared=true;}}}
            require(declared,"Image cache does not match this package inventory");Path blobs=StorageGuard.managedDirectory(root.resolve("jdk/packages/blobs")),target=blobs.resolve(hash);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))require(Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)&&SignedPackageStore.sha(target).equals(hash),"Existing cache is corrupt");else Files.createLink(target,cached);
            System.out.println("Matching image archive cached without duplicating data; signature verification remains mandatory.");
        }
        JsonNode spec=StateFiles.JSON.createObjectNode().put("name",archive.getFileName().toString()).put("size",Files.size(archive)).put("sha256",digest);
        JsonNode upload=request(client,port,token,"POST","/packages/uploads",spec);String id=upload.path("id").asText();require(id.matches("[a-f0-9]{32}"),"Invalid upload response");
        Path directory=StorageGuard.managedDirectory(root.resolve("jdk/packages/uploads")),destination=directory.resolve(id);
        require(Files.isRegularFile(destination,LinkOption.NOFOLLOW_LINKS)&&Files.size(destination)==0,"Upload staging changed");Path staged=directory.resolve(id+".local");
        try{Files.createLink(staged,archive);Files.move(staged,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(staged);}
        request(client,port,token,"POST","/packages/uploads/"+id+"/commit",StateFiles.JSON.createObjectNode());
        System.out.println("Upload "+id+" committed; verifying signature, payloads and actual Docker images.");
        long end=System.nanoTime()+Duration.ofHours(1).toNanos();String previous="";
        while(System.nanoTime()<end){JsonNode status=request(client,port,token,"GET","/packages/uploads/"+id,null);String state=status.path("status").asText();if(!state.equals(previous)){System.out.println("Import status: "+state);previous=state;}require(!state.equals("failed"),"Import failed: "+status.path("message").asText());if(state.equals("verified")){System.out.println("Verified package: "+digest);return;}Thread.sleep(1000);}
        throw new IllegalStateException("Import is still pending; query upload "+id+" before retrying");
    }
    private static JsonNode request(HttpClient client,String port,String token,String method,String path,JsonNode body)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+token).header("X-Ops-Actor","local-operator").header("X-Ops-Role","admin");
        if(method.equals("POST"))builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(StateFiles.JSON.writeValueAsBytes(body)));else builder.GET();
        var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());require(response.statusCode()==200,"Local import API failed (HTTP "+response.statusCode()+"): "+new String(response.body(),java.nio.charset.StandardCharsets.UTF_8));return StateFiles.JSON.readTree(response.body());
    }
}
