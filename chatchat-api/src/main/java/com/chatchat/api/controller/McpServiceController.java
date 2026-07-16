package com.chatchat.api.controller;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.integration.mcp.entity.McpServiceConfig;
import com.chatchat.integration.mcp.model.McpToolDefinition;
import com.chatchat.integration.mcp.model.McpToolInvokeResult;
import com.chatchat.integration.mcp.service.McpCenterSyncService;
import com.chatchat.integration.mcp.service.McpServiceConfigService;
import com.chatchat.integration.mcp.service.McpStdioProxyService;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final McpCenterSyncService centerSyncService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Lists the services.
     *
     * @return the services list
     */
    @GetMapping("/services")
    @Operation(summary = "List MCP service configurations")
    public ApiResponse<List<McpServiceView>> listServices() {
        List<McpServiceView> data = configService.listAll().stream().map(this::toView).toList();
        return ApiResponse.success(data);
    }

    /**
     * Creates the service.
     *
     * @param request the request value
     * @return the created service
     */
    @PostMapping("/services")
    @Operation(summary = "Create MCP service configuration")
    public ApiResponse<McpServiceView> createService(@RequestBody McpServiceUpsertRequest request) {
        McpServiceConfig config = fromRequest(request);
        McpServiceConfig saved = configService.create(config);
        stdioProxyService.closeSession(saved.getId());
        registryBridge.refreshRegistry();
        return ApiResponse.success(toView(saved), "MCP service created");
    }

    /**
     * Updates the service.
     *
     * @param serviceId the service id value
     * @param request the request value
     * @return the updated service
     */
    @PutMapping("/services/{serviceId}")
    @Operation(summary = "Update MCP service configuration")
    public ApiResponse<McpServiceView> updateService(@PathVariable("serviceId") String serviceId,
                                                     @RequestBody McpServiceUpsertRequest request) {
        McpServiceConfig saved = configService.update(serviceId, fromRequest(request));
        stdioProxyService.closeSession(serviceId);
        registryBridge.refreshRegistry();
        return ApiResponse.success(toView(saved), "MCP service updated");
    }

    /**
     * Deletes the service.
     *
     * @param serviceId the service id value
     * @return the operation result
     */
    @DeleteMapping("/services/{serviceId}")
    @Operation(summary = "Delete MCP service configuration")
    public ApiResponse<Void> deleteService(@PathVariable("serviceId") String serviceId) {
        configService.delete(serviceId);
        stdioProxyService.closeSession(serviceId);
        registryBridge.refreshRegistry();
        return ApiResponse.success(null, "MCP service deleted");
    }

    /**
     * Lists the service versions.
     *
     * @param serviceId the service id value
     * @return the service versions list
     */
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

    /**
     * Performs the rollback service operation.
     *
     * @param serviceId the service id value
     * @param versionId the version id value
     * @return the operation result
     */
    @PostMapping("/services/{serviceId}/rollback/{versionId}")
    @Operation(summary = "Rollback MCP service settings to one version")
    public ApiResponse<McpServiceView> rollbackService(@PathVariable("serviceId") String serviceId,
                                                       @PathVariable("versionId") String versionId) {
        McpServiceConfig saved = configService.rollbackToVersion(serviceId, versionId);
        stdioProxyService.closeSession(serviceId);
        registryBridge.refreshRegistry();
        return ApiResponse.success(toView(saved), "MCP service rolled back");
    }

    /**
     * Sets the enabled.
     *
     * @param serviceId the service id value
     * @param enabled the enabled value
     * @return the operation result
     */
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

    /**
     * Performs the discover tools operation.
     *
     * @param serviceId the service id value
     * @return the operation result
     */
    @GetMapping("/services/{serviceId}/discover")
    @Operation(summary = "Discover tools from one MCP service")
    public ApiResponse<List<McpToolDefinition>> discoverTools(@PathVariable("serviceId") String serviceId) {
        return ApiResponse.success(registryBridge.discoverTools(serviceId));
    }

    /**
     * Performs the invoke tool operation.
     *
     * @param serviceId the service id value
     * @param request the request value
     * @return the operation result
     */
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

    /**
     * Lists the registered tools.
     *
     * @return the registered tools list
     */
    @GetMapping("/tools")
    @Operation(summary = "List MCP tools registered into ToolRegistry")
    public ApiResponse<List<McpToolRegistryBridge.RegisteredMcpTool>> listRegisteredTools() {
        return ApiResponse.success(registryBridge.listRegisteredTools());
    }

    /**
     * Lists the tool cards.
     *
     * @param keyword the keyword value
     * @param service the service value
     * @param category the functional category value
     * @param sourceType the source type value
     * @param groupMode the group mode value
     * @param page the page value
     * @param pageSize the page size value
     * @return the tool cards list
     */
    @GetMapping("/tool-cards")
    @Operation(summary = "List registered backend and MCP tools as searchable cards")
    public ApiResponse<Map<String, Object>> listToolCards(@RequestParam(value = "keyword", required = false) String keyword,
                                                          @RequestParam(value = "service", required = false) String service,
                                                          @RequestParam(value = "category", required = false) String category,
                                                          @RequestParam(value = "sourceType", required = false) String sourceType,
                                                          @RequestParam(value = "groupMode", required = false) String groupMode,
                                                          @RequestParam(value = "page", required = false) Integer page,
                                                          @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        List<ToolCardView> allTools = buildToolCards();
        List<ToolCardView> sourceScopedTools = allTools.stream()
            .filter(tool -> isAll(sourceType) || sourceType.equalsIgnoreCase(tool.sourceType()))
            .toList();
        List<ToolServiceOption> serviceOptions = buildToolServiceOptions(sourceScopedTools);
        List<ToolServiceOption> categoryOptions = buildToolCategoryOptions(sourceScopedTools);
        List<ToolCardView> filteredTools = sourceScopedTools.stream()
            .filter(tool -> matchesToolFilters(tool, keyword, service, category))
            .toList();
        int normalizedPage = normalizePage(page);
        int normalizedPageSize = normalizePageSize(pageSize, 6, 100);
        List<ToolCardView> pagedTools = filteredTools.stream()
            .skip(pageOffset(normalizedPage, normalizedPageSize))
            .limit(normalizedPageSize)
            .toList();
        Map<String, Object> pageData = new LinkedHashMap<>();
        pageData.put("tools", pagedTools);
        pageData.put("total", filteredTools.size());
        pageData.put("page", normalizedPage);
        pageData.put("pageSize", normalizedPageSize);
        pageData.put("totalPages", totalPages(filteredTools.size(), normalizedPageSize));
        pageData.put("filteredGroupCount", filteredToolGroupCount(filteredTools, groupMode));
        pageData.put("serviceOptions", serviceOptions);
        pageData.put("categoryOptions", categoryOptions);
        return ApiResponse.success(pageData);
    }

    /**
     * Performs the refresh operation.
     *
     * @return the operation result
     */
    @PostMapping("/refresh")
    @Operation(summary = "Refresh MCP tool registration")
    public ApiResponse<Map<String, Object>> refresh() {
        registryBridge.refreshRegistry();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registeredTools", registryBridge.listRegisteredTools().size());
        return ApiResponse.success(data, "MCP registry refreshed");
    }

    /**
     * Performs the center status operation.
     *
     * @return the operation result
     */
    @GetMapping("/center/status")
    @Operation(summary = "Get external MCP center integration status")
    public ApiResponse<McpCenterSyncService.CenterStatus> centerStatus() {
        return ApiResponse.success(centerSyncService.status());
    }

    /**
     * Synchronizes the center.
     *
     * @return the operation result
     */
    @PostMapping("/center/sync")
    @Operation(summary = "Sync services from external ChatChat MCP center")
    public ApiResponse<McpCenterSyncService.SyncResult> syncCenter() {
        return ApiResponse.success(centerSyncService.syncFromCenter(), "MCP center synced");
    }

    /**
     * Creates the value from request.
     *
     * @param request the request value
     * @return the operation result
     */
    private McpServiceConfig fromRequest(McpServiceUpsertRequest request) {
        McpServiceConfig config = new McpServiceConfig();
        config.setName(request.name());
        config.setBaseUrl(request.baseUrl());
        config.setToolDiscoveryPath(request.toolDiscoveryPath());
        config.setToolInvokePath(request.toolInvokePath());
        config.setAuthToken(request.authToken());
        config.setEnabled(request.enabled() == null || request.enabled());
        config.setTimeoutMs(request.timeoutMs() == null ? 0 : Math.max(0, request.timeoutMs()));
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

    /**
     * Builds the tool cards.
     *
     * @return the built tool cards
     */
    private List<ToolCardView> buildToolCards() {
        Map<String, McpToolRegistryBridge.RegisteredMcpTool> mcpToolsByName = new LinkedHashMap<>();
        for (McpToolRegistryBridge.RegisteredMcpTool tool : registryBridge.listRegisteredTools()) {
            mcpToolsByName.put(tool.localToolName(), tool);
        }

        Set<String> names = new LinkedHashSet<>(toolRegistry.getAllToolNames());
        names.addAll(mcpToolsByName.keySet());

        return names.stream()
            .filter(name -> name != null && !name.isBlank())
            .filter(name -> isUserVisibleTool(name, mcpToolsByName.containsKey(name)))
            .map(name -> toToolCard(name, mcpToolsByName.get(name)))
            .sorted(Comparator
                .comparing(ToolCardView::sourceType)
                .thenComparing(ToolCardView::localToolName))
            .toList();
    }

    /**
     * Returns whether is user visible tool.
     *
     * @param localToolName the local tool name value
     * @param registeredMcpTool the registered mcp tool value
     * @return whether the condition is satisfied
     */
    private boolean isUserVisibleTool(String localToolName, boolean registeredMcpTool) {
        if (registeredMcpTool) {
            return true;
        }
        ToolMetadata metadata = toolRegistry.getToolMetadata(localToolName);
        return metadata == null || metadata.isUserVisible();
    }

    /**
     * Builds the tool service options.
     *
     * @param tools the tools value
     * @return the built tool service options
     */
    private List<ToolServiceOption> buildToolServiceOptions(List<ToolCardView> tools) {
        Map<String, ToolServiceOptionBuilder> builders = new LinkedHashMap<>();
        for (ToolCardView tool : tools) {
            String key = firstNonBlank(tool.serviceId(), tool.serviceName(), "ungrouped");
            ToolServiceOptionBuilder builder = builders.computeIfAbsent(key, ignored ->
                new ToolServiceOptionBuilder(key, firstNonBlank(tool.serviceName(), tool.serviceId(), "未归属服务")));
            builder.count += 1;
        }
        List<ToolServiceOption> options = new ArrayList<>();
        options.add(new ToolServiceOption("all", "全部服务", tools.size()));
        builders.values().stream()
            .map(builder -> new ToolServiceOption(builder.value, builder.label, builder.count))
            .sorted(Comparator.comparing(ToolServiceOption::label))
            .forEach(options::add);
        return options;
    }

    /**
     * Builds service type options from the backend service type declared by each tool.
     * Tool category and tag values are intentionally not used here; they are often
     * finer-grained than the service capability type shown in the MCP service page.
     */
    private List<ToolServiceOption> buildToolCategoryOptions(List<ToolCardView> tools) {
        Map<String, ToolServiceOptionBuilder> builders = new LinkedHashMap<>();
        for (ToolCardView tool : tools) {
            String serviceType = toolServiceType(tool);
            String key = normalizeKeyword(serviceType);
            ToolServiceOptionBuilder builder = builders.computeIfAbsent(key, ignored ->
                new ToolServiceOptionBuilder(key, serviceType));
            builder.count += 1;
        }
        List<ToolServiceOption> options = new ArrayList<>();
        options.add(new ToolServiceOption("all", "全部服务类型", tools.size()));
        builders.values().stream()
            .map(builder -> new ToolServiceOption(builder.value, builder.label, builder.count))
            .sorted(Comparator.comparing(ToolServiceOption::label))
            .forEach(options::add);
        return options;
    }

    /**
     * Returns whether matches tool filters.
     *
     * @param tool the tool value
     * @param keyword the keyword value
     * @param service the service value
     * @param category the functional category value
     * @return whether the condition is satisfied
     */
    private boolean matchesToolFilters(ToolCardView tool, String keyword, String service, String category) {
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedService = normalizeKeyword(service);
        String normalizedCategory = normalizeKeyword(category);
        String serviceKey = firstNonBlank(tool.serviceId(), tool.serviceName(), "ungrouped");
        boolean serviceMatched = normalizedService.isEmpty() || serviceKey.equalsIgnoreCase(normalizedService);
        boolean categoryMatched = normalizedCategory.isEmpty()
            || normalizeKeyword(toolServiceType(tool)).equals(normalizedCategory);
        boolean keywordMatched = normalizedKeyword.isEmpty() || toolSearchText(tool).contains(normalizedKeyword);
        return serviceMatched && categoryMatched && keywordMatched;
    }

    private String toolServiceType(ToolCardView tool) {
        if (tool.functionalCategory() != null && !tool.functionalCategory().isBlank()) {
            return tool.functionalCategory().trim();
        }
        return "未分类";
    }

    /**
     * Converts the value to ol search text.
     *
     * @param tool the tool value
     * @return the converted ol search text
     */
    private String toolSearchText(ToolCardView tool) {
        List<String> fields = new ArrayList<>();
        fields.add(tool.localToolName());
        fields.add(tool.displayName());
        fields.add(tool.description());
        fields.add(tool.serviceId());
        fields.add(tool.serviceName());
        fields.add(tool.remoteToolName());
        fields.add(tool.outputType());
        fields.add(tool.functionalCategory());
        fields.addAll(tool.categories() == null ? List.of() : tool.categories());
        fields.addAll(tool.tags() == null ? List.of() : tool.tags());
        if (tool.parameters() != null) {
            for (ToolParameterView parameter : tool.parameters()) {
                fields.add(parameter.name());
                fields.add(parameter.type());
                fields.add(parameter.description());
            }
        }
        return fields.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.toLowerCase(java.util.Locale.ROOT))
            .reduce("", (left, right) -> left + " " + right);
    }

    /**
     * Performs the filtered tool group count operation.
     *
     * @param tools the tools value
     * @param groupMode the group mode value
     * @return the operation result
     */
    private int filteredToolGroupCount(List<ToolCardView> tools, String groupMode) {
        return (int) tools.stream()
            .map(tool -> toolGroupKey(tool, groupMode))
            .distinct()
            .count();
    }

    /**
     * Converts the value to ol group key.
     *
     * @param tool the tool value
     * @param groupMode the group mode value
     * @return the converted ol group key
     */
    private String toolGroupKey(ToolCardView tool, String groupMode) {
        if ("category".equalsIgnoreCase(groupMode)) {
            return "category:" + toolServiceType(tool);
        }
        if ("tag".equalsIgnoreCase(groupMode)) {
            return "tag:" + ((tool.tags() == null || tool.tags().isEmpty()) ? "未打标签" : tool.tags().get(0));
        }
        return "service:" + firstNonBlank(tool.serviceId(), tool.serviceName(), "未归属服务");
    }

    /**
     * Returns whether is all.
     *
     * @param value the value value
     * @return whether the condition is satisfied
     */
    private boolean isAll(String value) {
        return value == null || value.isBlank() || "all".equalsIgnoreCase(value.trim());
    }

    /**
     * Normalizes the keyword.
     *
     * @param value the value value
     * @return the operation result
     */
    private String normalizeKeyword(String value) {
        return isAll(value) ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Normalizes the page.
     *
     * @param page the page value
     * @return the operation result
     */
    private int normalizePage(Integer page) {
        return page == null || page <= 0 ? 1 : page;
    }

    /**
     * Normalizes the page size.
     *
     * @param pageSize the page size value
     * @param defaultSize the default size value
     * @param maxSize the max size value
     * @return the operation result
     */
    private int normalizePageSize(Integer pageSize, int defaultSize, int maxSize) {
        int value = pageSize == null || pageSize <= 0 ? defaultSize : pageSize;
        return Math.min(value, maxSize);
    }

    /**
     * Performs the page offset operation.
     *
     * @param page the page value
     * @param pageSize the page size value
     * @return the operation result
     */
    private long pageOffset(int page, int pageSize) {
        return (long) Math.max(0, page - 1) * Math.max(1, pageSize);
    }

    /**
     * Converts the value to tal pages.
     *
     * @param total the total value
     * @param pageSize the page size value
     * @return the converted tal pages
     */
    private int totalPages(int total, int pageSize) {
        return Math.max(1, (int) Math.ceil((double) Math.max(0, total) / Math.max(1, pageSize)));
    }

    private static final class ToolServiceOptionBuilder {
        private final String value;
        private final String label;
        private int count;

        /**
         * Creates a new McpServiceController instance.
         *
         * @param value the value value
         * @param label the label value
         */
        private ToolServiceOptionBuilder(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    /**
     * Converts the value to tool card.
     *
     * @param localToolName the local tool name value
     * @param mcpTool the mcp tool value
     * @return the converted tool card
     */
    private ToolCardView toToolCard(String localToolName, McpToolRegistryBridge.RegisteredMcpTool mcpTool) {
        ToolMetadata metadata = toolRegistry.getToolMetadata(localToolName);
        ToolRegistry.Tool simpleTool = toolRegistry.getTool(localToolName);
        String sourceType = mcpTool == null ? "backend" : "mcp";
        String displayName = firstNonBlank(
            metadata == null ? null : metadata.getTitle(),
            mcpTool == null ? null : mcpTool.remoteToolName(),
            simpleTool == null ? null : simpleTool.getName(),
            localToolName
        );
        String description = firstNonBlank(
            metadata == null ? null : metadata.getDescription(),
            mcpTool == null ? null : mcpTool.description(),
            simpleTool == null ? null : simpleTool.getDescription(),
            "暂无工具说明"
        );
        List<String> categories = metadata == null ? List.of() : safeList(metadata.getCategories());
        List<String> tags = metadata == null ? List.of() : safeList(metadata.getTags());
        String functionalCategory = mcpTool == null
            ? firstNonBlank(metadata == null ? null : metadata.getCategory(), "未分类")
            : firstNonBlank(mcpTool.backendServiceType(), "未分类");
        List<ToolParameterView> parameters = metadata == null || metadata.getParameters() == null
            ? List.of()
            : metadata.getParameters().stream().map(this::toParameterView).toList();
        Map<String, Object> metadataMap = metadata == null || metadata.getMetadata() == null
            ? Map.of()
            : metadata.getMetadata();
        Object inputSchema = metadataMap.get("inputSchema");
        return new ToolCardView(
            localToolName,
            displayName,
            description,
            sourceType,
            sourceTypeLabel(sourceType),
            mcpTool == null ? null : mcpTool.serviceId(),
            mcpTool == null ? null : mcpTool.serviceName(),
            mcpTool == null ? null : mcpTool.remoteToolName(),
            metadata == null ? null : metadata.getOutputType(),
            metadata != null && metadata.isAgentCompatible(),
            metadata != null && metadata.isRequiresAuth(),
            metadata != null && metadata.isRateLimited(),
            metadata == null ? null : metadata.getTimeoutMillis(),
            parameters.size(),
            functionalCategory,
            categories,
            tags,
            parameters,
            safeObjectMap(inputSchema)
        );
    }

    /**
     * Converts the value to parameter view.
     *
     * @param parameter the parameter value
     * @return the converted parameter view
     */
    private ToolParameterView toParameterView(ToolParameter parameter) {
        return new ToolParameterView(
            parameter.getName(),
            parameter.getType(),
            parameter.getDescription(),
            parameter.isRequired()
        );
    }

    /**
     * Performs the safe list operation.
     *
     * @param values the values value
     * @return the operation result
     */
    private List<String> safeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && !normalized.contains(value.trim())) {
                normalized.add(value.trim());
            }
        }
        return List.copyOf(normalized);
    }

    /**
     * Performs the safe object map operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private Map<String, Object> safeObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> source) || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return normalized;
    }

    /**
     * Performs the source type label operation.
     *
     * @param sourceType the source type value
     * @return the operation result
     */
    private String sourceTypeLabel(String sourceType) {
        return "mcp".equals(sourceType) ? "MCP工具" : "后端工具";
    }

    /**
     * Performs the first non blank operation.
     *
     * @param values the values value
     * @return the operation result
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Converts the value to view.
     *
     * @param config the config value
     * @return the converted view
     */
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

    /**
     * Writes the headers json.
     *
     * @param headers the headers value
     * @return the operation result
     */
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

    /**
     * Reads the headers.
     *
     * @param headersJson the headers json value
     * @return the operation result
     */
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

    public record ToolCardView(
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
        int parameterCount,
        String functionalCategory,
        List<String> categories,
        List<String> tags,
        List<ToolParameterView> parameters,
        Map<String, Object> inputSchema
    ) {
    }

    public record ToolCardPage(
        List<ToolCardView> tools,
        int total,
        int page,
        int pageSize,
        int totalPages,
        int filteredGroupCount,
        List<ToolServiceOption> serviceOptions
    ) {
    }

    public record ToolServiceOption(
        String value,
        String label,
        int count
    ) {
    }

    public record ToolParameterView(
        String name,
        String type,
        String description,
        boolean required
    ) {
    }
}
