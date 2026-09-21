package com.chatchat.mcpserver.grpc;

import com.chatchat.agents.tool.DefaultToolRegistry;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.mcp.service.McpToolQuery;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.tool.McpDynamicToolRegistryMirror;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LocalMcpRuntimeServiceProviderTest {

    @Test
    void fillsAliasForExistingToolNameWhenPublishedMetadataHasNoAlias() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        ToolMetadata metadata = ToolMetadata.builder().id("api_template_execute")
            .title("api_template_execute").description("Execute a template").metadata(Map.of()).build();
        registry.registerTool("api_template_execute", metadata, new ToolRegistry.EnhancedTool() {
            @Override public ToolMetadata getMetadata() { return metadata; }
            @Override public ToolOutput execute(ToolInput input) { return ToolOutput.success(Map.of()); }
        });
        LocalMcpRuntimeServiceProvider provider = new LocalMcpRuntimeServiceProvider(
            registry, new McpDynamicToolRegistryMirror(registry));

        assertThat(provider.tools(McpToolQuery.all())).singleElement()
            .satisfies(tool -> assertThat(tool.metadata()).containsEntry("chineseAlias", "API 模板执行"));
    }

    @Test
    void publishesLocalAliasAndInvokesMirroredTool() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        ToolMetadata metadata = ToolMetadata.builder().id("customer_template_query")
            .description("customer template discovery")
            .metadata(Map.of("inputSchema", Map.of("type", "object")))
            .build();
        registry.registerTool("customer_template_query", metadata, new ToolRegistry.EnhancedTool() {
            @Override public ToolMetadata getMetadata() { return metadata; }
            @Override public ToolOutput execute(ToolInput input) {
                return ToolOutput.success(Map.of(
                    "received", input.getParameters().get("customerNo"),
                    "resultKind", "RAW_RECORDS",
                    "resultSchemaRef", "template_query_result.v1"));
            }
        });
        LocalMcpRuntimeServiceProvider provider = new LocalMcpRuntimeServiceProvider(
            registry, new McpDynamicToolRegistryMirror(registry));
        String localName = LocalMcpRuntimeServiceProvider.LOCAL_PREFIX + "customer_template_query";

        assertThat(provider.tools(McpToolQuery.all())).singleElement().satisfies(tool -> {
            assertThat(tool.localToolName()).isEqualTo(localName);
            assertThat(tool.remoteToolName()).isEqualTo("customer_template_query");
            assertThat(tool.inputSchema()).containsEntry("type", "object");
            assertThat(tool.outputSchema()).containsEntry("type", "object");
            assertThat(tool.metadata()).containsEntry("contractVersion", "mcp_tool_contract.v1");
        });
        var result = provider.invoke(new McpServiceCall(null, "request-1",
            LocalMcpRuntimeServiceProvider.SERVICE_ID, localName,
            Map.of("customerNo", "070200046604"), Map.of("userId", "user-1"), 0));

        assertThat(result.status()).isEqualTo(McpServiceResultStatus.SUCCESS);
        assertThat(result.data()).isEqualTo(Map.of(
            "received", "070200046604",
            "resultKind", "RAW_RECORDS",
            "resultSchemaRef", "template_query_result.v1"));
        assertThat(result.resultKind()).isEqualTo(com.chatchat.common.mcp.service.McpResultKind.RAW_RECORDS);
        assertThat(result.resultSchemaRef()).isEqualTo("template_query_result.v1");
    }

    @Test
    void delegatesScopedChildCapabilityToDeclaredParent() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        ToolMetadata parentMetadata = ToolMetadata.builder().id("api_template_query")
            .description("parent template query").metadata(Map.of()).build();
        registry.registerTool("api_template_query", parentMetadata, new ToolRegistry.EnhancedTool() {
            @Override public ToolMetadata getMetadata() { return parentMetadata; }
            @Override public ToolOutput execute(ToolInput input) {
                received.set(input.getParameters());
                return ToolOutput.success(Map.of("templates", java.util.List.of()));
            }
        });
        ToolMetadata childMetadata = ToolMetadata.builder().id("customer_service_template_query")
            .description("scoped customer query")
            .metadata(Map.of("mcpDynamicCapabilityRoute", Map.of(
                "routingMode", "parent_delegation",
                "parentToolName", "api_template_query",
                "implementationIdentityArgument", "_templateQueryChildToolName")))
            .build();
        registry.registerTool("customer_service_template_query", childMetadata,
            new ToolRegistry.EnhancedTool() {
                @Override public ToolMetadata getMetadata() { return childMetadata; }
                @Override public ToolOutput execute(ToolInput input) {
                    return ToolOutput.failure("child must not execute directly");
                }
            });
        LocalMcpRuntimeServiceProvider provider = new LocalMcpRuntimeServiceProvider(
            registry, new McpDynamicToolRegistryMirror(registry));

        var result = provider.invoke(new McpServiceCall(null, "request-2",
            LocalMcpRuntimeServiceProvider.SERVICE_ID,
            LocalMcpRuntimeServiceProvider.LOCAL_PREFIX + "customer_service_template_query",
            Map.of("limit", 10), Map.of(), 0));

        assertThat(result.status()).isEqualTo(McpServiceResultStatus.SUCCESS);
        assertThat(received.get()).containsEntry("_templateQueryChildToolName",
            "customer_service_template_query");
    }
}
