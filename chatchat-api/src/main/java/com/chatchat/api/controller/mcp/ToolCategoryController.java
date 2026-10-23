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

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/tool-categories")
public class ToolCategoryController {
    private final McpControlPlaneClient client;
    private final ObjectAccessService access;
    private final EnterpriseAdminService identities;
    private final WorkspaceService workspaces;
    private final SysAuditLogRepository audits;
    @GetMapping public ApiResponse<JsonNode> list(HttpServletRequest request) {return forward(request,"GET","",null,"read");}
    @PostMapping public ApiResponse<JsonNode> create(HttpServletRequest request,@RequestBody JsonNode body) {return forward(request,"POST","",body,"create");}
    @PutMapping("/{id}") public ApiResponse<JsonNode> rename(HttpServletRequest request,@PathVariable("id") String id,@RequestBody JsonNode body) {return forward(request,"PUT","/"+id(id),body,"rename");}
    @DeleteMapping("/{id}") public ApiResponse<JsonNode> delete(HttpServletRequest request,@PathVariable("id") String id,@RequestBody JsonNode body) {return forward(request,"DELETE","/"+id(id),body,"delete");}
    @PutMapping("/assignments/{logicalId}") public ApiResponse<JsonNode> assign(HttpServletRequest request,@PathVariable("logicalId") String logical,@RequestBody JsonNode body) {return forward(request,"PUT","/assignments/"+id(logical),body,"assign");}
    private ApiResponse<JsonNode> forward(HttpServletRequest request,String method,String suffix,JsonNode body,String action) {
        workspaces.requireAvailable(request,"mcp-publication");
        if(access.user(request)==null || !identities.hasPermission(access.user(request),"mcp:publication:"+("GET".equals(method)?"read":"edit"))
            || !identities.hasPermission(access.user(request),"system:permission-center:manage")) throw new SecurityException("分类管理权限不足");
        String scope="?tenantId="+encode(access.tenant(request))+"&actorId="+encode(access.user(request));
        JsonNode result=client.request(method,"/api/v1/tool-categories"+suffix+scope,body);
        if(!"GET".equals(method)) {
            var log=new SysAuditLog();log.setTenantId(access.tenant(request));log.setActorId(access.user(request));
            log.setModuleName("tool-category");log.setActionName(action);log.setResourceType("mcp-tool-category");
            log.setResourceId(suffix.isEmpty()?result.path("id").asText():suffix.substring(suffix.lastIndexOf('/')+1));audits.saveAndFlush(log);
        }
        return ApiResponse.success(result);
    }
    private String id(String id) {if(id==null||!id.matches("[a-zA-Z0-9-]{1,64}"))throw new IllegalArgumentException("分类或工具不存在");return id;}
    private String encode(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
