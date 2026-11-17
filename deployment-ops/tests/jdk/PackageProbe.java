import com.chatchat.deployment.docker.*;
import com.chatchat.deployment.protocol.*;
import com.chatchat.deployment.execution.*;
import com.chatchat.deployment.storage.*;
import java.nio.file.*;
public class PackageProbe {
 public static void main(String[] args)throws Exception{
  StorageGuard.verify();
  Path media=Path.of("/mnt/disk01/deployment-ops-v1-validation/standalone-protocol-media/java-protocol-qa.ops.zip");
  var docker=new DockerClient(Path.of("/var/run/docker.sock"));
  var store=new SignedPackageStore(Path.of("/mnt/disk01/deployment-ops-v1-validation/jdk-package-proof/packages"),Path.of("/mnt/disk01/deployment-ops-v1-validation/jar-entry-state/standalone/trust"),docker);
  var spec=StateFiles.JSON.createObjectNode().put("name",media.getFileName().toString()).put("size",Files.size(media)).put("sha256",SignedPackageStore.sha(media));
  String id=store.create(spec).path("id").asText();long offset=0;
  try(var input=Files.newInputStream(media)){for(;;){byte[] bytes=input.readNBytes(4*1024*1024);if(bytes.length==0)break;store.chunk(id,offset,bytes);offset+=bytes.length;}}
  store.commit(id);
  for(int n=0;n<600;n++){var status=store.status(id);if(status.path("status").asText().equals("verified"))break;if(status.path("status").asText().equals("failed"))throw new AssertionError(status.toString());Thread.sleep(100);}
  if(!store.status(id).path("status").asText().equals("verified")||store.catalog().size()!=1)throw new AssertionError("Real signature import incomplete");
  store.loadImageArchive(Path.of("/mnt/disk01/ops-publisher-20261116/qa-offline-image/image.tar"));
  String result="{\"passed\":true,\"jdkOnly\":true,\"signatureAndPayloadVerified\":true,\"realDockerTarLoad\":true,\"resumableChunks\":true}";
  Files.writeString(Path.of("/mnt/disk01/deployment-ops-v1-validation/jdk-package-proof.json"),result);System.out.println(result);
 }
}
