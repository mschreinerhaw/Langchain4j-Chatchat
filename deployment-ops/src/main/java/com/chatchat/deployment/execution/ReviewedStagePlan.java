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
        require(directory.startsWith("/mnt/disk01/")&&directory.toRealPath().equals(directory)&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"Stage plan must be a regular private file on the data disk");
        require(SignedPackageStore.sha(file).equals(entry.path("sha256").asText()),"Private stage plan changed after signing; review and republish it");
        JsonNode plan=StateFiles.JSON.readTree(Files.readAllBytes(file));validate(plan,manifest);
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
    }
    public static void checkMountUse(JsonNode manifest,DockerClient docker,String project)throws Exception{
        JsonNode plan=StateFiles.JSON.readTree(Files.readAllBytes(path(manifest)));List<Path> writers=new ArrayList<>();
        for(JsonNode service:plan.path("compose").path("services"))for(JsonNode mount:service.path("volumes")){
            String source,target;boolean readOnly;
            if(mount.isObject()){source=mount.path("source").asText();target=mount.path("target").asText();readOnly=mount.path("read_only").asBoolean();}
            else{String[] parts=mount.asText().split(":");require(parts.length>=2,"Invalid stage bind");source=parts[0];target=parts[1];readOnly=parts.length>2&&parts[2].equals("ro");}
            if(source.startsWith("${INSTALL_ROOT}/profiles/")){require(plan.path("generatedProfiles").has(source.substring("${INSTALL_ROOT}/profiles/".length())),"Generated stage profile missing");continue;}Path resolved=Path.of(source).toRealPath();require(resolved.isAbsolute(),"Existing stage source missing");
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
