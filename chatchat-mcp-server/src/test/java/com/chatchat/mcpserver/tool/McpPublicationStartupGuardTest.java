package com.chatchat.mcpserver.tool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class McpPublicationStartupGuardTest {

    @Test
    void onePublisherFailureDoesNotPreventTheNextPublisherFromStarting() {
        AtomicBoolean nextPublisherRan = new AtomicBoolean();

        McpPublicationStartupGuard.StartupPublicationResult failed =
            McpPublicationStartupGuard.run(FailingPublisher.class,
                () -> { throw new IllegalStateException("catalog unavailable"); });
        McpPublicationStartupGuard.StartupPublicationResult succeeded =
            McpPublicationStartupGuard.run(HealthyPublisher.class,
                () -> nextPublisherRan.set(true));

        assertThat(failed.success()).isFalse();
        assertThat(failed.publisher()).isEqualTo(FailingPublisher.class.getName());
        assertThat(failed.errorType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(failed.errorMessage()).isEqualTo("catalog unavailable");
        assertThat(succeeded.success()).isTrue();
        assertThat(nextPublisherRan).isTrue();
    }

    @Test
    void startupPublicationHasOneCoordinatorAndPublishersOnlyContribute() throws IOException {
        Path moduleLocal = Path.of("src/main/java");
        Path sourceRoot = Files.isDirectory(moduleLocal)
            ? moduleLocal.toAbsolutePath().normalize()
            : Path.of("chatchat-mcp-server/src/main/java").toAbsolutePath().normalize();
        List<Path> eventListeners;
        List<Path> publishers;
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            List<Path> javaSources = sources.filter(path -> path.toString().endsWith(".java")).toList();
            eventListeners = javaSources.stream()
                .filter(path -> path.toString().endsWith("Publisher.java"))
                .filter(path -> contains(path, "@EventListener(ApplicationReadyEvent.class)"))
                .toList();
            publishers = javaSources.stream()
                .filter(path -> path.toString().endsWith("Publisher.java"))
                .filter(path -> contains(path, "McpSyncServer"))
                .toList();
        }

        assertThat(eventListeners).isEmpty();
        Path coordinator = sourceRoot.resolve(
            "com/chatchat/mcpserver/tool/McpToolPublicationCoordinator.java");
        assertThat(contains(coordinator, "@EventListener(ApplicationReadyEvent.class)")).isTrue();
        assertThat(contains(coordinator, "McpPublicationStartupGuard.run")).isTrue();
        assertThat(publishers).isNotEmpty();
        assertThat(publishers)
            .allSatisfy(path -> assertThat(contains(path, "McpToolContributor"))
                .as("publisher must contribute through the central pipeline: %s", path)
                .isTrue());
    }

    private boolean contains(Path path, String expected) {
        try {
            return Files.readString(path).contains(expected);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot inspect " + path, failure);
        }
    }

    private static final class FailingPublisher {
    }

    private static final class HealthyPublisher {
    }
}
