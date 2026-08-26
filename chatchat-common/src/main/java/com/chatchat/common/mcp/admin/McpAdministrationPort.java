package com.chatchat.common.mcp.admin;

import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.List;
import java.util.Map;

/**
 * Stable control-plane port for MCP administration.
 *
 * <p>HTTP, CLI and future management adapters depend on this contract. Persistence,
 * transport sessions, registry implementations and center clients remain behind it.</p>
 */
public interface McpAdministrationPort extends RuntimeProtocolPort {
    String PROTOCOL_VERSION = "runtime_os.mcp.administration.v1";

    default String protocolVersion() {
        return PROTOCOL_VERSION;
    }

    List<ServiceConfiguration> listServices();

    ServiceConfiguration createService(ServiceConfigurationDraft draft);

    ServiceConfiguration updateService(String serviceId, ServiceConfigurationDraft draft);

    void deleteService(String serviceId);

    List<ServiceVersion> listServiceVersions(String serviceId);

    ServiceConfiguration rollbackService(String serviceId, String versionId);

    ServiceConfiguration setServiceEnabled(String serviceId, boolean enabled);

    List<DiscoveredTool> discoverTools(String serviceId);

    McpServiceResult invoke(McpServiceCall scopedCall);

    List<RegisteredTool> listRegisteredTools();

    List<ToolCatalogEntry> listToolCatalog();

    RefreshResult refresh();

    CenterStatus centerStatus();

    CenterRecoveryStatus centerRecoveryStatus();

    CenterSyncResult syncCenter();

    record ServiceConfigurationDraft(
        String name,
        String baseUrl,
        String toolDiscoveryPath,
        String toolInvokePath,
        String protocol,
        String stdioCommand,
        String stdioArgsJson,
        String stdioEnvJson,
        String stdioWorkingDirectory,
        String authToken,
        Integer timeoutMs,
        Boolean enabled,
        Boolean contractAutoPublish,
        Map<String, String> customHeaders,
        Boolean proxyEnabled,
        String proxyType,
        String proxyHost,
        Integer proxyPort,
        String proxyUsername,
        String proxyPassword
    ) {
        public ServiceConfigurationDraft {
            customHeaders = customHeaders == null ? Map.of() : Map.copyOf(customHeaders);
        }
    }

    record ServiceConfiguration(
        String id,
        String name,
        String baseUrl,
        String toolDiscoveryPath,
        String toolInvokePath,
        String protocol,
        String stdioCommand,
        String stdioArgsJson,
        String stdioEnvJson,
        String stdioWorkingDirectory,
        boolean enabled,
        boolean contractAutoPublish,
        int timeoutMs,
        Map<String, String> customHeaders,
        boolean proxyEnabled,
        String proxyType,
        String proxyHost,
        Integer proxyPort,
        String proxyUsername,
        Long createdAt,
        Long updatedAt
    ) {
        public ServiceConfiguration {
            customHeaders = customHeaders == null ? Map.of() : Map.copyOf(customHeaders);
        }
    }

    record ServiceVersion(
        String id,
        String serviceId,
        String action,
        String name,
        String protocol,
        String baseUrl,
        boolean enabled,
        boolean contractAutoPublish,
        String toolDiscoveryPath,
        String toolInvokePath,
        int timeoutMs,
        Long createdAt
    ) {
    }

    record DiscoveredTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        String category,
        String riskLevel,
        String operationType,
        String runtimeLevel,
        Boolean userVisible,
        Map<String, Object> confirmation,
        Map<String, Object> permissions,
        Map<String, Object> inputPolicy,
        Map<String, Object> outputPolicy,
        Long timeoutMillis,
        Map<String, Object> meta
    ) {
    }

    record RegisteredTool(
        String localToolName,
        String serviceId,
        String serviceName,
        String remoteToolName,
        String description,
        String backendServiceType,
        String category,
        List<String> categories,
        List<String> tags,
        Map<String, Object> applicability
    ) {
    }

    record ToolCatalogEntry(
        String localToolName,
        String displayName,
        String description,
        String sourceType,
        String sourceLabel,
        String serviceId,
        String serviceName,
        String remoteToolName,
        String outputType,
        boolean agentCompatible,
        boolean requiresAuth,
        boolean rateLimited,
        Long timeoutMillis,
        String functionalCategory,
        List<String> categories,
        List<String> tags,
        List<ToolParameterDescriptor> parameters,
        Map<String, Object> inputSchema
    ) {
    }

    record ToolParameterDescriptor(String name, String type, String description, boolean required) {
    }

    record RefreshResult(int registeredToolCount) {
    }

    record CenterStatus(boolean enabled, String baseUrl, String standaloneMcpEndpoint,
                        boolean importStandaloneServer) {
    }

    record CenterRecoveryStatus(boolean enabled, String state, int attempts, int maxAttempts,
                                boolean retryExhausted, String lastFailure, long lastHeartbeatAt,
                                long lastSuccessAt, long lastAutoSyncAt) {
    }

    record ImportedService(String id, String name, String baseUrl, String protocol,
                           boolean enabled, String source) {
    }

    record CenterSyncResult(int importedCount, List<ImportedService> importedServices, List<String> errors) {
        public CenterSyncResult {
            importedServices = importedServices == null ? List.of() : List.copyOf(importedServices);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }
}
