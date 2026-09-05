package com.chatchat.common.runtime.summary;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;
import com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisParticipant;
import com.chatchat.common.runtime.summary.model.ModelSummaryProgress;
import com.chatchat.common.runtime.summary.spi.ModelSummaryDispatcher;
import com.chatchat.common.runtime.summary.spi.ModelSummaryReducer;
import com.chatchat.common.runtime.summary.spi.ModelSummaryWorker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelSummaryContractTest {

    @Test
    void distributedSummaryPortsParticipateInRuntimeProtocolComposition() {
        assertThat(RuntimeProtocolPort.class).isAssignableFrom(ModelSummaryDispatcher.class);
        assertThat(RuntimeProtocolPort.class).isAssignableFrom(ModelSummaryReducer.class);
        assertThat(RuntimeProtocolPort.class).isAssignableFrom(DataAnalysisParticipant.class);
        assertThat(ModelSummaryWorker.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
    }

    @Test
    void progressEnvelopeProtectsControlFieldsAndIsImmutable() {
        ModelSummaryProgress progress = new ModelSummaryProgress(null, "WORKER_CLAIMED",
            "task-1", "dataset-1", 1, 2, "worker-1", 42L,
            Map.of("stage", "FORGED", "taskId", "forged-task", "custom", "value"));

        assertThat(progress.toMap())
            .containsEntry("schemaVersion", ModelSummaryProgress.SCHEMA_VERSION)
            .containsEntry("stage", "WORKER_CLAIMED")
            .containsEntry("taskId", "task-1")
            .containsEntry("custom", "value");
        assertThatThrownBy(() -> progress.toMap().put("stage", "MUTATED"))
            .isInstanceOf(UnsupportedOperationException.class);
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

    @Test
    void summaryKernelKeepsModelSpiAndAnalysisResponsibilitiesSeparated() throws IOException {
        Path root = sourceRoot();
        List<String> rootTypes;
        List<String> categories;
        try (var files = Files.list(root)) {
            rootTypes = files.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".java"))
                .toList();
        }
        try (var files = Files.list(root)) {
            categories = files.filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
        }

        assertThat(rootTypes).containsExactly("package-info.java");
        assertThat(categories).containsExactly("analysis", "model", "spi");
        assertThat(importsUnder(root.resolve("model")))
            .noneMatch(line -> line.contains(".summary.analysis.")
                || line.contains(".summary.spi."));
        assertThat(importsUnder(root.resolve("spi")))
            .noneMatch(line -> line.contains(".summary.analysis."));
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

    private List<String> importsUnder(Path root) throws IOException {
        try (var sources = Files.walk(root)) {
            return sources.filter(path -> path.toString().endsWith(".java"))
                .flatMap(path -> read(path).lines().filter(line -> line.startsWith("import ")))
                .toList();
        }
    }
}
