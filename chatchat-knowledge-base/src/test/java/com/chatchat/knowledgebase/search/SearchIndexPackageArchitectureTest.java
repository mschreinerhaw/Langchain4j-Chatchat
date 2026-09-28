package com.chatchat.knowledgebase.search;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchIndexPackageArchitectureTest {

    @Test
    void searchAndIndexRootsRemainOrganizationBoundaries() throws IOException {
        assertPackageInfoOnly(sourceRoot(""));
        assertPackageInfoOnly(sourceRoot("index"));
    }

    @Test
    void indexContractsAndModelsRemainStorageNeutral() {
        List<String> violations = List.of(sourceRoot("index/api"), sourceRoot("index/model")).stream()
            .flatMap(root -> sourceFiles(root).stream())
            .flatMap(path -> forbiddenImports(path, ".index.application.", ".index.infrastructure.").stream())
            .toList();

        assertThat(violations)
            .as("Index contracts and models must remain independent of orchestration and storage adapters")
            .isEmpty();
    }

    @Test
    void indexApplicationDoesNotDependOnStorageAdapters() {
        List<String> violations = sourceFiles(sourceRoot("index/application")).stream()
            .flatMap(path -> forbiddenImports(path, ".index.infrastructure.").stream())
            .toList();

        assertThat(violations)
            .as("Index application services must depend on the index port, not concrete storage adapters")
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
            throw new IllegalStateException("Cannot inspect search-index packages", error);
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
