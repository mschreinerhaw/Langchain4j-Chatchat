package com.chatchat.api.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Source-level guard for the dependency direction of the Runtime OS core. */
class RuntimeOsArchitectureBoundaryTest {

    @Test
    void genericRuntimeContractsLiveInCommonAndNotAgent() {
        assertThat(source("chatchat-common/src/main/java/com/chatchat/common/runtime/event/RuntimeEvent.java"))
            .contains("package com.chatchat.common.runtime.event");
        assertThat(source("chatchat-common/src/main/java/com/chatchat/common/runtime/protocol/RuntimeProtocolRegistry.java"))
            .contains("package com.chatchat.common.runtime.protocol");
        assertThat(root().resolve("chatchat-agents/src/main/java/com/chatchat/agents/runtime/event/RuntimeEvent.java"))
            .doesNotExist();
        assertThat(source("chatchat-common/src/main/java/com/chatchat/common/runtime/workflow/RuntimeWorkflow.java"))
            .contains("extends RuntimeOsKernel");
        assertThat(root().resolve("chatchat-agents/src/main/java/com/chatchat/agents/runtime/workflow/RuntimeWorkflow.java"))
            .doesNotExist();
    }

    @Test
    void mcpRuntimeCoreDoesNotDependOnAgentOrIntegrationImplementations() {
        String pom = source("chatchat-runtime-mcp/pom.xml");
        assertThat(pom).doesNotContain("<artifactId>chatchat-agents</artifactId>",
            "<artifactId>chatchat-integration</artifactId>");
        assertThat(allJava("chatchat-runtime-mcp/src/main/java"))
            .doesNotContain("import com.chatchat.agents.", "import com.chatchat.integration.");
        assertThat(source("chatchat-runtime-mcp/src/main/java/com/chatchat/runtime/mcp/kernel/DefaultMcpRuntimeKernel.java"))
            .contains("implements McpRuntimeKernel");
    }

    @Test
    void publicMcpRuntimeApiDependsOnlyOnProtocolAndApplicationPort() {
        String controller = source("chatchat-api/src/main/java/com/chatchat/api/controller/McpRuntimeBridgeController.java");
        assertThat(controller).doesNotContain("com.chatchat.integration", "McpToolRegistryBridge", "McpGatewayClient");
        assertThat(source("chatchat-api/src/main/java/com/chatchat/api/service/McpRuntimeAccessService.java"))
            .doesNotContain("com.chatchat.integration", "McpToolRegistryBridge", "McpGatewayClient");
    }

    @Test
    void mcpAdministrationControllerDependsOnlyOnCommonControlPlanePort() {
        String port = source(
            "chatchat-common/src/main/java/com/chatchat/common/mcp/admin/McpAdministrationPort.java");
        assertThat(port).contains("interface McpAdministrationPort extends RuntimeProtocolPort",
            "runtime_os.mcp.administration.v1");

        String controller = source("chatchat-api/src/main/java/com/chatchat/api/controller/McpServiceController.java");
        assertThat(controller).contains("private final McpAdministrationPort administrationPort");
        assertThat(controller).doesNotContain("com.chatchat.integration", "com.chatchat.agents",
            "McpToolRegistryBridge", "McpServiceConfigService", "McpStdioProxyService");

        String adapter = source(
            "chatchat-integration/src/main/java/com/chatchat/integration/mcp/admin/DefaultMcpAdministrationAdapter.java");
        assertThat(adapter).contains("implements McpAdministrationPort");
    }

    @Test
    void entireApiMainSourceIsIndependentFromIntegrationImplementations() {
        assertThat(allJava("chatchat-api/src/main/java"))
            .doesNotContain("import com.chatchat.integration.");

        String pom = source("chatchat-api/pom.xml");
        assertThat(pom).containsPattern(
            "(?s)<artifactId>chatchat-integration</artifactId>\\s*.*?<scope>runtime</scope>");
    }

    @Test
    void agentMcpExecutionUsesKernelInsteadOfTransportAdapter() {
        String runtime = source("chatchat-agents/src/main/java/com/chatchat/agents/runtime/ToolRuntimeService.java");
        assertThat(runtime).contains("kernel.execute(new McpServiceCall");
        assertThat(runtime).doesNotContain("McpGatewayClient", "McpToolRegistryBridge");
    }

    private String allJava(String relativeRoot) {
        try (var files = Files.walk(root().resolve(relativeRoot))) {
            return files.filter(path -> path.toString().endsWith(".java"))
                .map(this::read).reduce("", (left, right) -> left + "\n" + right);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private String source(String relative) { return read(root().resolve(relative)); }

    private String read(Path path) {
        try { return Files.readString(path); }
        catch (IOException error) { throw new IllegalStateException("Cannot read " + path, error); }
    }

    private Path root() {
        Path current = Path.of("").toAbsolutePath().normalize();
        return Files.isDirectory(current.resolve("chatchat-common")) ? current : current.getParent();
    }
}
