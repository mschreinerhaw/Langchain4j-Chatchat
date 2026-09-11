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

    @GetMapping("/snapshot")
    @Operation(summary = "Pull the current MCP authorization snapshot")
    public ApiResponse<McpAuthorizationSnapshot> snapshot() {
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
                .filter(role -> !isAdminRole(role))
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

    private boolean isAdminRole(SysRole role) {
        return role != null
            && ("admin".equalsIgnoreCase(role.getRoleCode())
            || "admin".equalsIgnoreCase(role.getRoleName()));
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
