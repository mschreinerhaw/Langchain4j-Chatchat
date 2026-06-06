package com.chatchat.api.controller;

import com.chatchat.api.mcp.entity.McpServiceConfig;
import com.chatchat.api.mcp.model.McpToolDefinition;
import com.chatchat.api.mcp.model.McpToolInvokeResult;
import com.chatchat.api.mcp.service.McpServiceConfigService;
import com.chatchat.api.mcp.service.McpStdioProxyService;
import com.chatchat.api.mcp.service.McpToolRegistryBridge;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP service config and tool management APIs.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/mcp")
@Tag(name = "MCP", description = "MCP service configuration and tool gateway")
public class McpServiceController {

    private final McpServiceConfigService configService;
    private final McpStdioProxyService stdioProxyService;
    private final McpToolRegistryBridge registryBridge;
    private final ObjectMapper objectMapper;

    @GetMapping("/services")
    @Operation(summary = "List MCP service configurations")
    public ApiResponse<List<McpServiceView>> listServices() {
        List<McpServiceView> data = configService.listAll().stream().map(this::toView).toList();
        return ApiResponse.success(data);
    }

    @PostMapping("/services")
    @Operation(summary = "Create MCP service configuration")
    public ApiResponse<McpServiceView> createService(@RequestBody McpServiceUpsertRequest request) {
        McpServiceConfig config = fromRequest(request);
        McpServiceConfig saved = configService.create(config);
        stdioProxyService.closeSession(saved.getId());
        registryBridge.refreshRegistry();
        return ApiResponse.success(toView(saved), "MCP service created");
    }

    @PutMapping("/services/{serviceId}")
    @Operation(summary = "Update MCP service configuration")
    public ApiResponse<McpServiceView> updateService(@PathVariable("serviceId") String serviceId,
                                                     @RequestBody McpServiceUpsertRequest request) {
        McpServiceConfig saved = configService.update(serviceId, fromRequest(request));
        stdioProxyService.closeSession(serviceId);
        registryBridge.refreshRegistry();
        return ApiResponse.success(toView(saved), "MCP service updated");
    }

    @DeleteMapping("/services/{serviceId}")
    @Operation(summary = "Delete MCP service configuration")
    public ApiResponse<Void> deleteService(@PathVariable("serviceId") String serviceId) {
        configService.delete(serviceId);
        stdioProxyService.closeSession(serviceId);
        registryBridge.refreshRegistry();
        return ApiResponse.success(null, "MCP service deleted");
    }

    @GetMapping("/services/{serviceId}/versions")
    @Operation(summary = "List MCP service setting versions")
    public ApiResponse<List<McpServiceVersionView>> listServiceVersions(@PathVariable("serviceId") String serviceId) {
        List<McpServiceVersionView> versions = configService.listVersions(serviceId).stream()
            .map(v -> new McpServiceVersionView(
                v.id(),
                v.serviceId(),
                v.action(),
                v.name(),
                v.protocol(),
                v.baseUrl(),
                v.enabled(),
                v.toolDiscoveryPath(),
                v.toolInvokePath(),
                v.timeoutMs(),
                v.createdAt()
            ))
            .toList();
        return ApiResponse.success(versions);
    }

    @PostMapping("/services/{serviceId}/rollback/{versionId}")
    @Operation(summary = "Rollback MCP service settings to one version")
    public ApiResponse<McpServiceView> rollbackService(@PathVariable("serviceId") String serviceId,
                                                       @PathVariable("versionId") String versionId) {
        McpServiceConfig saved = configService.rollbackToVersion(serviceId, versionId);
        stdioProxyService.closeSession(serviceId);
        registryBridge.refreshRegistry();
        return ApiResponse.success(toView(saved), "MCP service rolled back");
    }

    @PostMapping("/services/{serviceId}/enabled")
    @Operation(summary = "Enable or disable MCP service")
    public ApiResponse<McpServiceView> setEnabled(@PathVariable("serviceId") String serviceId,
                                                  @RequestParam("enabled") boolean enabled) {
        McpServiceConfig updated = configService.setEnabled(serviceId, enabled);
        if (!enabled) {
            stdioProxyService.closeSession(serviceId);
        }
        registryBridge.refreshRegistry();
        return ApiResponse.success(toView(updated), "MCP service status updated");
    }

    @GetMapping("/services/{serviceId}/discover")
    @Operation(summary = "Discover tools from one MCP service")
    public ApiResponse<List<McpToolDefinition>> discoverTools(@PathVariable("serviceId") String serviceId) {
        return ApiResponse.success(registryBridge.discoverTools(serviceId));
    }

    @PostMapping("/services/{serviceId}/invoke")
    @Operation(summary = "Invoke a remote MCP tool for testing")
    public ApiResponse<McpToolInvokeResult> invokeTool(@PathVariable("serviceId") String serviceId,
                                                       @RequestBody McpInvokeRequest request) {
        if (request == null || request.toolName() == null || request.toolName().isBlank()) {
            return ApiResponse.badRequest("toolName is required");
        }
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        McpToolInvokeResult result = registryBridge.invoke(serviceId, request.toolName(), arguments);
        return ApiResponse.success(result);
    }

    @GetMapping("/tools")
    @Operation(summary = "List MCP tools registered into ToolRegistry")
    public ApiResponse<List<McpToolRegistryBridge.RegisteredMcpTool>> listRegisteredTools() {
        return ApiResponse.success(registryBridge.listRegisteredTools());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh MCP tool registration")
    public ApiResponse<Map<String, Object>> refresh() {
        registryBridge.refreshRegistry();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registeredTools", registryBridge.listRegisteredTools().size());
        return ApiResponse.success(data, "MCP registry refreshed");
    }

    private McpServiceConfig fromRequest(McpServiceUpsertRequest request) {
        McpServiceConfig config = new McpServiceConfig();
        config.setName(request.name());
        config.setBaseUrl(request.baseUrl());
        config.setToolDiscoveryPath(request.toolDiscoveryPath());
        config.setToolInvokePath(request.toolInvokePath());
        config.setAuthToken(request.authToken());
        config.setEnabled(request.enabled() == null || request.enabled());
        config.setTimeoutMs(request.timeoutMs() == null ? 20000 : request.timeoutMs());
        config.setCustomHeadersJson(writeHeadersJson(request.customHeaders()));
        config.setProtocol(request.protocol());
        config.setStdioCommand(request.stdioCommand());
        config.setStdioArgsJson(request.stdioArgsJson());
        config.setStdioEnvJson(request.stdioEnvJson());
        config.setStdioWorkingDirectory(request.stdioWorkingDirectory());
        config.setProxyEnabled(request.proxyEnabled() != null && request.proxyEnabled());
        config.setProxyType(request.proxyType());
        config.setProxyHost(request.proxyHost());
        config.setProxyPort(request.proxyPort());
        config.setProxyUsername(request.proxyUsername());
        config.setProxyPassword(request.proxyPassword());
        return config;
    }

    private McpServiceView toView(McpServiceConfig config) {
        return new McpServiceView(
            config.getId(),
            config.getName(),
            config.getBaseUrl(),
            config.getToolDiscoveryPath(),
            config.getToolInvokePath(),
            config.getProtocol(),
            config.getStdioCommand(),
            config.getStdioArgsJson(),
            config.getStdioEnvJson(),
            config.getStdioWorkingDirectory(),
            config.isEnabled(),
            config.getTimeoutMs(),
            readHeaders(config.getCustomHeadersJson()),
            config.isProxyEnabled(),
            config.getProxyType(),
            config.getProxyHost(),
            config.getProxyPort(),
            config.getProxyUsername(),
            config.getCreatedAt() == null ? null : config.getCreatedAt().toEpochMilli(),
            config.getUpdatedAt() == null ? null : config.getUpdatedAt().toEpochMilli()
        );
    }

    private String writeHeadersJson(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("customHeaders is not valid JSON map");
        }
    }

    private Map<String, String> readHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(headersJson, objectMapper.getTypeFactory()
                .constructMapType(Map.class, String.class, String.class));
        } catch (Exception ex) {
            return Map.of();
        }
    }

    public record McpServiceUpsertRequest(
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
        Map<String, String> customHeaders,
        Boolean proxyEnabled,
        String proxyType,
        String proxyHost,
        Integer proxyPort,
        String proxyUsername,
        String proxyPassword
    ) {
    }

    public record McpServiceView(
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
    }

    public record McpInvokeRequest(String toolName, Map<String, Object> arguments) {
    }

    public record McpServiceVersionView(
        String id,
        String serviceId,
        String action,
        String name,
        String protocol,
        String baseUrl,
        boolean enabled,
        String toolDiscoveryPath,
        String toolInvokePath,
        Integer timeoutMs,
        Long createdAt
    ) {
    }
}
