package com.chatchat.enterprise.service;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.enterprise.entity.DataSourceConfig;
import com.chatchat.enterprise.entity.McpToolPermission;
import com.chatchat.enterprise.entity.SysOrg;
import com.chatchat.enterprise.entity.SysRole;
import com.chatchat.enterprise.entity.SysTenant;
import com.chatchat.enterprise.entity.SysUser;
import com.chatchat.enterprise.repository.SysTenantRepository;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DataJpaTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.datasource.url=jdbc:h2:mem:enterprise_release;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.show-sql=false",
    "logging.level.org.hibernate.SQL=WARN"
})
@ContextConfiguration(classes = EnterpriseAdminServiceIntegrationTest.Config.class)
class EnterpriseAdminServiceIntegrationTest {

    @Autowired
    private EnterpriseAdminService service;

    @BeforeEach
    void initializeEnterpriseDomain() {
        service.run(null);
    }

    @Test
    void initializesSecureAdminAndEnforcesLoginAndEmbedTokenLifecycle() {
        EnterpriseAdminService.AuthResult login = service.login("admin", "123456");
        assertThat(login.token()).isNotBlank();
        assertThat(login.user().username()).isEqualTo("admin");
        assertThat(service.isTokenValid(login.token())).isTrue();
        assertThat(service.resolveSessionByToken(login.token())).isPresent();
        assertThatThrownBy(() -> service.login("admin", "wrong-password"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid username or password");

        EnterpriseAdminService.EmbedLoginTokenView embedded = service.createEmbedLoginToken(
            "admin", new EnterpriseAdminService.EmbedLoginTokenRequest(300L));
        assertThat(embedded.token()).isNotBlank();
        assertThat(service.loginWithEmbedToken(embedded.token()).embedded()).isTrue();
        service.expireEmbedLoginToken("admin", embedded.id());
        assertThatThrownBy(() -> service.loginWithEmbedToken(embedded.token()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid or expired");
    }

    @Test
    void persistsTenantGovernanceAndRejectsCrossTenantRoleEscalation() {
        SysTenant tenantA = service.saveTenant(tenant("release-a"));
        SysTenant tenantB = service.saveTenant(tenant("release-b"));
        SysOrg orgA = service.saveOrg(org(tenantA.getId(), "ORG-A"));
        SysRole roleA = service.saveRole(role(tenantA.getId(), "ANALYST-A"));
        SysRole roleB = service.saveRole(role(tenantB.getId(), "ANALYST-B"));

        EnterpriseAdminService.UserView userA = service.saveUser(
            user(tenantA.getId(), orgA.getId(), "release-user-a"), List.of(roleA.getId()));
        assertThatThrownBy(() -> service.saveUser(
            user(tenantA.getId(), orgA.getId(), "cross-tenant-role"), List.of(roleB.getId())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not belong");

        service.saveRoleAuthorization(roleA.getId(),
            new EnterpriseAdminService.RoleAuthorizationRequest(
                List.of(), List.of(), List.of(userA.id()), List.of("agent-release")));
        assertThat(service.canAccessAgent(userA.id(), "agent-release")).isTrue();
        assertThat(service.canAccessAgent(userA.id(), "agent-forbidden")).isFalse();
        assertThat(service.accessibleAgentIds(userA.id())).containsExactly("agent-release");
    }

    @Test
    void persistsToolAndDatasourceGovernanceAndProducesAuditSummary() {
        long initialDataSources = ((Number) service.summary().get("dataSourceCount")).longValue();
        long initialToolPermissions = ((Number) service.summary().get("toolPermissionCount")).longValue();
        SysTenant tenant = service.saveTenant(tenant("release-assets"));

        DataSourceConfig dataSource = new DataSourceConfig();
        dataSource.setTenantId(tenant.getId());
        dataSource.setName("release-mysql");
        dataSource.setType("mysql");
        dataSource.setJdbcUrl("jdbc:mysql://db.example/release");
        dataSource.setUsername("release_reader");
        dataSource.setPasswordCipher("ENC(test-fixture-only)");
        DataSourceConfig savedDataSource = service.saveDataSource(dataSource);
        assertThat(savedDataSource.getId()).isNotBlank();

        McpToolPermission permission = new McpToolPermission();
        permission.setTenantId(tenant.getId());
        permission.setTargetType("tenant");
        permission.setTargetId(tenant.getId());
        permission.setLocalToolName("dynamic_release_tool");
        permission.setScopeExpression("tenantId == '" + tenant.getId() + "'");
        permission.setEffect("allow");
        McpToolPermission savedPermission = service.saveToolPermission(permission);
        assertThat(savedPermission.getId()).isNotBlank();

        assertThat(service.summary())
            .containsEntry("dataSourceCount", initialDataSources + 1L)
            .containsEntry("toolPermissionCount", initialToolPermissions + 1L)
            .containsKey("auditLogCount");
        service.delete("data-source", savedDataSource.getId());
        service.delete("tool-permission", savedPermission.getId());
        assertThat(service.summary())
            .containsEntry("dataSourceCount", initialDataSources)
            .containsEntry("toolPermissionCount", initialToolPermissions);
    }

    private SysTenant tenant(String code) {
        SysTenant value = new SysTenant();
        value.setTenantCode(code);
        value.setTenantName(code);
        value.setStatus("enabled");
        return value;
    }

    private SysOrg org(String tenantId, String code) {
        SysOrg value = new SysOrg();
        value.setTenantId(tenantId);
        value.setOrgCode(code);
        value.setOrgName(code);
        value.setStatus("enabled");
        return value;
    }

    private SysRole role(String tenantId, String code) {
        SysRole value = new SysRole();
        value.setTenantId(tenantId);
        value.setRoleCode(code);
        value.setRoleName(code);
        value.setStatus("enabled");
        return value;
    }

    private SysUser user(String tenantId, String orgId, String username) {
        SysUser value = new SysUser();
        value.setTenantId(tenantId);
        value.setOrgId(orgId);
        value.setUsername(username);
        value.setDisplayName(username);
        value.setPasswordHash("release-password");
        value.setStatus("enabled");
        return value;
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.chatchat.enterprise.repository")
    @EntityScan(basePackages = "com.chatchat.enterprise.entity")
    @Import(EnterpriseAdminService.class)
    static class Config {
        @Bean
        McpToolRegistryBridge registryBridge() {
            return mock(McpToolRegistryBridge.class);
        }

        @Bean
        InternalCredentialProperties internalCredentialProperties() {
            InternalCredentialProperties properties = new InternalCredentialProperties();
            properties.setEnabled(false);
            return properties;
        }
    }
}
