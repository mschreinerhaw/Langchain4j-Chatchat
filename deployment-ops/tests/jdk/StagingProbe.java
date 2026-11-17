import com.chatchat.deployment.docker.*;
import com.chatchat.deployment.execution.*;
import com.chatchat.deployment.protocol.*;
import com.chatchat.deployment.storage.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.*;
import java.io.*;
import static com.chatchat.deployment.execution.ExecutionJson.*;

/** Actual signed import, staging reclamation, approved QA image deletion and cache-only cold repair. */
public class StagingProbe {
    static final String IMAGE="sha256:420230edb17e4614b38d745e7c1bb201dd45d05fb15c3eacc2325559c3669c44";
    static void waitFor(SignedPackageStore store,String id)throws Exception {
        for(int n=0;n<1200;n++){JsonNode status=store.status(id);if(status.path("status").asText().equals("failed"))throw new AssertionError(status.toString());if(status.path("status").asText().equals("verified")&&!store.busy())return;Thread.sleep(100);}throw new AssertionError("Verification timed out");
    }
    static JsonNode execute(ResourceManager manager,ObjectNode spec)throws Exception {
        JsonNode plan=manager.plan(spec,"jdk-staging-proof");JsonNode job=manager.execute(object().put("planId",plan.path("id").asText()).put("confirmation",plan.path("target").asText()).put("idempotencyKey","jdk-staging-"+StateFiles.id()),"jdk-staging-proof");
        for(int i=0;i<300;i++){job=manager.job(job.path("id").asText());if(job.path("status").asText().equals("SUCCESS"))return job;if(job.path("status").asText().equals("FAILED"))throw new AssertionError(job.toString());Thread.sleep(100);}throw new AssertionError("Approved deletion timed out");
    }
    static Map<String,String> running(DockerClient docker)throws Exception {
        Map<String,String> result=new TreeMap<>();for(JsonNode c:docker.call("GET","/containers/json?all=1"))if(c.path("State").asText().equals("running"))result.put(c.path("Id").asText(),docker.call("GET","/containers/"+c.path("Id").asText()+"/json").path("State").path("StartedAt").asText());return result;
    }
    public static void main(String[] args)throws Exception {
        StorageGuard.verify();Path root=Path.of("/mnt/disk01/deployment-ops-v1-validation/jdk-staging-proof");Files.createDirectories(root);DockerClient docker=new DockerClient(Path.of("/var/run/docker.sock"));JsonNode info=docker.call("GET","/images/"+IMAGE+"/json");if(!info.path("Config").path("Labels").path("deployment-ops.qa").asText().equals("jdk-image-acceptance"))throw new AssertionError("Unexpected QA image scope");Map<String,String> before=running(docker);
        Path publisher=Path.of("/mnt/disk01/deployment-ops-v1-validation/standalone-protocol-media"),media=root.resolve("java-staging-proof.ops.zip"),tar=Path.of("/mnt/disk01/deployment-ops-v1-validation/jdk-resource-proof/image-1794884053940.tar");
        if(!Files.exists(media))try(ZipFile original=new ZipFile(publisher.resolve("java-protocol-qa-1.0.1.ops.zip").toFile())) {
            ObjectNode manifest=(ObjectNode)StateFiles.JSON.readTree(original.getInputStream(original.getEntry("manifest.json")));manifest.put("version","1.0.4-staging-qa");ObjectNode checks=(ObjectNode)StateFiles.JSON.readTree(original.getInputStream(original.getEntry("checksums.json")));
            for(JsonNode image:manifest.path("images")){((ObjectNode)image).put("id",IMAGE).put("size",info.path("Size").asLong());checks.put(image.path("archive").asText(),SignedPackageStore.sha(tar));}
            byte[] definition=StateFiles.JSON.writeValueAsBytes(manifest);checks.put("manifest.json",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(definition)));byte[] inventory=StateFiles.JSON.writeValueAsBytes(checks);
            Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(publisher.resolve("private.der")))));signer.update(inventory);byte[] signature=StateFiles.JSON.writeValueAsBytes(Map.of("keyId","qa-jar-protocol","signature",Base64.getEncoder().encodeToString(signer.sign())));
            try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(media,StandardOpenOption.CREATE_NEW))){for(ZipEntry entry:Collections.list(original.entries())){ZipEntry copy=new ZipEntry(entry.getName());copy.setTime(0);zip.putNextEntry(copy);if(entry.getName().equals("manifest.json"))zip.write(definition);else if(entry.getName().equals("checksums.json"))zip.write(inventory);else if(entry.getName().equals("signature.json"))zip.write(signature);else if(entry.getName().startsWith("images/"))Files.copy(tar,zip);else try(InputStream in=original.getInputStream(entry)){in.transferTo(zip);}zip.closeEntry();}}
        }
        ReentrantLock lock=new ReentrantLock(true);
        try(ExecutionJournal journal=new ExecutionJournal(root.resolve("journal"),"jdk-staging-proof-private");SignedPackageStore store=new SignedPackageStore(root.resolve("packages"),Path.of("/mnt/disk01/deployment-ops-v1-validation/jdk-jar-state/standalone/trust"),docker,lock);ResourceManager manager=new ResourceManager(docker,journal,"jdk-staging-proof",root.resolve("apps"),lock,false)) {
            manager.installationBusy(store::busy);String id=store.create(object().put("name",media.getFileName().toString()).put("size",Files.size(media)).put("sha256",SignedPackageStore.sha(media))).path("id").asText();long offset=0;try(InputStream in=Files.newInputStream(media)){for(;;){byte[] chunk=in.readNBytes(4*1024*1024);if(chunk.length==0)break;store.chunk(id,offset,chunk);offset+=chunk.length;}}
            store.commit(id);waitFor(store,id);Path staged=root.resolve("packages/uploads/"+id);if(Files.exists(staged)||!store.status(id).path("stagingReclaimed").asBoolean())throw new AssertionError("Verified upload staging was retained");
            execute(manager,object().put("action","image.delete").put("id",IMAGE));try{docker.call("GET","/images/"+IMAGE+"/json");throw new AssertionError("QA image was not really deleted");}catch(IOException missing){if(!missing.getMessage().startsWith("Docker HTTP 404:"))throw missing;}
            store.commit(id);waitFor(store,id);if(!docker.call("GET","/images/"+IMAGE+"/json").path("Id").asText().equals(IMAGE)||Files.exists(staged))throw new AssertionError("Cache-only cold image repair failed");if(!before.equals(running(docker)))throw new AssertionError("A running business container changed");
            ObjectNode result=object().put("passed",true).put("jdkOnly",true).put("signatureAndStoredPayloadsReverified",true).put("verifiedStagingReclaimed",true).put("realQaImageDeletedWithApprovalAndAudit",true).put("coldImageRepairedWithoutOriginalUpload",true).put("runningBusinessIdsAndStartedAtUnchanged",true).put("upload",id);Files.writeString(root.resolve("result.json"),result.toPrettyString());System.out.println(result);
        }
    }
}
