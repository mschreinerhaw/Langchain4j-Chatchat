package com.chatchat.mcpserver.grpc;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.mcp.service.McpResultKind;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceProvider;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.mcp.service.McpToolQuery;
import com.chatchat.common.mcp.contract.McpToolContractValidator;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.tool.McpDynamicToolRegistryMirror;
import com.chatchat.mcpserver.tool.McpToolChineseAliasResolver;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exposes this MCP publisher's live ToolRegistry to its Runtime OS gRPC kernel. */
@Component
@org.springframework.context.annotation.DependsOn("mcpToolChineseAliasResolver")
public final class LocalMcpRuntimeServiceProvider implements McpServiceProvider {

    static final String SERVICE_ID = "chatchat-mcp-server";
    static final String LOCAL_PREFIX = "mcp_chatchat_mcp_server_";
    private final ToolRegistry toolRegistry;
    private final McpDynamicToolRegistryMirror publicationMirror;

    public LocalMcpRuntimeServiceProvider(ToolRegistry toolRegistry,
                                          McpDynamicToolRegistryMirror publicationMirror) {
        this.toolRegistry = toolRegistry;
        this.publicationMirror = publicationMirror;
    }

    @Override
    public String providerId() {
        return "local-mcp-tool-registry";
    }

    @Override
    public Collection<McpServiceDescriptor> services() {
        return List.of(new McpServiceDescriptor(
            SERVICE_ID, "ChatChat MCP Server", providerId(), "in-process", true,
            Map.of("liveToolRegistry", true)));
    }

    @Override
    public Collection<McpToolDescriptor> tools(McpToolQuery query) {
        McpToolQuery effective = query == null ? McpToolQuery.all() : query;
        return toolRegistry.getAllToolNames().stream()
            .map(this::descriptor)
            .filter(effective::matches)
            .toList();
    }

    @Override
    public boolean supports(String serviceId, String toolName) {
        return SERVICE_ID.equals(serviceId) && resolveRegistryName(toolName) != null;
    }

    @Override
    public McpServiceResult invoke(McpServiceCall call) {
        String registryName = resolveRegistryName(call.toolName());
        if (registryName == null) {
            return new McpServiceResult(null, call.requestId(), call.serviceId(), call.toolName(),
                McpServiceResultStatus.NOT_FOUND, null, null, "MCP_TOOL_NOT_FOUND",
                "Local MCP tool is not registered", true, "REFRESH_OR_DISCOVER", Map.of(), 0);
        }
        Map<String, Object> arguments = new LinkedHashMap<>(call.arguments());
        String executionName = resolveExecutionName(registryName, arguments);
        ToolOutput output = toolRegistry.executeEnhancedTool(executionName, ToolInput.builder()
            .parameters(arguments)
            .rawInput(String.valueOf(call.arguments()))
            .requestId(call.requestId())
            .userId(text(call.context(), "userId", "user_id"))
            .conversationId(text(call.context(), "conversationId", "conversation_id"))
            .context(new LinkedHashMap<>(call.context()))
            .build());
        Map<String, Object> metadata = new LinkedHashMap<>(output == null || output.getMetadata() == null
            ? Map.of() : output.getMetadata());
        Map<String, Object> payload = map(output == null ? null : output.getData());
        for (String key : List.of(McpServiceResult.RESULT_KIND_KEY,
            McpServiceResult.RESULT_SCHEMA_REF_KEY, McpServiceResult.PROVENANCE_KEY,
            McpServiceResult.PAGINATION_KEY)) {
            if (payload.get(key) != null) metadata.putIfAbsent(key, payload.get(key));
        }
        boolean success = output != null && output.isSuccess();
        return new McpServiceResult(null, call.requestId(), call.serviceId(), call.toolName(),
            success ? McpServiceResultStatus.SUCCESS : McpServiceResultStatus.FAILED,
            output == null ? null : output.getData(), output == null ? null : output.getData(),
            success ? null : "MCP_TOOL_EXECUTION_FAILED",
            success ? null : firstText(output == null ? null : output.getErrorMessage(),
                output == null ? null : output.getMessage(), "Local MCP tool execution failed"),
            false, null, metadata, McpResultKind.parse(metadata.get(McpServiceResult.RESULT_KIND_KEY)),
            text(metadata, McpServiceResult.RESULT_SCHEMA_REF_KEY), null, null, 0);
    }

    @Override
    public void refresh() {
        // ToolRegistry is live and dynamic publications are mirrored into it.
    }

    private McpToolDescriptor descriptor(String registryName) {
        ToolMetadata source = toolRegistry.getToolMetadata(registryName);
        McpSchema.Tool published = publicationMirror.publishedContract(registryName);
        Map<String, Object> extra = new LinkedHashMap<>(source == null || source.getMetadata() == null
            ? Map.of() : source.getMetadata());
        if (published != null && published.meta() != null) extra.putAll(published.meta());
        String alias = McpToolChineseAliasResolver.resolve(registryName,
            published == null ? null : published.title(), extra);
        if (alias == null) alias = McpToolChineseAliasResolver.resolve(registryName,
            source == null ? null : source.getTitle(), extra);
        if (alias != null) extra.put("chineseAlias", alias);
        extra.putIfAbsent("contractVersion", McpToolContractValidator.CONTRACT_VERSION);
        Map<String, Object> inputSchema = canonicalObjectSchema(published == null
            ? map(extra.get("inputSchema")) : published.inputSchema());
        Map<String, Object> outputSchema = canonicalObjectSchema(published == null
            ? firstMap(extra, "outputSchema", "resultSchema", "output_schema", "result_schema")
            : firstMap(published.outputSchema(), extra, "outputSchema", "resultSchema",
                "output_schema", "result_schema"));
        Map<String, Object> governance = new LinkedHashMap<>();
        if (source != null) {
            governance.put("riskLevel", source.getRiskLevel());
            governance.put("operationType", source.getOperationType());
            governance.put("runtimeLevel", source.getRuntimeLevel());
            governance.put("confirmation", source.getConfirmation());
            governance.put("permissions", source.getPermissions());
        }
        return new McpToolDescriptor(SERVICE_ID, localName(registryName), registryName,
            source == null ? registryName : firstText(source.getDescription(), registryName),
            source == null ? null : source.getCategory(), inputSchema,
            outputSchema, governance, extra);
    }

    private String resolveRegistryName(String requested) {
        if (requested == null || requested.isBlank()) return null;
        if (toolRegistry.hasTool(requested)) return requested;
        if (requested.startsWith(LOCAL_PREFIX)) {
            String remote = requested.substring(LOCAL_PREFIX.length());
            if (toolRegistry.hasTool(remote)) return remote;
        }
        return null;
    }

    private String resolveExecutionName(String registryName, Map<String, Object> arguments) {
        ToolMetadata source = toolRegistry.getToolMetadata(registryName);
        Map<String, Object> metadata = source == null ? Map.of() : source.getMetadata();
        Map<String, Object> route = map(metadata == null ? null : metadata.get("mcpDynamicCapabilityRoute"));
        if (route.isEmpty() || !"parent_delegation".equals(text(route, "routingMode"))) {
            return registryName;
        }
        String parent = text(route, "parentToolName");
        String identityArgument = text(route, "implementationIdentityArgument");
        if (parent == null || identityArgument == null || !toolRegistry.hasTool(parent)) {
            return registryName;
        }
        arguments.putIfAbsent(identityArgument, registryName);
        return parent;
    }

    private String localName(String registryName) {
        return registryName.startsWith("mcp_") ? registryName : LOCAL_PREFIX + registryName;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private Map<String, Object> canonicalObjectSchema(Map<String, Object> source) {
        Map<String, Object> schema = new LinkedHashMap<>(source == null ? Map.of() : source);
        schema.putIfAbsent("type", "object");
        schema.putIfAbsent("additionalProperties", true);
        return Map.copyOf(schema);
    }

    private Map<String, Object> firstMap(Map<String, Object> values, String... keys) {
        if (values != null) {
            for (String key : keys) {
                Map<String, Object> candidate = map(values.get(key));
                if (!candidate.isEmpty()) return candidate;
            }
        }
        return Map.of();
    }

    private Map<String, Object> firstMap(Map<String, Object> preferred,
                                         Map<String, Object> fallback,
                                         String... keys) {
        if (preferred != null && !preferred.isEmpty()) return preferred;
        return firstMap(fallback, keys);
    }

    private String text(Map<String, Object> values, String... keys) {
        if (values == null) return null;
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value).trim();
        }
        return null;
    }

    private String firstText(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }
}
