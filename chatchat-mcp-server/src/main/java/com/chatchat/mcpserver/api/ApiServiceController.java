package com.chatchat.mcpserver.api;

import com.chatchat.agents.protocol.ModelProtocolJson;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.search.McpAssetLuceneIndexService;
import com.chatchat.mcpserver.search.McpTemplateLuceneIndexService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/api-services")
public class ApiServiceController {

    private final ApiServiceConfigService configService;
    private final ApiMcpToolPublisher publisher;
    private final ApiInvokeService invokeService;
    private final McpTemplateLuceneIndexService templateIndexService;
    private final McpAssetLuceneIndexService assetIndexService;
    private final ObjectMapper objectMapper;

    /**
     * Lists the list.
     *
     * @return the list list
     */
    @GetMapping
    public ApiResponse<List<ApiServiceView>> list() {
        return ApiResponse.success(configService.listAll().stream().map(this::toView).toList());
    }

    /**
     * Creates the create.
     *
     * @param request the request value
     * @return the created create
     */
    @PostMapping
    public ApiResponse<ApiServiceView> create(@RequestBody ApiServiceUpsertRequest request) {
        ApiServiceConfig saved = configService.create(fromRequest(request));
        refreshPublishedTemplates(saved);
        return ApiResponse.success(toView(saved), "API service registered");
    }

    /**
     * Updates the update.
     *
     * @param id the id value
     * @param request the request value
     * @return the updated update
     */
    @PutMapping("/{id}")
    public ApiResponse<ApiServiceView> update(@PathVariable("id") String id,
                                              @RequestBody ApiServiceUpsertRequest request) {
        ApiServiceConfig saved = configService.update(id, fromRequest(request));
        refreshPublishedTemplates(saved);
        return ApiResponse.success(toView(saved), "API service updated");
    }

    /**
     * Deletes the delete.
     *
     * @param id the id value
     * @return the operation result
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") String id) {
        configService.delete(id);
        refreshPublishedTemplates();
        return ApiResponse.success(null, "API service deleted");
    }

    /**
     * Performs the batch delete operation.
     *
     * @param request the request value
     * @return the operation result
     */
    @PostMapping("/batch-delete")
    public ApiResponse<Map<String, Object>> batchDelete(@RequestBody BatchDeleteRequest request) {
        int deleted = configService.deleteAll(request.ids());
        refreshPublishedTemplates();
        return ApiResponse.success(Map.of("deleted", deleted), "API services deleted");
    }

    /**
     * Sets the enabled.
     *
     * @param id the id value
     * @param enabled the enabled value
     * @return the operation result
     */
    @PostMapping("/{id}/enabled")
    public ApiResponse<ApiServiceView> setEnabled(@PathVariable("id") String id,
                                                  @RequestParam("enabled") boolean enabled) {
        ApiServiceConfig saved = configService.setEnabled(id, enabled);
        if (saved.isEnabled()) {
            refreshPublishedTemplates(saved);
        } else {
            refreshPublishedTemplates();
        }
        return ApiResponse.success(toView(saved), "API service status updated");
    }

    /**
     * Performs the test operation.
     *
     * @param id the id value
     * @param arguments the arguments value
     * @return the operation result
     */
    @PostMapping("/{id}/test")
    public ApiResponse<ApiInvokeResult> test(@PathVariable("id") String id,
                                             @RequestBody(required = false) Map<String, Object> arguments) {
        return ApiResponse.success(invokeService.invoke(configService.getById(id), arguments));
    }

    /**
     * Performs the refresh operation.
     *
     * @return the operation result
     */
    @PostMapping("/refresh")
    public ApiResponse<Map<String, Object>> refresh() {
        refreshPublishedTemplates();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refreshed", true);
        return ApiResponse.success(data, "API MCP tools refreshed");
    }

    private void refreshPublishedTemplates() {
        publisher.refresh();
        templateIndexService.refreshApiServiceTemplateIndex();
        assetIndexService.refresh("api_service");
    }

    private void refreshPublishedTemplates(ApiServiceConfig saved) {
        publisher.refresh();
        templateIndexService.upsertApiServiceTemplates(List.of(saved));
        assetIndexService.upsertApiService(saved);
    }

    /**
     * Creates the value from request.
     *
     * @param request the request value
     * @return the operation result
     */
    private ApiServiceConfig fromRequest(ApiServiceUpsertRequest request) {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setToolName(request.toolName());
        config.setTitle(request.title());
        config.setDescription(request.description());
        config.setBusinessGroup(request.businessGroup());
        config.setBusinessGroupName(request.businessGroupName());
        config.setBusinessGroupDescription(request.businessGroupDescription());
        config.setGatewayId(request.gatewayId());
        config.setMethod(request.method());
        config.setUrlTemplate(request.urlTemplate());
        config.setHeadersJson(writeJson(request.headers()));
        config.setBodyTemplate(request.bodyTemplate());
        config.setInputSchemaJson(writeJson(request.inputSchema()));
        config.setOutputSchemaJson(writeJson(request.outputSchema()));
        config.setCapabilitySpecJson(writeJson(request.capabilitySpec()));
        config.setDependencySpecJson(writeJson(request.dependencySpec()));
        config.setGovernanceJson(writeJson(request.governance()));
        config.setEnabled(request.enabled() == null || request.enabled());
        config.setTimeoutMs(request.timeoutMs() == null ? 20000 : request.timeoutMs());
        config.setCacheEnabled(request.cacheEnabled() != null && request.cacheEnabled());
        config.setCacheTtlSeconds(request.cacheTtlSeconds() == null ? 300 : request.cacheTtlSeconds());
        return config;
    }

    /**
     * Converts the value to view.
     *
     * @param config the config value
     * @return the converted view
     */
    private ApiServiceView toView(ApiServiceConfig config) {
        return new ApiServiceView(
            config.getId(),
            config.getToolName(),
            config.getTitle(),
            config.getDescription(),
            config.getBusinessGroup(),
            config.getBusinessGroupName(),
            config.getBusinessGroupDescription(),
            config.getGatewayId(),
            config.getMethod(),
            config.getUrlTemplate(),
            readJsonMap(config.getHeadersJson()),
            config.getBodyTemplate(),
            readJsonMap(config.getInputSchemaJson()),
            readJsonMap(config.getOutputSchemaJson()),
            readJsonMap(config.getCapabilitySpecJson()),
            readJsonMap(config.getDependencySpecJson()),
            readJsonMap(config.getGovernanceJson()),
            config.isEnabled(),
            config.getTimeoutMs(),
            config.isCacheEnabled(),
            config.getCacheTtlSeconds(),
            config.getCreatedAt() == null ? null : config.getCreatedAt().toEpochMilli(),
            config.getUpdatedAt() == null ? null : config.getUpdatedAt().toEpochMilli()
        );
    }

    /**
     * Writes the json.
     *
     * @param map the map value
     * @return the operation result
     */
    private String writeJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return ModelProtocolJson.compact(map);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON map is invalid");
        }
    }

    /**
     * Reads the json map.
     *
     * @param json the json value
     * @return the operation result
     */
    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    public record ApiServiceUpsertRequest(
        String toolName,
        String title,
        String description,
        String businessGroup,
        String businessGroupName,
        String businessGroupDescription,
        String gatewayId,
        String method,
        String urlTemplate,
        Map<String, Object> headers,
        String bodyTemplate,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        Map<String, Object> capabilitySpec,
        Map<String, Object> dependencySpec,
        Map<String, Object> governance,
        Boolean enabled,
        Integer timeoutMs,
        Boolean cacheEnabled,
        Integer cacheTtlSeconds
    ) {
    }

    public record BatchDeleteRequest(List<String> ids) {
    }

    public record ApiServiceView(
        String id,
        String toolName,
        String title,
        String description,
        String businessGroup,
        String businessGroupName,
        String businessGroupDescription,
        String gatewayId,
        String method,
        String urlTemplate,
        Map<String, Object> headers,
        String bodyTemplate,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        Map<String, Object> capabilitySpec,
        Map<String, Object> dependencySpec,
        Map<String, Object> governance,
        boolean enabled,
        int timeoutMs,
        boolean cacheEnabled,
        int cacheTtlSeconds,
        Long createdAt,
        Long updatedAt
    ) {
    }
}
