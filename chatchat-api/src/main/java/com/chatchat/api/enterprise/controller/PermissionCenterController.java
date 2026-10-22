package com.chatchat.api.enterprise.controller;

import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.enterprise.entity.mcp.McpToolPermission;
import com.chatchat.enterprise.entity.audit.SysAuditLog;
import com.chatchat.enterprise.repository.mcp.*;
import com.chatchat.enterprise.repository.identity.*;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Tenant-scoped management of the existing central policy tables. */
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/permission-center")
public class PermissionCenterController {
    private final ObjectAccessService access;
    private final WorkspaceService workspaces;
    private final EnterpriseAdminService identities;
    private final McpToolPermissionRepository grants;
    private final McpToolAssetRepository tools;
    private final SysRoleRepository roles;
    private final SysUserRepository users;
    private final SysAuditLogRepository audits;
    @GetMapping public ApiResponse<Map<String,Object>> catalog(HttpServletRequest request) {
        authorize(request); String tenant=access.tenant(request);
        return ApiResponse.success(Map.of("roles",roles.findByTenantIdOrderByRoleNameAsc(tenant),
            "users",identities.listUserViews(tenant),"tools",tools.findAllByOrderByLocalToolNameAsc(),
            "grants",grants.findByTenantIdOrderByUpdatedAtDesc(tenant),"audits",audits.findTop100ByTenantIdOrderByCreatedAtDesc(tenant)));
    }
    @PostMapping("/tool-grants") @Transactional
    public ApiResponse<McpToolPermission> save(HttpServletRequest request,@RequestBody McpToolPermission input) {
        authorize(request); String tenant=access.tenant(request);
        if (input.getId()!=null) requireGrant(tenant,input.getId());
        if (input.getTenantId()!=null && !tenant.equals(input.getTenantId())) throw new SecurityException("Cross-tenant grant denied");
        String type=Objects.toString(input.getTargetType(),"").toUpperCase(Locale.ROOT);
        boolean valid=switch(type) {
            case "ROLE" -> roles.findById(Objects.toString(input.getTargetId(),"")).filter(row->tenant.equals(row.getTenantId())).isPresent();
            case "USER" -> users.findById(Objects.toString(input.getTargetId(),"")).filter(row->tenant.equals(row.getTenantId())).isPresent();
            case "TENANT" -> tenant.equals(input.getTargetId());
            default -> false;
        };
        if (!valid || !Set.of("allow","deny").contains(input.getEffect())) throw new IllegalArgumentException("Invalid grant subject or effect");
        if (tools.findAllByOrderByLocalToolNameAsc().stream().noneMatch(tool->tool.getLocalToolName().equals(input.getLocalToolName())))
            throw new IllegalArgumentException("Select a registered tool");
        input.setTenantId(tenant); input.setToolId(null);
        var saved=identities.saveToolPermission(input);audit(request,"save",saved.getId());return ApiResponse.success(saved);
    }
    @DeleteMapping("/tool-grants/{id}") @Transactional
    public ApiResponse<Void> delete(HttpServletRequest request,@PathVariable("id") String id) {
        authorize(request);requireGrant(access.tenant(request),id);grants.deleteById(id);audit(request,"delete",id);return ApiResponse.success(null);
    }
    private void requireGrant(String tenant,String id) {
        if(grants.findById(id).filter(row->tenant.equals(row.getTenantId())).isEmpty())throw new SecurityException("Grant outside current tenant");
    }
    private void authorize(HttpServletRequest request) {
        workspaces.requireAvailable(request,"permissions");
        if(access.user(request)==null || !identities.hasPermission(access.user(request),"system:permission-center:manage"))throw new SecurityException("Permission management denied");
    }
    private void audit(HttpServletRequest request,String action,String id) {
        var log=new SysAuditLog();log.setTenantId(access.tenant(request));log.setActorId(access.user(request));log.setModuleName("permission-center");
        log.setActionName(action);log.setResourceType("mcp-tool-permission");log.setResourceId(id);audits.saveAndFlush(log);
    }
}
