package com.chatchat.chat.skills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DomainSkillPackageArchitectureTest {

    private static final Set<String> CAPABILITIES = Set.of(
        "adapter", "application", "artifact", "catalog",
        "importing", "indexing", "planning", "source"
    );

    @Test
    void domainRootRemainsAnOrganizationBoundary() throws IOException {
        Path root = domainRoot();
        List<String> directTypes;
        Set<String> childPackages;

        try (var entries = Files.list(root)) {
            List<Path> paths = entries.toList();
            directTypes = paths.stream()
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".java"))
                .toList();
            childPackages = paths.stream()
                .filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toSet());
        }

        assertThat(directTypes).containsExactly("package-info.java");
        assertThat(childPackages).containsExactlyInAnyOrderElementsOf(CAPABILITIES);
    }

    @Test
    void sourceAndCatalogDoNotDependOnApplicationServices() {
        List<String> violations = List.of("source", "catalog").stream()
            .flatMap(name -> sourceFiles(domainRoot().resolve(name)).stream())
            .flatMap(path -> read(path).lines())
            .filter(line -> line.startsWith("import com.chatchat.chat.skills.domain.application."))
            .toList();

        assertThat(violations)
            .as("Source acquisition and catalog persistence must remain below application orchestration")
            .isEmpty();
    }

    private List<Path> sourceFiles(Path root) {
        try (var files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot inspect Domain Skill sources", error);
        }
    }

    private Path domainRoot() {
        return Path.of(System.getProperty("basedir", "."))
            .resolve("src/main/java/com/chatchat/chat/skills/domain")
            .toAbsolutePath()
            .normalize();
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot inspect " + path, error);
        }
    }
}
