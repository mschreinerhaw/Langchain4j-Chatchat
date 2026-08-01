package com.chatchat.agents.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeDeploymentHardcodingTest {

    private static final Pattern MAINTAINED_TEMPLATE_LITERAL = Pattern.compile(
        "[\\\"]sample_[a-z0-9_]+[\\\"]", Pattern.CASE_INSENSITIVE);

    @Test
    void runtimeSourceContainsNoDeploymentNamespaceOrMaintainedTemplateIdentifiers() throws IOException {
        Path sourceRoot = runtimeSourceRoot();
        String forbiddenNamespace = "mcp_" + "chatchat_mcp_server_";

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            List<String> violations = paths
                .filter(path -> path.toString().endsWith(".java"))
                .flatMap(path -> violations(path, forbiddenNamespace).stream())
                .toList();

            assertThat(violations)
                .as("Runtime must derive server namespaces and maintained template ids from registry/discovery data")
                .isEmpty();
        }
    }

    private Path runtimeSourceRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        Path module = current.resolve("chatchat-agents");
        Path root = Files.isDirectory(module) ? module : current;
        Path source = root.resolve("src/main/java/com/chatchat/agents");
        assertThat(source).isDirectory();
        return source;
    }

    private List<String> violations(Path path, String forbiddenNamespace) {
        try {
            String source = Files.readString(path);
            return Stream.of(
                    source.contains(forbiddenNamespace)
                        ? path + ": deployment-specific MCP namespace"
                        : null,
                    MAINTAINED_TEMPLATE_LITERAL.matcher(source).find()
                        ? path + ": maintained template identifier"
                        : null)
                .filter(value -> value != null)
                .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot inspect " + path, ex);
        }
    }
}
