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
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
    private final SkillCatalogService skillCatalogService;

    /**
     * Performs the login operation.
     *
     * @param request the request value
     * @return the operation result
     */
    @PostMapping("/auth/login")
    @Operation(summary = "Development login endpoint")
    public ApiResponse<EnterpriseAdminService.AuthResult> login(@RequestBody LoginRequest request) {
        return ApiResponse.success(adminService.login(request.username(), request.password()), "login success");
    }

    /**
     * Performs the embed token login operation.
     *
     * @param request the request value
     * @return the operation result
     */
    @PostMapping("/auth/embed-login")
    @Operation(summary = "Admin embed-token login endpoint")
    public ApiResponse<EnterpriseAdminService.AuthResult> embedLogin(@RequestBody EmbedLoginRequest request) {
        return ApiResponse.success(adminService.loginWithEmbedToken(request.token()), "login success");
    }

    /**
     * Lists the admin embed login tokens.
     *
     * @param servletRequest the servlet request
     * @return the embed login tokens
     */
    @GetMapping("/auth/embed-tokens")
    @Operation(summary = "List admin embed login tokens")
    public ApiResponse<List<EnterpriseAdminService.EmbedLoginTokenView>> listEmbedTokens(HttpServletRequest servletRequest) {
        return ApiResponse.success(adminService.listEmbedLoginTokens(currentUsername(servletRequest)));
    }

    /**
     * Creates an admin embed login token.
     *
     * @param servletRequest the servlet request
     * @param request the request value
     * @return the created embed token
     */
    @PostMapping("/auth/embed-tokens")
    @Operation(summary = "Create admin embed login token")
    public ApiResponse<EnterpriseAdminService.EmbedLoginTokenView> createEmbedToken(
        HttpServletRequest servletRequest,
        @RequestBody EnterpriseAdminService.EmbedLoginTokenRequest request
    ) {
        return ApiResponse.success(adminService.createEmbedLoginToken(currentUsername(servletRequest), request), "embed token created");
    }

    /**
     * Expires an admin embed login token.
     *
     * @param servletRequest the servlet request
     * @param id the token id
     * @return the expired embed token
     */
    @PostMapping("/auth/embed-tokens/{id}/expire")
    @Operation(summary = "Expire admin embed login token")
    public ApiResponse<EnterpriseAdminService.EmbedLoginTokenView> expireEmbedToken(
        HttpServletRequest servletRequest,
        @PathVariable("id") String id
    ) {
        return ApiResponse.success(adminService.expireEmbedLoginToken(currentUsername(servletRequest), id), "embed token expired");
    }

    /**
     * Performs the summary operation.
     *
     * @return the operation result
     */
    @GetMapping("/summary")
    @Operation(summary = "Enterprise operation summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.success(adminService.summary());
    }

    /**
     * Performs the menus operation.
     *
     * @return the operation result
     */
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

    /**
     * Lists the tenants.
     *
     * @return the tenants list
     */
    @GetMapping("/tenants")
    public ApiResponse<List<SysTenant>> listTenants() {
        return ApiResponse.success(tenantRepository.findAllByOrderByTenantNameAsc());
    }

    /**
     * Creates the tenant.
     *
     * @param input the input value
     * @return the created tenant
     */
    @PostMapping("/tenants")
    public ApiResponse<SysTenant> createTenant(@RequestBody SysTenant input) {
        return ApiResponse.success(adminService.saveTenant(input), "tenant saved");
    }

    /**
     * Updates the tenant.
     *
     * @param id the id value
     * @param input the input value
     * @return the updated tenant
     */
    @PutMapping("/tenants/{id}")
    public ApiResponse<SysTenant> updateTenant(@PathVariable("id") String id, @RequestBody SysTenant input) {
        input.setId(id);
        return ApiResponse.success(adminService.saveTenant(input), "tenant saved");
    }

    /**
     * Deletes the tenant.
     *
     * @param id the id value
     * @return the operation result
     */
    @DeleteMapping("/tenants/{id}")
    public ApiResponse<Void> deleteTenant(@PathVariable("id") String id) {
        adminService.delete("tenant", id);
        return ApiResponse.success(null, "tenant deleted");
    }

    /**
     * Lists the orgs.
     *
     * @param tenantId the tenant id value
     * @return the orgs list
     */
    @GetMapping("/orgs")
    public ApiResponse<List<SysOrg>> listOrgs(@RequestParam(name = "tenantId", required = false) String tenantId) {
        List<SysOrg> data = tenantId == null || tenantId.isBlank()
            ? orgRepository.findAll()
            : orgRepository.findByTenantIdOrderBySortOrderAscOrgNameAsc(tenantId);
        return ApiResponse.success(data);
    }

    /**
     * Creates the org.
     *
     * @param input the input value
     * @return the created org
     */
    @PostMapping("/orgs")
    public ApiResponse<SysOrg> createOrg(@RequestBody SysOrg input) {
        return ApiResponse.success(adminService.saveOrg(input), "org saved");
    }

    /**
     * Updates the org.
     *
     * @param id the id value
     * @param input the input value
     * @return the updated org
     */
    @PutMapping("/orgs/{id}")
    public ApiResponse<SysOrg> updateOrg(@PathVariable("id") String id, @RequestBody SysOrg input) {
        input.setId(id);
        return ApiResponse.success(adminService.saveOrg(input), "org saved");
    }

    /**
     * Deletes the org.
     *
     * @param id the id value
     * @return the operation result
     */
    @DeleteMapping("/orgs/{id}")
    public ApiResponse<Void> deleteOrg(@PathVariable("id") String id) {
        adminService.delete("org", id);
        return ApiResponse.success(null, "org deleted");
    }

    /**
     * Lists the roles.
     *
     * @param tenantId the tenant id value
     * @return the roles list
     */
    @GetMapping("/roles")
    public ApiResponse<List<SysRole>> listRoles(@RequestParam(name = "tenantId", required = false) String tenantId) {
        List<SysRole> data = tenantId == null || tenantId.isBlank()
            ? roleRepository.findAll()
            : roleRepository.findByTenantIdOrderByRoleNameAsc(tenantId);
        return ApiResponse.success(data);
    }

    /**
     * Creates the role.
     *
     * @param input the input value
     * @return the created role
     */
    @PostMapping("/roles")
    public ApiResponse<SysRole> createRole(@RequestBody SysRole input) {
        return ApiResponse.success(adminService.saveRole(input), "role saved");
    }

    /**
     * Updates the role.
     *
     * @param id the id value
     * @param input the input value
     * @return the updated role
     */
    @PutMapping("/roles/{id}")
    public ApiResponse<SysRole> updateRole(@PathVariable("id") String id, @RequestBody SysRole input) {
        input.setId(id);
        return ApiResponse.success(adminService.saveRole(input), "role saved");
    }

    /**
     * Deletes the role.
     *
     * @param id the id value
     * @return the operation result
     */
    @DeleteMapping("/roles/{id}")
    public ApiResponse<Void> deleteRole(@PathVariable("id") String id) {
        adminService.delete("role", id);
        return ApiResponse.success(null, "role deleted");
    }

    /**
     * Returns the role authorization.
     *
     * @param id the id value
     * @return the role authorization
     */
    @GetMapping("/roles/{id}/authorization")
    public ApiResponse<EnterpriseAdminService.RoleAuthorizationView> getRoleAuthorization(@PathVariable("id") String id) {
        return ApiResponse.success(adminService.getRoleAuthorization(id));
    }

    /**
     * Saves the role authorization.
     *
     * @param id the id value
     * @param request the request value
     * @return the saved role authorization
     */
    @PutMapping("/roles/{id}/authorization")
    public ApiResponse<EnterpriseAdminService.RoleAuthorizationView> saveRoleAuthorization(
        @PathVariable("id") String id,
        @RequestBody EnterpriseAdminService.RoleAuthorizationRequest request
    ) {
        return ApiResponse.success(adminService.saveRoleAuthorization(id, request), "role authorization saved");
    }

    /**
     * Lists the permissions.
     *
     * @return the permissions list
     */
    @GetMapping("/permissions")
    public ApiResponse<List<SysPermission>> listPermissions() {
        return ApiResponse.success(adminService.listPermissions());
    }

    /**
     * Lists the agent options for role binding.
     *
     * @return the agent options list
     */
    @GetMapping("/agent-options")
    public ApiResponse<List<AgentOption>> listAgentOptions() {
        List<AgentOption> options = skillCatalogService.list().stream()
            .map(this::toAgentOption)
            .toList();
        return ApiResponse.success(options);
    }

    /**
     * Creates the permission.
     *
     * @param input the input value
     * @return the created permission
     */
    @PostMapping("/permissions")
    public ApiResponse<SysPermission> createPermission(@RequestBody SysPermission input) {
        return ApiResponse.success(adminService.savePermission(input), "permission saved");
    }

    /**
     * Updates the permission.
     *
     * @param id the id value
     * @param input the input value
     * @return the updated permission
     */
    @PutMapping("/permissions/{id}")
    public ApiResponse<SysPermission> updatePermission(@PathVariable("id") String id, @RequestBody SysPermission input) {
        input.setId(id);
        return ApiResponse.success(adminService.savePermission(input), "permission saved");
    }

    /**
     * Deletes the permission.
     *
     * @param id the id value
     * @return the operation result
     */
    @DeleteMapping("/permissions/{id}")
    public ApiResponse<Void> deletePermission(@PathVariable("id") String id) {
        adminService.delete("permission", id);
        return ApiResponse.success(null, "permission deleted");
    }

    /**
     * Lists the users.
     *
     * @param tenantId the tenant id value
     * @return the users list
     */
    @GetMapping("/users")
    public ApiResponse<List<EnterpriseAdminService.UserView>> listUsers(@RequestParam(name = "tenantId", required = false) String tenantId) {
        return ApiResponse.success(adminService.listUserViews(tenantId));
    }

    /**
     * Creates the user.
     *
     * @param request the request value
     * @return the created user
     */
    @PostMapping("/users")
    public ApiResponse<EnterpriseAdminService.UserView> createUser(@RequestBody UserUpsertRequest request) {
        return ApiResponse.success(adminService.saveUser(request.user(), request.roleIds()), "user saved");
    }

    /**
     * Updates the user.
     *
     * @param id the id value
     * @param request the request value
     * @return the updated user
     */
    @PutMapping("/users/{id}")
    public ApiResponse<EnterpriseAdminService.UserView> updateUser(@PathVariable("id") String id,
                                                                   @RequestBody UserUpsertRequest request) {
        SysUser user = request.user();
        user.setId(id);
        return ApiResponse.success(adminService.saveUser(user, request.roleIds()), "user saved");
    }

    /**
     * Deletes the user.
     *
     * @param id the id value
     * @return the operation result
     */
    @DeleteMapping("/users/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable("id") String id) {
        adminService.delete("user", id);
        return ApiResponse.success(null, "user deleted");
    }

    /**
     * Lists the external orgs.
     *
     * @return the external orgs list
     */
    @GetMapping("/external/orgs")
    public ApiResponse<List<EnterpriseAdminService.ExternalOrgView>> listExternalOrgs() {
        return ApiResponse.success(adminService.listExternalOrgs());
    }

    /**
     * Lists the external users.
     *
     * @return the external users list
     */
    @GetMapping("/external/users")
    public ApiResponse<List<EnterpriseAdminService.ExternalUserView>> listExternalUsers() {
        return ApiResponse.success(adminService.listExternalUsers());
    }

    /**
     * Synchronizes the orgs.
     *
     * @param tenantId the tenant id value
     * @return the operation result
     */
    @PostMapping("/sync/orgs")
    public ApiResponse<EnterpriseAdminService.SyncResult> syncOrgs(@RequestParam(name = "tenantId", required = false) String tenantId) {
        return ApiResponse.success(adminService.syncExternalOrgs(tenantId), "external orgs synced");
    }

    /**
     * Synchronizes the users.
     *
     * @param tenantId the tenant id value
     * @return the operation result
     */
    @PostMapping("/sync/users")
    public ApiResponse<EnterpriseAdminService.SyncResult> syncUsers(@RequestParam(name = "tenantId", required = false) String tenantId) {
        return ApiResponse.success(adminService.syncExternalUsers(tenantId), "external users synced");
    }

    /**
     * Lists the mcp tools.
     *
     * @param serviceId the service id value
     * @return the mcp tools list
     */
    @GetMapping("/mcp-tools")
    public ApiResponse<List<McpToolAsset>> listMcpTools(@RequestParam(name = "serviceId", required = false) String serviceId) {
        List<McpToolAsset> data = serviceId == null || serviceId.isBlank()
            ? toolAssetRepository.findAllByOrderByLocalToolNameAsc()
            : toolAssetRepository.findByServiceIdOrderByLocalToolNameAsc(serviceId);
        return ApiResponse.success(data);
    }

    /**
     * Synchronizes the mcp tools.
     *
     * @return the operation result
     */
    @PostMapping("/mcp-tools/sync")
    public ApiResponse<List<McpToolAsset>> syncMcpTools() {
        return ApiResponse.success(adminService.syncMcpTools(), "mcp tools synced");
    }

    /**
     * Lists the tool permissions.
     *
     * @param tenantId the tenant id value
     * @return the tool permissions list
     */
    @GetMapping("/tool-permissions")
    public ApiResponse<List<McpToolPermission>> listToolPermissions(@RequestParam(name = "tenantId", required = false) String tenantId) {
        List<McpToolPermission> data = tenantId == null || tenantId.isBlank()
            ? toolPermissionRepository.findAll()
            : toolPermissionRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
        return ApiResponse.success(data);
    }

    /**
     * Creates the tool permission.
     *
     * @param input the input value
     * @return the created tool permission
     */
    @PostMapping("/tool-permissions")
    public ApiResponse<McpToolPermission> createToolPermission(@RequestBody McpToolPermission input) {
        return ApiResponse.success(adminService.saveToolPermission(input), "tool permission saved");
    }

    /**
     * Updates the tool permission.
     *
     * @param id the id value
     * @param input the input value
     * @return the updated tool permission
     */
    @PutMapping("/tool-permissions/{id}")
    public ApiResponse<McpToolPermission> updateToolPermission(@PathVariable("id") String id,
                                                               @RequestBody McpToolPermission input) {
        input.setId(id);
        return ApiResponse.success(adminService.saveToolPermission(input), "tool permission saved");
    }

    /**
     * Deletes the tool permission.
     *
     * @param id the id value
     * @return the operation result
     */
    @DeleteMapping("/tool-permissions/{id}")
    public ApiResponse<Void> deleteToolPermission(@PathVariable("id") String id) {
        adminService.delete("tool-permission", id);
        return ApiResponse.success(null, "tool permission deleted");
    }

    /**
     * Lists the data sources.
     *
     * @param tenantId the tenant id value
     * @return the data sources list
     */
    @GetMapping("/data-sources")
    public ApiResponse<List<DataSourceConfig>> listDataSources(@RequestParam(name = "tenantId", required = false) String tenantId) {
        List<DataSourceConfig> data = tenantId == null || tenantId.isBlank()
            ? dataSourceRepository.findAll()
            : dataSourceRepository.findByTenantIdOrderByNameAsc(tenantId);
        return ApiResponse.success(data);
    }

    /**
     * Creates the data source.
     *
     * @param input the input value
     * @return the created data source
     */
    @PostMapping("/data-sources")
    public ApiResponse<DataSourceConfig> createDataSource(@RequestBody DataSourceConfig input) {
        return ApiResponse.success(adminService.saveDataSource(input), "data source saved");
    }

    /**
     * Updates the data source.
     *
     * @param id the id value
     * @param input the input value
     * @return the updated data source
     */
    @PutMapping("/data-sources/{id}")
    public ApiResponse<DataSourceConfig> updateDataSource(@PathVariable("id") String id,
                                                          @RequestBody DataSourceConfig input) {
        input.setId(id);
        return ApiResponse.success(adminService.saveDataSource(input), "data source saved");
    }

    /**
     * Deletes the data source.
     *
     * @param id the id value
     * @return the operation result
     */
    @DeleteMapping("/data-sources/{id}")
    public ApiResponse<Void> deleteDataSource(@PathVariable("id") String id) {
        adminService.delete("data-source", id);
        return ApiResponse.success(null, "data source deleted");
    }

    /**
     * Lists the audit logs.
     *
     * @param tenantId the tenant id value
     * @return the audit logs list
     */
    @GetMapping("/audit-logs")
    public ApiResponse<List<SysAuditLog>> listAuditLogs(@RequestParam(name = "tenantId", required = false) String tenantId) {
        List<SysAuditLog> data = tenantId == null || tenantId.isBlank()
            ? auditLogRepository.findTop100ByOrderByCreatedAtDesc()
            : auditLogRepository.findTop100ByTenantIdOrderByCreatedAtDesc(tenantId);
        return ApiResponse.success(data);
    }

    public record LoginRequest(String username, String password) {
    }

    public record EmbedLoginRequest(String token) {
    }

    public record UserUpsertRequest(SysUser user, List<String> roleIds) {
    }

    public record MenuGroup(String id, String title, List<MenuItem> children) {
    }

    public record MenuItem(String id, String title, String path) {
    }

    public record AgentOption(
        String id,
        String name,
        String description,
        String marketStatus,
        boolean defaultAgent,
        List<String> skillTags
    ) {
    }

    private AgentOption toAgentOption(SkillDefinition skill) {
        return new AgentOption(
            skill.id(),
            skill.label(),
            skill.description(),
            skill.marketStatus(),
            Boolean.TRUE.equals(skill.defaultAgent()),
            skill.skillTags()
        );
    }

    private String currentUsername(HttpServletRequest request) {
        Object username = request.getAttribute(ApiAuthenticationFilter.CURRENT_USERNAME);
        return username == null ? "" : String.valueOf(username);
    }
}
