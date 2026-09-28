package com.chatchat.knowledgebase.search.document;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentPackageArchitectureTest {

    @Test
    void rootPackageRemainsAnOrganizationBoundary() throws IOException {
        try (var files = Files.list(sourceRoot(""))) {
            List<String> directTypes = files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".java"))
                .toList();

            assertThat(directTypes).containsExactly("package-info.java");
        }
    }

    @Test
    void publicContractsDoNotDependOnApplicationOrInfrastructure() {
        List<String> violations = sourceFiles(sourceRoot("api")).stream()
            .flatMap(path -> forbiddenImports(path, ".application.", ".infrastructure.").stream())
            .toList();

        assertThat(violations)
            .as("Document API contracts must remain independent of implementations")
            .isEmpty();
    }

    @Test
    void applicationServicesDoNotDependOnExtractionAdapters() {
        List<String> violations = sourceFiles(sourceRoot("application")).stream()
            .flatMap(path -> forbiddenImports(path, ".infrastructure.").stream())
            .toList();

        assertThat(violations)
            .as("Document application services must not depend directly on extraction adapters")
            .isEmpty();
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
            throw new IllegalStateException("Cannot inspect document search packages", error);
        }
    }

    private Path sourceRoot(String relative) {
        return Path.of(System.getProperty("basedir", "."))
            .resolve("src/main/java/com/chatchat/knowledgebase/search/document")
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
