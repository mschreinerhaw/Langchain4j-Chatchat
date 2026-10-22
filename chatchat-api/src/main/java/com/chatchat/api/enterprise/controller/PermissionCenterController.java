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
    private final com.chatchat.enterprise.repository.security.ResourceGrantRepository resources;
    @GetMapping public ApiResponse<Map<String,Object>> catalog(HttpServletRequest request) {
        authorizeCatalog(request); String tenant=access.tenant(request);
        var tenantRoles=roles.findByTenantIdOrderByRoleNameAsc(tenant);
        return ApiResponse.success(Map.of("roles",tenantRoles,
            "users",identities.listUserViews(tenant),"tools",tools.findAllByOrderByLocalToolNameAsc(),
            "grants",grants.findByTenantIdOrderByUpdatedAtDesc(tenant),"audits",audits.findTop100ByTenantIdOrderByCreatedAtDesc(tenant),
            "permissions",identities.listPermissions().stream().filter(p->!"internal".equals(p.getPermissionType())).toList(),
            "resourceGrants",resources.findByTenantIdOrderByUpdatedAtDesc(tenant),
            "roleAuthorizations",tenantRoles.stream().map(role->identities.getRoleAuthorization(role.getId())).toList(),
            "tenantId",tenant));
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
    private void authorizeCatalog(HttpServletRequest request) {
        workspaces.requireAvailable(request,"permissions");String user=access.user(request);
        if(user==null || !(identities.hasPermission(user,"system:permission-center:manage")
            || identities.hasPermission(user,"system:role") || identities.hasPermission(user,"system:role:authorize") || identities.hasPermission(user,"system:resource-grants:manage")))
            throw new SecurityException("Permission catalog denied");
    }
    @GetMapping("/audits")
    public ApiResponse<Map<String,Object>> auditPage(HttpServletRequest request,
        @RequestParam(value="query",defaultValue="") String query,
        @RequestParam(value="action",defaultValue="") String action,
        @RequestParam(value="page",defaultValue="1") int page,
        @RequestParam(value="pageSize",defaultValue="20") int size) {
        workspaces.requireAvailable(request,"permissions");String user=access.user(request),tenant=access.tenant(request);
        if(user==null || !(identities.hasPermission(user,"audit") || identities.hasPermission(user,"system:permission-center:manage")
            || identities.hasPermission(user,"system:role:authorize") || identities.hasPermission(user,"system:resource-grants:manage")))throw new SecurityException("Audit access denied");
        org.springframework.data.jpa.domain.Specification<SysAuditLog> spec=(root,q,cb)->{
            var checks=new ArrayList<jakarta.persistence.criteria.Predicate>();
            checks.add(cb.equal(root.get("tenantId"),tenant));checks.add(cb.equal(root.get("moduleName"),"permission-center"));
            if(!action.isBlank())checks.add(cb.equal(root.get("actionName"),action));
            if(!query.isBlank()){
                String term="%"+query.toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
                checks.add(cb.or(cb.like(cb.lower(root.get("actorName")),term,'\\'),cb.like(cb.lower(root.get("detail")),term,'\\'),cb.like(cb.lower(root.get("resourceType")),term,'\\')));
            }
            return cb.and(checks.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result=audits.findAll(spec,org.springframework.data.domain.PageRequest.of(Math.max(0,page-1),Math.max(1,Math.min(100,size)),org.springframework.data.domain.Sort.by("createdAt").descending()));
        var names=identities.listUserViews(tenant).stream().collect(java.util.stream.Collectors.toMap(EnterpriseAdminService.UserView::id,u->Objects.toString(u.displayName(),u.username()),(a,b)->a));
        return ApiResponse.success(Map.of("items",result.getContent().stream().map(log->Map.of("id",log.getId(),"actor",names.getOrDefault(Objects.toString(log.getActorId(),""),Objects.toString(log.getActorName(),"系统")),"action",log.getActionName(),"resourceType",Objects.toString(log.getResourceType(),""),"resourceId",Objects.toString(log.getResourceId(),""),"detail",Objects.toString(log.getDetail(),""),"result",log.getResult(),"createdAt",log.getCreatedAt())).toList(),"total",result.getTotalElements(),"pages",result.getTotalPages(),"page",result.getNumber()+1));
    }
    private void audit(HttpServletRequest request,String action,String id) {
        var log=new SysAuditLog();log.setTenantId(access.tenant(request));log.setActorId(access.user(request));log.setModuleName("permission-center");
        log.setActionName(action);log.setResourceType("mcp-tool-permission");log.setResourceId(id);audits.saveAndFlush(log);
    }
}
