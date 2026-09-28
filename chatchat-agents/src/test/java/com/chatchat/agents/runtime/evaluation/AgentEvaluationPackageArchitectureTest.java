package com.chatchat.agents.runtime.evaluation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvaluationPackageArchitectureTest {

    private static final Set<String> CAPABILITIES = Set.of(
        "assessment", "comparison", "quality", "regression"
    );

    @Test
    void evaluationRootRemainsAnOrganizationBoundary() throws IOException {
        Path root = evaluationRoot();
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
    void assessmentDoesNotDependOnQualityAggregation() {
        List<String> violations = sourceFiles(evaluationRoot().resolve("assessment")).stream()
            .flatMap(path -> read(path).lines())
            .filter(line -> line.startsWith("import com.chatchat.agents.runtime.evaluation.quality."))
            .toList();

        assertThat(violations)
            .as("Individual case assessment must remain below quality aggregation")
            .isEmpty();
    }

    private List<Path> sourceFiles(Path root) {
        try (var files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot inspect Agent evaluation sources", error);
        }
    }

    private Path evaluationRoot() {
        return Path.of(System.getProperty("basedir", "."))
            .resolve("src/main/java/com/chatchat/agents/runtime/evaluation")
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
