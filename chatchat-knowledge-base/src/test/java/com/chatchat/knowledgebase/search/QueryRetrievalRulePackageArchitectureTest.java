package com.chatchat.knowledgebase.search;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRetrievalRulePackageArchitectureTest {

    @Test
    void featureRootsRemainOrganizationBoundaries() throws IOException {
        assertPackageInfoOnly(sourceRoot("query"));
        assertPackageInfoOnly(sourceRoot("retrieval"));
        assertPackageInfoOnly(sourceRoot("rule"));
    }

    @Test
    void queryAndRetrievalModelsRemainImplementationIndependent() {
        List<String> violations = List.of(sourceRoot("query/model"), sourceRoot("retrieval/model")).stream()
            .flatMap(root -> sourceFiles(root).stream())
            .flatMap(path -> forbiddenImports(path, ".application.", ".infrastructure.").stream())
            .toList();

        assertThat(violations)
            .as("Query and retrieval models must not depend on application or infrastructure code")
            .isEmpty();
    }

    @Test
    void queryAndRetrievalApplicationsDoNotDependOnTheirInfrastructureAdapters() {
        List<String> violations = List.of(sourceRoot("query/application"), sourceRoot("retrieval/application")).stream()
            .flatMap(root -> sourceFiles(root).stream())
            .flatMap(path -> forbiddenImports(path,
                ".search.query.infrastructure.",
                ".search.retrieval.infrastructure.").stream())
            .toList();

        assertThat(violations)
            .as("Query and retrieval applications must remain independent of technology adapters")
            .isEmpty();
    }

    @Test
    void ruleEntitiesRemainIndependentOfRepositoriesAndServices() {
        List<String> violations = sourceFiles(sourceRoot("rule/infrastructure/persistence/entity")).stream()
            .flatMap(path -> forbiddenImports(path, ".repository.", ".application.").stream())
            .toList();

        assertThat(violations)
            .as("Rule persistence entities must not depend on repositories or rule services")
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
            throw new IllegalStateException("Cannot inspect query, retrieval, and rule packages", error);
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
