package com.chatchat.api.enterprise.service;

import com.chatchat.api.enterprise.entity.DataSourceConfig;
import com.chatchat.api.enterprise.entity.McpToolAsset;
import com.chatchat.api.enterprise.entity.McpToolPermission;
import com.chatchat.api.enterprise.entity.SysAuditLog;
import com.chatchat.api.enterprise.entity.SysOrg;
import com.chatchat.api.enterprise.entity.SysRole;
import com.chatchat.api.enterprise.entity.SysTenant;
import com.chatchat.api.enterprise.entity.SysUser;
import com.chatchat.api.enterprise.entity.SysUserRole;
import com.chatchat.api.enterprise.repository.DataSourceConfigRepository;
import com.chatchat.api.enterprise.repository.McpToolAssetRepository;
import com.chatchat.api.enterprise.repository.McpToolPermissionRepository;
import com.chatchat.api.enterprise.repository.SysAuditLogRepository;
import com.chatchat.api.enterprise.repository.SysOrgRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EnterpriseAdminService implements ApplicationRunner {

    private static final String DEFAULT_TENANT_CODE = "guodu";

    private final SysTenantRepository tenantRepository;
    private final SysOrgRepository orgRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRepository userRepository;
    private final SysUserRoleRepository userRoleRepository;
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
            case "role" -> roleRepository.deleteById(id);
            case "user" -> userRepository.deleteById(id);
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
            ds.setJdbcUrl("jdbc:h2:file:./h2/chatchat");
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
        return orgRepository.findByTenantIdOrderBySortOrderAscOrgNameAsc(tenantId).stream()
            .filter(org -> code.equals(org.getOrgCode()))
            .findFirst()
            .orElseGet(() -> {
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

    public record AuthResult(String token, UserView user) {
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
