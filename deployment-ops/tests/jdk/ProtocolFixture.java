import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;

/** Test publisher: regular signing key, explicit named data volume, no credentials. */
public class ProtocolFixture {
    public static void main(String[] args)throws Exception{
        Path root=Path.of("/mnt/disk01/deployment-ops-v1-validation/standalone-protocol-media");Path target=root.resolve("java-protocol-qa-1.0.1.ops.zip");ObjectMapper json=new ObjectMapper();
        if(!Files.exists(target))try(ZipFile source=new ZipFile(root.resolve("java-protocol-qa.ops.zip").toFile())){
            ObjectNode manifest=(ObjectNode)json.readTree(source.getInputStream(source.getEntry("manifest.json")));manifest.put("version","1.0.1");ObjectNode volumes=json.createObjectNode();volumes.set("service-data",json.createObjectNode().put("description","Explicit data-disk mapping for image-declared /data"));manifest.set("volumes",volumes);
            for(JsonNode c:manifest.path("components")){ArrayNode mounts=json.createArrayNode();mounts.add(json.createObjectNode().put("volume","service-data").put("target","/data"));((ObjectNode)c).set("mounts",mounts);}
            byte[] manifestBytes=json.writeValueAsBytes(manifest);ObjectNode checks=(ObjectNode)json.readTree(source.getInputStream(source.getEntry("checksums.json")));checks.put("manifest.json",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)));byte[] inventory=json.writeValueAsBytes(checks);
            Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(root.resolve("private.der")))));signer.update(inventory);byte[] signature=json.writeValueAsBytes(Map.of("keyId","qa-jar-protocol","signature",Base64.getEncoder().encodeToString(signer.sign())));
            try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(target,StandardOpenOption.CREATE_NEW))){for(ZipEntry entry:Collections.list(source.entries())){ZipEntry copy=new ZipEntry(entry.getName());copy.setTime(0);out.putNextEntry(copy);if(entry.getName().equals("manifest.json"))out.write(manifestBytes);else if(entry.getName().equals("checksums.json"))out.write(inventory);else if(entry.getName().equals("signature.json"))out.write(signature);else try(InputStream input=source.getInputStream(entry)){input.transferTo(out);}out.closeEntry();}}
        }
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(target)){byte[] block=new byte[65536];for(int n;(n=in.read(block))!=-1;)digest.update(block,0,n);}Files.writeString(root.resolve("jdk-media.json"),json.writeValueAsString(Map.of("file",target.toString(),"sha256",HexFormat.of().formatHex(digest.digest()))));System.out.println("JDK protocol fixture signed with explicit persistent mapping; no private key packaged");
    }
}
