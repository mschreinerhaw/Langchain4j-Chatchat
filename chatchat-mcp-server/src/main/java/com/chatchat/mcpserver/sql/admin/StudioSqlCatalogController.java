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

/** Shared management projection; authoring/revalidation remain in the original Studio lifecycle. */
@RestController @RequiredArgsConstructor @lombok.extern.slf4j.Slf4j
@RequestMapping("/api/v1/database-query/studio-publications")
public class StudioSqlCatalogController {
    private final SqlTemplateConfigRepository templates;
    private final SqlDatasourceConfigRepository sources;
    private final ToolReleaseRepository releases;
    private final AdminMenuAccessService access;
    private final McpAuthorizationService authorization;
    private final ObjectMapper json;
    private final SqlTemplateAuthoringService authoring;

    @GetMapping @Transactional(readOnly=true)
    public ApiResponse<List<Map<String,Object>>> list(HttpServletRequest request) {
        access.require(request,"databaseMcp");
        var groups=new LinkedHashMap<String,List<SqlTemplateConfig>>();
        for(var row:templates.findByPublicationGroupIdIsNotNullOrderByCreatedAtDesc())
            if(row.getReleasedCapabilityId()!=null) groups.computeIfAbsent(row.getPublicationGroupId(),k->new ArrayList<>()).add(row);
        return ApiResponse.success(groups.values().stream().map(rows->rows.stream().max(Comparator
            .comparing(SqlTemplateConfig::isEnabled).thenComparingInt(r->releases.findById(r.getReleasedCapabilityId()).map(ToolRelease::getReleaseNumber).orElse(0))).orElseThrow())
            .map(row->{var item=summary(row);item.put("editable",access.superAdmin(request));item.put("testable",access.superAdmin(request));return item;}).toList());
    }

    @GetMapping("/{id}") @Transactional(readOnly=true)
    public ApiResponse<Map<String,Object>> detail(@PathVariable("id")String id,HttpServletRequest request) {
        access.require(request,"databaseMcp");
        var row=templates.findById(id).filter(r->r.getPublicationGroupId()!=null && r.getReleasedCapabilityId()!=null)
            .orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"SQL Studio publication not found"));
        var view=new LinkedHashMap<>(summary(row));
        boolean sqlVisible=access.superAdmin(request);
        view.put("sqlVisible",sqlVisible);
        view.put("sqlTemplate",sqlVisible?row.getSqlTemplate():"SQL 正文仅限 MCP 管理员查看；请使用 Runtime OS 对应租户账号访问源工程。");
        view.put("parameterSchema",tree(row.getParameterSchemaJson()));
        view.put("expectedVersion",row.getPublicationLock());
        view.put("editable",sqlVisible);view.put("testable",sqlVisible);
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
        view.put("accessNotice","模板信息修改与测试复用原发布流程，仍校验发布者当前的数据源及模板授权；SQL 正文与参数定义由来源工程维护。发布不扩大 Agent 访问权限。");
        return ApiResponse.success(view);
    }
    public record ManagedAction(Long expectedVersion,String title,String description,String category,
        String testId,Map<String,Object> parameters,String expectedActiveReleaseId) { }

    @PostMapping("/{id}/test")
    public ApiResponse<?> test(@PathVariable("id")String id,@RequestBody ManagedAction input,HttpServletRequest request) {
        var row=managedRow(id,request);
        log.info("MCP administrator testing Studio publication: administrator={}, template={}, tenant={}",access.username(request),id,row.getAuthorTenantId());
        return ApiResponse.success(authoring.test(row.getAuthorTenantId(),row.getAuthorId(),id,
            new SqlTemplateAuthoringService.Action(input.expectedVersion(),input.testId(),input.parameters(),source(row),input.expectedActiveReleaseId())));
    }
    @PostMapping("/{id}/metadata")
    public ApiResponse<?> update(@PathVariable("id")String id,@RequestBody ManagedAction input,HttpServletRequest request) {
        var row=managedRow(id,request);
        log.info("MCP administrator synchronizing Studio publication: administrator={}, template={}, tenant={}",access.username(request),id,row.getAuthorTenantId());
        return ApiResponse.success(authoring.updateMetadata(row.getAuthorTenantId(),row.getAuthorId(),id,
            new SqlTemplateAuthoringService.MetadataEdit(input.expectedVersion(),input.title(),input.description(),input.category(),
                input.testId(),input.parameters(),source(row),input.expectedActiveReleaseId())));
    }
    private SqlTemplateConfig managedRow(String id,HttpServletRequest request) {
        access.require(request,"databaseMcp");
        // Explicit platform-administrator control plane: ordinary menu access grants no author delegation.
        // Identity/source come only from the persisted revision; the original author policy is revalidated.
        if(!access.superAdmin(request))throw new SecurityException("只有平台管理员可以管理 SQL Studio 发布模板");
        return templates.findById(id).filter(r->r.getPublicationGroupId()!=null&&r.getReleasedCapabilityId()!=null)
            .orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"SQL Studio publication not found"));
    }
    private Map<String,Object> source(SqlTemplateConfig row) {
        return json.convertValue(tree(row.getAuthoringSourceJson()),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
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
