import com.chatchat.deployment.docker.*;
import com.chatchat.deployment.execution.*;
import com.chatchat.deployment.protocol.*;
import com.chatchat.deployment.storage.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.io.*;
import java.time.Duration;
import static com.chatchat.deployment.execution.ExecutionJson.*;

/** Real, scoped Docker acceptance. Retains the newly created data volume. */
public class ResourceProbe {
    static JsonNode execute(ResourceManager manager,ObjectNode spec)throws Exception{
        JsonNode plan=manager.plan(spec,"jdk-acceptance");ObjectNode request=object().put("planId",plan.path("id").asText()).put("confirmation",plan.path("target").asText()).put("idempotencyKey","jdk-"+StateFiles.id());JsonNode job=manager.execute(request,"jdk-acceptance");
        for(int n=0;n<400;n++){job=manager.job(job.path("id").asText());if(job.path("status").asText().equals("SUCCESS")){if(!manager.execute(request,"jdk-acceptance").path("id").equals(job.path("id")))throw new AssertionError("Idempotency lost");return job;}if(job.path("status").asText().equals("FAILED"))throw new AssertionError(job.toString());Thread.sleep(100);}
        throw new AssertionError("Operation timed out");
    }
    static Map<String,String> running(DockerClient docker)throws Exception{
        Map<String,String> result=new TreeMap<>();for(JsonNode item:docker.call("GET","/containers/json?all=1"))if(item.path("State").asText().equals("running")){JsonNode state=docker.call("GET","/containers/"+item.path("Id").asText()+"/json").path("State");result.put(item.path("Id").asText(),state.path("StartedAt").asText());}return result;
    }
    public static void main(String[] args)throws Exception{
        StorageGuard.verify();Path root=Path.of("/mnt/disk01/deployment-ops-v1-validation/jdk-resource-proof");Files.createDirectories(root);DockerClient docker=new DockerClient(Path.of("/var/run/docker.sock"));Map<String,String> before=new TreeMap<>();List<String> checks=new ArrayList<>();String suffix=Long.toString(System.currentTimeMillis()),name="ops-jdk-proof-"+suffix,volume=name+"-data";
        String image=docker.call("GET","/images/ops-offline-import-qa:20261117/json").path("Id").asText();
        try(ExecutionJournal journal=new ExecutionJournal(root.resolve("journal"),"jdk-proof-only-password");ResourceManager manager=new ResourceManager(docker,journal,"jdk-resource-acceptance",root.resolve("apps"),new ReentrantLock(),false)){
            for(JsonNode prior:docker.call("GET","/containers/json?all=1"))if(prior.path("Labels").path("deployment-ops.owner").asText().equals("jdk-resource-acceptance")){
                String oldId=prior.path("Id").asText();JsonNode old=docker.call("GET","/containers/"+oldId+"/json");
                if(!old.path("Name").asText().startsWith("/ops-jdk-proof-")||!old.path("Image").asText().equals(image)||old.path("Mounts").isEmpty())throw new AssertionError("Unexpected prior QA scope");
                for(JsonNode mount:old.path("Mounts")){if(!mount.path("Type").asText().equals("volume")||!docker.call("GET","/volumes/"+mount.path("Name").asText()).path("Options").path("device").asText().startsWith(root.resolve("apps/volumes").toString()))throw new AssertionError("Unexpected QA data scope");}
                if(old.path("State").path("Running").asBoolean())execute(manager,object().put("action","container.stop").put("id",oldId));
                execute(manager,object().put("action","container.delete").put("id",oldId));
            }
            before.putAll(running(docker));
            execute(manager,object().put("action","volume.create").put("name",volume));JsonNode retained=docker.call("GET","/volumes/"+volume);if(!retained.path("Options").path("device").asText().startsWith(root.toString()))throw new AssertionError("Wrong volume filesystem");checks.add("real data-disk volume created after exact confirmation");
            ObjectNode spec=object().put("action","container.create").put("name",name).put("image",image).put("memoryMiB",32).put("cpus",.5).put("restart","no");spec.set("command",array().add("sh").add("-c").add("echo jdk-resource-live-log; echo \"$OPS_TEST_SECRET\"; sleep 600"));spec.set("environment",object().put("OPS_TEST_SECRET","qa-secret-do-not-return"));spec.set("volumes",array().add(object().put("source",volume).put("target","/data")));
            JsonNode preview=manager.plan(spec,"jdk-acceptance");if(preview.toString().contains("qa-secret-do-not-return"))throw new AssertionError("Secret in approval preview");String id=execute(manager,spec).path("result").path("Id").asText();checks.add("owned container created with CPU memory and persistent volume configuration");
            ObjectNode start=object().put("action","container.start").put("id",id);JsonNode stale=manager.plan(start,"jdk-acceptance");docker.call("POST","/containers/"+id+"/start");
            try{manager.execute(object().put("planId",stale.path("id").asText()).put("confirmation",name).put("idempotencyKey","jdk-"+StateFiles.id()),"jdk-acceptance");throw new AssertionError("Stale plan executed");}catch(IllegalArgumentException expected){checks.add("changed resource invalidates a previously reviewed plan");}
            execute(manager,object().put("action","container.restart").put("id",id));Thread.sleep(300);JsonNode logs=manager.get("/manager/logs/"+id);if(!logs.path("text").asText().contains("jdk-resource-live-log")||logs.toString().contains("qa-secret-do-not-return"))throw new AssertionError("Real log or redaction failed");checks.add("real logs decoded and credential environment values redacted");
            JsonNode metrics=manager.get("/manager/containers/"+id+"/metrics");if(metrics.path("memoryBytes").asLong()<=0)throw new AssertionError("No live memory metrics");checks.add("actual Docker resource metrics sampled");
            String external=before.keySet().iterator().next();try{manager.plan(object().put("action","container.stop").put("id",external),"jdk-acceptance");throw new AssertionError("External lifecycle approval allowed");}catch(IllegalArgumentException expected){checks.add("existing external business container mutations rejected");}
            execute(manager,object().put("action","container.stop").put("id",id));
            ObjectNode commit=object();commit.set("Labels",object().put("deployment-ops.qa","jdk-image-acceptance"));commit.set("Env",array().add("OPS_TEST_SECRET=cleared"));String newImage=docker.call("POST","/commit?container="+id+"&repo=ops-jdk-image-qa&tag="+suffix,commit).path("Id").asText();if(newImage.equals(image))throw new AssertionError("Distinct image required");
            execute(manager,object().put("action","container.delete").put("id",id));if(!docker.call("GET","/volumes/"+volume).path("Name").asText().equals(volume))throw new AssertionError("Persistent volume lost");checks.add("stop and non-forced delete preserve the data volume");
            execute(manager,object().put("action","image.tag").put("id",newImage).put("repository","ops-jdk-image-qa").put("tag","accepted-"+suffix));Path tar=root.resolve("image-"+suffix+".tar");try(DockerClient.Response response=docker.request("GET","/images/"+newImage+"/get",InputStream.nullInputStream(),0,"application/json",Duration.ofMinutes(3));OutputStream out=Files.newOutputStream(tar)){if(response.status!=200)throw new AssertionError("Export rejected");response.body.transferTo(out);}
            execute(manager,object().put("action","image.delete").put("id",newImage));try{docker.call("GET","/images/"+newImage+"/json");throw new AssertionError("Distinct QA image remains");}catch(IOException expected){}
            SignedPackageStore store=new SignedPackageStore(root.resolve("packages"),root.resolve("trust"),docker);store.loadImageArchive(tar);if(!docker.call("GET","/images/"+newImage+"/json").path("Id").asText().equals(newImage))throw new AssertionError("Offline image identity differs");checks.add("actual immutable tag, export, deletion and cold TAR reload of only a distinct QA image");
            String interrupted=StateFiles.id();journal.write("resource-jobs",interrupted,object().put("id",interrupted).put("status","RUNNING").put("action","container.start").put("target","never-execute-recovery-fixture"));
            try(ResourceManager recovered=new ResourceManager(docker,journal,"jdk-resource-acceptance",root.resolve("apps"),new ReentrantLock(),false)){if(!recovered.job(interrupted).path("status").asText().equals("UNKNOWN"))throw new AssertionError("Recovery replay risk");}checks.add("interrupted journal recovered to UNKNOWN without replay");
        }
        Map<String,String> after=running(docker);if(!before.equals(after))throw new AssertionError("Existing business identity or start time changed");ObjectNode report=object().put("passed",true).put("jdkOnly",true).put("allBusinessIdsAndStartTimesUnchanged",true).put("persistentVolumeRetained",volume);report.set("checks",StateFiles.JSON.valueToTree(checks));report.set("before",StateFiles.JSON.valueToTree(before));report.set("after",StateFiles.JSON.valueToTree(after));Files.writeString(root.resolve("result.json"),StateFiles.JSON.writeValueAsString(report));ObjectNode visible=report.deepCopy();visible.remove(List.of("before","after"));System.out.println(visible.toString());
    }
}
