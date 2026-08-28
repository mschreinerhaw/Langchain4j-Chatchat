package com.chatchat.api.architecture;

import com.chatchat.agents.tool.RegistryMcpCapabilityHierarchy;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.orchestration.AgentWorkflowDecisionEngine;

import com.chatchat.agents.runtime.execution.DefaultAgentRuntime;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;

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
    void dataAnalysisSummaryModelAndModelPortLiveInCommon() {
        assertThat(source(
            "chatchat-common/src/main/java/com/chatchat/common/runtime/summary/DataAnalysisSummary.java"))
            .contains("interface DataAnalysisSummary extends ModelSummary",
                "DataAnalysisIsolationScope isolationScope()")
            .doesNotContain("com.chatchat.agents", "dev.langchain4j", "org.springframework");
        assertThat(source(
            "chatchat-common/src/main/java/com/chatchat/common/runtime/summary/DataAnalysisSummaryProtocol.java"))
            .contains("interface DataAnalysisSummaryProtocol", "extends RuntimeProtocolPort",
                "ModelSummaryModel model", "DataAnalysisPosition position")
            .doesNotContain("com.chatchat.agents", "dev.langchain4j", "org.springframework");
        assertThat(source(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/AgentOrchestrator.java"))
            .contains("DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope>")
            .doesNotContain("RuntimeAnalysisSummaryProtocol", "RuntimeAnalysisPosition");
        assertThat(root().resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/runtime/protocol/RuntimeAnalysisSummaryProtocol.java"))
            .doesNotExist();
        assertThat(root().resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/runtime/protocol/RuntimeAnalysisSummary.java"))
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
            .contains("McpRuntimeTransportPort", "@Qualifier(\"mcpRuntimeTransportPort\")")
            .doesNotContain("McpRuntimeKernel", "com.chatchat.integration",
                "McpToolRegistryBridge", "McpGatewayClient");
    }

    @Test
    void apiToMcpSouthboundTransportUsesVersionedStreamingGrpc() {
        assertThat(source("chatchat-mcp-grpc/src/main/proto/mcp_runtime.proto"))
            .contains("service McpRuntimeService", "returns (stream PayloadChunk)",
                "rpc Invoke", "rpc Repair", "rpc Refresh");
        assertThat(source(
            "chatchat-integration/src/main/java/com/chatchat/integration/mcp/grpc/GrpcMcpRuntimeTransportClient.java"))
            .contains("implements McpRuntimeTransportPort", "McpGrpcPayloads.assemble",
                "withCompression(\"gzip\")", "withDeadlineAfter");
        assertThat(source(
            "chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/grpc/McpRuntimeGrpcService.java"))
            .contains("McpGrpcPayloads.emit", "kernel.execute", "setCompression(\"gzip\")");
        assertThat(source(
            "chatchat-integration/src/main/java/com/chatchat/integration/mcp/grpc/GrpcBackedMcpRuntimeKernel.java"))
            .contains("implements McpRuntimeKernel", "transport.invoke(call)");
        assertThat(source(
            "chatchat-integration/src/main/java/com/chatchat/integration/mcp/service/config/McpRuntimeKernelConfiguration.java"))
            .contains("GrpcBackedMcpRuntimeKernel", "chatchat.mcp.grpc.client");
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
        String runtime = source("chatchat-agents/src/main/java/com/chatchat/agents/runtime/tool/ToolRuntimeService.java");
        assertThat(runtime).contains("kernel.execute(new McpServiceCall");
        assertThat(runtime).doesNotContain("McpGatewayClient", "McpToolRegistryBridge");
    }

    @Test
    void bridgeCoreHasNoApiSpecialCaseAndTemplateCapabilityOwnsItsPort() {
        assertThat(root().resolve(
            "chatchat-common/src/main/java/com/chatchat/common/bridge/api"))
            .doesNotExist();
        assertThat(allJava("chatchat-common/src/main/java/com/chatchat/common/bridge"))
            .doesNotContain("McpApi", "KernelChannel.API", "chatchat.api.bridge");

        String port = source(
            "chatchat-common/src/main/java/com/chatchat/common/knowledge/template/TemplateServicePort.java");
        assertThat(port).contains("interface TemplateServicePort extends RuntimeBridge");
        assertThat(source(
            "chatchat-common/src/main/java/com/chatchat/common/knowledge/template/TemplateServiceCall.java"))
            .doesNotContain("caller", "targetService", "Http", "URL");
    }

    @Test
    void allRuntimeCoreModulesAreIndependentFromIntegrationDrivers() {
        for (String module : java.util.List.of(
            "chatchat-common", "chatchat-runtime-mcp", "chatchat-agents", "chatchat-chat",
            "chatchat-enterprise", "chatchat-api", "chatchat-mcp-server")) {
            assertThat(allJava(module + "/src/main/java"))
                .as(module + " must depend on ports, not integration implementations")
                .doesNotContain("import com.chatchat.integration.");
        }
        assertThat(source("chatchat-chat/pom.xml"))
            .doesNotContain("<artifactId>chatchat-integration</artifactId>");
        assertThat(source("chatchat-enterprise/pom.xml"))
            .doesNotContain("<artifactId>chatchat-integration</artifactId>");
        assertThat(source("chatchat-mcp-server/pom.xml")).containsPattern(
            "(?s)<artifactId>chatchat-integration</artifactId>\\s*.*?<scope>runtime</scope>");
        assertThat(source(
            "chatchat-integration/src/main/java/com/chatchat/integration/mcp/capability/DefaultMcpCapabilityStateAdapter.java"))
            .contains("implements McpCapabilityStatePort");
        assertThat(source(
            "chatchat-chat/src/main/java/com/chatchat/chat/interaction/service/AgentToolPolicyResolver.java"))
            .contains("private final McpToolCatalogQueryPort mcpToolCatalog")
            .doesNotContain("McpToolRegistryBridge");
    }

    @Test
    void workflowKernelContractPropagatesScopeAndUsesStableIdentity() {
        String workflow = source(
            "chatchat-common/src/main/java/com/chatchat/common/runtime/workflow/RuntimeWorkflow.java");
        assertThat(workflow)
            .contains("String workflowId()", "execute(payload, scope)")
            .doesNotContain("getClass().getName()");
        assertThat(source(
            "chatchat-agents/src/main/java/com/chatchat/agents/runtime/execution/DefaultAgentRuntime.java"))
            .contains("runExecutor.execute(request, scope)");
    }

    @Test
    void publishedMcpServicesUseCapabilityTreeIdentityAcrossRuntimeLayers() {
        assertThat(source(
            "chatchat-common/src/main/java/com/chatchat/common/mcp/capability/McpCapabilityHierarchy.java"))
            .contains("interface McpCapabilityHierarchy", "sameNode", "lineage",
                "isImplementationOf", "mostSpecific", "directlyInvocable");
        assertThat(source(
            "chatchat-common/src/main/java/com/chatchat/common/mcp/capability/McpCapabilityRouteContract.java"))
            .contains("interface McpCapabilityRouteContract", "parentToolName()",
                "implementationIdentityArgument()", "toMetadata()");
        assertThat(source(
            "chatchat-common/src/main/java/com/chatchat/common/mcp/capability/McpDynamicCapabilityRoute.java"))
            .contains("implements McpCapabilityRouteContract", "mcp.dynamic-capability-route.v1");
        assertThat(source(
            "chatchat-integration/src/main/java/com/chatchat/integration/mcp/service/routing/McpToolRegistryBridge.java"))
            .contains("McpCapabilityHierarchy.METADATA_KEY", "McpCapabilityNode",
                "addToolsChangeListener", "drainToolsChangeRefreshes");
        assertThat(source(
            "chatchat-integration/src/main/java/com/chatchat/integration/mcp/service/transport/McpGatewayClient.java"))
            .contains("toolsChangeConsumer", "notifyToolsChanged");
        assertThat(source(
            "chatchat-integration/src/main/java/com/chatchat/integration/mcp/service/directory/ConfiguredRemoteMcpServiceProvider.java"))
            .contains("McpDynamicCapabilityRoute.METADATA_KEY");
        assertThat(source(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/tool/AgentToolNameResolver.java"))
            .contains("capabilityHierarchy.sameNode");
        assertThat(source(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/AgentWorkflowDecisionEngine.java"))
            .contains("RegistryMcpCapabilityHierarchy", "capabilityHierarchy.sameNode",
                "preferBusinessImplementations");
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
