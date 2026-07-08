package com.chatchat.mcpserver.authorization;

import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/mcp-authorization")
public class McpAuthorizationAdminController {

    private final McpAuthorizationService authorizationService;

    @GetMapping("/snapshot")
    public ApiResponse<McpAuthorizationService.AuthorizationSyncView> snapshot() {
        return ApiResponse.success(authorizationService.currentView());
    }

    @PostMapping("/sync")
    public ApiResponse<McpAuthorizationService.AuthorizationSyncView> sync() {
        return ApiResponse.success(authorizationService.refreshNow(), "MCP authorization synchronized");
    }

    @GetMapping("/roles")
    public ApiResponse<List<McpAuthorizationService.RoleView>> roles(
        @RequestParam(name = "tenantId", required = false) String tenantId) {
        return ApiResponse.success(authorizationService.roles(tenantId));
    }

    @GetMapping("/users")
    public ApiResponse<List<JsonNode>> users(@RequestParam(name = "tenantId", required = false) String tenantId) {
        return ApiResponse.success(authorizationService.apiUsers(tenantId));
    }

    @GetMapping("/role-permissions")
    public ApiResponse<List<JsonNode>> rolePermissions(@RequestParam("roleId") String roleId,
                                                       @RequestParam(name = "tenantId", required = false) String tenantId) {
        return ApiResponse.success(authorizationService.rolePermissions(roleId, tenantId));
    }

    @PostMapping("/role-permissions")
    public ApiResponse<JsonNode> createRolePermission(@RequestBody McpAuthorizationService.RolePermissionRequest request) {
        return ApiResponse.success(authorizationService.createRolePermission(request), "role permission saved");
    }

    @DeleteMapping("/role-permissions/{id}")
    public ApiResponse<Void> deleteRolePermission(@PathVariable("id") String id) {
        authorizationService.deleteRolePermission(id);
        return ApiResponse.success(null, "role permission deleted");
    }
}
