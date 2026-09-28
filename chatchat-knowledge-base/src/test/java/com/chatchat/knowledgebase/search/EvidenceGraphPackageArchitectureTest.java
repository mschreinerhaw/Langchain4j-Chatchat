package com.chatchat.knowledgebase.search;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceGraphPackageArchitectureTest {

    @Test
    void featureRootsRemainOrganizationBoundaries() throws IOException {
        assertPackageInfoOnly(sourceRoot("evidence"));
        assertPackageInfoOnly(sourceRoot("graph"));
    }

    @Test
    void contractsAndModelsDoNotDependOnApplicationServices() {
        List<String> violations = List.of(
                sourceRoot("evidence/api"),
                sourceRoot("evidence/model"),
                sourceRoot("graph/api"),
                sourceRoot("graph/model")
            ).stream()
            .flatMap(root -> sourceFiles(root).stream())
            .flatMap(path -> forbiddenImports(path, ".application.").stream())
            .toList();

        assertThat(violations)
            .as("Evidence and graph contracts/models must remain independent of application services")
            .isEmpty();
    }

    @Test
    void graphModelsDoNotDependOnEvidenceImplementations() {
        List<String> violations = sourceFiles(sourceRoot("graph/model")).stream()
            .flatMap(path -> forbiddenImports(path, ".search.evidence.application.").stream())
            .toList();

        assertThat(violations)
            .as("Graph models must not depend on evidence application services")
            .isEmpty();
    }

    private void assertPackageInfoOnly(Path root) throws IOException {
        try (var files = Files.list(root)) {
            List<String> directTypes = files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".java"))
                .toList();

            assertThat(directTypes).containsExactly("package-info.java");
        }
    }

    private List<String> forbiddenImports(Path path, String... fragments) {
        return read(path).lines()
            .filter(line -> line.startsWith("import "))
            .filter(line -> List.of(fragments).stream().anyMatch(line::contains))
            .map(line -> path.getFileName() + ": " + line)
            .toList();
    }

    private List<Path> sourceFiles(Path root) {
        try (var files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot inspect evidence and graph search packages", error);
        }
    }

    private Path sourceRoot(String relative) {
        return Path.of(System.getProperty("basedir", "."))
            .resolve("src/main/java/com/chatchat/knowledgebase/search")
            .resolve(relative)
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
