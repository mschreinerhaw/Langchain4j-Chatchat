package com.chatchat.mcpserver.api;

import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
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
    private final ObjectMapper objectMapper;

    @GetMapping
    public ApiResponse<List<ApiServiceView>> list() {
        return ApiResponse.success(configService.listAll().stream().map(this::toView).toList());
    }

    @PostMapping
    public ApiResponse<ApiServiceView> create(@RequestBody ApiServiceUpsertRequest request) {
        ApiServiceConfig saved = configService.create(fromRequest(request));
        publisher.refresh();
        return ApiResponse.success(toView(saved), "API service registered");
    }

    @PutMapping("/{id}")
    public ApiResponse<ApiServiceView> update(@PathVariable("id") String id,
                                              @RequestBody ApiServiceUpsertRequest request) {
        ApiServiceConfig saved = configService.update(id, fromRequest(request));
        publisher.refresh();
        return ApiResponse.success(toView(saved), "API service updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") String id) {
        configService.delete(id);
        publisher.refresh();
        return ApiResponse.success(null, "API service deleted");
    }

    @PostMapping("/batch-delete")
    public ApiResponse<Map<String, Object>> batchDelete(@RequestBody BatchDeleteRequest request) {
        int deleted = configService.deleteAll(request.ids());
        publisher.refresh();
        return ApiResponse.success(Map.of("deleted", deleted), "API services deleted");
    }

    @PostMapping("/{id}/enabled")
    public ApiResponse<ApiServiceView> setEnabled(@PathVariable("id") String id,
                                                  @RequestParam("enabled") boolean enabled) {
        ApiServiceConfig saved = configService.setEnabled(id, enabled);
        publisher.refresh();
        return ApiResponse.success(toView(saved), "API service status updated");
    }

    @PostMapping("/{id}/test")
    public ApiResponse<ApiInvokeResult> test(@PathVariable("id") String id,
                                             @RequestBody(required = false) Map<String, Object> arguments) {
        return ApiResponse.success(invokeService.invoke(configService.getById(id), arguments));
    }

    @PostMapping("/refresh")
    public ApiResponse<Map<String, Object>> refresh() {
        publisher.refresh();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refreshed", true);
        return ApiResponse.success(data, "API MCP tools refreshed");
    }

    private ApiServiceConfig fromRequest(ApiServiceUpsertRequest request) {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setToolName(request.toolName());
        config.setTitle(request.title());
        config.setDescription(request.description());
        config.setMethod(request.method());
        config.setUrlTemplate(request.urlTemplate());
        config.setHeadersJson(writeJson(request.headers()));
        config.setBodyTemplate(request.bodyTemplate());
        config.setInputSchemaJson(writeJson(request.inputSchema()));
        config.setGovernanceJson(writeJson(request.governance()));
        config.setEnabled(request.enabled() == null || request.enabled());
        config.setTimeoutMs(request.timeoutMs() == null ? 20000 : request.timeoutMs());
        config.setCacheEnabled(request.cacheEnabled() != null && request.cacheEnabled());
        config.setCacheTtlSeconds(request.cacheTtlSeconds() == null ? 300 : request.cacheTtlSeconds());
        return config;
    }

    private ApiServiceView toView(ApiServiceConfig config) {
        return new ApiServiceView(
            config.getId(),
            config.getToolName(),
            config.getTitle(),
            config.getDescription(),
            config.getMethod(),
            config.getUrlTemplate(),
            readJsonMap(config.getHeadersJson()),
            config.getBodyTemplate(),
            readJsonMap(config.getInputSchemaJson()),
            readJsonMap(config.getGovernanceJson()),
            config.isEnabled(),
            config.getTimeoutMs(),
            config.isCacheEnabled(),
            config.getCacheTtlSeconds(),
            config.getCreatedAt() == null ? null : config.getCreatedAt().toEpochMilli(),
            config.getUpdatedAt() == null ? null : config.getUpdatedAt().toEpochMilli()
        );
    }

    private String writeJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON map is invalid");
        }
    }

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
        String method,
        String urlTemplate,
        Map<String, Object> headers,
        String bodyTemplate,
        Map<String, Object> inputSchema,
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
        String method,
        String urlTemplate,
        Map<String, Object> headers,
        String bodyTemplate,
        Map<String, Object> inputSchema,
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
