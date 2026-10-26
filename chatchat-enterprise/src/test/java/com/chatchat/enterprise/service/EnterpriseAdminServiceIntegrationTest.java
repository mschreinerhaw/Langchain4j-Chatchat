package com.chatchat.enterprise.service;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.PasswordHashCodec;
import com.chatchat.enterprise.entity.federation.ExternalUser;
import com.chatchat.enterprise.repository.federation.ExternalUserRepository;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.enterprise.entity.security.ResourceGrant;
import com.chatchat.enterprise.entity.datasource.DataSourceConfig;
import com.chatchat.enterprise.entity.mcp.McpToolPermission;
import com.chatchat.enterprise.entity.identity.SysOrg;
import com.chatchat.enterprise.entity.identity.SysMenu;
import com.chatchat.enterprise.entity.identity.SysPermission;
import com.chatchat.enterprise.entity.identity.SysRole;
import com.chatchat.enterprise.entity.identity.SysTenant;
import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.repository.identity.SysTenantRepository;
import com.chatchat.enterprise.repository.security.ResourceGrantRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DataJpaTest(properties = {
    "chatchat.enterprise.bootstrap.admin-password=Test-Only-Admin-Password!",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.datasource.url=jdbc:h2:mem:enterprise_release;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.show-sql=false",
    "logging.level.org.hibernate.SQL=WARN"
})
@ContextConfiguration(classes = EnterpriseAdminServiceIntegrationTest.Config.class)
class EnterpriseAdminServiceIntegrationTest {

    @Autowired
    private EnterpriseAdminService service;

    @Autowired
    private ResourceGrantRepository resourceGrantRepository;

    @Autowired
    private SysTenantRepository tenantRepository;

    @Autowired
    private SysUserRepository userRepository;

    @Autowired
    private ExternalUserRepository externalUserRepository;

    @BeforeEach
    void initializeEnterpriseDomain() {
        service.run(null);
    }

    @Test
    void rejectsKnownDefaultPasswordAndStoresOnlyConfiguredPasswordHash() {
        assertThatThrownBy(() -> service.login("admin", "123456"))
            .isInstanceOf(IllegalArgumentException.class);
        String hash = userRepository.findByUsername("admin").orElseThrow().getPasswordHash();
        assertThat(PasswordHashCodec.isEncoded(hash)).isTrue();
        assertThat(PasswordHashCodec.matches("Test-Only-Admin-Password!", hash)).isTrue();
    }

    @Test
    void requiresSecureBootstrapConfigurationOnlyWhenAdminNeedsAPassword() {
        SysUser admin = userRepository.findByUsername("admin").orElseThrow();
        String existingHash = admin.getPasswordHash();
        try {
            ReflectionTestUtils.setField(service, "bootstrapAdminPassword", "");
            service.run(null);
            assertThat(admin.getPasswordHash()).isEqualTo(existingHash);
            admin.setPasswordHash(null);
            assertThatThrownBy(() -> service.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chatchat.enterprise.bootstrap.admin-password");
            ReflectionTestUtils.setField(service, "bootstrapAdminPassword", "123456");
            assertThatThrownBy(() -> service.run(null)).isInstanceOf(IllegalStateException.class);
        } finally {
            ReflectionTestUtils.setField(service, "bootstrapAdminPassword", "Test-Only-Admin-Password!");
        }
    }

    @Test
    void rejectsNewUserWithoutPasswordAndPreservesExistingPasswordOnUpdate() {
        SysTenant tenant = tenantRepository.findByTenantCode("default").orElseThrow();
        SysUser input = user(tenant.getId(), null, "password-required");
        input.setPasswordHash(null);
        assertThatThrownBy(() -> service.saveUser(input, List.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("password");
        assertThat(userRepository.findByUsername(input.getUsername())).isEmpty();
        input.setPasswordHash("Explicit-User-Password!");
        var saved = service.saveUser(input, List.of());
        input.setId(saved.id());
        input.setPasswordHash(null);
        service.saveUser(input, null);
        assertThat(service.login(input.getUsername(), "Explicit-User-Password!").user().id())
            .isEqualTo(saved.id());
    }

    @Test
    void passwordlessExternalUsersGetIndependentCredentialsThatSurviveResync() {
        for (long id = 1; id <= 2; id++) {
            ExternalUser external = new ExternalUser();
            external.setId(id);
            external.setUserId("passwordless-" + id);
            externalUserRepository.save(external);
        }
        externalUserRepository.flush();
        service.syncExternalUsers(null);
        String firstHash = userRepository.findByUsername("passwordless-1").orElseThrow().getPasswordHash();
        String secondHash = userRepository.findByUsername("passwordless-2").orElseThrow().getPasswordHash();
        assertThat(firstHash).isNotEqualTo(secondHash);
        assertThat(PasswordHashCodec.isEncoded(firstHash)).isTrue();
        for (String username : List.of("passwordless-1", "passwordless-2")) {
            assertThatThrownBy(() -> service.login(username, "123456"))
                .isInstanceOf(IllegalArgumentException.class);
        }
        service.syncExternalUsers(null);
        assertThat(userRepository.findByUsername("passwordless-1").orElseThrow().getPasswordHash())
            .isEqualTo(firstHash);
    }

    @Test
    void identifiesPlatformTenantByNumberWithoutEnterpriseNameSpecialCases() {
        SysTenant platform = tenantRepository.findByTenantCode("default").orElseThrow();
        platform.setTenantCode("custom-platform");
        tenantRepository.saveAndFlush(platform);
        SysTenant resolved = ReflectionTestUtils.invokeMethod(service, "resolveDefaultTenant");
        assertThat(resolved.getId()).isEqualTo(platform.getId());
        assertThat(resolved.getTenantCode()).isEqualTo("default");
    }

    @Test
    void doesNotRenameAnUnconfiguredEnterpriseTenant() {
        SysTenant enterprise = tenantRepository.findByTenantCode("default").orElseThrow();
        enterprise.setTenantCode("guodu");
        enterprise.setTenantNo(100001L);
        tenantRepository.saveAndFlush(enterprise);
        SysTenant resolved = ReflectionTestUtils.invokeMethod(service, "resolveDefaultTenant");
        assertThat(resolved.getId()).isNotEqualTo(enterprise.getId());
        assertThat(enterprise.getTenantCode()).isEqualTo("guodu");
    }

    @Test
    void migratesOnlyExplicitlyConfiguredLegacyTenantAndKeepsItsIdentity() {
        SysTenant legacy = tenantRepository.findByTenantCode("default").orElseThrow();
        legacy.setTenantCode("configured-legacy");
        legacy.setTenantNo(null);
        tenantRepository.saveAndFlush(legacy);
        try {
            ReflectionTestUtils.setField(service, "legacyTenantCode", "configured-legacy");
            SysTenant resolved = ReflectionTestUtils.invokeMethod(service, "resolveDefaultTenant");
            assertThat(resolved.getId()).isEqualTo(legacy.getId());
            assertThat(resolved.getTenantCode()).isEqualTo("default");
        } finally {
            ReflectionTestUtils.setField(service, "legacyTenantCode", "");
        }
    }

    @Test
    void initializesSecureAdminAndEnforcesLoginAndEmbedTokenLifecycle() {
        EnterpriseAdminService.AuthResult login = service.login("admin", "Test-Only-Admin-Password!");
        assertThat(login.token()).isNotBlank();
        assertThat(login.user().username()).isEqualTo("admin");
        assertThat(service.isTokenValid(login.token())).isTrue();
        assertThat(service.resolveSessionByToken(login.token())).isPresent();
        assertThat(service.listPermissions())
            .anySatisfy(permission -> {
                assertThat(permission.getPermissionCode()).isEqualTo("capability:data-science");
                assertThat(permission.getParentId()).isNotBlank();
                assertThat(permission.getResourcePath()).isEqualTo("/api/v1/data-science/**");
                assertThat(permission.getHttpMethod()).isEqualTo("*");
            });
        assertThat(login.user().permissionCodes()).contains("capability:data-science");
        assertThat(service.listMenuConfigurations().stream()
            .map(item -> item.menu().getMenuCode())
            .filter(code -> code.startsWith("system") && !"system".equals(code))
            .toList())
            .containsSubsequence(
                "systemUsers", "systemRoles", "systemOrganizations", "systemResources",
                "systemLogins");
        assertThat(service.listMenuConfigurations()).extracting(item -> item.menu().getMenuCode())
            .doesNotContain("systemSkillRoles", "permissionTools");
        assertThat(resourceGrantRepository.findAll())
            .allSatisfy(grant -> {
                assertThat(grant.getResourceId()).isEqualTo("*");
                assertThat(grant.getPrincipalType()).isEqualTo("ROLE");
                assertThat(grant.getEffect()).isEqualTo("ALLOW");
            });
        assertThat(resourceGrantRepository.findAll()).extracting(ResourceGrant::getResourceType)
            .containsExactlyInAnyOrder(
                ResourceAuthorizationPort.KNOWLEDGE, ResourceAuthorizationPort.KNOWLEDGE_BASE,
                ResourceAuthorizationPort.MCP_TOOL, ResourceAuthorizationPort.SKILL,
                ResourceAuthorizationPort.AGENT_SKILL, ResourceAuthorizationPort.WORKFLOW,
                ResourceAuthorizationPort.TEMPLATE);
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
    void migratesPermissionAliasesAndHierarchyToTheChineseDatabaseCatalogOnce() {
        SysPermission allApi = service.listPermissions().stream()
            .filter(permission -> "system:api:all".equals(permission.getPermissionCode()))
            .findFirst()
            .orElseThrow();
        SysPermission workspace = service.listPermissions().stream()
            .filter(permission -> "workspace".equals(permission.getPermissionCode()))
            .findFirst()
            .orElseThrow();
        SysPermission search = service.listPermissions().stream()
            .filter(permission -> "workspace:search".equals(permission.getPermissionCode()))
            .findFirst()
            .orElseThrow();

        assertThat(allApi.getPermissionName()).isEqualTo("系统全部接口权限");
        assertThat(search.getPermissionName()).isEqualTo("文档检索");
        assertThat(search.getParentId()).isEqualTo(workspace.getId());
        assertThat(service.listPermissions())
            .anySatisfy(permission -> assertThat(permission.getPermissionCode())
                .isEqualTo("system:permission-catalog:zh:v1"));

        allApi.setPermissionName("数据库自定义接口别名");
        service.savePermission(allApi);
        service.run(null);

        assertThat(service.listPermissions().stream()
            .filter(permission -> "system:api:all".equals(permission.getPermissionCode()))
            .findFirst().orElseThrow().getPermissionName())
            .isEqualTo("数据库自定义接口别名");
    }

    @Test
    void exposesTrustedSuperAdminKeysForDownstreamDocumentAuthorization() {
        EnterpriseAdminService.AuthResult login = service.login("admin", "Test-Only-Admin-Password!");

        assertThat(service.authorizationRoleKeys(login.user().id()))
            .contains("SUPER_ADMIN", "超级管理员");
    }

    @Test
    void roleAuthorizationExposesEffectiveAllAgentPermissionWithoutFabricatingBindings() {
        var user = service.login("admin", "Test-Only-Admin-Password!").user();
        String roleId = user.roleIds().get(0);
        var initial = service.getRoleAuthorization(roleId);
        assertThat(initial.allAgentAccess()).isTrue();
        SysPermission allAgents = service.listPermissions().stream()
            .filter(permission -> "platform:agents:all".equals(permission.getPermissionCode())).findFirst().orElseThrow();
        allAgents.setStatus("disabled");
        service.savePermission(allAgents);
        assertThat(service.getRoleAuthorization(roleId).allAgentAccess()).isFalse();
        allAgents.setStatus("enabled");
        service.savePermission(allAgents);
        service.saveRoleAuthorization(roleId, new EnterpriseAdminService.RoleAuthorizationRequest(
            List.of(), List.of(), null, List.of("agent-a")));
        var restricted = service.getRoleAuthorization(roleId);
        assertThat(restricted.role().getRoleCode()).isEqualTo("SUPER_ADMIN");
        assertThat(restricted.allAgentAccess()).isFalse();
        assertThat(restricted.agentIds()).containsExactly("agent-a");
    }

    @Test
    void adminUsernameDoesNotBypassPersistedRolePermissions() {
        EnterpriseAdminService.AuthResult login = service.login("admin", "Test-Only-Admin-Password!");
        SysPermission selfRead = service.listPermissions().stream()
            .filter(permission -> "account:self:read".equals(permission.getPermissionCode()))
            .findFirst()
            .orElseThrow();
        service.saveRoleAuthorization(login.user().roleIds().get(0),
            new EnterpriseAdminService.RoleAuthorizationRequest(
                List.of(selfRead.getId()), List.of(), null, List.of()));

        service.run(null);
        EnterpriseAdminService.UserView reloaded = service.login("admin", "Test-Only-Admin-Password!").user();

        assertThat(reloaded.permissionCodes()).containsExactly("account:self:read");
        assertThat(service.hasAllAgentAccess(reloaded)).isFalse();
    }

    @Test
    void persistsMenuPermissionRelationsAndDoesNotOverwriteConfigurationOnRestart() {
        EnterpriseAdminService.AuthResult login = service.login("admin", "Test-Only-Admin-Password!");
        EnterpriseAdminService.MenuConfigurationView chatConfig = service.listMenuConfigurations().stream()
            .filter(item -> "chat".equals(item.menu().getMenuCode()))
            .findFirst()
            .orElseThrow();
        assertThat(chatConfig.permissionIds()).hasSize(1);
        assertThat(service.listAuthorizedMenus(login.user().id()))
            .flatExtracting(EnterpriseAdminService.MenuNode::children)
            .anySatisfy(menu -> assertThat(menu.id()).isEqualTo("chat"));

        SysMenu chat = chatConfig.menu();
        chat.setMenuName("数据库配置的对话入口");
        chat.setRoutePath("/index.html#chat-configured");
        service.saveMenu(chat, chatConfig.permissionIds());
        service.run(null);

        EnterpriseAdminService.MenuConfigurationView reloaded = service.listMenuConfigurations().stream()
            .filter(item -> "chat".equals(item.menu().getMenuCode()))
            .findFirst()
            .orElseThrow();
        assertThat(reloaded.menu().getMenuName()).isEqualTo("数据库配置的对话入口");
        assertThat(reloaded.menu().getRoutePath()).isEqualTo("/index.html#chat-configured");
        assertThat(reloaded.permissionIds()).containsExactlyElementsOf(chatConfig.permissionIds());
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
        assertThat(userA.tenantName()).isEqualTo("release-a");
        assertThatThrownBy(() -> service.saveUser(
            user(tenantA.getId(), orgA.getId(), "cross-tenant-role"), List.of(roleB.getId())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not belong");
        SysOrg orgB = service.saveOrg(org(tenantB.getId(), "ORG-B"));
        assertThatThrownBy(() -> service.saveUser(
            user(tenantA.getId(), orgB.getId(), "cross-tenant-org"), List.of(roleA.getId())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("organization does not belong");

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
        assertThatThrownBy(() -> service.saveToolPermission(permission))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid MCP scope");
        permission.setScopeExpression("mcp:*:*:*@tenant=" + tenant.getId());
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

    @Test
    void deletesToolPermissionsInOneBatchAndIgnoresDuplicatesAndMissingIds() {
        long initialToolPermissions = ((Number) service.summary().get("toolPermissionCount")).longValue();
        SysTenant tenant = service.saveTenant(tenant("release-batch-permissions"));
        SysRole batchRole = service.saveRole(role(tenant.getId(), "BATCH"));
        McpToolPermission first = service.saveToolPermission(toolPermission(tenant.getId(), batchRole.getId(), "batch_tool_1"));
        McpToolPermission second = service.saveToolPermission(toolPermission(tenant.getId(), batchRole.getId(), "batch_tool_2"));
        service.saveToolPermission(toolPermission(tenant.getId(), batchRole.getId(), "batch_tool_3"));

        int deleted = service.deleteToolPermissions(List.of(first.getId(), second.getId(), first.getId(), "missing-id"));

        assertThat(deleted).isEqualTo(2);
        assertThat(service.summary()).containsEntry("toolPermissionCount", initialToolPermissions + 1L);
        assertThatThrownBy(() -> service.deleteToolPermissions(List.of(" ")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be empty");
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

    private McpToolPermission toolPermission(String tenantId, String roleId, String toolName) {
        McpToolPermission value = new McpToolPermission();
        value.setTenantId(tenantId);
        value.setTargetType("role");
        value.setTargetId(roleId);
        value.setLocalToolName(toolName);
        value.setEffect("allow");
        value.setEnabled(true);
        return value;
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.chatchat.enterprise.repository")
    @EntityScan(basePackages = "com.chatchat.enterprise.entity")
    @Import(EnterpriseAdminService.class)
    static class Config {
        @Bean
        McpRuntimeKernel runtimeKernel() {
            return mock(McpRuntimeKernel.class);
        }

        @Bean
        InternalCredentialProperties internalCredentialProperties() {
            InternalCredentialProperties properties = new InternalCredentialProperties();
            properties.setEnabled(false);
            return properties;
        }
    }
}
