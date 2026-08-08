package com.chatchat.mcpserver.templatepublication;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
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
    private final TemplateQueryParentCatalog parentCatalog;
    private final McpAuthorizationService authorizationService;
    private final TemplateQueryMcpToolPublisher toolPublisher;

    @GetMapping
    public ApiResponse<List<TemplateQueryBindingService.BindingView>> list() {
        return ApiResponse.success(bindingService.list());
    }

    @PostMapping
    public ApiResponse<TemplateQueryBindingService.BindingView> create(
        @RequestBody TemplateQueryBindingService.UpsertRequest request) {
        TemplateQueryBindingService.BindingView result = bindingService.create(request);
        toolPublisher.refresh();
        return ApiResponse.success(result, "Template query binding created");
    }

    @PutMapping("/{id}")
    public ApiResponse<TemplateQueryBindingService.BindingView> update(
        @PathVariable("id") String id,
        @RequestBody TemplateQueryBindingService.UpsertRequest request) {
        TemplateQueryBindingService.BindingView result = bindingService.update(id, request);
        toolPublisher.refresh();
        return ApiResponse.success(result, "Template query binding updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") String id) {
        bindingService.delete(id);
        toolPublisher.refresh();
        return ApiResponse.success(null, "Template query binding deleted");
    }

    @PostMapping("/{id}/enabled")
    public ApiResponse<TemplateQueryBindingService.BindingView> setEnabled(
        @PathVariable("id") String id, @RequestParam("enabled") boolean enabled) {
        TemplateQueryBindingService.BindingView result = bindingService.setEnabled(id, enabled);
        toolPublisher.refresh();
        return ApiResponse.success(result, "Template query binding status updated");
    }

    @GetMapping("/templates")
    public ApiResponse<List<TemplateAssetCatalogService.TemplateAsset>> templates(
        @RequestParam("roleId") String roleId,
        @RequestParam("parentToolName") String parentToolName) {
        TemplateQueryParentCatalog.ParentTool parent = parentCatalog.require(parentToolName);
        return ApiResponse.success(catalogService.listAuthorizedForRoleAndType(roleId, parent.assetType()));
    }

    @GetMapping("/parents")
    public ApiResponse<List<TemplateQueryParentCatalog.ParentTool>> parents() {
        return ApiResponse.success(parentCatalog.list());
    }

    @GetMapping("/roles")
    public ApiResponse<List<McpAuthorizationService.RoleView>> roles(
        @RequestParam(name = "tenantId", required = false) String tenantId) {
        return ApiResponse.success(authorizationService.roles(tenantId));
    }

    @GetMapping("/members")
    public ApiResponse<List<MemberOption>> members(@RequestParam("roleId") String roleId) {
        return ApiResponse.success(authorizationService.roleMembers(roleId).stream()
            .map(item -> new MemberOption(item.id(), item.username()))
            .toList());
    }

    public record MemberOption(String id, String username) { }
}
