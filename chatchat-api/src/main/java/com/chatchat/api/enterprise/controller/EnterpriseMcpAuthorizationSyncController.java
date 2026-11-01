package com.chatchat.api.enterprise.controller;

import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.enterprise.entity.mcp.McpToolAsset;
import com.chatchat.enterprise.entity.mcp.McpToolPermission;
import com.chatchat.enterprise.entity.identity.SysRole;
import com.chatchat.enterprise.repository.mcp.McpToolAssetRepository;
import com.chatchat.enterprise.repository.mcp.McpToolPermissionRepository;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysTenantRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/enterprise/mcp-auth")
@Tag(name = "Enterprise MCP Authorization Sync", description = "Authorization snapshots for MCP execution nodes")
public class EnterpriseMcpAuthorizationSyncController {

    private final EnterpriseAdminService adminService;
    private final SysRoleRepository roleRepository;
    private final SysTenantRepository tenantRepository;
    private final McpToolAssetRepository toolAssetRepository;
    private final McpToolPermissionRepository toolPermissionRepository;
    private final InternalCredentialProperties internalCredentialProperties;
    private final AtomicReference<CachedSnapshot> cachedSnapshot = new AtomicReference<>();
    private final Object snapshotMonitor = new Object();

    @Value("${chatchat.mcp.authorization.snapshot-cache-ttl-ms:300000}")
    private long snapshotCacheTtlMs;

    @org.springframework.beans.factory.annotation.Autowired
    private com.chatchat.chat.skills.catalog.SkillCatalogService agents;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chatchat.api.service.AgentTemplateToolPolicy agentTools;

    /** Fresh configuration projection through the same principal policy used for execution. */
    @GetMapping("/template-bindings")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ApiResponse<List<java.util.Map<String,Object>>> templateBindings(
        @RequestParam("tenantId") String tenant, @RequestParam("userId") String user,
        @RequestParam("toolName") String toolName) {
        if (com.chatchat.common.tool.TemplateToolNames.remote(toolName) == null)
            throw new IllegalArgumentException("Published template tool name required");
        var principal=adminService.getUserView(user);
        if (!tenant.equals(principal.tenantId()) || !"enabled".equalsIgnoreCase(principal.status()))
            throw new SecurityException("Publication principal is unavailable in this tenant");
        return ApiResponse.success(agents.list().stream()
            .filter(agent -> !agentTools.allowed(tenant,user,agent.id(),java.util.Set.of(toolName)).isEmpty())
            .map(agent -> {
                var value=new java.util.LinkedHashMap<String,Object>();
                value.put("agentId",agent.id());value.put("name",agent.label());value.put("marketStatus",agent.marketStatus());
                value.put("tenantId",tenant);value.put("evaluatedUserId",user);value.put("toolName",toolName);
                return (java.util.Map<String,Object>)value;
            }).toList());
    }

    @GetMapping("/snapshot")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @Operation(summary = "Pull the current MCP authorization snapshot")
    public ApiResponse<McpAuthorizationSnapshot> snapshot(@RequestParam(name = "fresh", defaultValue = "false") boolean fresh) {
        // Execution decisions must observe revocations; discovery may use the cache.
        if (fresh) return ApiResponse.success(buildSnapshot());
        CachedSnapshot cached = cachedSnapshot.get();
        long now = System.currentTimeMillis();
        if (cached != null && cached.validAt(now, snapshotCacheTtlMs)) {
            return ApiResponse.success(cached.snapshot());
        }
        synchronized (snapshotMonitor) {
            cached = cachedSnapshot.get();
            now = System.currentTimeMillis();
            if (cached != null && cached.validAt(now, snapshotCacheTtlMs)) {
                return ApiResponse.success(cached.snapshot());
            }
            McpAuthorizationSnapshot rebuilt = buildSnapshot();
            cachedSnapshot.set(new CachedSnapshot(rebuilt, now));
            return ApiResponse.success(rebuilt);
        }
    }

    private McpAuthorizationSnapshot buildSnapshot() {
        return new McpAuthorizationSnapshot(
            Instant.now(),
            adminService.listUserViews(null).stream()
                .filter(user -> user.username() == null
                    || !internalCredentialProperties.resolvedUsername().equalsIgnoreCase(user.username()))
                .toList(),
            roleRepository.findAll().stream()
                .map(this::toRoleView)
                .toList(),
            tenantRepository.findAllByOrderByTenantNameAsc().stream()
                .map(tenant -> new TenantView(tenant.getId(), tenant.getTenantName()))
                .toList(),
            toolAssetRepository.findAllByOrderByLocalToolNameAsc(),
            toolPermissionRepository.findAll()
        );
    }

    private RoleView toRoleView(SysRole role) {
        return new RoleView(
            role.getId(),
            role.getTenantId(),
            role.getRoleCode(),
            role.getRoleName(),
            role.getRoleType(),
            role.getStatus()
        );
    }

    public record McpAuthorizationSnapshot(
        Instant syncedAt,
        List<EnterpriseAdminService.UserView> users,
        List<RoleView> roles,
        List<TenantView> tenants,
        List<McpToolAsset> tools,
        List<McpToolPermission> permissions
    ) {
    }

    public record TenantView(String id, String tenantName) {
    }

    public record RoleView(
        String id,
        String tenantId,
        String roleCode,
        String roleName,
        String roleType,
        String status
    ) {
    }

    private record CachedSnapshot(McpAuthorizationSnapshot snapshot, long createdAtMs) {
        private boolean validAt(long nowMs, long ttlMs) {
            return ttlMs > 0L && nowMs - createdAtMs < ttlMs;
        }
    }
}
