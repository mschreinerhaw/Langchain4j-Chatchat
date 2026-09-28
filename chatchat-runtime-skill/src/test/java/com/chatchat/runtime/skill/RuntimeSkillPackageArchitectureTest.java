package com.chatchat.runtime.skill;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeSkillPackageArchitectureTest {

    @Test
    void apiContractsDoNotDependOnPortsOrApplicationImplementations() {
        List<String> violations = sourceFiles(sourceRoot("api")).stream()
            .flatMap(path -> forbiddenImports(path, ".port.", ".application.").stream())
            .toList();

        assertThat(violations)
            .as("Skill API contracts must remain independent of ports and implementations")
            .isEmpty();
    }

    @Test
    void portsDoNotDependOnApplicationImplementations() {
        List<String> violations = sourceFiles(sourceRoot("port")).stream()
            .flatMap(path -> forbiddenImports(path, ".application.", ".core.", ".spi.").stream())
            .toList();

        assertThat(violations)
            .as("Skill runtime ports must remain independent of implementations and legacy packages")
            .isEmpty();
    }

    @Test
    void legacyResponsibilityBucketsRemainRemoved() {
        assertThat(sourceFiles(sourceRoot("core"))).isEmpty();
        assertThat(sourceFiles(sourceRoot("spi"))).isEmpty();
    }

    private List<String> forbiddenImports(Path path, String... fragments) {
        return read(path).lines()
            .filter(line -> line.startsWith("import "))
            .filter(line -> List.of(fragments).stream().anyMatch(line::contains))
            .map(line -> path.getFileName() + ": " + line)
            .toList();
    }

    private List<Path> sourceFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot inspect runtime skill sources", error);
        }
    }

    private Path sourceRoot(String relative) {
        Path moduleRoot = Path.of(System.getProperty("basedir", "."));
        return moduleRoot.resolve("src/main/java/com/chatchat/runtime/skill")
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
