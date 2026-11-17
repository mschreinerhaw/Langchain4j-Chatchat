package com.chatchat.deployment.release;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationReleaseTest {
    private ApplicationRelease.Component component(String name, String... dependencies) {
        return new ApplicationRelease.Component(name, "docker", List.of(dependencies), true, Map.of());
    }
    @Test void acceptsDependencyGraphWithoutAssumingDockerTopology() {
        assertDoesNotThrow(() -> new ApplicationRelease("runtimeos.io/v1", "runtime-os", "1.0", List.of(component("api", "database"), component("database"))));
    }
    @Test void rejectsCycle() {
        assertThrows(IllegalArgumentException.class, () -> new ApplicationRelease("runtimeos.io/v1", "runtime-os", "1.0", List.of(component("api", "database"), component("database", "api"))));
    }
    @Test void rejectsUnknownDependencyAndDuplicateComponent() {
        assertThrows(IllegalArgumentException.class, () -> new ApplicationRelease("runtimeos.io/v1", "runtime-os", "1", List.of(component("api", "missing"))));
        assertThrows(IllegalArgumentException.class, () -> new ApplicationRelease("runtimeos.io/v1", "runtime-os", "1", List.of(component("api"), component("api"))));
    }
}
