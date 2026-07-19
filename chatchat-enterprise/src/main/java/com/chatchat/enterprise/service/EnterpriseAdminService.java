package com.chatchat.enterprise.service;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.enterprise.entity.DataSourceConfig;
import com.chatchat.enterprise.entity.EmbedLoginToken;
import com.chatchat.enterprise.entity.ExternalOrg;
import com.chatchat.enterprise.entity.ExternalUser;
import com.chatchat.enterprise.entity.McpToolAsset;
import com.chatchat.enterprise.entity.McpToolPermission;
import com.chatchat.enterprise.entity.RoleAgentBinding;
import com.chatchat.enterprise.entity.SysAuditLog;
import com.chatchat.enterprise.entity.SysOrg;
import com.chatchat.enterprise.entity.SysPermission;
import com.chatchat.enterprise.entity.SysRole;
import com.chatchat.enterprise.entity.SysRoleOrgScope;
import com.chatchat.enterprise.entity.SysRolePermission;
import com.chatchat.enterprise.entity.SysTenant;
import com.chatchat.enterprise.entity.SysUser;
import com.chatchat.enterprise.entity.SysUserRole;
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
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EnterpriseAdminService implements ApplicationRunner {

    private static final String DEFAULT_TENANT_CODE = "default";
    private static final String LEGACY_TENANT_CODE = "guodu";
    private static final String DEFAULT_ADMIN_PASSWORD = "123456";

    private final SysTenantRepository tenantRepository;
    private final SysOrgRepository orgRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRepository userRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysPermissionRepository permissionRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final SysRoleOrgScopeRepository roleOrgScopeRepository;
    private final RoleAgentBindingRepository roleAgentBindingRepository;
    private final ExternalOrgRepository externalOrgRepository;
    private final ExternalUserRepository externalUserRepository;
    private final McpToolAssetRepository toolAssetRepository;
    private final McpToolPermissionRepository toolPermissionRepository;
    private final DataSourceConfigRepository dataSourceRepository;
    private final SysAuditLogRepository auditLogRepository;
    private final McpToolRegistryBridge registryBridge;
    private final EmbedLoginTokenRepository embedLoginTokenRepository;
    private final InternalCredentialProperties internalCredentialProperties;
    private final Map<String, String> activeTokens = new ConcurrentHashMap<>();
    private final Map<String, UserView> activeTokenUsers = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Runs the configured startup logic.
     *
     * @param args the args value
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        initializeDemoData();
    }

    /**
     * Performs the summary operation.
     *
     * @return the operation result
     */
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

    /**
     * Performs the login operation.
     *
     * @param username the username value
     * @param password the password value
     * @return the operation result
     */
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
        String tokenSeed = user.getId() + ":" + user.getUsername() + ":" + UUID.randomUUID();
        String token = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(tokenSeed.getBytes(StandardCharsets.UTF_8));
        UserView userView = toUserView(user);
        activeTokens.put(token, user.getId());
        activeTokenUsers.put(token, userView);
        return new AuthResult(token, userView);
    }

    /**
     * Logs in with an admin-owned embed token.
     *
     * @param token the embed token value
     * @return the operation result
     */
    @Transactional
    public AuthResult loginWithEmbedToken(String token) {
        EmbedLoginToken record = resolveUsableEmbedToken(token)
            .orElseThrow(() -> new IllegalArgumentException("embed token is invalid or expired"));
        SysUser user = userRepository.findById(record.getUserId())
            .orElseThrow(() -> new IllegalArgumentException("user not found"));
        if (!isAdminUser(user) || !"enabled".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalArgumentException("embed token user is not available");
        }
        Instant now = Instant.now();
        user.setLastLoginAt(now);
        userRepository.save(user);
        record.setLastUsedAt(now);
        record.setUsedCount(record.getUsedCount() + 1L);
        embedLoginTokenRepository.save(record);
        UserView userView = toUserView(user);
        activeTokenUsers.put(record.getToken(), userView);
        return new AuthResult(record.getToken(), userView, record.getExpiresAt(), true);
    }

    /**
     * Lists the embed login tokens for the admin user.
     *
     * @param operatorUsername the current operator username
     * @return the embed token views
     */
    @Transactional(readOnly = true)
    public List<EmbedLoginTokenView> listEmbedLoginTokens(String operatorUsername) {
        SysUser admin = requireAdminOperator(operatorUsername);
        return embedLoginTokenRepository.findByUserIdOrderByCreatedAtDesc(admin.getId()).stream()
            .map(this::toEmbedLoginTokenView)
            .toList();
    }

    /**
     * Creates an embed login token for the admin user.
     *
     * @param operatorUsername the current operator username
     * @param request the create request
     * @return the created token view
     */
    @Transactional
    public EmbedLoginTokenView createEmbedLoginToken(String operatorUsername, EmbedLoginTokenRequest request) {
        SysUser admin = requireAdminOperator(operatorUsername);
        long expiresInSeconds = request == null || request.expiresInSeconds() == null
            ? ChronoUnit.DAYS.getDuration().getSeconds()
            : request.expiresInSeconds();
        Instant expiresAt = expiresInSeconds <= 0 ? null : Instant.now().plusSeconds(expiresInSeconds);
        String token = generateSecureToken();
        EmbedLoginToken record = new EmbedLoginToken();
        record.setToken(token);
        record.setTokenPreview(tokenPreview(token));
        record.setTenantId(admin.getTenantId());
        record.setUserId(admin.getId());
        record.setUsername(admin.getUsername());
        record.setDisplayName(admin.getDisplayName());
        record.setExpiresAt(expiresAt);
        record.setStatus("active");
        record.setCreatedBy(admin.getId());
        record.setCreatedByName(admin.getDisplayName());
        EmbedLoginToken saved = embedLoginTokenRepository.save(record);
        audit(admin.getTenantId(), admin.getId(), admin.getDisplayName(), "auth", "embed-token-create",
            "embed_login_token", saved.getId(), expiresAt == null ? "permanent" : expiresAt.toString());
        return toEmbedLoginTokenView(saved);
    }

    /**
     * Expires an embed login token.
     *
     * @param operatorUsername the current operator username
     * @param tokenId the token id
     * @return the expired token view
     */
    @Transactional
    public EmbedLoginTokenView expireEmbedLoginToken(String operatorUsername, String tokenId) {
        SysUser admin = requireAdminOperator(operatorUsername);
        EmbedLoginToken record = embedLoginTokenRepository.findById(requireText(tokenId, "tokenId"))
            .orElseThrow(() -> new IllegalArgumentException("embed token not found"));
        if (!admin.getId().equals(record.getUserId())) {
            throw new IllegalArgumentException("embed token is not owned by admin");
        }
        record.setStatus("expired");
        record.setExpiresAt(Instant.now());
        record.setRevokedAt(Instant.now());
        activeTokenUsers.remove(record.getToken());
        EmbedLoginToken saved = embedLoginTokenRepository.save(record);
        audit(admin.getTenantId(), admin.getId(), admin.getDisplayName(), "auth", "embed-token-expire",
            "embed_login_token", saved.getId(), "expired by admin");
        return toEmbedLoginTokenView(saved);
    }

    /**
     * Returns whether is token valid.
     *
     * @param token the token value
     * @return whether the condition is satisfied
     */
    @Transactional(readOnly = true)
    public boolean isTokenValid(String token) {
        return resolveUserByToken(token)
            .filter(user -> "enabled".equalsIgnoreCase(user.getStatus()))
            .isPresent()
            || resolveUsableEmbedToken(token).isPresent();
    }

    /**
     * Resolves the user by bearer token.
     *
     * @param token the token value
     * @return the matching user
     */
    @Transactional(readOnly = true)
    public Optional<SysUser> resolveUserByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String userId = activeTokens.get(token.trim());
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findById(userId);
    }

    /**
     * Resolves the already-authenticated user snapshot without opening a JPA transaction.
     *
     * @param token the bearer token value
     * @return the cached user snapshot
     */
    @Transactional(readOnly = true)
    public Optional<UserView> resolveSessionByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String normalized = token.trim();
        UserView cached = activeTokenUsers.get(normalized);
        if (cached != null) {
            if (activeTokens.get(normalized) != null) {
                return Optional.of(cached);
            }
            Optional<EmbedLoginToken> embedToken = resolveUsableEmbedToken(normalized);
            if (embedToken.isEmpty()) {
                activeTokenUsers.remove(normalized);
                return Optional.empty();
            }
            return embedToken
                .flatMap(record -> userRepository.findById(record.getUserId()))
                .filter(user -> isAdminUser(user) && "enabled".equalsIgnoreCase(user.getStatus()))
                .map(user -> {
                    UserView userView = toUserView(user);
                    activeTokenUsers.put(normalized, userView);
                    return userView;
                });
        }
        return resolveUsableEmbedToken(normalized)
            .flatMap(record -> userRepository.findById(record.getUserId()))
            .filter(user -> isAdminUser(user) && "enabled".equalsIgnoreCase(user.getStatus()))
            .map(user -> {
                UserView userView = toUserView(user);
                activeTokenUsers.put(normalized, userView);
                return userView;
            });
    }

    /**
     * Saves the tenant.
     *
     * @param input the input value
     * @return the saved tenant
     */
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

    /**
     * Saves the org.
     *
     * @param input the input value
     * @return the saved org
     */
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

    /**
     * Saves the role.
     *
     * @param input the input value
     * @return the saved role
     */
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

    /**
     * Saves the permission.
     *
     * @param input the input value
     * @return the saved permission
     */
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

    /**
     * Saves the user.
     *
     * @param input the input value
     * @param roleIds the role ids value
     * @return the saved user
     */
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

    /**
     * Changes the platform admin password.
     *
     * @param operatorUsername the current operator username
     * @param currentPassword the current password value
     * @param newPassword the new password value
     * @param confirmPassword the confirmation password value
     * @return the updated admin user view
     */
    @Transactional
    public UserView changeAdminPassword(String operatorUsername,
                                        String currentPassword,
                                        String newPassword,
                                        String confirmPassword) {
        SysUser admin = requireAdminOperator(operatorUsername);
        String expected = admin.getPasswordHash() == null ? "" : admin.getPasswordHash();
        if (!expected.equals(currentPassword == null ? "" : currentPassword)) {
            throw new IllegalArgumentException("current password is incorrect");
        }
        String nextPassword = requireText(newPassword, "newPassword");
        if (nextPassword.length() < 6) {
            throw new IllegalArgumentException("new password must be at least 6 characters");
        }
        if (!nextPassword.equals(confirmPassword == null ? "" : confirmPassword)) {
            throw new IllegalArgumentException("password confirmation does not match");
        }
        admin.setPasswordHash(nextPassword);
        SysUser saved = userRepository.save(admin);
        invalidateUserPasswordSessions(saved.getId());
        audit(saved.getTenantId(), saved.getId(), saved.getDisplayName(), "auth", "password-change",
            "sys_user", saved.getId(), "admin password changed");
        return toUserView(saved);
    }

    /**
     * Lists the permissions.
     *
     * @return the permissions list
     */
    @Transactional(readOnly = true)
    public List<SysPermission> listPermissions() {
        return permissionRepository.findAllByOrderBySortOrderAscPermissionNameAsc();
    }

    /**
     * Returns the role authorization.
     *
     * @param roleId the role id value
     * @return the role authorization
     */
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
        List<String> agentIds = roleAgentBindingRepository.findByRoleId(role.getId()).stream()
            .map(RoleAgentBinding::getAgentId)
            .distinct()
            .toList();
        return new RoleAuthorizationView(
            role,
            permissionIds,
            roleOrgScopeRepository.findByRoleIdOrderByScopeTypeAscOrgIdAsc(role.getId()),
            userIds,
            agentIds
        );
    }

    /**
     * Saves the role authorization.
     *
     * @param roleId the role id value
     * @param request the request value
     * @return the saved role authorization
     */
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

        roleAgentBindingRepository.deleteByRoleId(role.getId());
        List<String> agentIds = request == null || request.agentIds() == null
            ? List.of()
            : request.agentIds();
        agentIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(id -> id.trim().toLowerCase())
            .distinct()
            .forEach(agentId -> {
                RoleAgentBinding binding = new RoleAgentBinding();
                binding.setTenantId(tenantId);
                binding.setRoleId(role.getId());
                binding.setAgentId(agentId);
                binding.setEnabled(true);
                roleAgentBindingRepository.save(binding);
            });

        audit(tenantId, null, "system", "role", "authorize",
            "sys_role", role.getId(), "permissions=" + permissionIds.size()
                + ", scopes=" + orgScopes.size()
                + ", agents=" + agentIds.size());
        return getRoleAuthorization(role.getId());
    }

    /**
     * Lists the external orgs.
     *
     * @return the external orgs list
     */
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

    /**
     * Lists the external users.
     *
     * @return the external users list
     */
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

    /**
     * Synchronizes the external orgs.
     *
     * @param tenantId the tenant id value
     * @return the operation result
     */
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

    /**
     * Synchronizes the external users.
     *
     * @param tenantId the tenant id value
     * @return the operation result
     */
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

    /**
     * Saves the data source.
     *
     * @param input the input value
     * @return the saved data source
     */
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

    /**
     * Synchronizes the mcp tools.
     *
     * @return the operation result
     */
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

    /**
     * Saves the tool permission.
     *
     * @param input the input value
     * @return the saved tool permission
     */
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

    /**
     * Deletes the delete.
     *
     * @param module the module value
     * @param id the id value
     */
    @Transactional
    public void delete(String module, String id) {
        switch (module) {
            case "tenant" -> tenantRepository.deleteById(id);
            case "org" -> orgRepository.deleteById(id);
            case "role" -> {
                rolePermissionRepository.deleteByRoleId(id);
                roleOrgScopeRepository.deleteByRoleId(id);
                userRoleRepository.deleteByRoleId(id);
                roleAgentBindingRepository.deleteByRoleId(id);
                roleRepository.deleteById(id);
            }
            case "permission" -> {
                rolePermissionRepository.deleteByPermissionId(id);
                permissionRepository.deleteById(id);
            }
            case "user" -> {
                SysUser user = userRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("user not found"));
                if ("admin".equalsIgnoreCase(user.getUsername())) {
                    throw new IllegalArgumentException("admin user cannot be deleted");
                }
                userRoleRepository.deleteByUserId(id);
                userRepository.deleteById(id);
            }
            case "data-source" -> dataSourceRepository.deleteById(id);
            case "tool-permission" -> toolPermissionRepository.deleteById(id);
            default -> throw new IllegalArgumentException("unsupported delete module: " + module);
        }
        audit(null, null, "system", module, "delete", module, id, "delete " + module);
    }

    /**
     * Lists the user views.
     *
     * @param tenantId the tenant id value
     * @return the user views list
     */
    @Transactional(readOnly = true)
    public List<UserView> listUserViews(String tenantId) {
        List<SysUser> users = tenantId == null || tenantId.isBlank()
            ? userRepository.findAll()
            : userRepository.findByTenantIdOrderByUsernameAsc(tenantId);
        return users.stream().map(this::toUserView).toList();
    }

    /**
     * Converts the value to user view.
     *
     * @param user the user value
     * @return the converted user view
     */
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

    /**
     * Returns whether the user has unrestricted agent access.
     *
     * @param userId the user id value
     * @return whether the user has all agent permissions
     */
    @Transactional(readOnly = true)
    public boolean hasAllAgentAccess(String userId) {
        return resolveUser(userId)
            .map(this::hasAllAgentAccess)
            .orElse(false);
    }

    /**
     * Returns whether can access agent.
     *
     * @param userId the user id value
     * @param agentId the agent id value
     * @return whether access is allowed
     */
    @Transactional(readOnly = true)
    public boolean canAccessAgent(String userId, String agentId) {
        String normalizedAgentId = normalizeAgentId(agentId);
        if (normalizedAgentId == null) {
            return true;
        }
        Optional<SysUser> user = resolveUser(userId);
        if (user.isEmpty()) {
            return false;
        }
        if (!"enabled".equalsIgnoreCase(user.get().getStatus())) {
            return false;
        }
        if (hasAllAgentAccess(user.get())) {
            return true;
        }
        return accessibleAgentIdsForUser(user.get()).contains(normalizedAgentId);
    }

    /**
     * Lists the accessible agent ids for a non-admin user.
     *
     * @param userId the user id value
     * @return the accessible agent ids
     */
    @Transactional(readOnly = true)
    public Set<String> accessibleAgentIds(String userId) {
        return resolveUser(userId)
            .map(user -> hasAllAgentAccess(user) ? Set.<String>of() : accessibleAgentIdsForUser(user))
            .orElse(Set.of());
    }

    private Optional<SysUser> resolveUser(String userIdOrUsername) {
        if (userIdOrUsername == null || userIdOrUsername.isBlank()) {
            return Optional.empty();
        }
        String value = userIdOrUsername.trim();
        return userRepository.findById(value)
            .or(() -> userRepository.findByUsername(value));
    }

    private SysUser requireAdminOperator(String username) {
        SysUser user = userRepository.findByUsername(requireText(username, "username"))
            .orElseThrow(() -> new IllegalArgumentException("admin user not found"));
        if (!isAdminUser(user) || !"enabled".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalArgumentException("only enabled admin user can perform this operation");
        }
        return user;
    }

    private void invalidateUserPasswordSessions(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        List<String> tokens = activeTokens.entrySet().stream()
            .filter(entry -> userId.equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .toList();
        tokens.forEach(token -> {
            activeTokens.remove(token);
            activeTokenUsers.remove(token);
        });
    }

    private Optional<EmbedLoginToken> resolveUsableEmbedToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return embedLoginTokenRepository.findByToken(token.trim())
            .filter(record -> "active".equalsIgnoreCase(record.getStatus()))
            .filter(record -> record.getExpiresAt() == null || record.getExpiresAt().isAfter(now));
    }

    private EmbedLoginTokenView toEmbedLoginTokenView(EmbedLoginToken record) {
        boolean expired = record.getExpiresAt() != null && !record.getExpiresAt().isAfter(Instant.now());
        String effectiveStatus = expired && "active".equalsIgnoreCase(record.getStatus())
            ? "expired"
            : record.getStatus();
        return new EmbedLoginTokenView(
            record.getId(),
            record.getToken(),
            record.getTokenPreview(),
            record.getUserId(),
            record.getUsername(),
            record.getDisplayName(),
            effectiveStatus,
            record.getExpiresAt(),
            record.getLastUsedAt(),
            record.getUsedCount(),
            record.getCreatedAt(),
            record.getUpdatedAt()
        );
    }

    private boolean isAdminUser(SysUser user) {
        return user != null && "admin".equalsIgnoreCase(user.getUsername());
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String tokenPreview(String token) {
        if (token == null || token.length() <= 12) {
            return token;
        }
        return token.substring(0, 6) + "..." + token.substring(token.length() - 6);
    }

    private boolean hasAllAgentAccess(SysUser user) {
        if (user == null) {
            return false;
        }
        return "admin".equalsIgnoreCase(user.getUsername());
    }

    private Set<String> accessibleAgentIdsForUser(SysUser user) {
        if (user == null || user.getId() == null || user.getTenantId() == null) {
            return Set.of();
        }
        String tenantId = user.getTenantId().trim();
        List<String> roleIds = userRoleRepository.findByUserId(user.getId()).stream()
            .filter(userRole -> tenantId.equals(userRole.getTenantId()))
            .map(SysUserRole::getRoleId)
            .toList();
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        Set<String> activeRoleIds = roleRepository.findAllById(roleIds).stream()
            .filter(role -> tenantId.equals(role.getTenantId()))
            .filter(role -> "enabled".equalsIgnoreCase(role.getStatus()))
            .map(SysRole::getId)
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (activeRoleIds.isEmpty()) {
            return Set.of();
        }
        Instant now = Instant.now();
        return roleAgentBindingRepository.findByRoleIdIn(List.copyOf(activeRoleIds)).stream()
            .filter(binding -> tenantId.equals(binding.getTenantId()))
            .filter(binding -> isActiveAgentBinding(binding, now))
            .map(RoleAgentBinding::getAgentId)
            .map(this::normalizeAgentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private boolean isActiveAgentBinding(RoleAgentBinding binding, Instant now) {
        if (binding == null || !binding.isEnabled()) {
            return false;
        }
        Instant effectiveTime = binding.getEffectiveTime();
        Instant expireTime = binding.getExpireTime();
        return (effectiveTime == null || !effectiveTime.isAfter(now))
            && (expireTime == null || expireTime.isAfter(now));
    }

    private String normalizeAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        return agentId.trim().toLowerCase();
    }

    /**
     * Performs the initialize demo data operation.
     */
    private void initializeDemoData() {
        SysTenant tenant = resolveDefaultTenant();

        SysOrg root = ensureNeutralOrg(tenant.getId(), null, "guodu-root", "default-root", "默认组织", 0);
        ensureNeutralOrg(tenant.getId(), root.getId(), "wealth", "operations", "运营部", 10);
        ensureNeutralOrg(tenant.getId(), root.getId(), "fixed-income", "product", "产品部", 20);
        ensureNeutralOrg(tenant.getId(), root.getId(), "asset-management", "knowledge", "知识管理部", 30);
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
            seed.setPasswordHash(DEFAULT_ADMIN_PASSWORD);
            seed.setEmail("admin@example.com");
            return userRepository.save(seed);
        });
        admin.setTenantId(tenant.getId());
        admin.setOrgId(it.getId());
        admin.setDisplayName("系统管理员");
        if (admin.getPasswordHash() == null || admin.getPasswordHash().isBlank() || "admin".equals(admin.getPasswordHash())) {
            admin.setPasswordHash(DEFAULT_ADMIN_PASSWORD);
        }
        admin.setEmail(defaultText(admin.getEmail(), "admin@example.com"));
        admin.setStatus(defaultText(admin.getStatus(), "enabled"));
        userRepository.save(admin);
        if (userRoleRepository.findByUserId(admin.getId()).isEmpty()) {
            SysUserRole userRole = new SysUserRole();
            userRole.setTenantId(tenant.getId());
            userRole.setUserId(admin.getId());
            userRole.setRoleId(superAdmin.getId());
            userRoleRepository.save(userRole);
        }
        ensureInternalServiceAccount(tenant, it, superAdmin);

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
    }

    private void ensureInternalServiceAccount(SysTenant tenant, SysOrg org, SysRole superAdmin) {
        if (internalCredentialProperties == null || !internalCredentialProperties.isEnabled()) {
            return;
        }
        String secret = internalCredentialProperties.resolvedSecret();
        if (secret.isBlank()) {
            return;
        }
        String username = internalCredentialProperties.resolvedUsername();
        SysUser internal = userRepository.findByUsername(username).orElseGet(SysUser::new);
        internal.setTenantId(tenant.getId());
        internal.setOrgId(org.getId());
        internal.setUsername(username);
        internal.setDisplayName("ChatChat Internal Service Account");
        internal.setPasswordHash(secret);
        internal.setEmail(defaultText(internal.getEmail(), username + "@internal.chatchat"));
        internal.setStatus("enabled");
        SysUser saved = userRepository.save(internal);
        boolean bound = userRoleRepository.findByUserId(saved.getId()).stream()
            .anyMatch(userRole -> superAdmin.getId().equals(userRole.getRoleId()));
        if (!bound) {
            SysUserRole userRole = new SysUserRole();
            userRole.setTenantId(tenant.getId());
            userRole.setUserId(saved.getId());
            userRole.setRoleId(superAdmin.getId());
            userRoleRepository.save(userRole);
        }
    }

    /**
     * Resolves the default tenant.
     *
     * @return the resolved default tenant
     */
    private SysTenant resolveDefaultTenant() {
        return tenantRepository.findByTenantCode(DEFAULT_TENANT_CODE)
            .map(this::neutralizeDefaultTenant)
            .orElseGet(() -> tenantRepository.findByTenantCode(LEGACY_TENANT_CODE)
                .map(this::neutralizeDefaultTenant)
                .orElseGet(() -> {
                    SysTenant seed = new SysTenant();
                    seed.setTenantCode(DEFAULT_TENANT_CODE);
                    seed.setTenantName("默认租户");
                    seed.setContactName("平台管理员");
                    seed.setDescription("企业级智能问答助手平台默认租户");
                    return tenantRepository.save(seed);
                }));
    }

    /**
     * Performs the neutralize default tenant operation.
     *
     * @param tenant the tenant value
     * @return the operation result
     */
    private SysTenant neutralizeDefaultTenant(SysTenant tenant) {
        tenant.setTenantCode(DEFAULT_TENANT_CODE);
        tenant.setTenantName("默认租户");
        tenant.setContactName(defaultText(tenant.getContactName(), "平台管理员"));
        tenant.setDescription("企业级智能问答助手平台默认租户");
        tenant.setStatus(defaultText(tenant.getStatus(), "enabled"));
        return tenantRepository.save(tenant);
    }

    /**
     * Ensures the neutral org.
     *
     * @param tenantId the tenant id value
     * @param parentId the parent id value
     * @param legacyCode the legacy code value
     * @param code the code value
     * @param name the name value
     * @param sortOrder the sort order value
     * @return the operation result
     */
    private SysOrg ensureNeutralOrg(String tenantId, String parentId, String legacyCode, String code, String name, int sortOrder) {
        SysOrg current = orgRepository.findByTenantIdAndOrgCode(tenantId, code).orElse(null);
        SysOrg legacy = orgRepository.findByTenantIdAndOrgCode(tenantId, legacyCode).orElse(null);
        if (current != null) {
            current.setParentId(parentId);
            current.setOrgName(name);
            current.setSortOrder(sortOrder);
            current.setStatus(defaultText(current.getStatus(), "enabled"));
            SysOrg saved = orgRepository.save(current);
            if (legacy != null && !legacy.getId().equals(saved.getId())) {
                migrateOrgReferences(tenantId, legacy.getId(), saved.getId());
                orgRepository.deleteById(legacy.getId());
            }
            return saved;
        }
        if (legacy != null) {
            legacy.setParentId(parentId);
            legacy.setOrgCode(code);
            legacy.setOrgName(name);
            legacy.setSortOrder(sortOrder);
            legacy.setStatus(defaultText(legacy.getStatus(), "enabled"));
            return orgRepository.save(legacy);
        }
        return ensureOrg(tenantId, parentId, code, name, sortOrder);
    }

    /**
     * Performs the migrate org references operation.
     *
     * @param tenantId the tenant id value
     * @param oldOrgId the old org id value
     * @param newOrgId the new org id value
     */
    private void migrateOrgReferences(String tenantId, String oldOrgId, String newOrgId) {
        orgRepository.findByTenantIdOrderBySortOrderAscOrgNameAsc(tenantId).stream()
            .filter(org -> oldOrgId.equals(org.getParentId()))
            .forEach(org -> {
                org.setParentId(newOrgId);
                orgRepository.save(org);
            });
        userRepository.findByTenantIdOrderByUsernameAsc(tenantId).stream()
            .filter(user -> oldOrgId.equals(user.getOrgId()))
            .forEach(user -> {
                user.setOrgId(newOrgId);
                userRepository.save(user);
            });
    }

    /**
     * Ensures the org.
     *
     * @param tenantId the tenant id value
     * @param parentId the parent id value
     * @param code the code value
     * @param name the name value
     * @param sortOrder the sort order value
     * @return the operation result
     */
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

    /**
     * Ensures the role.
     *
     * @param tenantId the tenant id value
     * @param code the code value
     * @param name the name value
     * @param type the type value
     * @return the operation result
     */
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

    /**
     * Ensures the default permissions.
     *
     * @return the operation result
     */
    private List<SysPermission> ensureDefaultPermissions() {
        List<PermissionSeed> seeds = List.of(
            new PermissionSeed(null, "workspace", "工作台", "menu", "/index.html", null, "layout-dashboard", 10),
            new PermissionSeed("workspace", "workspace:chat", "智能对话", "menu", "/index.html#chat", null, "message-circle", 11),
            new PermissionSeed("workspace", "workspace:search", "文档检索", "menu", "/index.html#search", null, "search", 12),
            new PermissionSeed(null, "capability", "能力管理", "menu", "/index.html#market", null, "grid-3x3", 20),
            new PermissionSeed("capability", "capability:market", "能力市场", "menu", "/index.html#market", null, "store", 21),
            new PermissionSeed("capability", "capability:library", "文档库", "menu", "/index.html#library", null, "book-open", 22),
            new PermissionSeed(null, "platform", "平台管理", "menu", "/index.html#tasks", null, "boxes", 30),
            new PermissionSeed("platform", "mcp", "MCP服务", "menu", "/index.html#mcp", null, "plug", 31),
            new PermissionSeed("mcp", "mcp:service:manage", "服务管理", "button", "/api/v1/mcp/**", "*", "server", 32),
            new PermissionSeed("mcp", "mcp:tool:authorize", "工具授权", "button", "/api/v1/enterprise/tool-permissions", "*", "key-round", 33),
            new PermissionSeed("platform", "platform:agents", "Agent管理", "menu", "/index.html#agents", null, "bot", 34),
            new PermissionSeed("platform", "platform:tasks", "运行监控", "menu", "/index.html#tasks", null, "clipboard-list", 35),
            new PermissionSeed("platform", "system", "系统管理", "menu", "/index.html#system", null, "settings", 40),
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

    /**
     * Ensures the role permissions.
     *
     * @param tenantId the tenant id value
     * @param roleId the role id value
     * @param permissions the permissions value
     */
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

    /**
     * Ensures the all org scope.
     *
     * @param tenantId the tenant id value
     * @param roleId the role id value
     */
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

    /**
     * Performs the audit operation.
     *
     * @param tenantId the tenant id value
     * @param actorId the actor id value
     * @param actorName the actor name value
     * @param module the module value
     * @param action the action value
     * @param resourceType the resource type value
     * @param resourceId the resource id value
     * @param detail the detail value
     */
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

    /**
     * Performs the require text operation.
     *
     * @param value the value value
     * @param field the field value
     * @return the operation result
     */
    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    /**
     * Performs the default text operation.
     *
     * @param value the value value
     * @param fallback the fallback value
     * @return the operation result
     */
    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * Performs the trim to null operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Performs the first text operation.
     *
     * @param values the values value
     * @return the operation result
     */
    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Performs the active status operation.
     *
     * @param status the status value
     * @return the operation result
     */
    private String activeStatus(Long status) {
        return status == null || status.longValue() == 1L ? "enabled" : "disabled";
    }

    /**
     * Converts the value to int.
     *
     * @param value the value value
     * @return the converted int
     */
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

    /**
     * Resolves the tenant id.
     *
     * @param tenantId the tenant id value
     * @return the resolved tenant id
     */
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

    public record AuthResult(String token, UserView user, Instant expiresAt, boolean embedded) {
        public AuthResult(String token, UserView user) {
            this(token, user, null, false);
        }
    }

    public record EmbedLoginTokenRequest(Long expiresInSeconds) {
    }

    public record EmbedLoginTokenView(
        String id,
        String token,
        String tokenPreview,
        String userId,
        String username,
        String displayName,
        String status,
        Instant expiresAt,
        Instant lastUsedAt,
        long usedCount,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record RoleAuthorizationRequest(
        List<String> permissionIds,
        List<RoleOrgScopeInput> orgScopes,
        List<String> userIds,
        List<String> agentIds
    ) {
    }

    public record RoleOrgScopeInput(String scopeType, String orgId) {
    }

    public record RoleAuthorizationView(
        SysRole role,
        List<String> permissionIds,
        List<SysRoleOrgScope> orgScopes,
        List<String> userIds,
        List<String> agentIds
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
