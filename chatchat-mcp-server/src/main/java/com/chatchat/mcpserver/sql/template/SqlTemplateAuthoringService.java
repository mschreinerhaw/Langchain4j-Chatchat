package com.chatchat.mcpserver.sql.template;

import com.chatchat.mcpserver.templatepublication.admin.*;
import com.chatchat.mcpserver.templatepublication.catalog.*;
import com.chatchat.mcpserver.sql.datasource.*;
import com.chatchat.mcpserver.sql.execution.workflow.SqlQueryExecutionWorkflow;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.search.index.McpTemplateLuceneIndexService;
import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.util.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;

/** Author lifecycle only: existing SQL definitions, query workflow and capability publisher remain authoritative. */
@Service @RequiredArgsConstructor
public class SqlTemplateAuthoringService {
    private final SqlTemplateConfigRepository repository;
    private final SqlTemplateService templates;
    private final TemplateCallerScope callers;
    private final McpAuthorizationService authorization;
    private final SqlDatasourceConfigService datasources;
    private final SqlQueryExecutionWorkflow queries;
    private final TemplateAssetCatalogService catalog;
    private final TemplateCapabilityService capabilities;
    private final ToolReleaseRepository releases;
    private final McpTemplateLuceneIndexService index;
    private final ObjectMapper json;
    private final com.chatchat.mcpserver.category.BusinessCategoryService businessCategories;

    public record Write(String operationId,String groupId,Long expectedVersion,String title,String description,String category,
        String datasourceId,String databaseType,String sqlTemplate,JsonNode parameterSchema,Map<String,Object> source) { }
    public record Action(Long expectedVersion,String testId,Map<String,Object> parameters,Map<String,Object> source,String expectedActiveReleaseId) { }
    public record MetadataEdit(Long expectedVersion,String title,String description,String category,String testId,
        Map<String,Object> parameters,Map<String,Object> source,String expectedActiveReleaseId) {
        Action action() { return new Action(expectedVersion,testId,parameters,source,expectedActiveReleaseId); }
    }

    public List<Map<String,Object>> categories() {
        return businessCategories.listEnabled().stream().map(c -> Map.<String,Object>of(
            "id",c.getId(),"code",c.getCode(),"name",c.getName())).toList();
    }

    /** Metadata changes use the existing authorized SQL code and publication group.
     * Retest a detached candidate before touching the live definition; immutable releases
     * retain their original metadata and SQL. No datasource allowlist is broadened. */
    @Transactional
    public Map<String,Object> updateMetadata(String tenant,String actor,String id,MetadataEdit edit) {
        try(var ignored=callers.open(tenant,actor)) {
            var row=locked(id,tenant,actor);var input=edit.action();
            expected(row,input.expectedVersion());source(row,input.source());requireHead(row,input);
            if(!"PUBLISHED".equals(row.getPublicationState()))throw new IllegalArgumentException("Only published template information can be synchronized");
            String title=required(edit.title(),200),description=optional(edit.description(),1000),category=category(edit.category());
            if(row.isEnabled()&&Objects.equals(title,row.getTitle())&&Objects.equals(description,row.getDescription())&&Objects.equals(category,row.getCategory()))return view(row);
            var candidate=new SqlTemplateConfig();org.springframework.beans.BeanUtils.copyProperties(row,candidate);
            candidate.setTitle(title);candidate.setDescription(description);candidate.setCategory(category);
            var evidence=json.valueToTree(execute(candidate,input));
            if(!evidence.path("success").asBoolean())throw new IllegalArgumentException("模板测试未通过，线上信息未修改："+evidence.path("diagnostic").asText());
            row.setTitle(title);row.setDescription(description);row.setCategory(category);
            return activate(row,evidence);
        }
    }

    @Transactional
    public Map<String,Object> create(String tenant,String actor,Write input) {
        try(var ignored=callers.open(tenant,actor)) {
            String operation=required(input.operationId(),128);
            String id=UUID.nameUUIDFromBytes((tenant+"\n"+actor+"\n"+operation).getBytes(StandardCharsets.UTF_8)).toString();
            var previous=repository.findById(id);
            if(previous.isPresent()) {
                var row=previous.get(); own(row,tenant,actor);
                if(!Objects.equals(row.getDraftRequestDigest(),digest(input))) conflict();
                return view(row);
            }
            var row=new SqlTemplateConfig();row.setId(id);row.setCode("STUDIO_"+id.replace("-","").toUpperCase(Locale.ROOT));
            String group=input.groupId()==null||input.groupId().isBlank()?id:input.groupId();
            List<SqlTemplateConfig> history=List.of();
            if(!group.equals(id)) {locked(group,tenant,actor);history=repository.findByPublicationGroupIdOrderByPublicationVersionAsc(group);}
            row.setPublicationGroupId(group);row.setPublicationVersion(history.stream().mapToInt(t->t.getPublicationVersion()).max().orElse(0)+1);
            row.setAuthorTenantId(tenant);row.setAuthorId(actor);row.setPublicationState("DRAFT");row.setPublicationLock(0L);row.setEnabled(false);
            apply(row,input);
            if(!history.isEmpty()&&!sameOrigin(tree(history.get(0).getAuthoringSourceJson()),tree(row.getAuthoringSourceJson())))
                throw new SecurityException("SQL publication belongs to a different Studio file");
            row.setDraftRequestDigest(digest(input));
            try {repository.saveAndFlush(row);}catch(org.springframework.dao.DataIntegrityViolationException concurrent){conflict();}
            return view(row);
        }
    }
    @Transactional
    public Map<String,Object> update(String tenant,String actor,String id,Write input) {
        try(var ignored=callers.open(tenant,actor)) {
            var row=locked(id,tenant,actor);expected(row,input.expectedVersion());draft(row);apply(row,input);
            row.setSqlTestEvidenceJson(null);row.setPublicationState("DRAFT");bump(row);repository.saveAndFlush(row);return view(row);
        }
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(String tenant,String actor,String project,String file) {
        return repository.findByAuthorTenantIdAndAuthorIdOrderByCreatedAtDesc(tenant,actor).stream()
            .filter(t->Objects.equals(tree(t.getAuthoringSourceJson()).path("projectId").asText(),project)
                && Objects.equals(tree(t.getAuthoringSourceJson()).path("fileId").asText(),file)).map(this::view).toList();
    }
    public List<Map<String,Object>> sources(String tenant,String actor) {
        try(var ignored=callers.open(tenant,actor)) {
            return datasources.listEnabled().stream().filter(t->authorization.callerAllowsOperation("sql_query_execute","sql_datasource",t.getId(),"read"))
                .map(t->Map.<String,Object>of("id",t.getId(),"name",t.getName(),"databaseType",Objects.toString(t.getDatabaseType(),"generic"))).toList();
        }
    }
    @Transactional
    public Map<String,Object> test(String tenant,String actor,String id,Action input) {
        try(var ignored=callers.open(tenant,actor)) {
            var row=locked(id,tenant,actor);draft(row);source(row,input.source());
            var old=tree(row.getSqlTestEvidenceJson());
            if(old.path("testId").asText().equals(input.testId()) && old.path("fingerprint").asText().equals(fingerprint(row))
                && old.path("parametersDigest").asText().equals(digest(parameters(input)))) return view(row);
            expected(row,input.expectedVersion());
            row.setSqlTestEvidenceJson(write(execute(row,input)));row.setPublicationState("TESTED");bump(row);
            repository.saveAndFlush(row);return view(row);
        }
    }
    @Transactional
    public Map<String,Object> publish(String tenant,String actor,String id,Action input) {
        try(var ignored=callers.open(tenant,actor)) {
            var row=locked(id,tenant,actor);source(row,input.source());
            if("PUBLISHED".equals(row.getPublicationState()) && row.isEnabled() && row.getReleasedCapabilityId()!=null) return view(row);
            expected(row,input.expectedVersion());draft(row);requireTest(row);requireHead(row,input);
            if(!tree(row.getSqlTestEvidenceJson()).path("parametersDigest").asText().equals(digest(parameters(input))))conflict();
            return activate(row,tree(row.getSqlTestEvidenceJson()));
        }
    }
    @Transactional
    public Map<String,Object> rollback(String tenant,String actor,String id,Action input) {
        try(var ignored=callers.open(tenant,actor)) {
            var row=locked(id,tenant,actor);expected(row,input.expectedVersion());
            requireHead(row,input);
            if(!"PUBLISHED".equals(row.getPublicationState()))throw new IllegalArgumentException("Only a published SQL snapshot can be restored");
            if(!sameOrigin(tree(row.getAuthoringSourceJson()),json.valueToTree(input.source())))throw new SecurityException("SQL source binding mismatch");
            var evidence=json.valueToTree(execute(row,input));
            if(!evidence.path("success").asBoolean()) {row.setSqlTestEvidenceJson(evidence.toString());bump(row);repository.saveAndFlush(row);return view(row);}
            return activate(row,evidence);
        }
    }
    private Map<String,Object> activate(SqlTemplateConfig row,JsonNode evidence) {
        var datasource=authorizedDatasource(row);
        if(!evidence.path("fingerprint").asText().equals(fingerprint(row))
            || !evidence.path("datasourceDigest").asText().equals(digest(datasource)))conflict();
        var history=repository.findByPublicationGroupIdOrderByPublicationVersionAsc(row.getPublicationGroupId());
        for(var old:history)if(old.isEnabled()&&!old.getId().equals(row.getId())) {old.setEnabled(false);repository.save(old);
            if(old.getReleasedCapabilityId()!=null)capabilities.offline(row.getAuthorTenantId(),row.getAuthorId(),old.getReleasedCapabilityId());}
        row.setPublicationState("PUBLISHED");row.setEnabled(true);row.setSqlTestEvidenceJson(evidence.toString());row.setReleasedCapabilityId(null);bump(row);repository.saveAndFlush(row);
        var bound=catalog.businessTemplates(false).stream().filter(t->row.getCode().equals(t.binding().get("template"))
            && row.getDatasourceId().equals(t.binding().get("datasourceId"))).findFirst()
            .orElseThrow(()->new SecurityException("Template and datasource execution authorization are required"));
        var saved=capabilities.publish(row.getAuthorTenantId(),row.getAuthorId(),bound.key(),bound.revision());
        var release=releases.findById(saved.get("id").toString()).orElseThrow();
        row.setReleasedCapabilityId(release.getId());repository.saveAndFlush(row);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){index.upsertSqlTemplates(List.of(row));}});
        return view(row);
    }
    private Map<String,Object> execute(SqlTemplateConfig row,Action input) {
        String testId=required(input.testId(),128);var datasource=authorizedDatasource(row);
        var result=new LinkedHashMap<String,Object>();
        result.put("type","SQL_DATABASE_EXECUTION");result.put("testId",testId);result.put("testedAt",Instant.now().toString());
        result.put("traceId",testId);result.put("source",tree(row.getAuthoringSourceJson()));
        result.put("fingerprint",fingerprint(row));result.put("sqlDigest",digest(row.getSqlTemplate()));
        result.put("datasourceId",row.getDatasourceId());result.put("datasourceDigest",digest(datasource));
        result.put("parameters",parameters(input));result.put("parametersDigest",digest(parameters(input)));
        result.put("timeoutSeconds",10);result.put("maxRows",50);
        long started=System.nanoTime();
        try {
            // Match the published MCP contract: only absent keys receive defaults.
            // The legacy argument compiler may repair malformed optional overrides;
            // such a repair must not certify a Studio query using different inputs.
            var schema=tree(row.getParameterSchemaJson());
            var sample=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(parameters(input));
            schema.path("properties").fields().forEachRemaining(field->{
                if(!sample.has(field.getKey())&&field.getValue().has("default"))sample.set(field.getKey(),field.getValue().get("default"));
            });
            ToolReleaseSchemas.validate(schema,sample);
            var prepared=templates.prepareAuthoring(row,parameters(input),datasource);
            String sql=prepared.sql();
            if(com.chatchat.mcpserver.sql.parsing.SqlStatementExtractor.splitStatements(sql).size()!=1)
                throw new IllegalArgumentException("Authoring test requires one read-only query");
            var arguments=new LinkedHashMap<String,Object>();arguments.put("datasourceId",datasource.getId());arguments.put("sql",sql);
            arguments.put(StudioPreparedSql.KEY,prepared);arguments.put("parameters",prepared.effectiveParameters());
            arguments.put(com.chatchat.mcpserver.routing.target.TemplateExecutionTarget.KEY,
                new com.chatchat.mcpserver.routing.target.TemplateExecutionTarget("sql_datasource",datasource.getId()));
            arguments.put("timeoutSeconds",10);arguments.put("maxRows",50);arguments.put("permissionLevel","read");
            arguments.put("purpose","SQL_TEMPLATE_PREPUBLICATION_TEST");arguments.put("sourceTaskId",testId);
            arguments.put("selfHealingAttempted",true); // Evidence must describe this exact SQL, never a repaired query.
            var output=queries.execute(arguments,new KernelDataScope(row.getAuthorTenantId(),row.getAuthorId(),testId,null,testId,null,Map.of()));
            var structured=json.valueToTree(output.structuredContent());
            boolean success=!Boolean.TRUE.equals(output.isError()) && structured.path("success").asBoolean(false);
            result.put("success",success);result.put("resultDigest",digest(structured));
            result.put("rowCount",structured.path("data").path("rowCount").asInt(0));
            result.put("errorCode",success?"":"SQL_EXECUTION_FAILED");
            if(success)result.put("preview",preview(structured.path("data")));
            result.put("diagnostic",success?"SQL query executed successfully":"SQL query execution failed; inspect governed query audit");
        } catch(IllegalArgumentException invalid) {result.put("success",false);result.put("errorCode","SQL_OR_PARAMETER_VALIDATION_FAILED");result.put("diagnostic","SQL or parameter validation failed");}
        result.put("durationMs",Math.max(0,(System.nanoTime()-started)/1_000_000));return result;
    }
    /** Small owner-scoped review sample, never a replacement for the full execution result. */
    private JsonNode preview(JsonNode data) {
        var preview=json.createObjectNode();var columns=preview.putArray("columns");
        for(var column:data.path("columns")) {if(columns.size()>=128)break;columns.add(column.asText().substring(0,Math.min(256,column.asText().length())));}
        var rows=preview.putArray("rows");int bytes=0;
        for(var value:data.path("rows")) {
            int size=write(value).getBytes(StandardCharsets.UTF_8).length;
            if(rows.size()>=10||bytes+size>32_000)break;
            rows.add(value);bytes+=size;
        }
        preview.put("returnedRowCount",data.path("returnedRowCount").asInt(data.path("rows").size()));
        preview.put("possiblyTruncated",data.path("possiblyTruncated").asBoolean(true));
        preview.put("complete",data.path("complete").asBoolean(false));
        preview.put("previewTruncated",rows.size()<data.path("rows").size()||columns.size()<data.path("columns").size());
        return preview;
    }
    private SqlDatasourceConfig authorizedDatasource(SqlTemplateConfig row) {
        return authorizedDatasource(row,true);
    }
    private SqlDatasourceConfig authorizedDatasource(SqlTemplateConfig row,boolean execution) {
        var value=datasources.getEnabled(row.getDatasourceId());
        if(!authorization.callerAllowsOperation("sql_query_execute","sql_datasource",value.getId(),"read"))throw new SecurityException("Datasource execution is not authorized");
        templates.validateAuthoringBinding(row,value);
        if(execution&&!templates.isCompatible(row,value))throw new SecurityException("Datasource binding or template allowlist is not effective");return value;
    }
    private void apply(SqlTemplateConfig row,Write input) {
        String sql=required(input.sqlTemplate(),200_000);var schema=input.parameterSchema();
        if(schema==null || !schema.isObject() || !"object".equals(schema.path("type").asText()))throw new IllegalArgumentException("Object parameter Schema required");
        ToolReleaseSchemas.compile(schema);
        var tokens=java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}").matcher(sql);
        while(tokens.find())if(!schema.path("properties").has(tokens.group(1)))throw new IllegalArgumentException("Every SQL placeholder must be declared in parameter Schema");
        row.setTitle(required(input.title(),200));row.setDescription(optional(input.description(),1000));row.setCategory(category(input.category()));
        if(schema.toString().length()>100_000)throw new IllegalArgumentException("Parameter Schema is too large");
        row.setSqlTemplate(sql);row.setParameterSchemaJson(schema.toString());row.setDatasourceId(required(input.datasourceId(),64));row.setDatabaseType(input.databaseType());
        var origin=json.valueToTree(input.source());
        if(!origin.path("sqlDigest").asText().equals(digest(sql)))throw new IllegalArgumentException("SQL source digest mismatch");
        required(origin.path("projectId").asText(),64);required(origin.path("fileId").asText(),64);
        if(origin.path("fileVersion").asInt()<1)throw new IllegalArgumentException("Saved SQL file version required");
        if(row.getAuthoringSourceJson()!=null && !sameOrigin(tree(row.getAuthoringSourceJson()),origin))throw new SecurityException("SQL file association cannot be changed");
        row.setAuthoringSourceJson(origin.toString());templates.validateAuthoring(row);row.setSqlTemplate(sql);authorizedDatasource(row,false);
    }
    private SqlTemplateConfig locked(String id,String tenant,String actor) {
        try {
            String group=repository.ownedPublicationGroup(id,tenant,actor).orElseThrow(()->new SecurityException("SQL revision is outside author scope"));
            repository.lockPublication(group).orElseThrow();var row=repository.lockPublication(id).orElseThrow();own(row,tenant,actor);return row;
        }catch(RuntimeException failure) {
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
                if(cause instanceof org.springframework.dao.PessimisticLockingFailureException
                    ||cause instanceof jakarta.persistence.LockTimeoutException||cause instanceof jakarta.persistence.PessimisticLockException
                    ||cause instanceof java.sql.SQLException sql&&Set.of("55P03","40P01","40001").contains(sql.getSQLState()))conflict();
            }
            throw failure;
        }
    }
    private void own(SqlTemplateConfig row,String tenant,String actor){if(!Objects.equals(tenant,row.getAuthorTenantId())||!Objects.equals(actor,row.getAuthorId()))throw new SecurityException("SQL revision is outside author scope");}
    private void expected(SqlTemplateConfig row,Long version){if(version==null||!Objects.equals(version,row.getPublicationLock()))conflict();}
    private void draft(SqlTemplateConfig row){if("PUBLISHED".equals(row.getPublicationState()))throw new IllegalArgumentException("Published SQL is immutable; create another draft");}
    private void source(SqlTemplateConfig row,Map<String,Object> source){if(!tree(row.getAuthoringSourceJson()).equals(json.valueToTree(source)))conflict();}
    private boolean sameOrigin(JsonNode a,JsonNode b){return a.path("projectId").equals(b.path("projectId"))&&a.path("fileId").equals(b.path("fileId"));}
    private void bump(SqlTemplateConfig row){row.setPublicationLock(Objects.requireNonNullElse(row.getPublicationLock(),0L)+1);}
    private Map<String,Object> parameters(Action input){return input.parameters()==null?Map.of():input.parameters();}
    private void requireTest(SqlTemplateConfig row){var test=tree(row.getSqlTestEvidenceJson());if(!test.path("success").asBoolean()||!test.path("fingerprint").asText().equals(fingerprint(row)))throw new IllegalArgumentException("A successful SQL execution for this exact revision is required");}
    private void requireHead(SqlTemplateConfig row,Action input) {
        String active=repository.findByPublicationGroupIdOrderByPublicationVersionAsc(row.getPublicationGroupId()).stream()
            .filter(SqlTemplateConfig::isEnabled).map(t->Objects.toString(t.getReleasedCapabilityId(),"")).findFirst().orElse("");
        if(!active.equals(Objects.toString(input.expectedActiveReleaseId(),"")))conflict();
    }
    public Map<String,Object> publicationEvidence(String id,String tenant,String actor) {
        var row=repository.findById(id).orElseThrow();
        if(!Objects.equals(tenant,row.getAuthorTenantId()) || !Objects.equals(actor,row.getAuthorId()) || !row.isEnabled() || !"PUBLISHED".equals(row.getPublicationState()))
            throw new SecurityException("SQL revision is not an authorized published snapshot");
        requireTest(row);var evidence=tree(row.getSqlTestEvidenceJson());
        if(!evidence.path("datasourceDigest").asText().equals(digest(authorizedDatasource(row))))conflict();
        return json.convertValue(evidence,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
    }
    /** Existing capability offlining also withdraws the authored SQL definition.
     * Lock the SQL publication group before the capability logical ID, matching
     * authoring publish/rollback lock order. A historical release cannot disable
     * a newer release of the same SQL revision. */
    @Transactional
    public void offlinePublication(String id,String tenant,String actor,String releaseId) {
        var row=locked(id,tenant,actor);
        if(!row.isEnabled()||!Objects.equals(releaseId,row.getReleasedCapabilityId()))return;
        row.setEnabled(false);bump(row);repository.saveAndFlush(row);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){index.upsertSqlTemplates(List.of(row));}
        });
    }
    public Map<String,Object> publicationSnapshot(String id,String tenant,String actor) {
        publicationEvidence(id,tenant,actor);
        return snapshot(repository.findById(id).orElseThrow());
    }
    public Optional<String> existingPublicationId(String id,String tenant,String actor) {
        var row=repository.findById(id).orElseThrow();own(row,tenant,actor);
        return Optional.ofNullable(row.getReleasedCapabilityId());
    }
    public String fingerprint(SqlTemplateConfig row){return digest(snapshot(row));}
    private Map<String,Object> snapshot(SqlTemplateConfig row){var m=new LinkedHashMap<String,Object>();m.put("id",row.getId());m.put("code",row.getCode());m.put("sqlTemplate",row.getSqlTemplate());m.put("parameterSchema",tree(row.getParameterSchemaJson()));m.put("datasourceId",row.getDatasourceId());m.put("databaseType",row.getDatabaseType());m.put("title",row.getTitle());m.put("description",row.getDescription());m.put("category",row.getCategory());m.put("source",tree(row.getAuthoringSourceJson()));return m;}
    private Map<String,Object> view(SqlTemplateConfig row){var m=new LinkedHashMap<>(snapshot(row));m.put("groupId",row.getPublicationGroupId());m.put("version",row.getPublicationVersion());m.put("expectedVersion",row.getPublicationLock());m.put("state",row.getPublicationState());m.put("active",row.isEnabled());m.put("testEvidence",tree(row.getSqlTestEvidenceJson()));m.put("templateKey","sql_datasource:"+row.getCode());if(row.getReleasedCapabilityId()!=null)releases.findById(row.getReleasedCapabilityId()).ifPresent(r->m.put("publication",capabilities.view(r)));
        m.put("releaseHistory",releases.findByTenantIdOrderByCreatedAtDesc(row.getAuthorTenantId()).stream()
            .filter(r->TemplateCapabilityService.KIND.equals(r.getKind())&&row.getAuthorId().equals(r.getActorId()))
            .filter(r->row.getId().equals(capabilities.definition(r).path("sqlSnapshot").path("id").asText()))
            .map(r->Map.of("publication",capabilities.view(r),"sqlSnapshot",capabilities.definition(r).path("sqlSnapshot"),"sqlTest",capabilities.definition(r).path("sqlTest"))).toList());return m;}
    private JsonNode tree(String value){try{return value==null?json.nullNode():json.readTree(value);}catch(Exception e){throw new IllegalStateException("Invalid SQL authoring record");}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid authoring data");}}
    private String digest(Object value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((value instanceof String s?s:write(value)).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalArgumentException("Cannot hash SQL revision");}}
    private String required(String value,int max){if(value==null||value.isBlank()||value.length()>max)throw new IllegalArgumentException("Missing or oversized authoring value");return value;}
    private String optional(String value,int max){if(value!=null&&value.length()>max)throw new IllegalArgumentException("Oversized authoring value");return value;}
    private String category(String value){var category=businessCategories.require(required(value,100));if(!category.isEnabled())throw new IllegalArgumentException("业务分类已停用，请重新选择");return category.getCode();}
    private void conflict(){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"SQL revision or source changed; reload and retest");}
}
