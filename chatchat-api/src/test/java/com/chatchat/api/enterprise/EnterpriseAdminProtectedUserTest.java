package com.chatchat.api.enterprise;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.PasswordHashCodec;
import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.enterprise.entity.SysUser;
import com.chatchat.enterprise.repository.DataSourceConfigRepository;
import com.chatchat.enterprise.repository.EmbedLoginTokenRepository;
import com.chatchat.enterprise.repository.ExternalOrgRepository;
import com.chatchat.enterprise.repository.ExternalUserRepository;
import com.chatchat.enterprise.repository.McpToolAssetRepository;
import com.chatchat.enterprise.repository.McpToolPermissionRepository;
import com.chatchat.enterprise.repository.RoleAgentBindingRepository;
import com.chatchat.enterprise.repository.SysAuditLogRepository;
import com.chatchat.enterprise.repository.SysOrgRepository;
import com.chatchat.enterprise.repository.SysPermissionRepository;
import com.chatchat.enterprise.repository.SysRoleOrgScopeRepository;
import com.chatchat.enterprise.repository.SysRolePermissionRepository;
import com.chatchat.enterprise.repository.SysRoleRepository;
import com.chatchat.enterprise.repository.SysTenantRepository;
import com.chatchat.enterprise.repository.SysUserRepository;
import com.chatchat.enterprise.repository.SysUserRoleRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnterpriseAdminProtectedUserTest {

    private SysUserRepository userRepository;
    private SysUserRoleRepository userRoleRepository;
    private SysRoleRepository roleRepository;
    private SysPermissionRepository permissionRepository;
    private EnterpriseAdminService service;

    @BeforeEach
    void setUp() {
        SysTenantRepository tenantRepository = mock(SysTenantRepository.class);
        userRepository = mock(SysUserRepository.class);
        userRoleRepository = mock(SysUserRoleRepository.class);
        roleRepository = mock(SysRoleRepository.class);
        permissionRepository = mock(SysPermissionRepository.class);
        InternalCredentialProperties internalCredentials = new InternalCredentialProperties();
        internalCredentials.setUsername("runtime_service_account");
        service = new EnterpriseAdminService(
            tenantRepository,
            mock(SysOrgRepository.class),
            roleRepository,
            userRepository,
            userRoleRepository,
            permissionRepository,
            mock(SysRolePermissionRepository.class),
            mock(SysRoleOrgScopeRepository.class),
            mock(RoleAgentBindingRepository.class),
            mock(ExternalOrgRepository.class),
            mock(ExternalUserRepository.class),
            mock(McpToolAssetRepository.class),
            mock(McpToolPermissionRepository.class),
            mock(DataSourceConfigRepository.class),
            mock(SysAuditLogRepository.class),
            mock(McpRuntimeKernel.class),
            mock(EmbedLoginTokenRepository.class),
            internalCredentials
        );
        when(userRoleRepository.findByUserId(any())).thenReturn(List.of());
        when(roleRepository.findAllById(any())).thenReturn(List.of());
        when(permissionRepository.findAllById(any())).thenReturn(List.of());
        when(tenantRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void marksBuiltInAccountsAsProtected() {
        SysUser internal = user("internal-id", "runtime_service_account");
        SysUser admin = user("admin-id", "admin");

        EnterpriseAdminService.UserView internalView = service.toUserView(internal);
        EnterpriseAdminService.UserView adminView = service.toUserView(admin);

        assertThat(internalView.protectedAccount()).isTrue();
        assertThat(adminView.protectedAccount()).isTrue();
    }

    @Test
    void rejectsDirectDeletionOfConfiguredInternalAccount() {
        SysUser internal = user("internal-id", "runtime_service_account");
        when(userRepository.findById("internal-id")).thenReturn(Optional.of(internal));

        assertThatThrownBy(() -> service.delete("user", "internal-id"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("built-in user cannot be deleted");
        verify(userRoleRepository, never()).deleteByUserId("internal-id");
        verify(userRepository, never()).deleteById("internal-id");
    }

    @Test
    void upgradesLegacyPlaintextPasswordAfterSuccessfulLogin() {
        SysUser legacy = user("legacy-id", "legacy-user");
        legacy.setPasswordHash("legacy-password");
        when(userRepository.findByUsername("legacy-user")).thenReturn(Optional.of(legacy));

        service.login("legacy-user", "legacy-password");

        assertThat(legacy.getPasswordHash()).isNotEqualTo("legacy-password");
        assertThat(PasswordHashCodec.matches("legacy-password", legacy.getPasswordHash())).isTrue();
    }

    private SysUser user(String id, String username) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId("tenant-id");
        user.setUsername(username);
        user.setDisplayName(username);
        user.setStatus("enabled");
        return user;
    }
}
