package com.chatchat.api.controller.datascience;

import com.chatchat.api.datascience.sql.SqlStudioService;
import com.chatchat.api.controller.mcp.TemplateCapabilityController;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Identity and saved source checks only. SQL lifecycle and publication remain in the existing MCP services. */
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/data-science/sql")
public class SqlStudioPublicationController {
    private final SqlStudioService studio;
    private final TemplateCapabilityController capabilities;
    private final WorkspaceService workspaces;
    private final ObjectMapper json;
    @GetMapping("/template-publications/datasources") public ApiResponse<?> sources(HttpServletRequest request) {
        workspaces.requireAvailable(request,"data-science");return ApiResponse.success(capabilities.sqlAuthoring(request,"GET","/datasources",null,"mcp:publication:read"));
    }
    @GetMapping("/projects/{projectId}/files/{fileId}/template-publications") public ApiResponse<?> list(@PathVariable("projectId")String project,@PathVariable("fileId")String file,HttpServletRequest request) {
        gate(request,project,file);return ApiResponse.success(capabilities.sqlAuthoring(request,"GET","?projectId="+project+"&fileId="+file,null,"mcp:publication:read"));
    }
    @PostMapping("/projects/{projectId}/files/{fileId}/template-publications") public ApiResponse<?> create(@PathVariable("projectId")String project,@PathVariable("fileId")String file,@RequestBody JsonNode input,HttpServletRequest request) {
        return write(request,project,file,null,"",input);
    }
    @PutMapping("/projects/{projectId}/files/{fileId}/template-publications/{id}") public ApiResponse<?> update(@PathVariable("projectId")String project,@PathVariable("fileId")String file,@PathVariable("id")String id,@RequestBody JsonNode input,HttpServletRequest request) {
        return write(request,project,file,id,"",input);
    }
    @PostMapping("/projects/{projectId}/files/{fileId}/template-publications/{id}/{action}") public ApiResponse<?> action(@PathVariable("projectId")String project,@PathVariable("fileId")String file,@PathVariable("id")String id,@PathVariable("action")String action,@RequestBody JsonNode input,HttpServletRequest request) {
        if(!Set.of("test","publish","rollback").contains(action))throw new IllegalArgumentException("Unknown publication action");
        return write(request,project,file,id,action,input);
    }
    private ApiResponse<?> write(HttpServletRequest request,String project,String file,String id,String action,JsonNode input) {
        var saved=gate(request,project,file);var body=json.createObjectNode();
        for(String key:List.of("operationId","groupId","expectedVersion","expectedActiveReleaseId","title","description","category","datasourceId","databaseType","parameterSchema","parameters","testId"))
            if(input.has(key))body.set(key,input.get(key));
        var origin=json.createObjectNode().put("projectId",project).put("fileId",file);
        if(!"rollback".equals(action)) {
            if(input.path("expectedSourceVersion").asInt(-1)!=saved.version())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"SQL file changed; save, reload and retest");
            origin.put("fileVersion",saved.version()).put("sqlDigest",sha(saved.content()));
        }
        body.set("source",origin);
        if(action.isBlank())body.put("sqlTemplate",saved.content());
        String suffix=id==null?"":"/"+id; if(!action.isBlank())suffix+="/"+action;
        String permission=Set.of("publish","rollback").contains(action)?"mcp:publication:publish":"mcp:publication:edit";
        return ApiResponse.success(capabilities.sqlAuthoring(request,id==null||!action.isBlank()?"POST":"PUT",suffix,body,permission));
    }
    private SqlStudioService.File gate(HttpServletRequest request,String project,String file) {
        if(!project.matches("[a-zA-Z0-9-]{1,64}")||!file.matches("[a-zA-Z0-9-]{1,64}"))throw new IllegalArgumentException("Invalid SQL source identity");
        workspaces.requireAvailable(request,"data-science");var value=studio.file(request,project,file);
        if(!"SQL".equals(value.kind()))throw new IllegalArgumentException("Only SQL files can become templates");return value;
    }
    private String sha(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
