package com.chatchat.api.enterprise.service;

import com.chatchat.api.enterprise.entity.DataSourceConfig;
import com.chatchat.api.enterprise.entity.ExternalOrg;
import com.chatchat.api.enterprise.entity.ExternalUser;
import com.chatchat.api.enterprise.entity.McpToolAsset;
import com.chatchat.api.enterprise.entity.McpToolPermission;
import com.chatchat.api.enterprise.entity.SysAuditLog;
import com.chatchat.api.enterprise.entity.SysOrg;
import com.chatchat.api.enterprise.entity.SysPermission;
import com.chatchat.api.enterprise.entity.SysRole;
import com.chatchat.api.enterprise.entity.SysRoleOrgScope;
import com.chatchat.api.enterprise.entity.SysRolePermission;
import com.chatchat.api.enterprise.entity.SysTenant;
import com.chatchat.api.enterprise.entity.SysUser;
import com.chatchat.api.enterprise.entity.SysUserRole;
import com.chatchat.api.enterprise.repository.DataSourceConfigRepository;
import com.chatchat.api.enterprise.repository.ExternalOrgRepository;
import com.chatchat.api.enterprise.repository.ExternalUserRepository;
import com.chatchat.api.enterprise.repository.McpToolAssetRepository;
import com.chatchat.api.enterprise.repository.McpToolPermissionRepository;
import com.chatchat.api.enterprise.repository.SysAuditLogRepository;
import com.chatchat.api.enterprise.repository.SysOrgRepository;
import com.chatchat.api.enterprise.repository.SysPermissionRepository;
import com.chatchat.api.enterprise.repository.SysRoleOrgScopeRepository;
import com.chatchat.api.enterprise.repository.SysRolePermissionRepository;
import com.chatchat.api.enterprise.repository.SysRoleRepository;
import com.chatchat.api.enterprise.repository.SysTenantRepository;
import com.chatchat.api.enterprise.repository.SysUserRepository;
import com.chatchat.api.enterprise.repository.SysUserRoleRepository;
import com.chatchat.api.mcp.service.McpToolRegistryBridge;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EnterpriseAdminService implements ApplicationRunner {

    private static final String DEFAULT_TENANT_CODE = "guodu";

    private final SysTenantRepository tenantRepository;
    private final SysOrgRepository orgRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRepository userRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysPermissionRepository permissionRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final SysRoleOrgScopeRepository roleOrgScopeRepository;
    private final ExternalOrgRepository externalOrgRepository;
    private final ExternalUserRepository externalUserRepository;
    private final McpToolAssetRepository toolAssetRepository;
    private final McpToolPermissionRepository toolPermissionRepository;
    private final DataSourceConfigRepository dataSourceRepository;
    private final SysAuditLogRepository auditLogRepository;
    private final McpToolRegistryBridge registryBridge;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        initializeDemoData();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenantCount", tenantRepository.count());
        data.put("orgCount", orgRepository.count());
        data.put("userCount", userRepository.count());
        data.put("roleCount", roleRepository.count());
        data.put("permissionCount", permissionRepository.count());
        data.put("rolePermissionCount", rolePermissionRepository.count());
        data.put("mcpToolCount", toolAssetRepository.count());
        data.put("toolPermissionCount", toolPermissionRepository.count());
        data.put("dataSourceCount", dataSourceRepository.count());
        data.put("auditLogCount", auditLogRepository.count());
        return data;
    }

    @Transactional
    public AuthResult login(String username, String password) {
        SysUser user = userRepository.findByUsername(requireText(username, "username"))
            .orElseThrow(() -> new IllegalArgumentException("user not found"));
        if (!"enabled".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalArgumentException("user is disabled");
        }
        String expected = user.getPasswordHash() == null ? "" : user.getPasswordHash();
        if (!expected.equals(password == null ? "" : password)) {
            throw new IllegalArgumentException("invalid username or password");
        }
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
        audit(user.getTenantId(), user.getId(), user.getDisplayName(), "auth", "login", "sys_user", user.getId(), "login success");
        String tokenSeed = user.getId() + ":" + user.getUsername() + ":" + System.currentTimeMillis();
        return new AuthResult(Base64.getUrlEncoder().withoutPadding()
            .encodeToString(tokenSeed.getBytes(StandardCharsets.UTF_8)), toUserView(user));
    }

    @Transactional
    public SysTenant saveTenant(SysTenant input) {
        SysTenant entity = input.getId() == null ? new SysTenant() :
            tenantRepository.findById(input.getId()).orElseGet(SysTenant::new);
        entity.setTenantCode(requireText(input.getTenantCode(), "tenantCode"));
        entity.setTenantName(requireText(input.getTenantName(), "tenantName"));
        entity.setStatus(defaultText(input.getStatus(), "enabled"));
        entity.setContactName(trimToNull(input.getContactName()));
        entity.setContactPhone(trimToNull(input.getContactPhone()));
        entity.setDescription(trimToNull(input.getDescription()));
        SysTenant saved = tenantRepository.save(entity);
        audit(saved.getId(), null, "system", "tenant", input.getId() == null ? "create" : "update",
            "sys_tenant", saved.getId(), saved.getTenantName());
        return saved;
    }

    @Transactional
    public SysOrg saveOrg(SysOrg input) {
        SysOrg entity = input.getId() == null ? new SysOrg() :
            orgRepository.findById(input.getId()).orElseGet(SysOrg::new);
        entity.setTenantId(requireText(input.getTenantId(), "tenantId"));
        entity.setParentId(trimToNull(input.getParentId()));
        entity.setOrgCode(requireText(input.getOrgCode(), "orgCode"));
        entity.setOrgName(requireText(input.getOrgName(), "orgName"));
        entity.setSortOrder(input.getSortOrder() == null ? 0 : input.getSortOrder());
        entity.setStatus(defaultText(input.getStatus(), "enabled"));
        SysOrg saved = orgRepository.save(entity);
        audit(saved.getTenantId(), null, "system", "org", input.getId() == null ? "create" : "update",
            "sys_org", saved.getId(), saved.getOrgName());
        return saved;
    }

    @Transactional
    public SysRole saveRole(SysRole input) {
        SysRole entity = input.getId() == null ? new SysRole() :
            roleRepository.findById(input.getId()).orElseGet(SysRole::new);
        entity.setTenantId(requireText(input.getTenantId(), "tenantId"));
        entity.setRoleCode(requireText(input.getRoleCode(), "roleCode"));
        entity.setRoleName(requireText(input.getRoleName(), "roleName"));
        entity.setRoleType(defaultText(input.getRoleType(), "business"));
        entity.setStatus(defaultText(input.getStatus(), "enabled"));
        entity.setDescription(trimToNull(input.getDescription()));
        SysRole saved = roleRepository.save(entity);
        audit(saved.getTenantId(), null, "system", "role", input.getId() == null ? "create" : "update",
            "sys_role", saved.getId(), saved.getRoleName());
        return saved;
    }

    @Transactional
    public SysPermission savePermission(SysPermission input) {
        SysPermission entity = input.getId() == null ? new SysPermission() :
            permissionRepository.findById(input.getId()).orElseGet(SysPermission::new);
        entity.setParentId(trimToNull(input.getParentId()));
        entity.setPermissionCode(requireText(input.getPermissionCode(), "permissionCode"));
        entity.setPermissionName(requireText(input.getPermissionName(), "permissionName"));
        entity.setPermissionType(defaultText(input.getPermissionType(), "menu").toLowerCase());
        entity.setResourcePath(trimToNull(input.getResourcePath()));
        entity.setHttpMethod(trimToNull(input.getHttpMethod()));
        entity.setIcon(trimToNull(input.getIcon()));
        entity.setSortOrder(input.getSortOrder() == null ? 0 : input.getSortOrder());
        entity.setStatus(defaultText(input.getStatus(), "enabled"));
        SysPermission saved = permissionRepository.save(entity);
        audit(null, null, "system", "permission", input.getId() == null ? "create" : "update",
            "sys_permission", saved.getId(), saved.getPermissionCode());
        return saved;
    }

    @Transactional
    public UserView saveUser(SysUser input, List<String> roleIds) {
        SysUser entity = input.getId() == null ? new SysUser() :
            userRepository.findById(input.getId()).orElseGet(SysUser::new);
        entity.setTenantId(requireText(input.getTenantId(), "tenantId"));
        entity.setOrgId(trimToNull(input.getOrgId()));
        entity.setUsername(requireText(input.getUsername(), "username"));
        entity.setDisplayName(requireText(input.getDisplayName(), "displayName"));
        entity.setPasswordHash(defaultText(input.getPasswordHash(), entity.getPasswordHash() == null ? "123456" : entity.getPasswordHash()));
        entity.setEmail(trimToNull(input.getEmail()));
        entity.setPhone(trimToNull(input.getPhone()));
        entity.setStatus(defaultText(input.getStatus(), "enabled"));
        SysUser saved = userRepository.save(entity);
        if (roleIds != null) {
            userRoleRepository.deleteByUserId(saved.getId());
            roleIds.stream().filter(id -> id != null && !id.isBlank()).distinct().forEach(roleId -> {
                SysUserRole userRole = new SysUserRole();
                userRole.setTenantId(saved.getTenantId());
                userRole.setUserId(saved.getId());
                userRole.setRoleId(roleId.trim());
                userRoleRepository.save(userRole);
            });
        }
        audit(saved.getTenantId(), null, "system", "user", input.getId() == null ? "create" : "update",
            "sys_user", saved.getId(), saved.getUsername());
        return toUserView(saved);
    }

    @Transactional(readOnly = true)
    public List<SysPermission> listPermissions() {
        return permissionRepository.findAllByOrderBySortOrderAscPermissionNameAsc();
    }

    @Transactional(readOnly = true)
    public RoleAuthorizationView getRoleAuthorization(String roleId) {
        SysRole role = roleRepository.findById(requireText(roleId, "roleId"))
            .orElseThrow(() -> new IllegalArgumentException("role not found"));
        List<String> permissionIds = rolePermissionRepository.findByRoleId(role.getId()).stream()
            .map(SysRolePermission::getPermissionId)
            .toList();
        List<String> userIds = userRoleRepository.findByRoleId(role.getId()).stream()
            .map(SysUserRole::getUserId)
            .toList();
        return new RoleAuthorizationView(
            role,
            permissionIds,
            roleOrgScopeRepository.findByRoleIdOrderByScopeTypeAscOrgIdAsc(role.getId()),
            userIds
        );
    }

    @Transactional
    public RoleAuthorizationView saveRoleAuthorization(String roleId, RoleAuthorizationRequest request) {
        SysRole role = roleRepository.findById(requireText(roleId, "roleId"))
            .orElseThrow(() -> new IllegalArgumentException("role not found"));
        String tenantId = role.getTenantId();

        rolePermissionRepository.deleteByRoleId(role.getId());
        List<String> permissionIds = request == null || request.permissionIds() == null
            ? List.of()
            : request.permissionIds();
        permissionIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(String::trim)
            .distinct()
            .forEach(permissionId -> {
                SysRolePermission rolePermission = new SysRolePermission();
                rolePermission.setTenantId(tenantId);
                rolePermission.setRoleId(role.getId());
                rolePermission.setPermissionId(permissionId);
                rolePermissionRepository.save(rolePermission);
            });

        roleOrgScopeRepository.deleteByRoleId(role.getId());
        List<RoleOrgScopeInput> orgScopes = request == null || request.orgScopes() == null
            ? List.of()
            : request.orgScopes();
        orgScopes.stream()
            .filter(Objects::nonNull)
            .forEach(scopeInput -> {
                String scopeType = defaultText(scopeInput.scopeType(), "org_and_children");
                SysRoleOrgScope scope = new SysRoleOrgScope();
                scope.setTenantId(tenantId);
                scope.setRoleId(role.getId());
                scope.setScopeType(scopeType);
                scope.setOrgId("all".equalsIgnoreCase(scopeType) ? null : trimToNull(scopeInput.orgId()));
                roleOrgScopeRepository.save(scope);
            });

        if (request != null && request.userIds() != null) {
            userRoleRepository.deleteByRoleId(role.getId());
            request.userIds().stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .forEach(userId -> {
                    SysUserRole userRole = new SysUserRole();
                    userRole.setTenantId(tenantId);
                    userRole.setUserId(userId);
                    userRole.setRoleId(role.getId());
                    userRoleRepository.save(userRole);
                });
        }

        audit(tenantId, null, "system", "role", "authorize",
            "sys_role", role.getId(), "permissions=" + permissionIds.size() + ", scopes=" + orgScopes.size());
        return getRoleAuthorization(role.getId());
    }

    @Transactional(readOnly = true)
    public List<ExternalOrgView> listExternalOrgs() {
        return externalOrgRepository.findAllByOrderByGradeAscOrgOrderAscNameAsc().stream()
            .map(org -> new ExternalOrgView(
                org.getId(),
                org.getFid(),
                org.getOrgCode(),
                org.getName(),
                org.getFdnCode(),
                org.getStatus(),
                org.getOrgOrder()
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<ExternalUserView> listExternalUsers() {
        return externalUserRepository.findAllByOrderByUserIdAscNameAsc().stream()
            .map(user -> new ExternalUserView(
                user.getId(),
                user.getUserId(),
                user.getName(),
                user.getOrgId(),
                user.getStatus(),
                firstText(user.getEmail(), user.getOaEmail()),
                firstText(user.getOaTelephone(), user.getHrTelephone())
            ))
            .toList();
    }

    @Transactional
    public SyncResult syncExternalOrgs(String tenantId) {
        String resolvedTenantId = resolveTenantId(tenantId);
        List<ExternalOrg> externalOrgs = externalOrgRepository.findAllByOrderByGradeAscOrgOrderAscNameAsc();
        Map<Long, SysOrg> savedByExternalId = new HashMap<>();
        int created = 0;
        int updated = 0;

        for (ExternalOrg external : externalOrgs) {
            String orgCode = firstText(external.getOrgCode(), "ext-org-" + external.getId());
            SysOrg org = orgRepository.findByTenantIdAndOrgCode(resolvedTenantId, orgCode)
                .orElseGet(SysOrg::new);
            boolean isNew = org.getId() == null;
            org.setTenantId(resolvedTenantId);
            org.setOrgCode(orgCode);
            org.setOrgName(firstText(external.getName(), orgCode));
            org.setSortOrder(toInt(external.getOrgOrder()));
            org.setStatus(activeStatus(external.getStatus()));
            SysOrg saved = orgRepository.save(org);
            savedByExternalId.put(external.getId(), saved);
            if (isNew) {
                created++;
            } else {
                updated++;
            }
        }

        for (ExternalOrg external : externalOrgs) {
            SysOrg saved = savedByExternalId.get(external.getId());
            SysOrg parent = external.getFid() == null ? null : savedByExternalId.get(external.getFid());
            String nextParentId = parent == null ? null : parent.getId();
            if (!Objects.equals(saved.getParentId(), nextParentId)) {
                saved.setParentId(nextParentId);
                orgRepository.save(saved);
            }
        }

        audit(resolvedTenantId, null, "system", "external_org", "sync",
            "lborganization", null, "synced orgs: " + externalOrgs.size());
        return new SyncResult(externalOrgs.size(), created, updated);
    }

    @Transactional
    public SyncResult syncExternalUsers(String tenantId) {
        String resolvedTenantId = resolveTenantId(tenantId);
        Map<String, String> sysOrgIdByCode = orgRepository.findByTenantIdOrderBySortOrderAscOrgNameAsc(resolvedTenantId).stream()
            .collect(Collectors.toMap(SysOrg::getOrgCode, SysOrg::getId, (left, right) -> left));
        Map<Long, String> externalOrgCodeById = externalOrgRepository.findAll().stream()
            .filter(org -> org.getId() != null && org.getOrgCode() != null)
            .collect(Collectors.toMap(ExternalOrg::getId, ExternalOrg::getOrgCode, (left, right) -> left));

        List<ExternalUser> externalUsers = externalUserRepository.findAllByOrderByUserIdAscNameAsc();
        int created = 0;
        int updated = 0;
        for (ExternalUser external : externalUsers) {
            String username = firstText(external.getUserId(), external.getOaFno(), "u" + external.getId());
            SysUser user = userRepository.findByUsername(username).orElseGet(SysUser::new);
            boolean isNew = user.getId() == null;
            user.setTenantId(resolvedTenantId);
            String orgCode = externalOrgCodeById.get(external.getOrgId());
            user.setOrgId(orgCode == null ? null : sysOrgIdByCode.get(orgCode));
            user.setUsername(username);
            user.setDisplayName(firstText(external.getName(), username));
            user.setPasswordHash(defaultText(user.getPasswordHash(), firstText(external.getPassword(), "123456")));
            user.setEmail(trimToNull(firstText(external.getEmail(), external.getOaEmail())));
            user.setPhone(trimToNull(firstText(external.getOaTelephone(), external.getHrTelephone())));
            user.setStatus(activeStatus(external.getStatus()));
            user.setLastLoginAt(external.getLastLogin());
            userRepository.save(user);
            if (isNew) {
                created++;
            } else {
                updated++;
            }
        }

        audit(resolvedTenantId, null, "system", "external_user", "sync",
            "tuser", null, "synced users: " + externalUsers.size());
        return new SyncResult(externalUsers.size(), created, updated);
    }

    @Transactional
    public DataSourceConfig saveDataSource(DataSourceConfig input) {
        DataSourceConfig entity = input.getId() == null ? new DataSourceConfig() :
            dataSourceRepository.findById(input.getId()).orElseGet(DataSourceConfig::new);
        entity.setTenantId(requireText(input.getTenantId(), "tenantId"));
        entity.setName(requireText(input.getName(), "name"));
        entity.setType(requireText(input.getType(), "type").toUpperCase());
        entity.setJdbcUrl(requireText(input.getJdbcUrl(), "jdbcUrl"));
        entity.setUsername(trimToNull(input.getUsername()));
        entity.setPasswordCipher(trimToNull(input.getPasswordCipher()));
        entity.setStatus(defaultText(input.getStatus(), "enabled"));
        entity.setRemark(trimToNull(input.getRemark()));
        DataSourceConfig saved = dataSourceRepository.save(entity);
        audit(saved.getTenantId(), null, "system", "data_source", input.getId() == null ? "create" : "update",
            "data_source", saved.getId(), saved.getName());
        return saved;
    }

    @Transactional
    public List<McpToolAsset> syncMcpTools() {
        registryBridge.refreshRegistry();
        List<McpToolRegistryBridge.RegisteredMcpTool> registered = registryBridge.listRegisteredTools();
        for (McpToolRegistryBridge.RegisteredMcpTool tool : registered) {
            McpToolAsset asset = toolAssetRepository.findByLocalToolName(tool.localToolName())
                .orElseGet(McpToolAsset::new);
            asset.setLocalToolName(tool.localToolName());
            asset.setServiceId(tool.serviceId());
            asset.setServiceName(tool.serviceName());
            asset.setRemoteToolName(tool.remoteToolName());
            asset.setDescription(trimToNull(tool.description()));
            asset.setResourceType("tool");
            asset.setEnabled(true);
            asset.setStatus("online");
            toolAssetRepository.save(asset);
        }
        audit(null, null, "system", "mcp_tool", "sync", "mcp_tool", null,
            "synced registered tools: " + registered.size());
        return toolAssetRepository.findAllByOrderByLocalToolNameAsc();
    }

    @Transactional
    public McpToolPermission saveToolPermission(McpToolPermission input) {
        McpToolPermission entity = input.getId() == null ? new McpToolPermission() :
            toolPermissionRepository.findById(input.getId()).orElseGet(McpToolPermission::new);
        entity.setTenantId(trimToNull(input.getTenantId()));
        entity.setTargetType(requireText(input.getTargetType(), "targetType").toUpperCase());
        entity.setTargetId(requireText(input.getTargetId(), "targetId"));
        entity.setToolId(trimToNull(input.getToolId()));
        entity.setLocalToolName(requireText(input.getLocalToolName(), "localToolName"));
        entity.setEffect(defaultText(input.getEffect(), "allow").toLowerCase());
        entity.setEnabled(input.isEnabled());
        entity.setExpiresAt(input.getExpiresAt());
        entity.setRemark(trimToNull(input.getRemark()));
        McpToolPermission saved = toolPermissionRepository.save(entity);
        audit(saved.getTenantId(), null, "system", "tool_permission", input.getId() == null ? "grant" : "update",
            "mcp_tool_permission", saved.getId(), saved.getTargetType() + ":" + saved.getTargetId());
        return saved;
    }

    @Transactional
    public void delete(String module, String id) {
        switch (module) {
            case "tenant" -> tenantRepository.deleteById(id);
            case "org" -> orgRepository.deleteById(id);
            case "role" -> {
                rolePermissionRepository.deleteByRoleId(id);
                roleOrgScopeRepository.deleteByRoleId(id);
                userRoleRepository.deleteByRoleId(id);
                roleRepository.deleteById(id);
            }
            case "permission" -> {
                rolePermissionRepository.deleteByPermissionId(id);
                permissionRepository.deleteById(id);
            }
            case "user" -> {
                userRoleRepository.deleteByUserId(id);
                userRepository.deleteById(id);
            }
            case "data-source" -> dataSourceRepository.deleteById(id);
            case "tool-permission" -> toolPermissionRepository.deleteById(id);
            default -> throw new IllegalArgumentException("unsupported delete module: " + module);
        }
        audit(null, null, "system", module, "delete", module, id, "delete " + module);
    }

    @Transactional(readOnly = true)
    public List<UserView> listUserViews(String tenantId) {
        List<SysUser> users = tenantId == null || tenantId.isBlank()
            ? userRepository.findAll()
            : userRepository.findByTenantIdOrderByUsernameAsc(tenantId);
        return users.stream().map(this::toUserView).toList();
    }

    public UserView toUserView(SysUser user) {
        List<String> roleIds = userRoleRepository.findByUserId(user.getId()).stream()
            .map(SysUserRole::getRoleId)
            .toList();
        return new UserView(
            user.getId(),
            user.getTenantId(),
            user.getOrgId(),
            user.getUsername(),
            user.getDisplayName(),
            user.getEmail(),
            user.getPhone(),
            user.getStatus(),
            user.getLastLoginAt(),
            roleIds,
            user.getCreatedAt(),
            user.getUpdatedAt()
        );
    }

    private void initializeDemoData() {
        SysTenant tenant = tenantRepository.findByTenantCode(DEFAULT_TENANT_CODE).orElseGet(() -> {
            SysTenant seed = new SysTenant();
            seed.setTenantCode(DEFAULT_TENANT_CODE);
            seed.setTenantName("国都证券");
            seed.setContactName("平台管理员");
            seed.setDescription("企业级智能问答助手平台默认租户");
            return tenantRepository.save(seed);
        });

        SysOrg root = ensureOrg(tenant.getId(), null, "guodu-root", "国都证券", 0);
        SysOrg wealth = ensureOrg(tenant.getId(), root.getId(), "wealth", "财富管理部", 10);
        ensureOrg(tenant.getId(), root.getId(), "fixed-income", "固收业务部", 20);
        ensureOrg(tenant.getId(), root.getId(), "asset-management", "资管部", 30);
        SysOrg it = ensureOrg(tenant.getId(), root.getId(), "it", "信息技术部", 40);

        SysRole superAdmin = ensureRole(tenant.getId(), "SUPER_ADMIN", "超级管理员", "platform");
        ensureRole(tenant.getId(), "TENANT_ADMIN", "租户管理员", "tenant");
        ensureRole(tenant.getId(), "BUSINESS_ADMIN", "业务管理员", "business");
        ensureRole(tenant.getId(), "USER", "普通用户", "business");
        ensureRole(tenant.getId(), "GUEST", "访客", "guest");
        List<SysPermission> permissions = ensureDefaultPermissions();
        ensureRolePermissions(tenant.getId(), superAdmin.getId(), permissions);
        ensureAllOrgScope(tenant.getId(), superAdmin.getId());

        SysUser admin = userRepository.findByUsername("admin").orElseGet(() -> {
            SysUser seed = new SysUser();
            seed.setTenantId(tenant.getId());
            seed.setOrgId(it.getId());
            seed.setUsername("admin");
            seed.setDisplayName("系统管理员");
            seed.setPasswordHash("admin");
            seed.setEmail("admin@example.com");
            return userRepository.save(seed);
        });
        if (userRoleRepository.findByUserId(admin.getId()).isEmpty()) {
            SysUserRole userRole = new SysUserRole();
            userRole.setTenantId(tenant.getId());
            userRole.setUserId(admin.getId());
            userRole.setRoleId(superAdmin.getId());
            userRoleRepository.save(userRole);
        }

        if (dataSourceRepository.findByTenantIdOrderByNameAsc(tenant.getId()).isEmpty()) {
            DataSourceConfig ds = new DataSourceConfig();
            ds.setTenantId(tenant.getId());
            ds.setName("开发环境 H2");
            ds.setType("H2");
            ds.setJdbcUrl("jdbc:h2:file:./data/h2/chatchat");
            ds.setUsername("sa");
            ds.setRemark("默认开发数据源");
            dataSourceRepository.save(ds);
        }

        if (auditLogRepository.count() == 0) {
            audit(tenant.getId(), admin.getId(), admin.getDisplayName(), "system", "initialize",
                "enterprise_platform", tenant.getId(), "enterprise seed data initialized");
        }
        wealth.getId();
    }

    private SysOrg ensureOrg(String tenantId, String parentId, String code, String name, int sortOrder) {
        return orgRepository.findByTenantIdAndOrgCode(tenantId, code).orElseGet(() -> {
            SysOrg org = new SysOrg();
            org.setTenantId(tenantId);
            org.setParentId(parentId);
            org.setOrgCode(code);
            org.setOrgName(name);
            org.setSortOrder(sortOrder);
            return orgRepository.save(org);
        });
    }

    private SysRole ensureRole(String tenantId, String code, String name, String type) {
        return roleRepository.findByTenantIdAndRoleCode(tenantId, code).orElseGet(() -> {
            SysRole role = new SysRole();
            role.setTenantId(tenantId);
            role.setRoleCode(code);
            role.setRoleName(name);
            role.setRoleType(type);
            return roleRepository.save(role);
        });
    }

    private List<SysPermission> ensureDefaultPermissions() {
        List<PermissionSeed> seeds = List.of(
            new PermissionSeed(null, "workspace", "工作台", "menu", "/index.html", null, "layout-dashboard", 10),
            new PermissionSeed("workspace", "workspace:chat", "对话助手", "menu", "/index.html#chat", null, "message-circle", 11),
            new PermissionSeed("workspace", "workspace:search", "AI 搜索", "menu", "/index.html#search", null, "search", 12),
            new PermissionSeed(null, "capability", "能力中心", "menu", "/index.html#market", null, "grid-3x3", 20),
            new PermissionSeed("capability", "capability:market", "能力广场", "menu", "/index.html#market", null, "store", 21),
            new PermissionSeed("capability", "capability:library", "投研中心", "menu", "/index.html#library", null, "book-open", 22),
            new PermissionSeed(null, "mcp", "MCP 中心", "menu", "/index.html#mcp", null, "plug", 30),
            new PermissionSeed("mcp", "mcp:service:manage", "服务管理", "button", "/api/v1/mcp/**", "*", "server", 31),
            new PermissionSeed("mcp", "mcp:tool:authorize", "工具授权", "button", "/api/v1/enterprise/tool-permissions", "*", "key-round", 32),
            new PermissionSeed(null, "system", "系统管理", "menu", "/index.html#system", null, "settings", 40),
            new PermissionSeed("system", "system:tenant", "租户管理", "menu", "/api/v1/enterprise/tenants", "*", "building", 41),
            new PermissionSeed("system", "system:org", "组织管理", "menu", "/api/v1/enterprise/orgs", "*", "building-2", 42),
            new PermissionSeed("system", "system:user", "用户管理", "menu", "/api/v1/enterprise/users", "*", "users", 43),
            new PermissionSeed("system", "system:role", "角色管理", "menu", "/api/v1/enterprise/roles", "*", "shield", 44),
            new PermissionSeed("system:role", "system:role:authorize", "角色授权", "button", "/api/v1/enterprise/roles/*/authorization", "*", "shield-check", 45),
            new PermissionSeed("system", "system:permission", "权限管理", "menu", "/api/v1/enterprise/permissions", "*", "list-tree", 46),
            new PermissionSeed("system", "system:external-sync", "外部组织用户同步", "button", "/api/v1/enterprise/sync/**", "*", "refresh-cw", 47),
            new PermissionSeed(null, "audit", "审计中心", "menu", "/api/v1/enterprise/audit-logs", "GET", "file-search", 50)
        );
        Map<String, String> idsByCode = permissionRepository.findAll().stream()
            .collect(Collectors.toMap(SysPermission::getPermissionCode, SysPermission::getId, (left, right) -> left));
        for (PermissionSeed seed : seeds) {
            SysPermission permission = permissionRepository.findByPermissionCode(seed.code()).orElseGet(SysPermission::new);
            permission.setParentId(seed.parentCode() == null ? null : idsByCode.get(seed.parentCode()));
            permission.setPermissionCode(seed.code());
            permission.setPermissionName(seed.name());
            permission.setPermissionType(seed.type());
            permission.setResourcePath(seed.path());
            permission.setHttpMethod(seed.method());
            permission.setIcon(seed.icon());
            permission.setSortOrder(seed.sortOrder());
            permission.setStatus("enabled");
            SysPermission saved = permissionRepository.save(permission);
            idsByCode.put(saved.getPermissionCode(), saved.getId());
        }
        return permissionRepository.findAllByOrderBySortOrderAscPermissionNameAsc();
    }

    private void ensureRolePermissions(String tenantId, String roleId, List<SysPermission> permissions) {
        Set<String> exists = rolePermissionRepository.findByRoleId(roleId).stream()
            .map(SysRolePermission::getPermissionId)
            .collect(Collectors.toSet());
        for (SysPermission permission : permissions) {
            if (exists.contains(permission.getId())) {
                continue;
            }
            SysRolePermission rolePermission = new SysRolePermission();
            rolePermission.setTenantId(tenantId);
            rolePermission.setRoleId(roleId);
            rolePermission.setPermissionId(permission.getId());
            rolePermissionRepository.save(rolePermission);
        }
    }

    private void ensureAllOrgScope(String tenantId, String roleId) {
        boolean hasAllScope = roleOrgScopeRepository.findByRoleIdOrderByScopeTypeAscOrgIdAsc(roleId).stream()
            .anyMatch(scope -> "all".equalsIgnoreCase(scope.getScopeType()));
        if (hasAllScope) {
            return;
        }
        SysRoleOrgScope scope = new SysRoleOrgScope();
        scope.setTenantId(tenantId);
        scope.setRoleId(roleId);
        scope.setScopeType("all");
        roleOrgScopeRepository.save(scope);
    }

    private void audit(String tenantId, String actorId, String actorName, String module, String action,
                       String resourceType, String resourceId, String detail) {
        SysAuditLog log = new SysAuditLog();
        log.setTenantId(tenantId);
        log.setActorId(actorId);
        log.setActorName(actorName);
        log.setModuleName(module);
        log.setActionName(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setDetail(detail);
        auditLogRepository.save(log);
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String activeStatus(Long status) {
        return status == null || status.longValue() == 1L ? "enabled" : "disabled";
    }

    private int toInt(Long value) {
        if (value == null) {
            return 0;
        }
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return value.intValue();
    }

    private String resolveTenantId(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantRepository.findById(tenantId.trim())
                .orElseThrow(() -> new IllegalArgumentException("tenant not found"))
                .getId();
        }
        return tenantRepository.findByTenantCode(DEFAULT_TENANT_CODE)
            .map(SysTenant::getId)
            .orElseGet(() -> tenantRepository.findAll().stream()
                .findFirst()
                .map(SysTenant::getId)
                .orElseThrow(() -> new IllegalArgumentException("tenant not found")));
    }

    public record AuthResult(String token, UserView user) {
    }

    public record RoleAuthorizationRequest(
        List<String> permissionIds,
        List<RoleOrgScopeInput> orgScopes,
        List<String> userIds
    ) {
    }

    public record RoleOrgScopeInput(String scopeType, String orgId) {
    }

    public record RoleAuthorizationView(
        SysRole role,
        List<String> permissionIds,
        List<SysRoleOrgScope> orgScopes,
        List<String> userIds
    ) {
    }

    public record SyncResult(int total, int created, int updated) {
    }

    public record ExternalOrgView(
        Long id,
        Long parentId,
        String orgCode,
        String orgName,
        String fdnCode,
        Long status,
        Long sortOrder
    ) {
    }

    public record ExternalUserView(
        Long id,
        String username,
        String displayName,
        Long orgId,
        Long status,
        String email,
        String phone
    ) {
    }

    private record PermissionSeed(
        String parentCode,
        String code,
        String name,
        String type,
        String path,
        String method,
        String icon,
        int sortOrder
    ) {
    }

    public record UserView(
        String id,
        String tenantId,
        String orgId,
        String username,
        String displayName,
        String email,
        String phone,
        String status,
        Instant lastLoginAt,
        List<String> roleIds,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}
