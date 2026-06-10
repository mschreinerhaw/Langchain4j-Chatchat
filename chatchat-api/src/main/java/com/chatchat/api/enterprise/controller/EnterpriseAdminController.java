package com.chatchat.api.enterprise.controller;

import com.chatchat.enterprise.entity.DataSourceConfig;
import com.chatchat.enterprise.entity.McpToolAsset;
import com.chatchat.enterprise.entity.McpToolPermission;
import com.chatchat.enterprise.entity.SysAuditLog;
import com.chatchat.enterprise.entity.SysOrg;
import com.chatchat.enterprise.entity.SysPermission;
import com.chatchat.enterprise.entity.SysRole;
import com.chatchat.enterprise.entity.SysTenant;
import com.chatchat.enterprise.entity.SysUser;
import com.chatchat.enterprise.repository.DataSourceConfigRepository;
import com.chatchat.enterprise.repository.McpToolAssetRepository;
import com.chatchat.enterprise.repository.McpToolPermissionRepository;
import com.chatchat.enterprise.repository.SysAuditLogRepository;
import com.chatchat.enterprise.repository.SysOrgRepository;
import com.chatchat.enterprise.repository.SysRoleRepository;
import com.chatchat.enterprise.repository.SysTenantRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
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

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/enterprise")
@Tag(name = "Enterprise Admin", description = "Enterprise governance, RBAC, MCP assets and audit APIs")
public class EnterpriseAdminController {

    private final EnterpriseAdminService adminService;
    private final SysTenantRepository tenantRepository;
    private final SysOrgRepository orgRepository;
    private final SysRoleRepository roleRepository;
    private final McpToolAssetRepository toolAssetRepository;
    private final McpToolPermissionRepository toolPermissionRepository;
    private final DataSourceConfigRepository dataSourceRepository;
    private final SysAuditLogRepository auditLogRepository;

    @PostMapping("/auth/login")
    @Operation(summary = "Development login endpoint")
    public ApiResponse<EnterpriseAdminService.AuthResult> login(@RequestBody LoginRequest request) {
        return ApiResponse.success(adminService.login(request.username(), request.password()), "login success");
    }

    @GetMapping("/summary")
    @Operation(summary = "Enterprise operation summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.success(adminService.summary());
    }

    @GetMapping("/menus")
    @Operation(summary = "Enterprise portal menu")
    public ApiResponse<List<MenuGroup>> menus() {
        return ApiResponse.success(List.of(
            new MenuGroup("workspace", "工作台", List.of(
                new MenuItem("chat", "智能对话", "/index.html#chat"),
                new MenuItem("search", "文档检索", "/index.html#search")
            )),
            new MenuGroup("capability", "能力管理", List.of(
                new MenuItem("market", "能力市场", "/index.html#market"),
                new MenuItem("library", "文档库", "/index.html#library")
            )),
            new MenuGroup("platform", "平台管理", List.of(
                new MenuItem("mcp", "MCP服务", "/index.html#mcp"),
                new MenuItem("agents", "Agent管理", "/index.html#agents"),
                new MenuItem("tasks", "运行监控", "/index.html#tasks"),
                new MenuItem("system", "系统管理", "/index.html#system")
            ))
        ));
    }

    @GetMapping("/tenants")
    public ApiResponse<List<SysTenant>> listTenants() {
        return ApiResponse.success(tenantRepository.findAllByOrderByTenantNameAsc());
    }

    @PostMapping("/tenants")
    public ApiResponse<SysTenant> createTenant(@RequestBody SysTenant input) {
        return ApiResponse.success(adminService.saveTenant(input), "tenant saved");
    }

    @PutMapping("/tenants/{id}")
    public ApiResponse<SysTenant> updateTenant(@PathVariable("id") String id, @RequestBody SysTenant input) {
        input.setId(id);
        return ApiResponse.success(adminService.saveTenant(input), "tenant saved");
    }

    @DeleteMapping("/tenants/{id}")
    public ApiResponse<Void> deleteTenant(@PathVariable("id") String id) {
        adminService.delete("tenant", id);
        return ApiResponse.success(null, "tenant deleted");
    }

    @GetMapping("/orgs")
    public ApiResponse<List<SysOrg>> listOrgs(@RequestParam(name = "tenantId", required = false) String tenantId) {
        List<SysOrg> data = tenantId == null || tenantId.isBlank()
            ? orgRepository.findAll()
            : orgRepository.findByTenantIdOrderBySortOrderAscOrgNameAsc(tenantId);
        return ApiResponse.success(data);
    }

    @PostMapping("/orgs")
    public ApiResponse<SysOrg> createOrg(@RequestBody SysOrg input) {
        return ApiResponse.success(adminService.saveOrg(input), "org saved");
    }

    @PutMapping("/orgs/{id}")
    public ApiResponse<SysOrg> updateOrg(@PathVariable("id") String id, @RequestBody SysOrg input) {
        input.setId(id);
        return ApiResponse.success(adminService.saveOrg(input), "org saved");
    }

    @DeleteMapping("/orgs/{id}")
    public ApiResponse<Void> deleteOrg(@PathVariable("id") String id) {
        adminService.delete("org", id);
        return ApiResponse.success(null, "org deleted");
    }

    @GetMapping("/roles")
    public ApiResponse<List<SysRole>> listRoles(@RequestParam(name = "tenantId", required = false) String tenantId) {
        List<SysRole> data = tenantId == null || tenantId.isBlank()
            ? roleRepository.findAll()
            : roleRepository.findByTenantIdOrderByRoleNameAsc(tenantId);
        return ApiResponse.success(data);
    }

    @PostMapping("/roles")
    public ApiResponse<SysRole> createRole(@RequestBody SysRole input) {
        return ApiResponse.success(adminService.saveRole(input), "role saved");
    }

    @PutMapping("/roles/{id}")
    public ApiResponse<SysRole> updateRole(@PathVariable("id") String id, @RequestBody SysRole input) {
        input.setId(id);
        return ApiResponse.success(adminService.saveRole(input), "role saved");
    }

    @DeleteMapping("/roles/{id}")
    public ApiResponse<Void> deleteRole(@PathVariable("id") String id) {
        adminService.delete("role", id);
        return ApiResponse.success(null, "role deleted");
    }

    @GetMapping("/roles/{id}/authorization")
    public ApiResponse<EnterpriseAdminService.RoleAuthorizationView> getRoleAuthorization(@PathVariable("id") String id) {
        return ApiResponse.success(adminService.getRoleAuthorization(id));
    }

    @PutMapping("/roles/{id}/authorization")
    public ApiResponse<EnterpriseAdminService.RoleAuthorizationView> saveRoleAuthorization(
        @PathVariable("id") String id,
        @RequestBody EnterpriseAdminService.RoleAuthorizationRequest request
    ) {
        return ApiResponse.success(adminService.saveRoleAuthorization(id, request), "role authorization saved");
    }

    @GetMapping("/permissions")
    public ApiResponse<List<SysPermission>> listPermissions() {
        return ApiResponse.success(adminService.listPermissions());
    }

    @PostMapping("/permissions")
    public ApiResponse<SysPermission> createPermission(@RequestBody SysPermission input) {
        return ApiResponse.success(adminService.savePermission(input), "permission saved");
    }

    @PutMapping("/permissions/{id}")
    public ApiResponse<SysPermission> updatePermission(@PathVariable("id") String id, @RequestBody SysPermission input) {
        input.setId(id);
        return ApiResponse.success(adminService.savePermission(input), "permission saved");
    }

    @DeleteMapping("/permissions/{id}")
    public ApiResponse<Void> deletePermission(@PathVariable("id") String id) {
        adminService.delete("permission", id);
        return ApiResponse.success(null, "permission deleted");
    }

    @GetMapping("/users")
    public ApiResponse<List<EnterpriseAdminService.UserView>> listUsers(@RequestParam(name = "tenantId", required = false) String tenantId) {
        return ApiResponse.success(adminService.listUserViews(tenantId));
    }

    @PostMapping("/users")
    public ApiResponse<EnterpriseAdminService.UserView> createUser(@RequestBody UserUpsertRequest request) {
        return ApiResponse.success(adminService.saveUser(request.user(), request.roleIds()), "user saved");
    }

    @PutMapping("/users/{id}")
    public ApiResponse<EnterpriseAdminService.UserView> updateUser(@PathVariable("id") String id,
                                                                   @RequestBody UserUpsertRequest request) {
        SysUser user = request.user();
        user.setId(id);
        return ApiResponse.success(adminService.saveUser(user, request.roleIds()), "user saved");
    }

    @DeleteMapping("/users/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable("id") String id) {
        adminService.delete("user", id);
        return ApiResponse.success(null, "user deleted");
    }

    @GetMapping("/external/orgs")
    public ApiResponse<List<EnterpriseAdminService.ExternalOrgView>> listExternalOrgs() {
        return ApiResponse.success(adminService.listExternalOrgs());
    }

    @GetMapping("/external/users")
    public ApiResponse<List<EnterpriseAdminService.ExternalUserView>> listExternalUsers() {
        return ApiResponse.success(adminService.listExternalUsers());
    }

    @PostMapping("/sync/orgs")
    public ApiResponse<EnterpriseAdminService.SyncResult> syncOrgs(@RequestParam(name = "tenantId", required = false) String tenantId) {
        return ApiResponse.success(adminService.syncExternalOrgs(tenantId), "external orgs synced");
    }

    @PostMapping("/sync/users")
    public ApiResponse<EnterpriseAdminService.SyncResult> syncUsers(@RequestParam(name = "tenantId", required = false) String tenantId) {
        return ApiResponse.success(adminService.syncExternalUsers(tenantId), "external users synced");
    }

    @GetMapping("/mcp-tools")
    public ApiResponse<List<McpToolAsset>> listMcpTools(@RequestParam(name = "serviceId", required = false) String serviceId) {
        List<McpToolAsset> data = serviceId == null || serviceId.isBlank()
            ? toolAssetRepository.findAllByOrderByLocalToolNameAsc()
            : toolAssetRepository.findByServiceIdOrderByLocalToolNameAsc(serviceId);
        return ApiResponse.success(data);
    }

    @PostMapping("/mcp-tools/sync")
    public ApiResponse<List<McpToolAsset>> syncMcpTools() {
        return ApiResponse.success(adminService.syncMcpTools(), "mcp tools synced");
    }

    @GetMapping("/tool-permissions")
    public ApiResponse<List<McpToolPermission>> listToolPermissions(@RequestParam(name = "tenantId", required = false) String tenantId) {
        List<McpToolPermission> data = tenantId == null || tenantId.isBlank()
            ? toolPermissionRepository.findAll()
            : toolPermissionRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
        return ApiResponse.success(data);
    }

    @PostMapping("/tool-permissions")
    public ApiResponse<McpToolPermission> createToolPermission(@RequestBody McpToolPermission input) {
        return ApiResponse.success(adminService.saveToolPermission(input), "tool permission saved");
    }

    @PutMapping("/tool-permissions/{id}")
    public ApiResponse<McpToolPermission> updateToolPermission(@PathVariable("id") String id,
                                                               @RequestBody McpToolPermission input) {
        input.setId(id);
        return ApiResponse.success(adminService.saveToolPermission(input), "tool permission saved");
    }

    @DeleteMapping("/tool-permissions/{id}")
    public ApiResponse<Void> deleteToolPermission(@PathVariable("id") String id) {
        adminService.delete("tool-permission", id);
        return ApiResponse.success(null, "tool permission deleted");
    }

    @GetMapping("/data-sources")
    public ApiResponse<List<DataSourceConfig>> listDataSources(@RequestParam(name = "tenantId", required = false) String tenantId) {
        List<DataSourceConfig> data = tenantId == null || tenantId.isBlank()
            ? dataSourceRepository.findAll()
            : dataSourceRepository.findByTenantIdOrderByNameAsc(tenantId);
        return ApiResponse.success(data);
    }

    @PostMapping("/data-sources")
    public ApiResponse<DataSourceConfig> createDataSource(@RequestBody DataSourceConfig input) {
        return ApiResponse.success(adminService.saveDataSource(input), "data source saved");
    }

    @PutMapping("/data-sources/{id}")
    public ApiResponse<DataSourceConfig> updateDataSource(@PathVariable("id") String id,
                                                          @RequestBody DataSourceConfig input) {
        input.setId(id);
        return ApiResponse.success(adminService.saveDataSource(input), "data source saved");
    }

    @DeleteMapping("/data-sources/{id}")
    public ApiResponse<Void> deleteDataSource(@PathVariable("id") String id) {
        adminService.delete("data-source", id);
        return ApiResponse.success(null, "data source deleted");
    }

    @GetMapping("/audit-logs")
    public ApiResponse<List<SysAuditLog>> listAuditLogs(@RequestParam(name = "tenantId", required = false) String tenantId) {
        List<SysAuditLog> data = tenantId == null || tenantId.isBlank()
            ? auditLogRepository.findTop100ByOrderByCreatedAtDesc()
            : auditLogRepository.findTop100ByTenantIdOrderByCreatedAtDesc(tenantId);
        return ApiResponse.success(data);
    }

    public record LoginRequest(String username, String password) {
    }

    public record UserUpsertRequest(SysUser user, List<String> roleIds) {
    }

    public record MenuGroup(String id, String title, List<MenuItem> children) {
    }

    public record MenuItem(String id, String title, String path) {
    }
}
