package com.chatchat.runtime.temporal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalPackageArchitectureTest {

    @Test
    void rootPackageContainsOnlyItsPackageDocumentation() throws IOException {
        Path root = Path.of("src/main/java/com/chatchat/runtime/temporal");
        assertContainsOnlyPackageDocumentation(root);
    }

    @Test
    void boundaryRootsContainOnlyTheirPackageDocumentation() throws IOException {
        Path root = Path.of("src/main/java/com/chatchat/runtime/temporal");
        assertContainsOnlyPackageDocumentation(root.resolve("activity"));
        assertContainsOnlyPackageDocumentation(root.resolve("contract"));
        assertContainsOnlyPackageDocumentation(root.resolve("workflow"));
    }

    private static void assertContainsOnlyPackageDocumentation(Path root) throws IOException {
        try (var files = Files.list(root)) {
            assertThat(files.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .toList()).containsExactly("package-info.java");
        }
    }
}
