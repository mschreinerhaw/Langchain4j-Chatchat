package com.chatchat.deployment.execution;

import com.chatchat.deployment.protocol.InstallerScripts;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScriptDeploymentTest {
    @Test void operatorConfirmsExactlyTheSignedCheckCommand() throws Exception {
        var manifest = StateFiles.JSON.readTree("""
                {"runtimeInstaller":{"script":"scripts/runtime-os.sh"}}
                """);
        InstallerScripts scripts = InstallerScripts.from(manifest);
        scripts.confirm("sh scripts/runtime-os.sh check");
        assertThrows(IllegalArgumentException.class, () -> scripts.confirm("sh scripts/runtime-os.sh install"));
        assertThrows(IllegalArgumentException.class, () -> scripts.confirm("sh scripts/runtime-os.sh check && sh other.sh"));
        assertThrows(IllegalArgumentException.class, () -> new InstallerScripts("scripts/../other.sh"));
        assertThrows(IllegalArgumentException.class, () -> scripts.verifyImage(StateFiles.JSON.readTree("""
                {"Config":{"Labels":{"deployment-ops.runtime-script":"scripts/other.sh"}}}
                """)));
    }

    @Test void onlyOwnedOrRecordedComposeResourcesAreVisible() throws Exception {
        var projects = Set.of("ops-env-team");
        assertTrue(ResourceManager.owns(StateFiles.JSON.readTree("""
                {"deployment-ops.owner":"node-one"}
                """), "node-one", projects));
        assertTrue(ResourceManager.owns(StateFiles.JSON.readTree("""
                {"com.docker.compose.project":"ops-env-team","com.docker.compose.service":"api"}
                """), "node-one", projects));
        assertFalse(ResourceManager.owns(StateFiles.JSON.readTree("""
                {"com.docker.compose.project":"ops-env-outsider","com.docker.compose.service":"api"}
                """), "node-one", projects));
        assertFalse(ResourceManager.owns(StateFiles.JSON.readTree("""
                {"deployment-ops.owner":"node-two"}
                """), "node-one", projects));
    }
}
