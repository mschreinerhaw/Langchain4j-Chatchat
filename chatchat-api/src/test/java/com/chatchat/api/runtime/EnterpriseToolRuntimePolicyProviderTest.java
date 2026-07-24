package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.ToolRuntimePolicy;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.enterprise.entity.McpToolPermission;
import com.chatchat.enterprise.entity.McpToolAsset;
import com.chatchat.enterprise.entity.SysRole;
import com.chatchat.enterprise.entity.SysTenant;
import com.chatchat.enterprise.entity.SysUser;
import com.chatchat.enterprise.entity.SysUserRole;
import com.chatchat.enterprise.repository.McpToolPermissionRepository;
import com.chatchat.enterprise.repository.McpToolAssetRepository;
import com.chatchat.enterprise.repository.SysRoleRepository;
import com.chatchat.enterprise.repository.SysTenantRepository;
import com.chatchat.enterprise.repository.SysUserRepository;
import com.chatchat.enterprise.repository.SysUserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnterpriseToolRuntimePolicyProviderTest {

    private final McpToolPermissionRepository permissionRepository = mock(McpToolPermissionRepository.class);
    private final McpToolAssetRepository toolAssetRepository = mock(McpToolAssetRepository.class);
    private final SysRoleRepository roleRepository = mock(SysRoleRepository.class);
    private final SysUserRoleRepository userRoleRepository = mock(SysUserRoleRepository.class);
    private final SysUserRepository userRepository = mock(SysUserRepository.class);
    private final SysTenantRepository tenantRepository = mock(SysTenantRepository.class);
    private final EnterpriseToolRuntimePolicyProvider provider = new EnterpriseToolRuntimePolicyProvider(
        permissionRepository,
        toolAssetRepository,
        roleRepository,
        userRoleRepository,
        userRepository,
        tenantRepository
    );

    @BeforeEach
    void setUp() {
        when(roleRepository.findByTenantIdOrderByRoleNameAsc(anyString())).thenReturn(List.of());
        when(toolAssetRepository.findByLocalToolName(anyString())).thenReturn(Optional.of(managedTool()));
        when(userRoleRepository.findByUserId(anyString())).thenReturn(List.of());
        when(permissionRepository.findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
            anyString(), anyString(), anyString())).thenReturn(List.of());
    }

    @Test
    void deniesCallerWithNoAssignedAssetAuthorization() {
        ToolRuntimePolicy policy = provider.resolve(request("tenant-a", "user-a", Map.of()), null);

        assertThat(policy.allowed()).isFalse();
        assertThat(policy.reason()).contains("No MCP asset authorization");
    }

    @Test
    void leavesNonMcpToolsToOtherRuntimePolicies() {
        when(toolAssetRepository.findByLocalToolName("local_image_tool")).thenReturn(Optional.empty());

        ToolRuntimePolicy policy = provider.resolve(ToolRuntimeRequest.builder()
            .toolName("local_image_tool")
            .build(), null);

        assertThat(policy).isNull();
    }

    @Test
    void deniesMissingTenantContext() {
        ToolRuntimePolicy policy = provider.resolve(request(null, "user-a", Map.of()), null);

        assertThat(policy.allowed()).isFalse();
        assertThat(policy.reason()).contains("requires tenant");
    }

    @Test
    void resolvesUserRoleAndAllowsOnlyMatchingAssetScope() {
        SysRole role = role("role-analyst", "tenant-a", "ANALYST");
        SysUserRole binding = new SysUserRole();
        binding.setUserId("user-a");
        binding.setRoleId(role.getId());
        when(roleRepository.findByTenantIdOrderByRoleNameAsc("tenant-a")).thenReturn(List.of(role));
        when(userRoleRepository.findByUserId("user-a")).thenReturn(List.of(binding));
        when(permissionRepository.findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
            "tenant-a", "ROLE", "role-analyst")).thenReturn(List.of(permission(
                "tenant-a",
                "role-analyst",
                "sql_asset_query",
                "mcp:sql_datasource:execute:query@tenant=tenant-a;domain=db-1;level=read"
            )));

        ToolRuntimePolicy allowed = provider.resolve(request("tenant-a", "user-a", Map.of(
            "scopeExpression", "mcp:sql_datasource:execute:query@tenant=tenant-a;domain=db-1;level=read"
        )), null);
        ToolRuntimePolicy otherAsset = provider.resolve(request("tenant-a", "user-a", Map.of(
            "scopeExpression", "mcp:sql_datasource:execute:query@tenant=tenant-a;domain=db-2;level=read"
        )), null);

        assertThat(allowed.allowed()).isTrue();
        assertThat(otherAsset.allowed()).isFalse();
    }

    @Test
    void superAdminBypassesAssetAllowList() {
        SysRole role = role("role-super", "tenant-a", "SUPER_ADMIN");
        when(roleRepository.findByTenantIdOrderByRoleNameAsc("tenant-a")).thenReturn(List.of(role));

        ToolRuntimePolicy policy = provider.resolve(ToolRuntimeRequest.builder()
            .tenantId("tenant-a")
            .userId("user-a")
            .toolName("unassigned_tool")
            .attributes(Map.of("roles", List.of("SUPER_ADMIN")))
            .build(), null);

        assertThat(policy.allowed()).isTrue();
    }

    @Test
    void adminUserIdBypassesAssetAllowListAfterUsernameResolution() {
        SysUser admin = new SysUser();
        admin.setId("user-admin-id");
        admin.setTenantId("tenant-a");
        admin.setUsername("admin");
        SysTenant platformTenant = new SysTenant();
        platformTenant.setId("tenant-a");
        platformTenant.setTenantNo(100000L);
        when(userRepository.findById("user-admin-id")).thenReturn(Optional.of(admin));
        when(tenantRepository.findById("tenant-a")).thenReturn(Optional.of(platformTenant));

        ToolRuntimePolicy policy = provider.resolve(request("tenant-a", "user-admin-id", Map.of()), null);

        assertThat(policy.allowed()).isTrue();
    }

    private ToolRuntimeRequest request(String tenantId, String userId, Map<String, Object> context) {
        return ToolRuntimeRequest.builder()
            .tenantId(tenantId)
            .userId(userId)
            .toolName("sql_asset_query")
            .toolInput(ToolInput.builder().context(context).build())
            .build();
    }

    private SysRole role(String id, String tenantId, String code) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setTenantId(tenantId);
        role.setRoleCode(code);
        role.setRoleName(code);
        return role;
    }

    private McpToolPermission permission(String tenantId,
                                         String roleId,
                                         String toolName,
                                         String scopeExpression) {
        McpToolPermission permission = new McpToolPermission();
        permission.setId("permission-1");
        permission.setTenantId(tenantId);
        permission.setTargetType("ROLE");
        permission.setTargetId(roleId);
        permission.setLocalToolName(toolName);
        permission.setScopeExpression(scopeExpression);
        permission.setEffect("allow");
        permission.setEnabled(true);
        return permission;
    }

    private McpToolAsset managedTool() {
        McpToolAsset tool = new McpToolAsset();
        tool.setId("mcp-tool-1");
        tool.setLocalToolName("sql_asset_query");
        return tool;
    }
}
