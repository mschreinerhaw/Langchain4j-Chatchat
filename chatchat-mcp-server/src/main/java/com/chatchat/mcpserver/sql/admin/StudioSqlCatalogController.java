package com.chatchat.mcpserver.sql.admin;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.admin.AdminMenuAccessService;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.sql.template.*;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigRepository;
import com.chatchat.mcpserver.templatepublication.admin.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Read projection of existing Studio definitions/releases, never a second writable template store. */
@RestController @RequiredArgsConstructor @Transactional(readOnly=true)
@RequestMapping("/api/v1/database-query/studio-publications")
public class StudioSqlCatalogController {
    private final SqlTemplateConfigRepository templates;
    private final SqlDatasourceConfigRepository sources;
    private final ToolReleaseRepository releases;
    private final AdminMenuAccessService access;
    private final McpAuthorizationService authorization;
    private final ObjectMapper json;

    @GetMapping
    public ApiResponse<List<Map<String,Object>>> list(HttpServletRequest request) {
        access.require(request,"databaseMcp");
        var groups=new LinkedHashMap<String,List<SqlTemplateConfig>>();
        for(var row:templates.findByPublicationGroupIdIsNotNullOrderByCreatedAtDesc())
            if(row.getReleasedCapabilityId()!=null) groups.computeIfAbsent(row.getPublicationGroupId(),k->new ArrayList<>()).add(row);
        return ApiResponse.success(groups.values().stream().map(rows->rows.stream().max(Comparator
            .comparing(SqlTemplateConfig::isEnabled).thenComparingInt(r->releases.findById(r.getReleasedCapabilityId()).map(ToolRelease::getReleaseNumber).orElse(0))).orElseThrow())
            .map(row->summary(row)).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String,Object>> detail(@PathVariable("id")String id,HttpServletRequest request) {
        access.require(request,"databaseMcp");
        var row=templates.findById(id).filter(r->r.getPublicationGroupId()!=null && r.getReleasedCapabilityId()!=null)
            .orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"SQL Studio publication not found"));
        var view=new LinkedHashMap<>(summary(row));
        boolean sqlVisible=access.superAdmin(request);
        view.put("sqlVisible",sqlVisible);
        view.put("sqlTemplate",sqlVisible?row.getSqlTemplate():"SQL 正文仅限 MCP 管理员查看；请使用 Runtime OS 对应租户账号访问源工程。");
        view.put("parameterSchema",tree(row.getParameterSchemaJson()));
        view.put("source",tree(row.getAuthoringSourceJson()));
        view.put("testEvidence",sqlVisible?tree(row.getSqlTestEvidenceJson()):Map.of("success",tree(row.getSqlTestEvidenceJson()).path("success").asBoolean()));
        var history=templates.findAllByPublicationGroupIdOrderByPublicationVersionAsc(row.getPublicationGroupId());
        var ids=history.stream().map(SqlTemplateConfig::getId).collect(java.util.stream.Collectors.toSet());
        view.put("history",releases.findByTenantIdOrderByCreatedAtDesc(row.getAuthorTenantId()).stream()
            .filter(r->ids.contains(tree(r.getDefinitionJson()).path("binding").path("sqlRevisionId").asText())).map(r->{
            var m=new LinkedHashMap<String,Object>();m.put("id",r.getId());m.put("version",r.getReleaseNumber());m.put("state",r.getState());m.put("publishedAt",r.getPublishedAt());
            if(sqlVisible){var d=tree(r.getDefinitionJson());m.put("sqlSnapshot",d.path("sqlSnapshot"));m.put("sqlTest",d.path("sqlTest"));}return m;
        }).toList());
        if(sqlVisible) {
          view.put("authorizationRules",authorization.currentView().permissions().stream()
            .filter(p->Objects.equals(p.tenantId(),row.getAuthorTenantId()) && (Objects.equals(p.localToolName(),view.get("toolName"))
                || Objects.equals(p.localToolName(),"mcp_chatchat_mcp_server_"+view.get("toolName"))) ).toList());
          try {
            view.put("accessibleAgents",authorization.templateAgentBindings(row.getAuthorTenantId(),row.getAuthorId(),String.valueOf(view.get("toolName"))));
            view.put("agentScopeStatus","CURRENT_PUBLICATION_AUTHOR_POLICY");
          } catch(Exception unavailable) {
            view.put("agentScopeStatus","UNAVAILABLE");
          }
          view.put("agentScopeNotice","按发布者当前租户、用户权限、已发布 Agent 的实际工具绑定实时核验；其他用户须另行满足角色及资源授权。范围读取失败时不代表没有绑定。");
        }
        view.put("accessNotice","此处为 SQL Studio 原始发布记录的只读视图。修改、试执行和版本恢复请在源工程操作；调用仍须同时满足租户、数据源、模板、用户/角色和 Agent 工具授权。发布状态不等于当前账号具备执行权限。");
        return ApiResponse.success(view);
    }
    private Map<String,Object> summary(SqlTemplateConfig row) {
        var m=new LinkedHashMap<String,Object>();var release=releases.findById(row.getReleasedCapabilityId()).orElseThrow();
        m.put("id","studio:"+row.getPublicationGroupId());m.put("templateId",row.getId());m.put("sourceKind","SQL_STUDIO");m.put("sourceLabel","SQL Studio");m.put("readOnly",true);
        m.put("title",row.getTitle());m.put("description",row.getDescription());m.put("code",row.getCode());m.put("toolName",TemplateCapabilityService.toolName(release));
        m.put("version",release.getReleaseNumber());m.put("publicationId",release.getId());m.put("publicationState",release.getState());m.put("publishedAt",release.getPublishedAt());
        m.put("tenantId",row.getAuthorTenantId());m.put("createdBy",row.getAuthorId());m.put("datasourceId",row.getDatasourceId());
        m.put("datasourceName",sources.findById(row.getDatasourceId()).map(s->s.getName()).orElse("数据源不可用"));
        m.put("databaseType",row.getDatabaseType());m.put("capabilityCategory",row.getCategory());m.put("enabled",row.isEnabled()&&"RUNNING".equals(release.getState()));
        m.put("parameterSummary",parameterSummary(tree(row.getParameterSchemaJson())));m.put("dataAvailabilityStatus","NOT_APPLICABLE");return m;
    }
    private String parameterSummary(JsonNode schema) {
        var required=new HashSet<String>();schema.path("required").forEach(n->required.add(n.asText()));var fields=new ArrayList<String>();
        schema.path("properties").fields().forEachRemaining(e->fields.add(e.getKey()+(required.contains(e.getKey())?"（必填）":e.getValue().has("default")?"（默认 "+e.getValue().get("default")+"）":"（可选）")));
        return fields.isEmpty()?"无参数":String.join("、",fields);
    }
    private JsonNode tree(String value){try{return value==null?json.createObjectNode():json.readTree(value);}catch(Exception e){throw new IllegalStateException("Invalid stored SQL publication",e);}}
}
