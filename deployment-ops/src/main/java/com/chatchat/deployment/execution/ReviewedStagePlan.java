package com.chatchat.deployment.execution;

import com.chatchat.deployment.protocol.SignedPackageStore;
import com.chatchat.deployment.docker.DockerClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import static com.chatchat.deployment.protocol.InstallationManifest.require;

/** An administrator-registered private plan; secrets never enter the signed public archive. */
public final class ReviewedStagePlan {
    private ReviewedStagePlan(){}
    public static boolean enabled(JsonNode manifest){return manifest.path("runtimeInstaller").has("stagePlan");}
    public static Path path(JsonNode manifest)throws Exception{
        JsonNode entry=manifest.path("runtimeInstaller").path("stagePlan");String configured=System.getenv("OPS_STAGE_PLAN_DIR");
        require(configured!=null&&!configured.isBlank(),"Existing-stage deployment needs an administrator-registered OPS_STAGE_PLAN_DIR");
        String name=entry.path("name").asText();require(name.matches("[a-z0-9][a-z0-9-]{0,63}\\.json"),"Invalid private stage plan name");
        Path directory=Path.of(configured).toAbsolutePath().normalize(),file=directory.resolve(name);
        require(directory.toRealPath().equals(directory)&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"Stage plan must be a regular private file in the registered directory");
        require(SignedPackageStore.sha(file).equals(entry.path("sha256").asText()),"Private stage plan changed after signing; review and republish it");
        JsonNode plan=StateFiles.JSON.readTree(Files.readAllBytes(file));validate(plan,manifest);
        require(plan.path("sourceHashes").isObject()&&!plan.path("sourceHashes").isEmpty(),"Existing configuration fingerprints are required");
        var sources=plan.path("sourceHashes").fields();while(sources.hasNext()){var source=sources.next();require(SignedPackageStore.sha(Path.of(source.getKey())).equals(source.getValue().asText()),"Existing stage configuration changed after review; republish the private plan");}
        require(plan.path("stageRoot").asText().equals(System.getenv("OPS_STAGE_ROOT")),"Stage root was not explicitly registered by the administrator");
        return file;
    }
    public static void validate(JsonNode plan,JsonNode manifest){
        require(plan.path("format").asText().equals("runtime-os-stage-plan/v1")&&plan.path("stageRoot").asText().equals("/opt/runtime-os-docker-stage"),"Unsupported existing-stage plan");
        Set<String> services=new HashSet<>();plan.path("compose").path("services").fieldNames().forEachRemaining(services::add);
        Set<String> expected=new HashSet<>();manifest.path("runtimeInstaller").path("services").forEach(n->expected.add(n.asText()));require(services.equals(expected),"Private stage inventory differs from signed contract");
        for(JsonNode component:manifest.path("components")){
            JsonNode service=plan.path("compose").path("services").path(component.path("id").asText());
            require(service.path("image").asText().equals(manifest.path("images").path(component.path("image").asText()).path("id").asText()),"Private stage image differs from signed contract");
            require(service.path("entrypoint").equals(component.path("entrypoint"))&&service.path("command").equals(component.path("command")),"Private stage argv differs from signed contract");
            require(!service.has("container_name")&&!service.has("build")&&!service.has("env_file"),"Stage plan must resolve private configuration and scope names before review");
        }
        if(enabled(manifest)){
            JsonNode summary=manifest.path("runtimeInstaller").path("stagePlan");require(summary.path("sourceRoot").equals(plan.path("stageRoot")),"Public stage root differs from private plan");
            Set<String> actual=new HashSet<>(),displayed=new HashSet<>();var entries=plan.path("compose").path("services").fields();
            while(entries.hasNext()){var entry=entries.next();for(JsonNode mount:entry.getValue().path("volumes")){
                String source,target,mode;if(mount.isObject()){source=mount.path("source").asText();target=mount.path("target").asText();mode=mount.path("read_only").asBoolean()?"read-only":"read-write";}else{String[] parts=mount.asText().split(":");require(parts.length>=2,"Invalid private bind");source=parts[0];target=parts[1];mode=parts.length>2&&parts[2].equals("ro")?"read-only":"read-write";}
                actual.add(entry.getKey()+"\0"+source+"\0"+target+"\0"+mode);
            }}
            for(JsonNode mount:summary.path("mounts"))displayed.add(mount.path("component").asText()+"\0"+mount.path("source").asText()+"\0"+mount.path("target").asText()+"\0"+mount.path("mode").asText());
            require(!actual.isEmpty()&&actual.equals(displayed),"Displayed stage mounts differ from private execution plan");
        }
    }
    public static void checkMountUse(JsonNode manifest,DockerClient docker,String project)throws Exception{
        JsonNode plan=StateFiles.JSON.readTree(Files.readAllBytes(path(manifest)));List<Path> writers=new ArrayList<>();
        for(JsonNode service:plan.path("compose").path("services"))for(JsonNode mount:service.path("volumes")){
            String source,target;boolean readOnly;
            if(mount.isObject()){source=mount.path("source").asText();target=mount.path("target").asText();readOnly=mount.path("read_only").asBoolean();}
            else{String[] parts=mount.asText().split(":");require(parts.length>=2,"Invalid stage bind");source=parts[0];target=parts[1];readOnly=parts.length>2&&parts[2].equals("ro");}
            if(source.startsWith("${INSTALL_ROOT}/profiles/")){require(plan.path("generatedProfiles").has(source.substring("${INSTALL_ROOT}/profiles/".length())),"Generated stage profile missing");continue;}if(source.startsWith("${INSTALL_ROOT}/generated/")){require(plan.path("generatedText").has(source.substring("${INSTALL_ROOT}/generated/".length())),"Generated stage text missing");continue;}Path resolved=Path.of(source).toRealPath();require(resolved.isAbsolute(),"Existing stage source missing");
            if(!readOnly&&!target.equals("/var/run/docker.sock"))writers.add(resolved);
        }
        for(JsonNode container:docker.call("GET","/containers/json")){
            if(container.path("Labels").path("com.docker.compose.project").asText().equals(project))continue;
            for(JsonNode mount:container.path("Mounts")){
                if(!mount.path("RW").asBoolean())continue;String source=mount.path("Source").asText();if(source.isBlank())continue;Path other=Path.of(source).toRealPath();
                for(Path writer:writers)require(!writer.startsWith(other)&&!other.startsWith(writer),"Existing-stage data already has another running writer; stop or reconcile that application before deployment");
            }
        }
    }
}
