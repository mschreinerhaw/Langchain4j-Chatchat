package com.chatchat.e2e;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionReleaseCoverageE2E {

    @Test
    void releaseGateOwnsEveryRequiredSystemScenarioFamily() throws IOException {
        Path root = repositoryRoot();
        String dataSuite = Files.readString(root.resolve("scripts/test-data-capability-center.ps1"));
        String reasoningSuite = Files.readString(root.resolve("scripts/test-agent-extreme-reasoning.ps1"));
        String releaseSuite = Files.readString(root.resolve("scripts/test-production-release-e2e.ps1"));
        String combined = dataSuite + reasoningSuite;

        assertThat(combined).contains(
            "AgentOrchestratorTest",
            "InterpretationPlanRuntimeTest",
            "RuntimeDeploymentHardcodingTest",
            "ApiTemplateDiscoveryMcpToolPublisherTest",
            "HttpRequestToolServiceLivedataTest",
            "LinuxCommandServiceTest",
            "SqlQueryExecuteServiceTest",
            "CommandTemplateDiscoveryDatabaseQueryTest",
            "FinancialQueryRuntimeContractAcceptanceTest"
        );
        assertThat(releaseSuite)
            .contains("chatchat-e2e-tests", "-am", "verify", "frontend.skip=true");
        assertThat(Files.readString(root.resolve("pom.xml")))
            .contains("<module>chatchat-e2e-tests</module>");
        assertThat(Files.readString(root.resolve(
            "chatchat-e2e-tests/src/test/java/com/chatchat/e2e/ProductionWebSearchTimeoutIsolationE2E.java")))
            .contains("timeoutStormCancelsTheRuntimeChainAvoidsDatabaseWorkAndRecoversWithoutRestart",
                "McpToolConcurrencyManager", "RemoteNewsMcpToolProvider", "never()).query");
    }

    @Test
    void runtimeContainsNoDeploymentSpecificMcpNamespaceOrMaintainedTemplateLiteral() throws IOException {
        Path runtimeSource = repositoryRoot().resolve("chatchat-agents/src/main/java");
        String forbiddenNamespace = "mcp_" + "chatchat_mcp_server_";

        try (Stream<Path> paths = Files.walk(runtimeSource)) {
            List<Path> violations = paths
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    try {
                        String source = Files.readString(path);
                        return source.contains(forbiddenNamespace)
                            || source.matches("(?s).*[\\\"]sample_[a-zA-Z0-9_]+[\\\"].*");
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .toList();
            assertThat(violations).isEmpty();
        }
    }

    private Path repositoryRoot() {
        String configured = System.getProperty("chatchat.e2e.repository-root");
        Path root = configured == null || configured.isBlank()
            ? Path.of("").toAbsolutePath().normalize().getParent()
            : Path.of(configured).toAbsolutePath().normalize();
        assertThat(root.resolve("pom.xml")).isRegularFile();
        return root;
    }
}
