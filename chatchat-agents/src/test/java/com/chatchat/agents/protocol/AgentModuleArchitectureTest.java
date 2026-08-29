package com.chatchat.agents.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentModuleArchitectureTest {

    @Test
    void topLevelPackageCallGraphIsExplicitAndAcyclic() throws IOException {
        assertThat(importEdges()).containsExactlyInAnyOrder(
            "assessment -> evidence",
            "assessment -> protocol",
            "assessment -> runtime",
            "evidence -> protocol",
            "orchestration -> assessment",
            "orchestration -> evidence",
            "orchestration -> model",
            "orchestration -> protocol",
            "orchestration -> routing",
            "orchestration -> runtime",
            "orchestration -> tool",
            "protocol -> tool",
            "runtime -> evidence",
            "runtime -> protocol",
            "runtime -> routing",
            "runtime -> tool"
        );
    }

    @Test
    void runtimeLayerDoesNotDependOnOrchestrationLayer() throws IOException {
        List<Path> violations = javaSources("runtime").stream()
            .filter(path -> read(path).contains("import com.chatchat.agents.orchestration."))
            .toList();

        assertThat(violations)
            .as("runtime is an execution layer and must not import the upper orchestration layer")
            .isEmpty();
    }

    @Test
    void routingLayerRemainsIndependentFromRuntimeAndOrchestration() throws IOException {
        List<Path> violations = javaSources("routing").stream()
            .filter(path -> {
                String source = read(path);
                return source.contains("import com.chatchat.agents.runtime.")
                    || source.contains("import com.chatchat.agents.orchestration.");
            })
            .toList();

        assertThat(violations)
            .as("routing is shared by orchestration and runtime and cannot depend on either caller")
            .isEmpty();
    }

    @Test
    void evidenceDomainDoesNotDependOnRuntimeImplementation() throws IOException {
        List<Path> violations = javaSources("evidence").stream()
            .filter(path -> {
                String source = read(path);
                return source.contains("import com.chatchat.agents.runtime.")
                    || source.contains("import com.chatchat.agents.orchestration.");
            })
            .toList();

        assertThat(violations)
            .as("domain evidence types must remain reusable and cannot import runtime or orchestration implementations")
            .isEmpty();
    }

    @Test
    void evidenceRootContainsNoConcreteTypes() throws IOException {
        Path evidenceRoot = mainJavaRoot().resolve("com/chatchat/agents/evidence");
        Set<String> rootSources;
        try (var paths = Files.list(evidenceRoot)) {
            rootSources = paths
                .filter(path -> path.toString().endsWith(".java"))
                .map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        assertThat(rootSources)
            .as("evidence domain types must be placed in a functional child package")
            .containsExactly("package-info.java");
    }

    @Test
    void runtimeRootContainsOnlyStableCallerFacingContracts() throws IOException {
        Set<String> allowed = Set.of(
            "AgentRunExecutor.java",
            "AgentRunHandle.java",
            "AgentRunRequest.java",
            "AgentRunResult.java",
            "AgentRuntime.java",
            "AgentRuntimeSnapshot.java",
            "package-info.java"
        );

        Path runtimeRoot = mainJavaRoot().resolve("com/chatchat/agents/runtime");
        Set<String> rootSources;
        try (var paths = Files.list(runtimeRoot)) {
            rootSources = paths
                .filter(path -> path.toString().endsWith(".java"))
                .map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        assertThat(rootSources)
            .as("runtime implementations must be placed in a functional child package")
            .containsExactlyInAnyOrderElementsOf(allowed);
    }

    @Test
    void orchestrationRootContainsOnlyEntryPointsAndCoreEngines() throws IOException {
        Set<String> allowed = Set.of(
            "AgentOrchestrationEngine.java",
            "AgentOrchestrator.java",
            "AgentPlanningPort.java",
            "AgentPlanningRequest.java",
            "AgentPlanner.java",
            "AgentRunResultAdapter.java",
            "AgentWorkflowDecisionEngine.java",
            "AgentWorkflowDecisionPort.java",
            "package-info.java"
        );

        Path orchestrationRoot = mainJavaRoot().resolve("com/chatchat/agents/orchestration");
        Set<String> rootSources;
        try (var paths = Files.list(orchestrationRoot)) {
            rootSources = paths
                .filter(path -> path.toString().endsWith(".java"))
                .map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        assertThat(rootSources)
            .as("orchestration feature implementations must be placed in a functional child package")
            .containsExactlyInAnyOrderElementsOf(allowed);
    }

    @Test
    void crossLayerProtocolVersionsAreDeclaredOnlyInCatalog() throws IOException {
        Path catalog = mainJavaRoot().resolve(
            "com/chatchat/agents/protocol/AgentProtocolCatalog.java").normalize();
        List<String> versions = List.of(
            AgentProtocolCatalog.INTERPRETATION_EXECUTION,
            AgentProtocolCatalog.TEMPLATE_PARAMETER,
            AgentProtocolCatalog.RUNTIME_TEMPLATE_BINDING,
            AgentProtocolCatalog.RUNTIME_DEPENDENCY_EVIDENCE,
            AgentProtocolCatalog.TARGET_FILTERS,
            AgentProtocolCatalog.ROUTING_TRACE,
            AgentProtocolCatalog.RUNTIME_ARGUMENT_RESOLUTION,
            AgentProtocolCatalog.RUNTIME_ANSWER_CANDIDATE
        );
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources("")) {
            if (source.normalize().equals(catalog)) {
                continue;
            }
            String content = read(source);
            for (String version : versions) {
                if (content.contains("\"" + version + "\"")) {
                    violations.add(mainJavaRoot().relativize(source) + " repeats " + version);
                }
            }
        }

        assertThat(violations)
            .as("cross-layer protocol versions must have one declaration in AgentProtocolCatalog")
            .isEmpty();
    }

    private List<Path> javaSources(String packageName) throws IOException {
        Path root = packageName == null || packageName.isBlank()
            ? mainJavaRoot()
            : mainJavaRoot().resolve("com/chatchat/agents").resolve(packageName);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private Set<String> importEdges() throws IOException {
        Set<String> edges = new LinkedHashSet<>();
        Path agentsRoot = mainJavaRoot().resolve("com/chatchat/agents");
        for (Path source : javaSources("")) {
            Path relative = agentsRoot.relativize(source);
            if (relative.getNameCount() < 2) {
                continue;
            }
            String from = relative.getName(0).toString();
            for (String line : read(source).lines().toList()) {
                String prefix = "import com.chatchat.agents.";
                if (!line.startsWith(prefix)) {
                    continue;
                }
                String imported = line.substring(prefix.length());
                int separator = imported.indexOf('.');
                if (separator <= 0) {
                    continue;
                }
                String to = imported.substring(0, separator);
                if (!from.equals(to)) {
                    edges.add(from + " -> " + to);
                }
            }
        }
        return edges;
    }

    private Path mainJavaRoot() {
        Path moduleLocal = Path.of("src/main/java");
        return Files.isDirectory(moduleLocal)
            ? moduleLocal.toAbsolutePath().normalize()
            : Path.of("chatchat-agents/src/main/java").toAbsolutePath().normalize();
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect source file " + path, exception);
        }
    }
}
