package com.chatchat.mcpserver.templatepublication;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.mcp.McpServiceRegistration;
import com.chatchat.mcpserver.mcp.McpServiceRegistryService;
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

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/template-query-publications")
public class TemplateQueryAdminController {

    private final TemplateQueryBindingService bindingService;
    private final TemplateAssetCatalogService catalogService;
    private final McpServiceRegistryService serviceRegistryService;
    private final McpAuthorizationService authorizationService;

    @GetMapping
    public ApiResponse<List<TemplateQueryBindingService.BindingView>> list() {
        return ApiResponse.success(bindingService.list());
    }

    @PostMapping
    public ApiResponse<TemplateQueryBindingService.BindingView> create(
        @RequestBody TemplateQueryBindingService.UpsertRequest request) {
        return ApiResponse.success(bindingService.create(request), "Template query binding created");
    }

    @PutMapping("/{id}")
    public ApiResponse<TemplateQueryBindingService.BindingView> update(
        @PathVariable("id") String id,
        @RequestBody TemplateQueryBindingService.UpsertRequest request) {
        return ApiResponse.success(bindingService.update(id, request), "Template query binding updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") String id) {
        bindingService.delete(id);
        return ApiResponse.success(null, "Template query binding deleted");
    }

    @PostMapping("/{id}/enabled")
    public ApiResponse<TemplateQueryBindingService.BindingView> setEnabled(
        @PathVariable("id") String id, @RequestParam("enabled") boolean enabled) {
        return ApiResponse.success(bindingService.setEnabled(id, enabled), "Template query binding status updated");
    }

    @GetMapping("/templates")
    public ApiResponse<List<TemplateAssetCatalogService.TemplateAsset>> templates() {
        return ApiResponse.success(catalogService.listEnabled());
    }

    @GetMapping("/services")
    public ApiResponse<List<ServiceOption>> services() {
        return ApiResponse.success(serviceRegistryService.listAll().stream()
            .filter(McpServiceRegistration::isEnabled)
            .map(item -> new ServiceOption(item.getId(), item.getName(), item.getEnvironment()))
            .toList());
    }

    @GetMapping("/roles")
    public ApiResponse<List<McpAuthorizationService.RoleView>> roles(
        @RequestParam(name = "tenantId", required = false) String tenantId) {
        return ApiResponse.success(authorizationService.roles(tenantId));
    }

    public record ServiceOption(String id, String name, String environment) { }
}
