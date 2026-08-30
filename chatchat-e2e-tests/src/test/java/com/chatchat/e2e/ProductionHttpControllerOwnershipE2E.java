package com.chatchat.e2e;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps externally published HTTP controller surfaces owned by the release suite. */
class ProductionHttpControllerOwnershipE2E {

    @Test
    void commercialControllerSurfacesRetainExplicitHttpContracts() throws Exception {
        Path root = repositoryRoot();
        List<String> controllers = List.of(
            "chatchat-api/src/main/java/com/chatchat/api/agent/published/AgentApiTokenAdminController.java",
            "chatchat-api/src/main/java/com/chatchat/api/controller/AgentOptimizationController.java",
            "chatchat-api/src/main/java/com/chatchat/api/controller/PythonDataScienceController.java",
            "chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/python/PythonAdminController.java"
        );
        for (String relative : controllers) {
            String source = Files.readString(root.resolve(relative));
            assertThat(source)
                .as("published controller contract: %s", relative)
                .contains("@RestController", "@RequestMapping")
                .containsAnyOf("@GetMapping", "@PostMapping", "@PutMapping", "@DeleteMapping");
        }
    }

    private Path repositoryRoot() {
        return Path.of(System.getProperty("chatchat.e2e.repository-root", "..")).toAbsolutePath().normalize();
    }
}
