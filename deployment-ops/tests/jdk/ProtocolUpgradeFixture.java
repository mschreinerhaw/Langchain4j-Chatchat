import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;

/** Produces compatible upgrade and deliberate readiness failure media for actual lifecycle tests. */
public class ProtocolUpgradeFixture {
    public static void main(String[] args)throws Exception {
        Path root=Path.of("/mnt/disk01/deployment-ops-v1-validation/standalone-protocol-media");ObjectMapper json=new ObjectMapper();
        for(boolean bad:List.of(false,true)) {
            String version=bad?"1.0.3":"1.0.2";Path target=root.resolve("java-protocol-qa-"+version+".ops.zip");
            if(Files.exists(target))throw new IOException("Publish once: "+target);
            try(ZipFile source=new ZipFile(root.resolve("java-protocol-qa-1.0.1.ops.zip").toFile())) {
                ObjectNode manifest=(ObjectNode)json.readTree(source.getInputStream(source.getEntry("manifest.json")));
                manifest.put("version",version).put("dataCompatibility","backward-compatible");manifest.set("upgradeFrom",json.createArrayNode().add("1.0.1").add("1.0.2"));
                for(JsonNode item:manifest.path("components"))if(!item.path("kind").asText().equals("job")) {
                    ObjectNode service=(ObjectNode)item;ObjectNode environment=service.has("environment")?(ObjectNode)service.path("environment"):json.createObjectNode();environment.put("OPS_UPGRADE_MARKER",version);service.set("environment",environment);
                    if(bad){ObjectNode health=(ObjectNode)service.path("health");health.put("type","tcp").put("port",6398).put("timeoutSeconds",5);}
                }
                byte[] manifestBytes=json.writeValueAsBytes(manifest);ObjectNode checks=(ObjectNode)json.readTree(source.getInputStream(source.getEntry("checksums.json")));checks.put("manifest.json",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)));byte[] inventory=json.writeValueAsBytes(checks);
                Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(root.resolve("private.der")))));signer.update(inventory);byte[] signature=json.writeValueAsBytes(Map.of("keyId","qa-jar-protocol","signature",Base64.getEncoder().encodeToString(signer.sign())));
                try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(target,StandardOpenOption.CREATE_NEW))) {
                    for(ZipEntry entry:Collections.list(source.entries())){ZipEntry copy=new ZipEntry(entry.getName());copy.setTime(0);out.putNextEntry(copy);if(entry.getName().equals("manifest.json"))out.write(manifestBytes);else if(entry.getName().equals("checksums.json"))out.write(inventory);else if(entry.getName().equals("signature.json"))out.write(signature);else try(InputStream in=source.getInputStream(entry)){in.transferTo(out);}out.closeEntry();}
                }
            }
            MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(target)){in.transferTo(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),hash));}
            System.out.println(json.writeValueAsString(Map.of("file",target.toString(),"sha256",HexFormat.of().formatHex(hash.digest()),"version",version)));
        }
    }
}
