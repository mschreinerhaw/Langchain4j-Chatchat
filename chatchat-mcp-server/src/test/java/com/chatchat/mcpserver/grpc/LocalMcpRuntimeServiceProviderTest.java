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
import com.chatchat.mcpserver.tool.McpToolAlias;
import com.chatchat.mcpserver.tool.McpToolAliasRepository;
import com.chatchat.mcpserver.tool.McpToolChineseAliasResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalMcpRuntimeServiceProviderTest {

    @Test
    void rejectsBeforeAnyHandlerForAllRegisteredToolsAndRestoresContext() {
        var registry = new DefaultToolRegistry();
        var executions = new java.util.concurrent.atomic.AtomicInteger();
        for (String name : java.util.List.of("opaque_reader", "opaque_writer", "opaque_child")) {
            var metadata = ToolMetadata.builder().id(name).metadata(Map.of()).build();
            registry.registerTool(name, metadata, new ToolRegistry.EnhancedTool() {
                public ToolMetadata getMetadata() { return metadata; }
                public ToolOutput execute(ToolInput input) {
                    executions.incrementAndGet(); return ToolOutput.success(Map.of());
                }
            });
        }
        var authorization = mock(com.chatchat.mcpserver.authorization.McpAuthorizationService.class);
        when(authorization.authorize(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap()))
            .thenAnswer(invocation -> {
                assertThat(com.chatchat.mcpserver.mcp.McpInvocationContext.current().userId()).isEqualTo("actual-user");
                return com.chatchat.mcpserver.authorization.McpAuthorizationService.AuthorizationDecision.denyDecision("Database denied");
            });
        var provider = new LocalMcpRuntimeServiceProvider(registry, new McpDynamicToolRegistryMirror(registry), authorization);
        for (String name : registry.getAllToolNames()) {
            var result = provider.invoke(new McpServiceCall(null, "request-denied", LocalMcpRuntimeServiceProvider.SERVICE_ID,
                name, Map.of("userId", "admin", "roles", "SUPER_ADMIN"), Map.of("userId", "actual-user"), 0));
            assertThat(result.status()).isEqualTo(McpServiceResultStatus.REJECTED);
            assertThat(result.errorCode()).isEqualTo("MCP_TOOL_FORBIDDEN");
            assertThat(com.chatchat.mcpserver.mcp.McpInvocationContext.current()).isNull();
        }
        assertThat(executions).hasValue(0);
    }

    @Test
    void carriesEnvelopeIdentityForAnyToolWithoutChangingBusinessArgumentsAndCleansUp() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        var metadata = ToolMetadata.builder().id("arbitrary_capability").metadata(Map.of()).build();
        AtomicReference<com.chatchat.mcpserver.mcp.McpInvocationContext.Context> caller = new AtomicReference<>();
        AtomicReference<Map<String, Object>> arguments = new AtomicReference<>();
        registry.registerTool("arbitrary_capability", metadata, new ToolRegistry.EnhancedTool() {
            @Override public ToolMetadata getMetadata() { return metadata; }
            @Override public ToolOutput execute(ToolInput input) {
                caller.set(com.chatchat.mcpserver.mcp.McpInvocationContext.current());
                arguments.set(input.getParameters());
                return ToolOutput.success(Map.of());
            }
        });
        var provider = new LocalMcpRuntimeServiceProvider(registry, new McpDynamicToolRegistryMirror(registry), allowedAuthorization());
        provider.invoke(new McpServiceCall(null, "request-scope", LocalMcpRuntimeServiceProvider.SERVICE_ID,
            "arbitrary_capability", Map.of("userId", "spoofed-user", "query", "generic query"),
            Map.of("userId", "actual-user", "tenantId", "actual-tenant", "roles", java.util.List.of("reader")), 0));
        assertThat(caller.get().userId()).isEqualTo("actual-user");
        assertThat(caller.get().tenantId()).isEqualTo("actual-tenant");
        assertThat(caller.get().roles()).isEqualTo("reader");
        assertThat(arguments.get()).containsExactlyInAnyOrderEntriesOf(
            Map.of("userId", "spoofed-user", "query", "generic query"));
        assertThat(com.chatchat.mcpserver.mcp.McpInvocationContext.current()).isNull();
        provider.invoke(new McpServiceCall(null, "request-anonymous", LocalMcpRuntimeServiceProvider.SERVICE_ID,
            "arbitrary_capability", Map.of("userId", "spoofed-user"), Map.of(), 0));
        assertThat(caller.get().userId()).isNull();
        assertThat(caller.get().tenantId()).isNull();
    }

    @Test
    void preservesDeclaredResultContractAndStructuredFailureCode() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        var metadata = ToolMetadata.builder().id("arbitrary_evidence").metadata(Map.of(
            "resultKind", "DOCUMENT", "resultSchemaRef", "generic_evidence.v1")).build();
        registry.registerTool("arbitrary_evidence", metadata, new ToolRegistry.EnhancedTool() {
            @Override public ToolMetadata getMetadata() { return metadata; }
            @Override public ToolOutput execute(ToolInput input) {
                return ToolOutput.builder().success(false).exceptionType("AUTHORIZATION_CONTEXT_MISSING")
                    .errorMessage("Identity required").build();
            }
        });
        var provider = new LocalMcpRuntimeServiceProvider(registry, new McpDynamicToolRegistryMirror(registry), allowedAuthorization());
        var result = provider.invoke(new McpServiceCall(null, "request-failed", LocalMcpRuntimeServiceProvider.SERVICE_ID,
            "arbitrary_evidence", Map.of(), Map.of(), 0));
        assertThat(result.errorCode()).isEqualTo("AUTHORIZATION_CONTEXT_MISSING");
        assertThat(result.resultKind()).isEqualTo(com.chatchat.common.mcp.service.McpResultKind.DOCUMENT);
        assertThat(result.resultSchemaRef()).isEqualTo("generic_evidence.v1");
    }

    @Test
    void fillsAliasForExistingToolNameWhenPublishedMetadataHasNoAlias() {
        McpToolAliasRepository aliases = mock(McpToolAliasRepository.class);
        when(aliases.findById("name:api_template_execute"))
            .thenReturn(Optional.of(new McpToolAlias("name:api_template_execute", "API 模板执行")));
        McpToolChineseAliasResolver resolver = new McpToolChineseAliasResolver(aliases, new ObjectMapper());
        try {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        ToolMetadata metadata = ToolMetadata.builder().id("api_template_execute")
            .title("api_template_execute").description("Execute a template").metadata(Map.of()).build();
        registry.registerTool("api_template_execute", metadata, new ToolRegistry.EnhancedTool() {
            @Override public ToolMetadata getMetadata() { return metadata; }
            @Override public ToolOutput execute(ToolInput input) { return ToolOutput.success(Map.of()); }
        });
        LocalMcpRuntimeServiceProvider provider = new LocalMcpRuntimeServiceProvider(
            registry, new McpDynamicToolRegistryMirror(registry), allowedAuthorization());

        assertThat(provider.tools(McpToolQuery.all())).singleElement()
            .satisfies(tool -> assertThat(tool.metadata()).containsEntry("chineseAlias", "API 模板执行"));
        } finally {
            resolver.close();
        }
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
            registry, new McpDynamicToolRegistryMirror(registry), allowedAuthorization());
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
            registry, new McpDynamicToolRegistryMirror(registry), allowedAuthorization());

        var result = provider.invoke(new McpServiceCall(null, "request-2",
            LocalMcpRuntimeServiceProvider.SERVICE_ID,
            LocalMcpRuntimeServiceProvider.LOCAL_PREFIX + "customer_service_template_query",
            Map.of("limit", 10, "_templateQueryChildToolName", "spoofed-child"), Map.of(), 0));

        assertThat(result.status()).isEqualTo(McpServiceResultStatus.SUCCESS);
        assertThat(received.get()).containsEntry("_templateQueryChildToolName",
            "customer_service_template_query");
    }
    private com.chatchat.mcpserver.authorization.McpAuthorizationService allowedAuthorization() {
        var authorization = mock(com.chatchat.mcpserver.authorization.McpAuthorizationService.class);
        when(authorization.authorize(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(com.chatchat.mcpserver.authorization.McpAuthorizationService.AuthorizationDecision.allowDecision());
        return authorization;
    }}
