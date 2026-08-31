package com.chatchat.common.bridge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommonRuntimeOsContractBoundaryTest {
    @Test
    void bridgeAndMcpContractsDoNotDependOnImplementationModulesOrFrameworks() throws IOException {
        List<String> violations = List.of(bridgeRoot(), mcpContractRoot(), mcpServiceRoot(), mcpAuditRoot(),
                knowledgeRoot(), analysisSemanticContractRoot()).stream()
            .flatMap(root -> sourceFiles(root).stream())
            .flatMap(path -> read(path).lines()
                .filter(line -> line.startsWith("import "))
                .filter(line -> !line.startsWith("import java."))
                .filter(line -> !line.startsWith("import com.chatchat.common."))
                .map(line -> path.getFileName() + ": " + line))
            .toList();

        assertThat(violations)
            .as("Common bridge and MCP contracts must not depend on Spring, Runtime or adapter modules")
            .isEmpty();
    }

    private List<Path> sourceFiles(Path root) {
        try (var files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot inspect common contract sources", error);
        }
    }

    private Path bridgeRoot() { return sourceRoot("bridge"); }
    private Path mcpContractRoot() { return sourceRoot("mcp/contract"); }
    private Path mcpServiceRoot() { return sourceRoot("mcp/service"); }
    private Path mcpAuditRoot() { return sourceRoot("mcp/audit"); }
    private Path knowledgeRoot() { return sourceRoot("knowledge"); }
    private Path analysisSemanticContractRoot() { return sourceRoot("runtime/summary/analysis/semantic"); }

    private Path sourceRoot(String relative) {
        Path local = Path.of("src/main/java/com/chatchat/common").resolve(relative);
        return Files.isDirectory(local) ? local.toAbsolutePath().normalize()
            : Path.of("chatchat-common/src/main/java/com/chatchat/common").resolve(relative)
                .toAbsolutePath().normalize();
    }

    private String read(Path path) {
        try { return Files.readString(path); }
        catch (IOException error) { throw new IllegalStateException("Cannot inspect " + path, error); }
    }
}
