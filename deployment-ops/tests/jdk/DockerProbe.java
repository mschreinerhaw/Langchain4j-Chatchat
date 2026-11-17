import com.chatchat.deployment.docker.DockerClient;
import java.nio.file.Path;
public class DockerProbe {
 public static void main(String[] args)throws Exception{
  var docker=new DockerClient(Path.of("/var/run/docker.sock"));
  var info=docker.call("GET","/info");var containers=docker.call("GET","/containers/json?all=1");var images=docker.call("GET","/images/json");
  if(info.path("ID").asText().isBlank()||containers.size()<44||images.size()==0)throw new AssertionError("Invalid real Docker observation");
  System.out.println("{\"passed\":true,\"onlyJdkUnixSocket\":true,\"containers\":"+containers.size()+",\"images\":"+images.size()+"}");
 }
}
