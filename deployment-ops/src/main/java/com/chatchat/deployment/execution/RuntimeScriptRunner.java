package com.chatchat.deployment.execution;

import com.chatchat.deployment.protocol.InstallerScripts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static com.chatchat.deployment.execution.ExecutionJson.*;
import static com.chatchat.deployment.protocol.InstallationManifest.require;

final class RuntimeScriptRunner {
    private final DeploymentService base;

    RuntimeScriptRunner(DeploymentService base) { this.base = base; }

    static String imageId(JsonNode manifest) {
        return manifest.path("images").path(manifest.path("runtimeInstaller").path("image").asText())
                .path("id").asText();
    }

    void check(JsonNode manifest, InstallerScripts scripts, Path root, String project) throws Exception {
        run(manifest, scripts, root, project, "check", Duration.ofSeconds(35));
    }

    JsonNode status(JsonNode manifest, InstallerScripts scripts, Path root, String project) throws Exception {
        JsonNode result = StateFiles.JSON.readTree(run(manifest, scripts, root, project, "status", Duration.ofSeconds(20)));
        require(result.path("project").asText().equals(project)
                && result.path("containers").isArray() && result.path("images").isArray(),
                "Invalid scoped monitoring output");
        return result;
    }

    private String run(JsonNode manifest, InstallerScripts scripts, Path root, String project,
                       String action, Duration timeout) throws Exception {
        require(Set.of("check", "status").contains(action), "Unsupported inspection action");
        String imageId = imageId(manifest);
        scripts.verifyImage(base.docker.call("GET", "/images/" + url(imageId) + "/json"));

        ObjectNode config = object().put("Image", imageId).put("User", "0:0");
        config.set("Entrypoint", array().add("/bin/sh").add(scripts.containerPath()));
        config.set("Cmd", array().add(action));
        config.set("Env", array().add("PROJECT=" + project));
        config.set("Labels", object().put("deployment-ops.owner", base.owner)
                .put("deployment-ops.installation", "true").put("deployment-ops.kind", "job")
                .put("deployment-ops.component", "native-" + action));
        ObjectNode host = object().put("NetworkMode", "none").put("ReadonlyRootfs", true)
                .put("Memory", 256L * 1024 * 1024).put("PidsLimit", 64);
        var mounts = array().add(System.getenv().getOrDefault("DOCKER_SOCKET", "/var/run/docker.sock") + ":/var/run/docker.sock:ro");
        if (Files.isDirectory(root)) {
            require(root.toRealPath().equals(root), "Untrusted Runtime OS root");
            mounts.add(root + ":/install:ro");
        }
        host.set("Binds", mounts);
        config.set("HostConfig", host);
        String id = base.docker.call("POST", "/containers/create", config).path("Id").asText();
        String container = "/containers/" + ResourceManager.id(id);
        try {
            base.docker.call("POST", container + "/start");
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode state = base.docker.call("GET", container + "/json").path("State");
                if (Set.of("exited", "dead").contains(state.path("Status").asText())) {
                    String logs = ResourceManager.logs(base.docker, id, false).strip();
                    require(state.path("ExitCode").asInt(-1) == 0, "Runtime script " + action + " failed: " + logs);
                    return logs;
                }
                Thread.sleep(250);
            }
            throw new IllegalArgumentException("Runtime script " + action + " timed out");
        } finally {
            base.docker.call("DELETE", container + "?force=true&v=false");
        }
    }
}
