package com.chatchat.mcpserver.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevents a newly added commercial controller from silently bypassing License enforcement. */
class McpLicenseCoverageContractTest {
    private static final Pattern BASE_PATH = Pattern.compile(
        "@RequestMapping\\(\\\"((?:/api/v1|/internal/v1)/[^\\\"]+)\\\"\\)");

    @Test
    void everyBusinessControllerIsMappedToALicenseModuleOrExplicitlyRecoveryOnly() throws Exception {
        McpAdminMenuCatalog catalog = new McpAdminMenuCatalog(new ObjectMapper());
        Path sourceRoot = Path.of("src/main/java");
        List<String> missing = new ArrayList<>();

        try (var files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith("Controller.java")).forEach(path -> {
                try {
                    var matcher = BASE_PATH.matcher(Files.readString(path));
                    while (matcher.find()) {
                        String apiPath = matcher.group(1);
                        if (recoveryOnly(apiPath) || catalog.menuForPath(apiPath).isPresent()) continue;
                        missing.add(apiPath + " (" + sourceRoot.relativize(path) + ")");
                    }
                } catch (Exception failure) {
                    throw new IllegalStateException("Cannot inspect controller " + path, failure);
                }
            });
        }

        assertThat(missing)
            .as("Every MCP business API must be explicitly assigned to a signed License module")
            .isEmpty();
    }

    private boolean recoveryOnly(String path) {
        return path.startsWith("/api/v1/license")
            || path.startsWith("/api/v1/admin/auth")
            || path.startsWith("/internal/v1/license");
    }
}
