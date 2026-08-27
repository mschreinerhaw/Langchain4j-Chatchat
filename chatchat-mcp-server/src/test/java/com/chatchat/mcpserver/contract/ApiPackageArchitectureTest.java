package com.chatchat.mcpserver.contract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiPackageArchitectureTest {

    @Test
    void apiRootContainsNoConcreteTypes() throws IOException {
        Path apiRoot = mainJavaRoot().resolve("com/chatchat/mcpserver/api");
        Set<String> rootSources;
        try (var paths = Files.list(apiRoot)) {
            rootSources = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        assertThat(rootSources)
                .as("API-service types must be placed in a functional child package")
                .containsExactly("package-info.java");
    }

    private Path mainJavaRoot() {
        Path moduleLocal = Path.of("src/main/java");
        return Files.isDirectory(moduleLocal)
                ? moduleLocal.toAbsolutePath().normalize()
                : Path.of("chatchat-mcp-server/src/main/java").toAbsolutePath().normalize();
    }
}
