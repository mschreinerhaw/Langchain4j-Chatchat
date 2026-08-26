package com.chatchat.common.runtime.summary;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSummaryContractTest {

    @Test
    void distributedSummaryPortsParticipateInRuntimeProtocolComposition() {
        assertThat(RuntimeProtocolPort.class).isAssignableFrom(ModelSummaryDispatcher.class);
        assertThat(RuntimeProtocolPort.class).isAssignableFrom(ModelSummaryReducer.class);
        assertThat(ModelSummaryWorker.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
    }

    @Test
    void summaryKernelContractIsFrameworkAndTransportNeutral() throws IOException {
        Path root = sourceRoot();
        List<String> violations;
        try (var sources = Files.walk(root)) {
            violations = sources
                .filter(path -> path.toString().endsWith(".java"))
                .flatMap(path -> read(path).lines()
                    .filter(line -> line.startsWith("import "))
                    .filter(line -> line.contains("dev.langchain4j")
                        || line.contains("org.springframework")
                        || line.contains("com.chatchat.agents")
                        || line.contains("com.chatchat.integration"))
                    .map(line -> path.getFileName() + ": " + line))
                .toList();
        }

        assertThat(violations)
            .as("summary kernel contracts cannot depend on model SDKs, frameworks or driver modules")
            .isEmpty();
    }

    private Path sourceRoot() {
        Path moduleLocal = Path.of("src/main/java/com/chatchat/common/runtime/summary");
        return Files.isDirectory(moduleLocal)
            ? moduleLocal.toAbsolutePath().normalize()
            : Path.of("chatchat-common/src/main/java/com/chatchat/common/runtime/summary")
                .toAbsolutePath().normalize();
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect " + path, exception);
        }
    }
}
