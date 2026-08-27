package com.chatchat.integration.mcp.service.directory;

import com.chatchat.integration.mcp.service.config.McpServiceConfigService;
import com.chatchat.integration.mcp.service.routing.McpToolRegistryBridge;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceProvider;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.mcp.service.McpToolQuery;
import com.chatchat.common.mcp.capability.McpCapabilityHierarchy;
import com.chatchat.common.mcp.capability.McpDynamicCapabilityRoute;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.integration.mcp.entity.McpServiceConfig;
import com.chatchat.integration.mcp.model.McpToolInvokeResult;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapts administratively configured remote MCP services to the common Runtime OS SPI. */
@Component
public class ConfiguredRemoteMcpServiceProvider implements McpServiceProvider {
    public static final String PROVIDER_ID = "configured-remote-mcp";
    private final McpServiceConfigService configService;
    private final McpToolRegistryBridge registryBridge;
    private final ToolRegistry toolRegistry;

    public ConfiguredRemoteMcpServiceProvider(McpServiceConfigService configService,
                                              McpToolRegistryBridge registryBridge,
                                              ToolRegistry toolRegistry) {
        this.configService = configService;
        this.registryBridge = registryBridge;
        this.toolRegistry = toolRegistry;
    }

    @Override public String providerId() { return PROVIDER_ID; }

    @Override
    public Collection<McpServiceDescriptor> services() {
        return configService.listAll().stream().map(this::descriptor).toList();
    }

    @Override
    public Collection<McpToolDescriptor> tools(McpToolQuery query) {
        McpToolQuery effective = query == null ? McpToolQuery.all() : query;
        return registryBridge.listRegisteredTools().stream().map(this::descriptor).filter(effective::matches).toList();
    }

    @Override
    public boolean supports(String serviceId, String toolName) {
        return registryBridge.listRegisteredTools().stream().anyMatch(tool ->
            tool.serviceId().equals(serviceId) && (tool.localToolName().equals(toolName) || tool.remoteToolName().equals(toolName)));
    }

    @Override
    public McpServiceResult invoke(McpServiceCall call) {
        McpToolInvokeResult result = registryBridge.invoke(call);
        return new McpServiceResult(null, call.requestId(), call.serviceId(), call.toolName(),
            result.success() ? McpServiceResultStatus.SUCCESS : McpServiceResultStatus.FAILED,
            result.data(), result.rawData(), result.errorCode(), result.errorMessage(), result.retryable(), result.action(),
            result.executionState(), 0);
    }

    @Override public void refresh() { registryBridge.refreshRegistry(); }

    private McpServiceDescriptor descriptor(McpServiceConfig config) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timeoutMs", config.getTimeoutMs());
        metadata.put("contractAutoPublish", config.isContractAutoPublish());
        metadata.put("endpointConfigured", config.getBaseUrl() != null || config.getStdioCommand() != null);
        metadata.put("createdAt", config.getCreatedAt());
        metadata.put("updatedAt", config.getUpdatedAt());
        return new McpServiceDescriptor(config.getId(), config.getName(), providerId(), config.getProtocol(),
            config.isEnabled(), metadata);
    }

    private McpToolDescriptor descriptor(McpToolRegistryBridge.RegisteredMcpTool tool) {
        ToolMetadata source = toolRegistry.getToolMetadata(tool.localToolName());
        Map<String, Object> extra = source == null || source.getMetadata() == null ? Map.of() : source.getMetadata();
        Map<String, Object> governance = new LinkedHashMap<>();
        if (source != null) {
            governance.put("riskLevel", source.getRiskLevel());
            governance.put("operationType", source.getOperationType());
            governance.put("runtimeLevel", source.getRuntimeLevel());
            governance.put("confirmation", source.getConfirmation());
            governance.put("permissions", source.getPermissions());
            governance.put("inputPolicy", source.getInputPolicy());
            governance.put("outputPolicy", source.getOutputPolicy());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("serviceName", tool.serviceName());
        metadata.put("backendServiceType", tool.backendServiceType());
        metadata.put("categories", tool.categories());
        metadata.put("tags", tool.tags());
        metadata.put("applicability", tool.applicability());
        metadata.put("contractVersion", extra.get("contractVersion"));
        metadata.put("workflowContractVersion", extra.get("workflowContractVersion"));
        metadata.put("workflowContractChecksum", extra.get("workflowContractChecksum"));
        metadata.put("contractMeta", safeContractMeta(extra.get("mcpToolMeta")));
        Map<String, Object> workflowContract = map(extra.get(ToolWorkflowContract.METADATA_KEY));
        if (workflowContract.isEmpty()) {
            workflowContract = map(map(extra.get("mcpToolMeta")).get(ToolWorkflowContract.METADATA_KEY));
        }
        if (!workflowContract.isEmpty()) {
            metadata.put(ToolWorkflowContract.METADATA_KEY, workflowContract);
        }
        metadata.put(McpCapabilityHierarchy.METADATA_KEY, tool.capabilityNode() == null
            ? map(extra.get(McpCapabilityHierarchy.METADATA_KEY))
            : tool.capabilityNode().toMetadata());
        metadata.put(McpDynamicCapabilityRoute.METADATA_KEY,
            map(extra.get(McpDynamicCapabilityRoute.METADATA_KEY)));
        return new McpToolDescriptor(tool.serviceId(), tool.localToolName(), tool.remoteToolName(), tool.description(),
            tool.category(), map(extra.get("inputSchema")), map(extra.get("outputSchema")), governance, metadata);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> values ? (Map<String, Object>) values : Map.of();
    }

    private Map<String, Object> safeContractMeta(Object value) {
        Map<String, Object> source = map(value);
        Map<String, Object> safe = new LinkedHashMap<>();
        List.of("capabilityCode", "providerModule", "contractVersion", "runtimeAction", "readOnly",
            "technicalType", "backendServiceType", "templateRegistryRequired", "templateSelectionPolicy",
            "templates", "resultSchema", "outputSchema", "toolResultInstruction", "tags")
            .forEach(key -> { if (source.containsKey(key)) safe.put(key, source.get(key)); });
        return safe;
    }
}
