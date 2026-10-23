package com.chatchat.api.controller.mcp;

import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.entity.audit.SysAuditLog;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** The only customer tool-publishing surface. Tenant and actor are bound to the login. */
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/tool-publications")
public class ToolPublicationController {
    private final McpControlPlaneClient client;
    private final ObjectAccessService access;
    private final EnterpriseAdminService identities;
    private final WorkspaceService workspaces;
    private final SysAuditLogRepository audits;
    private final com.chatchat.enterprise.repository.identity.SysRoleRepository roles;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.chatchat.api.service.McpRuntimeAccessService runtime;
    @GetMapping public ApiResponse<JsonNode> list(HttpServletRequest request) {
        authorize(request,"mcp:publication:read");
        return ApiResponse.success(client.request("GET","/api/v1/tool-publications"+scope(request),null));
    }
    @PostMapping public ApiResponse<JsonNode> draft(HttpServletRequest request,@RequestBody JsonNode body) {
        authorize(request,"mcp:publication:edit");
        JsonNode saved=client.request("POST","/api/v1/tool-publications"+scope(request),body);
        audit(request,"draft",saved.path("id").asText()); return ApiResponse.success(saved);
    }
    @PostMapping("/{id}/{action}") public ApiResponse<JsonNode> action(HttpServletRequest request,
        @PathVariable("id") String id,@PathVariable("action") String action,@RequestBody(required=false) JsonNode body) {
        if (!Set.of("validate","test","publish","offline","rollback").contains(action)) throw new IllegalArgumentException("Unknown publication action");
        authorize(request,Set.of("publish","offline","rollback").contains(action)?"mcp:publication:publish":"mcp:publication:edit");
        if (!id.matches("[a-zA-Z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid release ID");
        JsonNode saved=client.request("POST","/api/v1/tool-publications/"+id+"/"+action+scope(request),body);
        audit(request,action,id);
        if(runtime!=null && Set.of("publish","offline","rollback").contains(action)) {
            runtime.refresh();identities.syncRegisteredMcpTools();
        }
        return ApiResponse.success(saved);
    }
    @GetMapping("/catalog/{type}") public ApiResponse<JsonNode> catalog(HttpServletRequest request,@PathVariable("type") String type,
        @RequestParam(name="roleId",required=false) String roleId,@RequestParam(name="parentToolName",required=false) String parentToolName) {
        authorize(request,"mcp:publication:read");
        String path="/api/v1/template-query-publications/";
        if ("parents".equals(type)) path+="parents";
        else if ("roles".equals(type)) path+="roles?tenantId="+encode(access.tenant(request));
        else if ("templates".equals(type) || "members".equals(type)) {
            if (roles.findByTenantIdOrderByRoleNameAsc(access.tenant(request)).stream().noneMatch(role -> role.getId().equals(roleId))) throw new SecurityException("Role outside current tenant");
            path+=type+"?roleId="+encode(roleId);
            if ("templates".equals(type)) path+="&parentToolName="+encode(parentToolName);
        } else throw new IllegalArgumentException("Unknown catalog");
        return ApiResponse.success(client.request("GET",path,null));
    }
    private void authorize(HttpServletRequest request,String permission) {
        workspaces.requireAvailable(request,"mcp-publication");
        if (access.user(request)==null || !identities.hasPermission(access.user(request),permission)) throw new SecurityException("Tool publication denied");
        if (!identities.hasPermission(access.user(request),"system:permission-center:manage")) throw new SecurityException("Legacy publication management requires administrator permission");
    }
    private String scope(HttpServletRequest request) { return "?tenantId="+encode(access.tenant(request))+"&actorId="+encode(access.user(request)); }
    private String encode(String value) { if(value==null)throw new IllegalArgumentException("Missing parameter"); return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    private void audit(HttpServletRequest request,String action,String id) {
        var log=new SysAuditLog();log.setTenantId(access.tenant(request));log.setActorId(access.user(request));
        log.setModuleName("tool-publication");log.setActionName(action);log.setResourceType("mcp-tool-release");log.setResourceId(id);audits.saveAndFlush(log);
    }
}
