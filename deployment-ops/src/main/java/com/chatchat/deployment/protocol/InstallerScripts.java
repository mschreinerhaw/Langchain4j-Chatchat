package com.chatchat.deployment.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import static com.chatchat.deployment.protocol.InstallationManifest.require;

/** A signed installer image declares an entry point; the operator confirms its check command. */
public record InstallerScripts(String path) {
    public static final String LABEL = "deployment-ops.runtime-script";

    public InstallerScripts {
        require(path.matches("scripts/[a-z0-9][a-z0-9-]{0,63}\\.sh"), "Invalid installer script path");
    }

    public static InstallerScripts from(JsonNode manifest) {
        JsonNode adapter = manifest.path("runtimeInstaller");
        if (!adapter.has("script")) return null;
        require(adapter.path("script").isTextual() && !adapter.has("stagePlan"),
                "Script installers require a signed standalone installer image");
        return new InstallerScripts(adapter.path("script").asText());
    }

    public String checkCommand() { return "sh " + path + " check"; }
    public String containerPath() { return "/opt/product/" + path; }

    public void confirm(String command) {
        require(checkCommand().equals(command.strip()), "Enter the check command declared by the verified image");
    }

    public void verifyImage(JsonNode image) {
        require(path.equals(image.path("Config").path("Labels").path(LABEL).asText()),
                "Signed installer script differs from the image declaration");
    }
}
