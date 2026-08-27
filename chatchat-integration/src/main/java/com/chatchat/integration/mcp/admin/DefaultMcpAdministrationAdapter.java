package com.chatchat.integration.mcp.admin;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.mcp.admin.McpAdministrationPort;
import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.integration.mcp.entity.McpServiceConfig;
import com.chatchat.integration.mcp.model.McpToolDefinition;
import com.chatchat.integration.mcp.service.center.McpCenterRecoveryService;
import com.chatchat.integration.mcp.service.center.McpCenterSyncService;
import com.chatchat.integration.mcp.service.config.McpServiceConfigService;
import com.chatchat.integration.mcp.service.transport.McpStdioProxyService;
import com.chatchat.integration.mcp.service.routing.McpToolRegistryBridge;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Integration-side implementation of the MCP administration control-plane port. */
@Service
@RequiredArgsConstructor
public class DefaultMcpAdministrationAdapter implements McpAdministrationPort {
    private final McpServiceConfigService configService;
    private final McpStdioProxyService stdioProxyService;
    private final McpToolRegistryBridge registryBridge;
    private final McpRuntimeKernel runtimeKernel;
    private final McpCenterSyncService centerSyncService;
    private final McpCenterRecoveryService centerRecoveryService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public List<ServiceConfiguration> listServices() {
        return configService.listAll().stream().map(this::toConfiguration).toList();
    }

    @Override
    public ServiceConfiguration createService(ServiceConfigurationDraft draft) {
        McpServiceConfig saved = configService.create(fromDraft(draft));
        closeAndRefresh(saved.getId());
        return toConfiguration(saved);
    }

    @Override
    public ServiceConfiguration updateService(String serviceId, ServiceConfigurationDraft draft) {
        McpServiceConfig saved = configService.update(serviceId, fromDraft(draft));
        closeAndRefresh(serviceId);
        return toConfiguration(saved);
    }

    @Override
    public void deleteService(String serviceId) {
        configService.delete(serviceId);
        closeAndRefresh(serviceId);
    }

    @Override
    public List<ServiceVersion> listServiceVersions(String serviceId) {
        return configService.listVersions(serviceId).stream()
            .map(version -> new ServiceVersion(version.id(), version.serviceId(), version.action(), version.name(),
                version.protocol(), version.baseUrl(), version.enabled(), version.contractAutoPublish(),
                version.toolDiscoveryPath(), version.toolInvokePath(), version.timeoutMs(), version.createdAt()))
            .toList();
    }

    @Override
    public ServiceConfiguration rollbackService(String serviceId, String versionId) {
        McpServiceConfig saved = configService.rollbackToVersion(serviceId, versionId);
        closeAndRefresh(serviceId);
        return toConfiguration(saved);
    }

    @Override
    public ServiceConfiguration setServiceEnabled(String serviceId, boolean enabled) {
        McpServiceConfig saved = configService.setEnabled(serviceId, enabled);
        if (!enabled) stdioProxyService.closeSession(serviceId);
        runtimeKernel.refresh();
        return toConfiguration(saved);
    }

    @Override
    public List<DiscoveredTool> discoverTools(String serviceId) {
        return registryBridge.discoverTools(serviceId).stream().map(this::toDiscoveredTool).toList();
    }

    @Override
    public McpServiceResult invoke(McpServiceCall scopedCall) {
        return runtimeKernel.execute(scopedCall);
    }

    @Override
    public List<RegisteredTool> listRegisteredTools() {
        return registryBridge.listRegisteredTools().stream().map(this::toRegisteredTool).toList();
    }

    @Override
    public List<ToolCatalogEntry> listToolCatalog() {
        Map<String, McpToolRegistryBridge.RegisteredMcpTool> mcpByName = new LinkedHashMap<>();
        registryBridge.listRegisteredTools().forEach(tool -> mcpByName.put(tool.localToolName(), tool));
        Set<String> names = new LinkedHashSet<>(toolRegistry.getAllToolNames());
        names.addAll(mcpByName.keySet());
        return names.stream()
            .filter(name -> name != null && !name.isBlank())
            .filter(name -> isUserVisible(name, mcpByName.containsKey(name)))
            .map(name -> toCatalogEntry(name, mcpByName.get(name)))
            .sorted(Comparator.comparing(ToolCatalogEntry::sourceType).thenComparing(ToolCatalogEntry::localToolName))
            .toList();
    }

    @Override
    public RefreshResult refresh() {
        runtimeKernel.refresh();
        return new RefreshResult(registryBridge.listRegisteredTools().size());
    }

    @Override
    public CenterStatus centerStatus() {
        McpCenterSyncService.CenterStatus status = centerSyncService.status();
        return new CenterStatus(status.enabled(), status.baseUrl(), status.standaloneMcpEndpoint(),
            status.importStandaloneServer());
    }

    @Override
    public CenterRecoveryStatus centerRecoveryStatus() {
        McpCenterRecoveryService.RecoveryStatus status = centerRecoveryService.status();
        return new CenterRecoveryStatus(status.enabled(), status.state(), status.attempts(), status.maxAttempts(),
            status.retryExhausted(), status.lastFailure(), status.lastHeartbeatAt(), status.lastSuccessAt(),
            status.lastAutoSyncAt());
    }

    @Override
    public CenterSyncResult syncCenter() {
        McpCenterSyncService.SyncResult result = centerRecoveryService.syncManually();
        List<ImportedService> imported = result.importedServices().stream()
            .map(service -> new ImportedService(service.id(), service.name(), service.baseUrl(), service.protocol(),
                service.enabled(), service.source()))
            .toList();
        return new CenterSyncResult(result.importedCount(), imported, result.errors());
    }

    private void closeAndRefresh(String serviceId) {
        stdioProxyService.closeSession(serviceId);
        runtimeKernel.refresh();
    }

    private McpServiceConfig fromDraft(ServiceConfigurationDraft draft) {
        if (draft == null) throw new IllegalArgumentException("service configuration is required");
        McpServiceConfig config = new McpServiceConfig();
        config.setName(draft.name());
        config.setBaseUrl(draft.baseUrl());
        config.setToolDiscoveryPath(draft.toolDiscoveryPath());
        config.setToolInvokePath(draft.toolInvokePath());
        config.setAuthToken(draft.authToken());
        config.setEnabled(draft.enabled() == null || draft.enabled());
        config.setContractAutoPublish(draft.contractAutoPublish() == null || draft.contractAutoPublish());
        config.setTimeoutMs(draft.timeoutMs() == null ? 0 : Math.max(0, draft.timeoutMs()));
        config.setCustomHeadersJson(writeHeaders(draft.customHeaders()));
        config.setProtocol(draft.protocol());
        config.setStdioCommand(draft.stdioCommand());
        config.setStdioArgsJson(draft.stdioArgsJson());
        config.setStdioEnvJson(draft.stdioEnvJson());
        config.setStdioWorkingDirectory(draft.stdioWorkingDirectory());
        config.setProxyEnabled(Boolean.TRUE.equals(draft.proxyEnabled()));
        config.setProxyType(draft.proxyType());
        config.setProxyHost(draft.proxyHost());
        config.setProxyPort(draft.proxyPort());
        config.setProxyUsername(draft.proxyUsername());
        config.setProxyPassword(draft.proxyPassword());
        return config;
    }

    private ServiceConfiguration toConfiguration(McpServiceConfig config) {
        return new ServiceConfiguration(config.getId(), config.getName(), config.getBaseUrl(),
            config.getToolDiscoveryPath(), config.getToolInvokePath(), config.getProtocol(), config.getStdioCommand(),
            config.getStdioArgsJson(), config.getStdioEnvJson(), config.getStdioWorkingDirectory(), config.isEnabled(),
            config.isContractAutoPublish(), config.getTimeoutMs(), readHeaders(config.getCustomHeadersJson()),
            config.isProxyEnabled(), config.getProxyType(), config.getProxyHost(), config.getProxyPort(),
            config.getProxyUsername(), epoch(config.getCreatedAt()), epoch(config.getUpdatedAt()));
    }

    private DiscoveredTool toDiscoveredTool(McpToolDefinition tool) {
        return new DiscoveredTool(tool.name(), tool.description(), tool.inputSchema(), tool.category(), tool.riskLevel(),
            tool.operationType(), tool.runtimeLevel(), tool.userVisible(), tool.confirmation(), tool.permissions(),
            tool.inputPolicy(), tool.outputPolicy(), tool.timeoutMillis(), tool.meta());
    }

    private RegisteredTool toRegisteredTool(McpToolRegistryBridge.RegisteredMcpTool tool) {
        return new RegisteredTool(tool.localToolName(), tool.serviceId(), tool.serviceName(), tool.remoteToolName(),
            tool.description(), tool.backendServiceType(), tool.category(), tool.categories(), tool.tags(),
            tool.applicability());
    }

    private boolean isUserVisible(String name, boolean registeredMcpTool) {
        if (registeredMcpTool) return true;
        ToolMetadata metadata = toolRegistry.getToolMetadata(name);
        return metadata == null || metadata.isUserVisible();
    }

    private ToolCatalogEntry toCatalogEntry(String name, McpToolRegistryBridge.RegisteredMcpTool mcpTool) {
        ToolMetadata metadata = toolRegistry.getToolMetadata(name);
        ToolRegistry.Tool simpleTool = toolRegistry.getTool(name);
        Map<String, Object> metadataMap = metadata == null || metadata.getMetadata() == null
            ? Map.of() : metadata.getMetadata();
        boolean internalMcp = Boolean.TRUE.equals(metadataMap.get("mcpCapability"));
        String sourceType = mcpTool == null && !internalMcp ? "backend" : "mcp";
        String displayName = firstNonBlank(metadata == null ? null : metadata.getTitle(),
            mcpTool == null ? null : mcpTool.remoteToolName(), simpleTool == null ? null : simpleTool.getName(), name);
        String description = firstNonBlank(metadata == null ? null : metadata.getDescription(),
            mcpTool == null ? null : mcpTool.description(), simpleTool == null ? null : simpleTool.getDescription(),
            "暂无工具说明");
        List<String> categories = metadata == null ? List.of() : safeList(metadata.getCategories());
        List<String> tags = metadata == null ? List.of() : safeList(metadata.getTags());
        String functionalCategory = mcpTool == null
            ? firstNonBlank(metadata == null ? null : metadata.getCategory(), "未分类")
            : firstNonBlank(mcpTool.backendServiceType(), "未分类");
        List<ToolParameterDescriptor> parameters = metadata == null || metadata.getParameters() == null
            ? List.of() : metadata.getParameters().stream().map(this::toParameter).toList();
        return new ToolCatalogEntry(name, displayName, description, sourceType,
            "mcp".equals(sourceType) ? "MCP工具" : "后端工具",
            mcpTool == null ? stringValue(metadataMap.get("mcpCapabilityCode")) : mcpTool.serviceId(),
            mcpTool == null ? stringValue(metadataMap.get("mcpCapabilityName")) : mcpTool.serviceName(),
            mcpTool == null ? null : mcpTool.remoteToolName(), metadata == null ? null : metadata.getOutputType(),
            metadata != null && metadata.isAgentCompatible(), metadata != null && metadata.isRequiresAuth(),
            metadata != null && metadata.isRateLimited(), metadata == null ? null : metadata.getTimeoutMillis(),
            functionalCategory, categories, tags, parameters, safeObjectMap(metadataMap.get("inputSchema")));
    }

    private ToolParameterDescriptor toParameter(ToolParameter parameter) {
        return new ToolParameterDescriptor(parameter.getName(), parameter.getType(), parameter.getDescription(),
            parameter.isRequired());
    }

    private String writeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("customHeaders is not valid JSON map", error);
        }
    }

    private Map<String, String> readHeaders(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<String> safeList(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && !result.contains(value.trim())) result.add(value.trim());
        }
        return List.copyOf(result);
    }

    private Map<String, Object> safeObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> source) || source.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> { if (key != null) result.put(String.valueOf(key), item); });
        return result;
    }

    private String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long epoch(java.time.Instant value) {
        return value == null ? null : value.toEpochMilli();
    }
}
