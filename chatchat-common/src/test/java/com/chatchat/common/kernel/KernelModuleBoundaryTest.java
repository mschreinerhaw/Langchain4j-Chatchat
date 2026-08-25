package com.chatchat.common.kernel;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KernelModuleBoundaryTest {

    @Test
    void kernelAbiHasNoDependencyOnRuntimeFrameworkOrDomainImplementations() throws IOException {
        List<String> violations;
        try (var files = Files.walk(kernelRoot())) {
            violations = files
                .filter(path -> path.toString().endsWith(".java"))
                .flatMap(path -> read(path).lines()
                    .filter(line -> line.startsWith("import "))
                    .filter(line -> !line.startsWith("import java."))
                    .filter(line -> !line.startsWith("import com.chatchat.common.kernel."))
                    .map(line -> path.getFileName() + ": " + line))
                .toList();
        }

        assertThat(violations)
            .as("Kernel ABI must remain dependency-free and must not import Runtime, MCP, Spring, or domain code")
            .isEmpty();
    }

    private Path kernelRoot() {
        Path moduleLocal = Path.of("src/main/java/com/chatchat/common/kernel");
        return Files.isDirectory(moduleLocal)
            ? moduleLocal.toAbsolutePath().normalize()
            : Path.of("chatchat-common/src/main/java/com/chatchat/common/kernel").toAbsolutePath().normalize();
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot inspect Kernel source " + path, error);
        }
    }
}
