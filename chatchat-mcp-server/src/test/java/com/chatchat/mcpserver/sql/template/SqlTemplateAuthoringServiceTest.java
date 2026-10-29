package com.chatchat.mcpserver.sql.template;

import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.database.execution.DatabaseQueryInvokeService;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.mcpserver.routing.target.ExecutionTargetRouter;
import com.chatchat.mcpserver.search.index.McpTemplateLuceneIndexService;
import com.chatchat.mcpserver.sql.calendar.DynamicDateParamService;
import com.chatchat.mcpserver.sql.datasource.*;
import com.chatchat.mcpserver.sql.execution.*;
import com.chatchat.mcpserver.sql.execution.workflow.SqlQueryExecutionWorkflow;
import com.chatchat.mcpserver.sql.resolution.MetadataResolverService;
import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.chatchat.mcpserver.template.workflow.TemplateParameterWorkflow;
import com.chatchat.mcpserver.templatepublication.admin.*;
import com.chatchat.mcpserver.templatepublication.catalog.*;
import com.chatchat.mcpserver.tool.StandardToolExecutionResultFactory;
import com.chatchat.tools.builtin.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real H2 JDBC + existing renderer/workflow/executor; persistence and remote authorization are isolated fixtures. */
class SqlTemplateAuthoringServiceTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final SqlTemplateConfigRepository repository = mock(SqlTemplateConfigRepository.class);
    final Map<String,SqlTemplateConfig> rows = new LinkedHashMap<>();
    final TemplateCallerScope callers = mock(TemplateCallerScope.class);
    final McpAuthorizationService authorization = mock(McpAuthorizationService.class);
    final SqlDatasourceConfigService datasources = mock(SqlDatasourceConfigService.class);
    final InvocationAuditService audit = mock(InvocationAuditService.class);
    final TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
    final TemplateCapabilityService capabilities = mock(TemplateCapabilityService.class);
    final ToolReleaseRepository releases = mock(ToolReleaseRepository.class);
    final ExecutionTargetRouter router = mock(ExecutionTargetRouter.class);
    final SqlDatasourceConfig datasource = new SqlDatasourceConfig();
    SqlTemplateService templates;
    SqlTemplateAuthoringService service;

    @BeforeEach void setup() throws Exception {
        datasource.setId("db"); datasource.setName("controlled-h2"); datasource.setEnabled(true);
        datasource.setJdbcUrl("jdbc:h2:mem:publication_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        datasource.setUsername("sa"); datasource.setPassword(""); datasource.setDatabaseType("h2");
        try(var c=DriverManager.getConnection(datasource.getJdbcUrl(),"sa","");var s=c.createStatement()) {
            s.execute("create table publication_rows as select x as id from system_range(1,100)");
        }
        when(callers.open(anyString(),anyString())).thenAnswer(i->McpInvocationContext.openRequestMeta(Map.of("tenantId",i.getArgument(0),"userId",i.getArgument(1))));
        when(authorization.callerAllowsOperation(anyString(),anyString(),anyString(),anyString())).thenReturn(true);
        when(datasources.getEnabled("db")).thenReturn(datasource);
        when(repository.findById(anyString())).thenAnswer(i->Optional.ofNullable(rows.get(i.getArgument(0))));
        when(repository.lockPublication(anyString())).thenAnswer(i->Optional.ofNullable(rows.get(i.getArgument(0))));
        when(repository.ownedPublicationGroup(anyString(),anyString(),anyString())).thenAnswer(i->{var r=rows.get(i.getArgument(0));return r!=null&&r.getAuthorTenantId().equals(i.getArgument(1))&&r.getAuthorId().equals(i.getArgument(2))?Optional.of(r.getPublicationGroupId()):Optional.empty();});
        when(repository.findByPublicationGroupIdOrderByPublicationVersionAsc(anyString())).thenAnswer(i->rows.values().stream().filter(r->r.getPublicationGroupId().equals(i.getArgument(0))).toList());
        when(repository.saveAndFlush(any())).thenAnswer(i->{SqlTemplateConfig r=i.getArgument(0);rows.put(r.getId(),r);return r;});
        when(releases.findByTenantIdOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        var properties=new DatabaseToolProperties();
        var loader=new DynamicJdbcDriverLoader(properties);
        var dates=new DynamicDateParamService(loader,Clock.systemUTC());
        templates=new SqlTemplateService(repository,json,new TemplateParameterValidator(json),new SqlTemplateSeedProperties(),dates);
        var executor=new SqlQueryExecuteService(datasources,new SqlSafetyService(),templates,mock(MetadataResolverService.class),audit,json,loader,dates,properties);
        when(router.routeSqlQuery(anyMap())).thenAnswer(i->i.getArgument(0));
        var workflow=new SqlQueryExecutionWorkflow(templates,executor,mock(SqlScriptExecuteService.class),mock(DatabaseQueryConfigService.class),mock(DatabaseQueryInvokeService.class),router,new StandardToolExecutionResultFactory(properties),new TemplateParameterWorkflow(new TemplateParameterValidator(json),json));
        service=new SqlTemplateAuthoringService(repository,templates,callers,authorization,datasources,workflow,catalog,capabilities,releases,mock(McpTemplateLuceneIndexService.class),json);
    }
    SqlTemplateAuthoringService.Write write(String operation,String group,Long version,String sql) throws Exception {
        var schema=json.readTree("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"integer\"}},\"required\":[\"value\"],\"additionalProperties\":false}");
        String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return new SqlTemplateAuthoringService.Write(operation,group,version,"Sample","Controlled sample","sql_diagnostic","db","h2",sql,schema,Map.of("projectId","p","fileId","f","fileVersion",1,"sqlDigest",sha));
    }
    SqlTemplateConfig create(String sql) throws Exception {var result=service.create("tenant","owner",write(UUID.randomUUID().toString(),null,null,sql));return rows.get(result.get("id"));}
    SqlTemplateAuthoringService.Action action(SqlTemplateConfig row,Map<String,Object> parameters) throws Exception {
        return new SqlTemplateAuthoringService.Action(row.getPublicationLock(),UUID.randomUUID().toString(),parameters,json.readValue(row.getAuthoringSourceJson(),Map.class),"");
    }
    @Test void draftIsDisabledAndCreationDoesNotExecuteSql() throws Exception {
        var row=create("select * from publication_rows where id > {{value}}");
        assertThat(row.isEnabled()).isFalse();assertThat(row.getPublicationState()).isEqualTo("DRAFT");
        verifyNoInteractions(audit,capabilities);
    }
    @Test void realJdbcQueryProducesBoundedVersionEvidence() throws Exception {
        var row=create("select * from publication_rows where id > {{value}}");
        var result=service.test("tenant","owner",row.getId(),action(row,Map.of("value",0)));
        var evidence=json.valueToTree(result.get("testEvidence"));
        assertThat(evidence.path("success").asBoolean()).isTrue();
        assertThat(evidence.path("rowCount").asInt()).isEqualTo(50);
        assertThat(evidence.path("timeoutSeconds").asInt()).isEqualTo(10);
        assertThat(evidence.path("fingerprint").asText()).isEqualTo(service.fingerprint(row));
        verify(audit).recordSqlQueryCall(any(),anyMap(),argThat(r->r.success()&&r.rowCount()==50));
        verify(router).routeSqlQuery(argThat(args->args.get(com.chatchat.mcpserver.routing.target.TemplateExecutionTarget.KEY)
            instanceof com.chatchat.mcpserver.routing.target.TemplateExecutionTarget fixed && fixed.resourceId().equals("db")));
        assertThat(row.isEnabled()).isFalse();
    }
    @Test void databaseSyntaxFailureIsRecordedAsFailure() throws Exception {
        var row=create("select nonexistent from publication_rows where id = {{value}}");
        service.test("tenant","owner",row.getId(),action(row,Map.of("value",1)));
        assertThat(json.readTree(row.getSqlTestEvidenceJson()).path("success").asBoolean()).isFalse();
        verify(audit).recordSqlQueryCall(any(),anyMap(),argThat(r->!r.success()));
    }
    @Test void parameterFailureDoesNotExecuteJdbc() throws Exception {
        var row=create("select {{value}}");service.test("tenant","owner",row.getId(),action(row,Map.of("value","wrong-type")));
        assertThat(json.readTree(row.getSqlTestEvidenceJson()).path("success").asBoolean()).isFalse();verifyNoInteractions(audit);
    }
    @Test void forbiddenWriteCannotPassRealGatewayAndLeavesDataUntouched() throws Exception {
        var row=create("delete from publication_rows where id = {{value}}");
        service.test("tenant","owner",row.getId(),action(row,Map.of("value",1)));
        assertThat(json.readTree(row.getSqlTestEvidenceJson()).path("success").asBoolean()).isFalse();
        try(var c=DriverManager.getConnection(datasource.getJdbcUrl(),"sa","");var s=c.createStatement();var r=s.executeQuery("select count(*) from publication_rows")){r.next();assertThat(r.getInt(1)).isEqualTo(100);}
    }
    @Test void multipleStatementsAreRejectedBeforeExecution() throws Exception {
        var row=create("select {{value}}; select 2");service.test("tenant","owner",row.getId(),action(row,Map.of("value",1)));
        assertThat(json.readTree(row.getSqlTestEvidenceJson()).path("success").asBoolean()).isFalse();verifyNoInteractions(audit);
    }
    @Test void editingSqlClearsEvidenceAndRequiresNewTest() throws Exception {
        var row=create("select {{value}}");service.test("tenant","owner",row.getId(),action(row,Map.of("value",1)));
        service.update("tenant","owner",row.getId(),write("edit",null,row.getPublicationLock(),"select {{value}} + 1"));
        assertThat(row.getSqlTestEvidenceJson()).isNull();
        assertThatThrownBy(()->service.publish("tenant","owner",row.getId(),action(row,Map.of("value",1)))).hasMessageContaining("successful SQL execution");
        verifyNoInteractions(capabilities);
    }
    @Test void staleVersionAndCrossTenantAreRejected() throws Exception {
        var row=create("select {{value}}");
        assertThatThrownBy(()->service.update("tenant","owner",row.getId(),write("edit",null,99L,"select {{value}}"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->service.test("other","owner",row.getId(),action(row,Map.of("value",1)))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.test("tenant","other",row.getId(),action(row,Map.of("value",1)))).isInstanceOf(SecurityException.class);
    }
    @Test void revokingDatasourcePermissionPreventsTest() throws Exception {
        var row=create("select {{value}}");when(authorization.callerAllowsOperation(anyString(),anyString(),anyString(),anyString())).thenReturn(false);
        assertThatThrownBy(()->service.test("tenant","owner",row.getId(),action(row,Map.of("value",1)))).isInstanceOf(SecurityException.class);verifyNoInteractions(audit);
    }
    @Test void publishedBodyCannotBeEditedThroughEitherEntry() throws Exception {
        var row=create("select {{value}}");row.setPublicationState("PUBLISHED");
        assertThatThrownBy(()->service.update("tenant","owner",row.getId(),write("edit",null,row.getPublicationLock(),"select {{value}} + 1"))).hasMessageContaining("immutable");
        assertThatThrownBy(()->templates.save(row)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->templates.delete(row.getId())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void retryingSameTestDoesNotRepeatDatabaseQuery() throws Exception {
        var row=create("select {{value}}");var action=action(row,Map.of("value",1));
        service.test("tenant","owner",row.getId(),action);service.test("tenant","owner",row.getId(),action);
        verify(audit,times(1)).recordSqlQueryCall(any(),anyMap(),any());
    }
    @Test void createRetryIsIdempotentButDifferentContentConflicts() throws Exception {
        var input=write("same-operation",null,null,"select {{value}}");
        var first=service.create("tenant","owner",input);assertThat(service.create("tenant","owner",input).get("id")).isEqualTo(first.get("id"));
        assertThatThrownBy(()->service.create("tenant","owner",write("same-operation",null,null,"select {{value}} + 1"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void draftCanBeSavedForAllowlistApprovalButCannotExecuteBeforeApproval() throws Exception {
        datasource.setAllowedTemplatesJson("[\"EXISTING_ONLY\"]");var row=create("select {{value}}");
        assertThat(row.isEnabled()).isFalse();
        assertThatThrownBy(()->service.test("tenant","owner",row.getId(),action(row,Map.of("value",1)))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(audit);
    }
    @Test void databaseLockContentionIsASafeVersionConflict() throws Exception {
        var row=create("select {{value}}");
        for(RuntimeException failure:List.of(new org.springframework.dao.PessimisticLockingFailureException("private database detail"),new IllegalStateException(new java.sql.SQLException("private database detail","55P03")))) {
            doThrow(failure).when(repository).lockPublication(row.getPublicationGroupId());
            assertThatThrownBy(()->service.test("tenant","owner",row.getId(),action(row,Map.of("value",1))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("409").hasMessageNotContaining("private database detail");
        }
        verifyNoInteractions(audit);
    }
}
