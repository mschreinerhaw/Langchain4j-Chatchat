package com.chatchat.knowledgebase.search;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowPackageArchitectureTest {

    private static final Set<String> CAPABILITIES = Set.of(
        "analysis", "authorization", "core", "enrichment",
        "ranking", "recall", "verification"
    );

    @Test
    void workflowRootRemainsAnOrganizationBoundary() throws IOException {
        Path root = workflowRoot();
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
    void stagesDependOnCoreWithoutCoreDependingOnStages() {
        List<String> violations = sourceFiles(workflowRoot().resolve("core")).stream()
            .flatMap(path -> read(path).lines())
            .filter(line -> line.startsWith("import com.chatchat.knowledgebase.search.workflow."))
            .filter(line -> !line.contains(".workflow.core."))
            .toList();

        assertThat(violations).as("Workflow core must remain independent of stage packages").isEmpty();
    }

    private List<Path> sourceFiles(Path root) {
        try (var files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot inspect workflow sources", error);
        }
    }

    private Path workflowRoot() {
        return Path.of(System.getProperty("basedir", "."))
            .resolve("src/main/java/com/chatchat/knowledgebase/search/workflow")
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
