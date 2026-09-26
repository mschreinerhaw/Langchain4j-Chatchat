package com.chatchat.mcpserver.external;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.templatepublication.catalog.TemplateQueryParentCatalog;
import com.chatchat.mcpserver.search.index.McpTemplateLuceneIndexService;
import io.modelcontextprotocol.spec.McpSchema;
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

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/external-mcp-services")
public class ExternalMcpServiceController {
    private final ExternalMcpRegistryService registry;
    private final ExternalMcpToolPublisher publisher;
    private final McpTemplateLuceneIndexService templateIndexService;

    @GetMapping public ApiResponse<List<ServiceView>> list() {
        return ApiResponse.success(registry.list().stream().map(this::view).toList());
    }
    @GetMapping("/parents") public ApiResponse<List<TemplateQueryParentCatalog.ParentTool>> parents() {
        return ApiResponse.success(registry.parents());
    }
    @GetMapping("/workflows") public ApiResponse<List<String>> workflows() {
        return ApiResponse.success(registry.workflowIds());
    }
    @PostMapping public ApiResponse<ServiceView> create(@RequestBody ExternalMcpRegistryService.UpsertRequest request) {
        return ApiResponse.success(view(registry.create(request)), "External MCP registered; discover its tools before enabling");
    }
    @PutMapping("/{id}") public ApiResponse<ServiceView> update(@PathVariable String id,
        @RequestBody ExternalMcpRegistryService.UpsertRequest request) {
        ServiceView result = view(registry.update(id, request));
        refreshRuntimeCatalog();
        return ApiResponse.success(result);
    }
    @PostMapping("/{id}/discover") public ApiResponse<ServiceView> discover(@PathVariable String id) {
        ServiceView result = view(registry.discover(id));
        refreshRuntimeCatalog();
        return ApiResponse.success(result, "MCP tool templates discovered; review and enable explicitly");
    }
    @PostMapping("/{id}/enabled") public ApiResponse<ServiceView> enabled(@PathVariable String id,
        @RequestParam boolean enabled) {
        ServiceView result = view(registry.setEnabled(id, enabled));
        refreshRuntimeCatalog();
        return ApiResponse.success(result);
    }
    @PostMapping("/{id}/tools/{toolName}/invoke")
    public ApiResponse<McpSchema.CallToolResult> invoke(@PathVariable String id, @PathVariable String toolName,
        @RequestBody(required = false) Map<String, Object> arguments) {
        return ApiResponse.success(registry.invoke(id, toolName, arguments));
    }
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable String id) {
        registry.delete(id);
        refreshRuntimeCatalog();
        return ApiResponse.success(null);
    }

    private ServiceView view(ExternalMcpService service) {
        return new ServiceView(service.getId(), service.getName(), service.getEndpoint(),
            service.getAuthorization() != null && !service.getAuthorization().isBlank(),
            service.getParentToolName(), service.getWorkflowId(), service.isEnabled(),
            registry.templates(service), service.getDiscoveredAt(), service.getCreatedAt(), service.getUpdatedAt());
    }

    private void refreshRuntimeCatalog() {
        publisher.refreshPublication();
        templateIndexService.refreshAll();
    }

    public record ServiceView(String id, String name, String endpoint, boolean hasAuthorization,
        String parentToolName, String workflowId, boolean enabled,
        List<ExternalMcpRegistryService.ToolTemplate> templates,
        java.time.Instant discoveredAt, java.time.Instant createdAt, java.time.Instant updatedAt) { }
}
