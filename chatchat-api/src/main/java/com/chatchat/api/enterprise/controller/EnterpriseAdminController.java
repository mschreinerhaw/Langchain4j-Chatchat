package com.chatchat.api.enterprise.controller;

import com.chatchat.api.enterprise.entity.DataSourceConfig;
import com.chatchat.api.enterprise.entity.McpToolAsset;
import com.chatchat.api.enterprise.entity.McpToolPermission;
import com.chatchat.api.enterprise.entity.SysAuditLog;
import com.chatchat.api.enterprise.entity.SysOrg;
import com.chatchat.api.enterprise.entity.SysRole;
import com.chatchat.api.enterprise.entity.SysTenant;
import com.chatchat.api.enterprise.entity.SysUser;
import com.chatchat.api.enterprise.repository.DataSourceConfigRepository;
import com.chatchat.api.enterprise.repository.McpToolAssetRepository;
import com.chatchat.api.enterprise.repository.McpToolPermissionRepository;
import com.chatchat.api.enterprise.repository.SysAuditLogRepository;
import com.chatchat.api.enterprise.repository.SysOrgRepository;
import com.chatchat.api.enterprise.repository.SysRoleRepository;
import com.chatchat.api.enterprise.repository.SysTenantRepository;
import com.chatchat.api.enterprise.service.EnterpriseAdminService;
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
            new MenuGroup("home", "首页", List.of(new MenuItem("dashboard", "运营总览", "/index.html#enterprise"))),
            new MenuGroup("ai", "AI能力中心", List.of(
                new MenuItem("chat", "智能问答", "/index.html"),
                new MenuItem("skills", "技能管理", "/index.html#skills"),
                new MenuItem("models", "模型管理", "/index.html#enterprise"),
                new MenuItem("sessions", "会话管理", "/index.html#chat")
            )),
            new MenuGroup("mcp", "MCP中心", List.of(
                new MenuItem("mcp-services", "服务注册", "/index.html#mcp"),
                new MenuItem("mcp-tools", "工具管理", "/index.html#enterprise"),
                new MenuItem("tool-permissions", "工具授权", "/index.html#enterprise")
            )),
            new MenuGroup("data", "数据中心", List.of(
                new MenuItem("data-sources", "数据源管理", "/index.html#enterprise"),
                new MenuItem("knowledge", "知识库管理", "/index.html#chat"),
                new MenuItem("data-permissions", "数据权限", "/index.html#enterprise")
            )),
            new MenuGroup("system", "系统管理", List.of(
                new MenuItem("tenants", "租户管理", "/index.html#enterprise"),
                new MenuItem("orgs", "组织管理", "/index.html#enterprise"),
                new MenuItem("users", "用户管理", "/index.html#enterprise"),
                new MenuItem("roles", "角色管理", "/index.html#enterprise")
            )),
            new MenuGroup("audit", "审计中心", List.of(
                new MenuItem("login-logs", "登录日志", "/index.html#enterprise"),
                new MenuItem("operation-logs", "操作日志", "/index.html#enterprise"),
                new MenuItem("tool-call-logs", "工具调用日志", "/index.html#enterprise"),
                new MenuItem("ai-logs", "AI问答日志", "/index.html#chat")
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
    public ApiResponse<SysTenant> updateTenant(@PathVariable String id, @RequestBody SysTenant input) {
        input.setId(id);
        return ApiResponse.success(adminService.saveTenant(input), "tenant saved");
    }

    @DeleteMapping("/tenants/{id}")
    public ApiResponse<Void> deleteTenant(@PathVariable String id) {
        adminService.delete("tenant", id);
        return ApiResponse.success(null, "tenant deleted");
    }

    @GetMapping("/orgs")
    public ApiResponse<List<SysOrg>> listOrgs(@RequestParam(required = false) String tenantId) {
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
    public ApiResponse<SysOrg> updateOrg(@PathVariable String id, @RequestBody SysOrg input) {
        input.setId(id);
        return ApiResponse.success(adminService.saveOrg(input), "org saved");
    }

    @DeleteMapping("/orgs/{id}")
    public ApiResponse<Void> deleteOrg(@PathVariable String id) {
        adminService.delete("org", id);
        return ApiResponse.success(null, "org deleted");
    }

    @GetMapping("/roles")
    public ApiResponse<List<SysRole>> listRoles(@RequestParam(required = false) String tenantId) {
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
    public ApiResponse<SysRole> updateRole(@PathVariable String id, @RequestBody SysRole input) {
        input.setId(id);
        return ApiResponse.success(adminService.saveRole(input), "role saved");
    }

    @DeleteMapping("/roles/{id}")
    public ApiResponse<Void> deleteRole(@PathVariable String id) {
        adminService.delete("role", id);
        return ApiResponse.success(null, "role deleted");
    }

    @GetMapping("/users")
    public ApiResponse<List<EnterpriseAdminService.UserView>> listUsers(@RequestParam(required = false) String tenantId) {
        return ApiResponse.success(adminService.listUserViews(tenantId));
    }

    @PostMapping("/users")
    public ApiResponse<EnterpriseAdminService.UserView> createUser(@RequestBody UserUpsertRequest request) {
        return ApiResponse.success(adminService.saveUser(request.user(), request.roleIds()), "user saved");
    }

    @PutMapping("/users/{id}")
    public ApiResponse<EnterpriseAdminService.UserView> updateUser(@PathVariable String id,
                                                                   @RequestBody UserUpsertRequest request) {
        SysUser user = request.user();
        user.setId(id);
        return ApiResponse.success(adminService.saveUser(user, request.roleIds()), "user saved");
    }

    @DeleteMapping("/users/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable String id) {
        adminService.delete("user", id);
        return ApiResponse.success(null, "user deleted");
    }

    @GetMapping("/mcp-tools")
    public ApiResponse<List<McpToolAsset>> listMcpTools(@RequestParam(required = false) String serviceId) {
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
    public ApiResponse<List<McpToolPermission>> listToolPermissions(@RequestParam(required = false) String tenantId) {
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
    public ApiResponse<McpToolPermission> updateToolPermission(@PathVariable String id,
                                                               @RequestBody McpToolPermission input) {
        input.setId(id);
        return ApiResponse.success(adminService.saveToolPermission(input), "tool permission saved");
    }

    @DeleteMapping("/tool-permissions/{id}")
    public ApiResponse<Void> deleteToolPermission(@PathVariable String id) {
        adminService.delete("tool-permission", id);
        return ApiResponse.success(null, "tool permission deleted");
    }

    @GetMapping("/data-sources")
    public ApiResponse<List<DataSourceConfig>> listDataSources(@RequestParam(required = false) String tenantId) {
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
    public ApiResponse<DataSourceConfig> updateDataSource(@PathVariable String id,
                                                          @RequestBody DataSourceConfig input) {
        input.setId(id);
        return ApiResponse.success(adminService.saveDataSource(input), "data source saved");
    }

    @DeleteMapping("/data-sources/{id}")
    public ApiResponse<Void> deleteDataSource(@PathVariable String id) {
        adminService.delete("data-source", id);
        return ApiResponse.success(null, "data source deleted");
    }

    @GetMapping("/audit-logs")
    public ApiResponse<List<SysAuditLog>> listAuditLogs(@RequestParam(required = false) String tenantId) {
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
