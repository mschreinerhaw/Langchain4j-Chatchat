package com.chatchat.api.datascience.sql;

import com.chatchat.api.controller.agent.DocumentLibraryReadPort;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.service.InteractionOrchestrationService;
import com.chatchat.chat.task.core.AgentTaskLatestRepository;
import com.chatchat.common.security.PasswordHashCodec;
import com.chatchat.enterprise.entity.identity.*;
import com.chatchat.enterprise.repository.identity.*;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.chat.ChatModel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP authentication, object policy, H2 transactions and original scheduler.
 * Orchestration/model answers are fixtures: this is NOT real-model ETL verification. */
@SpringBootTest(properties={"chatchat.api.auth.enabled=true","chatchat.enterprise.bootstrap.admin-password=SqlStudioTest#2026",
    "spring.datasource.url=jdbc:h2:mem:sql_studio_integration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class SqlStudioIntegrationTest {
    static final String ROOT="/api/v1/data-science/sql/projects";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
    @Autowired EnterpriseAdminService identities; @Autowired AgentTaskLatestRepository tasks;
    @Autowired SysUserRepository users; @Autowired SysRoleRepository roles;
    @Autowired SysUserRoleRepository userRoles; @Autowired SysRolePermissionRepository rolePermissions;
    @Autowired SysPermissionRepository permissions;
    @Autowired SqlStudioMigration migration;
    @Autowired SqlStudioService studio;
    @Autowired List<com.chatchat.agents.runtime.tool.ToolRuntimePolicyProvider> toolPolicies;
    @Autowired SqlStudioGenerationScopeStore generationScopes;
    @MockBean ChatModel model;
    @MockBean InteractionOrchestrationService orchestration;
    @MockBean com.chatchat.chat.interaction.service.DatabaseRuntimeSemanticPolicySource policy;
    @MockBean com.chatchat.chat.skills.catalog.SkillCatalogService catalog;
    @MockBean com.chatchat.common.mcp.license.McpLicenseEntitlementPort license;
    @MockBean DocumentLibraryReadPort documentLibrary;
    @MockBean SqlStudioMetadataGateway metadataGateway;
    @MockBean com.chatchat.api.datascience.StudioCodePlanService codePlans;
    String token, tenant;
    com.chatchat.api.runtime.coding.workspace.CodingWorkspaceService codingFixture;

    @BeforeEach void setup() {
        codingFixture=mock(com.chatchat.api.runtime.coding.workspace.CodingWorkspaceService.class);
        studio.setCoding(codingFixture);

        when(codePlans.create(any(),any(),eq("sql"),any(),any(),any())).thenReturn(new com.chatchat.api.datascience.StudioCodePlanService.Ticket("studio-code-test",null,"READY"));
        when(codePlans.context(any())).thenReturn("\nAccepted code InterpretationPlan before exploration");
        when(license.workspaceEntitlement()).thenReturn(new com.chatchat.common.mcp.license.McpLicenseEntitlementPort.WorkspaceEntitlement(true,Set.of("zhice","data-science"),"VALID"));
        var auth=identities.login("admin","SqlStudioTest#2026"); token=auth.token(); tenant=auth.user().tenantId();
    }
    @Test void followsOnlyOwnedRetryAttemptsAndPreventsDeletingAProjectWhileItsRetryRuns() throws Exception {
        String p=project(),generation=UUID.randomUUID().toString(),execution=UUID.randomUUID().toString();
        String session=jdbc.queryForObject("select session_id from ds_sql_project where id=?",String.class,p);
        String owner=jdbc.queryForObject("select owner_id from ds_sql_project where id=?",String.class,p);
        var original=retryRow(owner,session,execution,1,"FAILED");original.setErrorMessage("Predecessor failed");tasks.saveAndFlush(original);
        var retry=retryRow(owner,session,execution,2,"WAIT_MODEL");tasks.saveAndFlush(retry);
        tasks.saveAndFlush(retryRow("another-owner",session,execution,99,"SUCCESS"));
        tasks.saveAndFlush(retryRow(owner,session,"different-execution",100,"SUCCESS"));
        jdbc.update("insert into ds_sql_generation(id,project_id,task_id,context_json,base_versions_json,applied,created_at) values(?,?,?,?,?,false,CURRENT_TIMESTAMP)",
            generation,p,original.getTaskId(),json(Map.of("sessionId",session,"query","Retry fixture","toolInput",Map.of("sqlStudioAction","explain"))),"{}");
        String url=ROOT+"/"+p+"/generations/"+generation;
        var active=data(mvc.perform(get(url).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn());
        assertThat(active.path("generation").path("taskId").asText()).isEqualTo(original.getTaskId());
        assertThat(active.path("task").path("taskId").asText()).isEqualTo(retry.getTaskId());assertThat(active.path("task").path("status").asText()).isEqualTo("WAIT_MODEL");
        mvc.perform(delete(ROOT+"/"+p).header("Authorization","Bearer "+token)).andExpect(status().isConflict());
        retry.setStatus("SUCCESS");retry.setAnswerSummary(json(Map.of("schemaVersion","sql-studio.output.v1","notes","Recovered answer","files",List.of())));tasks.saveAndFlush(retry);
        var completed=data(mvc.perform(get(url).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn());
        assertThat(completed.path("task").path("taskId").asText()).isEqualTo(retry.getTaskId());assertThat(completed.path("rawAnswer").asText()).contains("Recovered answer");
        assertThat(tasks.findById(original.getTaskId()).orElseThrow().getStatus()).isEqualTo("FAILED");
    }
    com.chatchat.chat.task.core.AgentTaskLatestEntity retryRow(String owner,String session,String execution,int number,String status){
        var row=new com.chatchat.chat.task.core.AgentTaskLatestEntity();row.setTaskId(UUID.randomUUID().toString());row.setTenantId(tenant);row.setUserId(owner);row.setSessionId(session);
        row.setExecutionId(execution);row.setRootExecutionId(execution);row.setExecutionAttemptId("attempt-"+UUID.randomUUID());row.setExecutionAttemptNumber(number);row.setQuestion("Retry fixture");
        row.setStatus(status);row.setCanonicalState(com.chatchat.chat.task.core.AgentExecutionState.fromWire(status).name());return row;
    }
    @Test void cannotPublishAnExplorationResultWithoutTrustedDomainExecutionEvidence() throws Exception {
        String p=project(),generation=UUID.randomUUID().toString();String session=jdbc.queryForObject("select session_id from ds_sql_project where id=?",String.class,p);
        String owner=jdbc.queryForObject("select owner_id from ds_sql_project where id=?",String.class,p);var task=retryRow(owner,session,UUID.randomUUID().toString(),1,"SUCCESS");
        task.setAnswerSummary(json(Map.of("schemaVersion","sql-studio.output.v1","notes","Untrusted generic answer","files",List.of(Map.of("path","sql/forged.sql","content","SELECT invented_field FROM invented_table")))));tasks.saveAndFlush(task);
        var request=Map.of("tenantId",tenant,"userId",owner,"sessionId",session,"query","Retry fixture","toolInput",Map.of("sqlStudioAction","explore","sqlMetadataDiscoveryId","missing-native-proof"));
        jdbc.update("insert into ds_sql_generation(id,project_id,task_id,context_json,base_versions_json,applied,created_at) values(?,?,?,?,?,false,CURRENT_TIMESTAMP)",generation,p,task.getTaskId(),json(request),"{}");
        var result=data(mvc.perform(get(ROOT+"/"+p+"/generations/"+generation).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn());
        assertThat(result.path("files").isEmpty()).isTrue();assertThat(result.path("rawAnswer").asText()).isEmpty();assertThat(result.path("outputError").asText()).contains("可信元数据执行证据");
        mvc.perform(post(ROOT+"/"+p+"/generations/"+generation+"/apply").header("Authorization","Bearer "+token).contentType("application/json").content(json(Map.of("paths",List.of("sql/forged.sql"))))).andExpect(status().isBadRequest());
    }
    JsonNode data(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data"); }
    @Test void deletingCodingProjectsChecksNativeTaskStateAndKeepsActiveProjectsIntact() throws Exception {
        for (var state : com.chatchat.common.runtime.coding.CodingTaskOutcome.Status.values()) {
            String p = project();
            var file = save(p, "sql/query.sql", "SQL", "SELECT 1;", 0);
            var prepared = prepare(p, List.of(), List.of());
            String generation = prepared.path("generationId").asText(), task = "sql-coding-" + generation;
            jdbc.update("update ds_sql_generation set task_id=? where id=?", task, generation);
            var outcome = mock(com.chatchat.common.runtime.coding.CodingTaskOutcome.class);
            when(outcome.status()).thenReturn(state);
            when(codingFixture.get(any(), eq(task))).thenReturn(outcome);
            assertThat(tasks.findById(task)).isEmpty();
            var deletion = mvc.perform(delete(ROOT + "/" + p).header("Authorization", "Bearer " + token));
            deletion.andExpect(state.terminal() ? status().isOk() : status().isConflict());
            int remaining = state.terminal() ? 0 : 1;
            for (String table : List.of("ds_sql_project", "ds_sql_file", "ds_sql_generation")) {
                String column = table.equals("ds_sql_project") ? "id" : "project_id";
                assertThat(jdbc.queryForObject("select count(*) from " + table + " where " + column + "=?", Integer.class, p)).isEqualTo(remaining);
            }
            assertThat(jdbc.queryForObject("select count(*) from ds_sql_file_version where file_id=?", Integer.class, file.path("id").asText())).isEqualTo(remaining);
            verify(codingFixture).get(argThat(scope -> scope.tenantId().equals(tenant)
                && scope.runId().equals("sql-generation-" + generation)), eq(task));
        }
    }
    String json(Object value) throws Exception { return mapper.writeValueAsString(value); }
    String project() throws Exception { return data(mvc.perform(post(ROOT).header("Authorization","Bearer "+token).header("X-Workspace-Id","data-science").contentType("application/json").content("{\"name\":\"ETL test\",\"dialect\":\"spark\"}")).andExpect(status().isOk()).andReturn()).path("id").asText(); }
    JsonNode save(String id,String path,String kind,String content,int version) throws Exception {
        return data(mvc.perform(put(ROOT+"/"+id+"/files").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json(Map.of("path",path,"kind",kind,"content",content,"expectedVersion",version)))).andExpect(status().isOk()).andReturn());
    }
    JsonNode prepare(String id,List<String> mappings,List<String> refs) throws Exception {
        return data(mvc.perform(post(ROOT+"/"+id+"/generations").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json(Map.of("prompt","Generate ETL using supplied mappings; keep case style", "dialect","spark","mappingIds",mappings,"referenceIds",refs))))
            .andExpect(status().isOk()).andReturn());
    }
    String run(String id,JsonNode prepared,List<Map<String,String>> files) throws Exception {
        if("SQL_WORKSPACE_V2".equals(prepared.path("taskRequest").path("toolInput").path("codingWorkspaceProfile").asText())) {
            String task="sql-coding-"+prepared.path("generationId").asText();
            var outcome=mock(com.chatchat.common.runtime.coding.CodingTaskOutcome.class);
            when(outcome.taskId()).thenReturn(task);when(outcome.status()).thenReturn(com.chatchat.common.runtime.coding.CodingTaskOutcome.Status.COMPLETED);
            when(outcome.createdAt()).thenReturn(java.time.Instant.now());when(outcome.updatedAt()).thenReturn(java.time.Instant.now());
            when(outcome.endedAt()).thenReturn(java.time.Instant.now());
            when(codingFixture.submit(any(),eq(task),anyString(),anyString(),nullable(String.class),anyMap())).thenReturn(outcome);
            when(codingFixture.get(any(),eq(task))).thenReturn(outcome);when(codingFixture.events(any(),eq(task))).thenReturn(List.of());
            when(codingFixture.output(any(),eq(task),eq(outcome))).thenReturn(Map.of("files",files,"notes","SQL analysis and assumptions",
                "validation",Map.of("fieldValidation","NOT_EVALUATED")));
            mvc.perform(post(ROOT+"/"+id+"/generations/"+prepared.path("generationId").asText()+"/coding")
                .header("Authorization","Bearer "+token)).andExpect(status().isOk());
            return task;
        }
        String answer=json(Map.of("schemaVersion","sql-studio.output.v1","files",files,"notes","SQL analysis and assumptions"));
        doAnswer(call -> InteractionResponse.builder()
            .conversationId(call.getArgument(0,com.chatchat.chat.interaction.model.InteractionRequest.class).getConversationId()).answer(answer).mode("agent_chat").build()).when(orchestration).chat(any());
        String taskId=data(mvc.perform(post("/api/v1/agent/tasks").header("Authorization","Bearer "+token).header("X-Workspace-Id","data-science")
            .contentType("application/json").content(json(prepared.path("taskRequest")))).andExpect(status().isOk()).andReturn()).path("taskId").asText();
        mvc.perform(put(ROOT+"/"+id+"/generations/"+prepared.path("generationId").asText()+"/task").header("Authorization","Bearer "+token)
            .contentType("application/json").content(json(Map.of("taskId",taskId)))).andExpect(status().isOk());
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline && !"SUCCESS".equals(tasks.findById(taskId).orElseThrow().getStatus())) Thread.sleep(30);
        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo("SUCCESS");
        return taskId;
    }
    JsonNode result(String project,JsonNode prepared) throws Exception {
        return data(mvc.perform(get(ROOT+"/"+project+"/generations/"+prepared.path("generationId").asText()).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andReturn());
    }
    String applyUrl(String project,JsonNode prepared) {return ROOT+"/"+project+"/generations/"+prepared.path("generationId").asText()+"/apply";}
    @Test void singleMappingThroughExistingTaskApiProducesReviewedSparkFileAndVersions() throws Exception {
        String p=project(); var mapping=save(p,"mapping/customer.json","MAPPING","{\"source\":\"stg_customer\",\"target\":\"dim_customer\"}",0);
        var prepared=prepare(p,List.of(mapping.path("id").asText()),List.of());
        String task=run(p,prepared,List.of(Map.of("path","sql/customer.sql","content","INSERT OVERWRITE TABLE dim_customer SELECT * FROM stg_customer;")));
        var view=result(p,prepared); assertThat(view.path("files").size()).isEqualTo(1); assertThat(view.path("validation").asText()).contains("PARSED","SQL_NOT_EXECUTED");
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_file where project_id=? and kind='SQL'",Integer.class,p)).isZero();
        var saved=data(mvc.perform(post(applyUrl(p,prepared)).header("Authorization","Bearer "+token).contentType("application/json").content("{\"paths\":[\"sql/customer.sql\"]}")).andExpect(status().isOk()).andReturn()).get(0);
        assertThat(saved.path("sourceTaskId").asText()).isEqualTo(task);
        var edited=save(p,"sql/customer.sql","SQL","-- reviewed\nSELECT 1;",1); assertThat(edited.path("version").asInt()).isEqualTo(2);
        mvc.perform(get(ROOT+"/"+p+"/files/"+saved.path("id").asText()+"/versions").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get(ROOT+"/"+p+"/files/"+saved.path("id").asText()+"/download").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(content().bytes("-- reviewed\nSELECT 1;".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void codingWorkspaceEntryKeepsProjectOwnershipReviewAndVersionedApply() throws Exception {
        var coding=mock(com.chatchat.api.runtime.coding.workspace.CodingWorkspaceService.class);studio.setCoding(coding);
        try{
            String p=project();save(p,"sql/existing.sql","SQL","-- preserve\nSELECT 1;",0);
            var prepared=prepare(p,List.of(),List.of());String generation=prepared.path("generationId").asText();
            assertThat(prepared.path("taskRequest").path("toolInput").path("codingWorkspaceProfile").asText()).isEqualTo("SQL_WORKSPACE_V2");
            String task="sql-coding-"+generation;
            var outcome=mock(com.chatchat.common.runtime.coding.CodingTaskOutcome.class);
            when(outcome.taskId()).thenReturn(task);when(outcome.status()).thenReturn(com.chatchat.common.runtime.coding.CodingTaskOutcome.Status.COMPLETED);
            when(outcome.createdAt()).thenReturn(java.time.Instant.now());when(outcome.updatedAt()).thenReturn(java.time.Instant.now());
            when(outcome.endedAt()).thenReturn(java.time.Instant.now());
            when(coding.submit(any(),anyString(),anyString(),anyString(),nullable(String.class),anyMap())).thenReturn(outcome);
            when(coding.get(any(),eq(task))).thenReturn(outcome);when(coding.events(any(),eq(task))).thenReturn(List.of());
            when(coding.output(any(),eq(task),eq(outcome))).thenReturn(Map.of("files",List.of(
                Map.of("path","sql/existing.sql","content","-- preserve\nSELECT 2;"),
                Map.of("path","etl/new.sql","content","SELECT 3;")),"notes","Reviewed proposal; no SQL execution",
                "validation",Map.of("fieldValidation","NOT_EVALUATED")));
            String submit=ROOT+"/"+p+"/generations/"+generation+"/coding";
            mvc.perform(post(submit).header("Authorization","Bearer "+otherUser(true))).andExpect(status().isForbidden());
            mvc.perform(post(submit).header("Authorization","Bearer "+token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(task));
            mvc.perform(put(ROOT+"/"+p+"/generations/"+generation+"/task").header("Authorization","Bearer "+token)
                .contentType("application/json").content("{\"taskId\":\"unrelated\"}")).andExpect(status().isBadRequest());
            var view=result(p,prepared);assertThat(view.path("files").size()).isEqualTo(2);
            assertThat(view.path("validation").asText()).contains("PARSED","FIELDS_NOT_EVALUATED","SQL_NOT_EXECUTED");
            assertThat(jdbc.queryForObject("select content from ds_sql_file where project_id=? and path='sql/existing.sql'",String.class,p))
                .isEqualTo("-- preserve\nSELECT 1;");
            mvc.perform(post(applyUrl(p,prepared)).header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"paths\":[\"sql/existing.sql\",\"etl/new.sql\"]}")).andExpect(status().isOk());
            assertThat(jdbc.queryForObject("select version from ds_sql_file where project_id=? and path='sql/existing.sql'",Integer.class,p)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from ds_sql_file where project_id=?",Integer.class,p)).isEqualTo(2);
            mvc.perform(post(applyUrl(p,prepared)).header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"paths\":[\"sql/existing.sql\"]}")).andExpect(status().isConflict());
        }finally{studio.setCoding(null);}
    }
    @Test void multipleMappingsAndHistoricalStyleAreFrozenAndMultipleOutputsApplied() throws Exception {
        String p=project(), other=project(); var a=save(p,"mapping/a.csv","MAPPING","source,target\na,b",0); var b=save(p,"mapping/b.md","MAPPING","source c maps to target d",0);
        var ref=save(other,"examples/style.sql","SQL","-- approved uppercase style\nSELECT ID FROM LEGACY;",0);
        var prepared=prepare(p,List.of(a.path("id").asText(),b.path("id").asText()),List.of(ref.path("id").asText()));
        assertThat(prepared.path("taskRequest").path("query").asText()).contains("mapping/a.csv","mapping/b.md","approved uppercase style","Dialect: spark");
        save(other,"examples/style.sql","SQL","changed after preparation",1);
        assertThat(result(p,prepared).path("generation").path("prepared").path("taskRequest").path("query").asText()).contains("approved uppercase style");
        run(p,prepared,List.of(Map.of("path","sql/a.sql","content","SELECT ID FROM A;"),Map.of("path","sql/b.sql","content","SELECT ID FROM B;")));
        mvc.perform(post(applyUrl(p,prepared)).header("Authorization","Bearer "+token).contentType("application/json").content("{\"paths\":[\"sql/a.sql\",\"sql/b.sql\"]}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
    }
    @Test void concurrentUserSaveRejectsEntireGeneratedBatchWithoutPartialWrites() throws Exception {
        String p=project(); var m=save(p,"mapping/a.txt","MAPPING","a to b",0); save(p,"sql/existing.sql","SQL","original",0);
        var prepared=prepare(p,List.of(m.path("id").asText()),List.of());
        run(p,prepared,List.of(Map.of("path","sql/new.sql","content","SELECT 1;"),Map.of("path","sql/existing.sql","content","SELECT 2;")));
        save(p,"sql/existing.sql","SQL","user change wins",1);
        mvc.perform(post(applyUrl(p,prepared)).header("Authorization","Bearer "+token).contentType("application/json").content("{\"paths\":[\"sql/new.sql\",\"sql/existing.sql\"]}")).andExpect(status().isConflict());
        assertThat(jdbc.queryForList("select content from ds_sql_file where project_id=? and path='sql/new.sql'",p)).isEmpty();
        assertThat(jdbc.queryForObject("select content from ds_sql_file where project_id=? and path='sql/existing.sql'",String.class,p)).isEqualTo("user change wins");
        assertThat(result(p,prepared).path("generation").path("applied").asBoolean()).isFalse();
    }
    @Test void duplicateCreationStaleSaveAndUnsafePathsAreRejected() throws Exception {
        String p=project(); save(p,"sql/a.sql","SQL","SELECT 1;",0);
        mvc.perform(put(ROOT+"/"+p+"/files").header("Authorization","Bearer "+token).contentType("application/json").content(json(Map.of("path","SQL/A.SQL","kind","SQL","content","bad","expectedVersion",0)))).andExpect(status().isConflict());
        for(String path:List.of("../a.sql","/root/a.sql","sql/../a.sql","C:/a.sql","sql\\a.sql","sql/%2e.sql","sql/CON.sql"))
            mvc.perform(put(ROOT+"/"+p+"/files").header("Authorization","Bearer "+token).contentType("application/json").content(json(Map.of("path",path,"kind","SQL","content","bad","expectedVersion",0)))).andExpect(status().isBadRequest());
    }
    @Test void renameKeepsFileIdentityAndRequiresOwnershipAndCurrentVersion() throws Exception {
        String p=project(); var file=save(p,"sql/original.sql","SQL","SELECT 1;",0);
        String url=ROOT+"/"+p+"/files/"+file.path("id").asText()+"/path";
        String other=otherUser(true);
        mvc.perform(put(url).header("Authorization","Bearer "+other).contentType("application/json")
            .content(json(Map.of("path","sql/renamed.sql","expectedVersion",1)))).andExpect(status().isForbidden());
        mvc.perform(put(url).header("Authorization","Bearer "+token).contentType("application/json")
            .content(json(Map.of("path","sql/renamed.sql","expectedVersion",1)))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(file.path("id").asText()))
            .andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(put(url).header("Authorization","Bearer "+token).contentType("application/json")
            .content(json(Map.of("path","sql/stale.sql","expectedVersion",1)))).andExpect(status().isConflict());
        mvc.perform(put(url).header("Authorization","Bearer "+token).contentType("application/json")
            .content(json(Map.of("path","mapping/wrong.sql","expectedVersion",2)))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select content from ds_sql_file where id=?",String.class,file.path("id").asText())).isEqualTo("SELECT 1;");
    }
    @Test void uploadPreviewOriginalDownloadAndVersionedReplacement() throws Exception {
        String p=project(); byte[] raw="source,target\nstg_customer,dim_customer".getBytes(StandardCharsets.UTF_8);
        var file=new MockMultipartFile("file","customer.csv","text/csv",raw);
        var saved=data(mvc.perform(multipart(ROOT+"/"+p+"/uploads").file(file).param("kind","MAPPING").param("expectedVersion","0").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn());
        assertThat(saved.path("content").asText()).contains("stg_customer");
        mvc.perform(get(ROOT+"/"+p+"/files/"+saved.path("id").asText()+"/download").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(content().bytes(raw));
        mvc.perform(multipart(ROOT+"/"+p+"/uploads").file(file).param("kind","MAPPING").param("expectedVersion","0").header("Authorization","Bearer "+token)).andExpect(status().isConflict());
        mvc.perform(multipart(ROOT+"/"+p+"/uploads").file(file).param("kind","MAPPING").param("expectedVersion","1").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
    }
    String otherUser(boolean science) {
        var role=new SysRole(); role.setTenantId(tenant); role.setRoleCode("SQL_"+UUID.randomUUID());role.setRoleName("SQL test role");roles.saveAndFlush(role);
        for(String code: science?List.of("account:self:read","capability:data-science","workspace:api:access","workspace:zhice:access"):List.of("account:self:read","workspace:zhice:access")) {
            var rp=new SysRolePermission();rp.setTenantId(tenant);rp.setRoleId(role.getId());rp.setPermissionId(permissions.findByPermissionCode(code).orElseThrow().getId());rolePermissions.saveAndFlush(rp);
        }
        var u=new SysUser();u.setTenantId(tenant);u.setUsername("sql_"+UUID.randomUUID());u.setDisplayName("SQL other user");u.setOrgId("another-organization");u.setPasswordHash(PasswordHashCodec.encode("test-password"));users.saveAndFlush(u);
        var ur=new SysUserRole();ur.setTenantId(tenant);ur.setUserId(u.getId());ur.setRoleId(role.getId());userRoles.saveAndFlush(ur);
        return identities.login(u.getUsername(),"test-password").token();
    }
    @Test void otherUserAndOrganizationCannotReadWriteDownloadOrReferenceFilesWithSpoofedWorkspace() throws Exception {
        String p=project();var file=save(p,"sql/private.sql","SQL","private",0);String other=otherUser(true);
        for(String suffix:List.of("","/files","/files/"+file.path("id").asText(),"/files/"+file.path("id").asText()+"/versions","/files/"+file.path("id").asText()+"/download","/generations"))
            mvc.perform(get(ROOT+"/"+p+suffix).header("Authorization","Bearer "+other).header("X-Workspace-Id","zhice")).andExpect(status().isForbidden());
        mvc.perform(put(ROOT+"/"+p+"/files").header("Authorization","Bearer "+other).header("X-Workspace-Id","data-science").contentType("application/json").content(json(Map.of("path","sql/private.sql","kind","SQL","content","attacker","expectedVersion",1)))).andExpect(status().isForbidden());
        mvc.perform(put(ROOT+"/"+p+"/files/"+file.path("id").asText()+"/path").header("Authorization","Bearer "+other).header("X-Workspace-Id","data-science").contentType("application/json").content(json(Map.of("path","sql/stolen.sql","expectedVersion",1)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/data-science/sql/reference-files").header("Authorization","Bearer "+other)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        String attackerProject=data(mvc.perform(post(ROOT).header("Authorization","Bearer "+other).contentType("application/json").content("{\"name\":\"other\",\"dialect\":\"spark\"}")).andExpect(status().isOk()).andReturn()).path("id").asText();
        var ownMapping=data(mvc.perform(put(ROOT+"/"+attackerProject+"/files").header("Authorization","Bearer "+other).contentType("application/json").content("{\"path\":\"mapping/a.txt\",\"kind\":\"MAPPING\",\"content\":\"a to b\",\"expectedVersion\":0}")).andExpect(status().isOk()).andReturn());
        mvc.perform(post(ROOT+"/"+attackerProject+"/generations").header("Authorization","Bearer "+other).contentType("application/json").content(json(Map.of("prompt","Generate","dialect","spark","mappingIds",List.of(ownMapping.path("id").asText()),"referenceIds",List.of(file.path("id").asText()))))).andExpect(status().isForbidden());
    }
    @Test void persistedTenantAndLicenseRbacGateOverrideClientWorkspaceClaims() throws Exception {
        String p=project(); jdbc.update("update ds_sql_project set tenant_id='foreign-tenant' where id=?",p);
        mvc.perform(get(ROOT+"/"+p).header("Authorization","Bearer "+token).header("X-Workspace-Id","data-science")).andExpect(status().isForbidden());
        mvc.perform(get(ROOT).header("Authorization","Bearer "+otherUser(false)).header("X-Workspace-Id","data-science")).andExpect(status().isForbidden());
        when(license.workspaceEntitlement()).thenReturn(new com.chatchat.common.mcp.license.McpLicenseEntitlementPort.WorkspaceEntitlement(true,Set.of("zhice"),"VALID"));
        mvc.perform(get(ROOT).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
    }
    @Test void switchingWorkspaceRetainsProjectSessionAndTaskResultWithoutCancelling() throws Exception {
        String p=project();var m=save(p,"mapping/a.txt","MAPPING","a to b",0);var prepared=prepare(p,List.of(m.path("id").asText()),List.of());
        String task=run(p,prepared,List.of(Map.of("path","sql/a.sql","content","SELECT 1;")));
        mvc.perform(put("/api/v1/workspaces/preferences").header("Authorization","Bearer "+token).contentType("application/json").content("{\"lastWorkspaceId\":\"zhice\"}")).andExpect(status().isOk());
        mvc.perform(get(ROOT+"/"+p+"/generations/"+prepared.path("generationId").asText()).header("Authorization","Bearer "+token).header("X-Workspace-Id","zhice")).andExpect(status().isOk()).andExpect(jsonPath("$.data.task.taskId").value(task));
        assertThat(result(p,prepared).path("task").path("sessionId").asText()).isEqualTo(prepared.path("taskRequest").path("sessionId").asText());
    }
    @Test void additiveMigrationIsRepeatableAndLeavesCustomWorkspaceConfigurationIntact() throws Exception {
        String original=jdbc.queryForObject("select manifest_json from workspace_registry where id='data-science'",String.class);
        long version=jdbc.queryForObject("select version from workspace_registry where id='data-science'",Long.class);
        assertThat(mapper.readTree(original).path("pages").toString()).contains("sqlStudio");
        migration.migrate(); migration.migrate();
        assertThat(jdbc.queryForObject("select version from workspace_registry where id='data-science'",Long.class)).isEqualTo(version);
        String customized=original.replace("Python 开发、SQL 工程、数据与脚本","Customized studio");
        try {
            jdbc.update("update workspace_registry set manifest_json=? where id='data-science'",customized);
            migration.migrate();
            assertThat(jdbc.queryForObject("select manifest_json from workspace_registry where id='data-science'",String.class)).isEqualTo(customized);
        } finally { jdbc.update("update workspace_registry set manifest_json=? where id='data-science'",original); }
    }
    @Test void foreignTaskAndGenerationCannotBeBoundOrAppliedByAnotherPrincipal() throws Exception {
        String p=project();var m=save(p,"mapping/a.txt","MAPPING","a to b",0);var prepared=prepare(p,List.of(m.path("id").asText()),List.of());
        String task=run(p,prepared,List.of(Map.of("path","sql/a.sql","content","SELECT 1;")));
        String originalToken=token;token=otherUser(true);
        try {
            String other=project();var own=save(other,"mapping/a.txt","MAPPING","c to d",0);var context=prepare(other,List.of(own.path("id").asText()),List.of());
            mvc.perform(put(ROOT+"/"+other+"/generations/"+context.path("generationId").asText()+"/task").header("Authorization","Bearer "+token).contentType("application/json").content(json(Map.of("taskId",task)))).andExpect(status().isBadRequest());
            mvc.perform(get(ROOT+"/"+p+"/generations/"+prepared.path("generationId").asText()).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            mvc.perform(post(applyUrl(p,prepared)).header("Authorization","Bearer "+token).contentType("application/json").content("{\"paths\":[\"sql/a.sql\"]}")).andExpect(status().isForbidden());
        } finally {token=originalToken;}
    }
    @Test void unsafeModelOutputIsVisibleForReviewAndNeverWritten() throws Exception {
        String p=project();var m=save(p,"mapping/a.txt","MAPPING","a to b",0);var prepared=prepare(p,List.of(m.path("id").asText()),List.of());
        run(p,prepared,List.of(Map.of("path","../escape.sql","content","SELECT 1;")));
        var view=result(p,prepared);assertThat(view.path("files").size()).isZero();assertThat(view.path("rawAnswer").asText()).contains("escape.sql");assertThat(view.path("outputError").asText()).isNotBlank();
        mvc.perform(post(applyUrl(p,prepared)).header("Authorization","Bearer "+token).contentType("application/json").content("{\"paths\":[\"../escape.sql\"]}")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_file where project_id=? and kind='SQL'",Integer.class,p)).isZero();
    }
    @Test void applicationSessionProvenanceUsesRegisteredApplicationNotBrowserWorkspaceClaim() throws Exception {
        var project=data(mvc.perform(post(ROOT).header("Authorization","Bearer "+token).header("X-Workspace-Id","zhice").contentType("application/json").content("{\"name\":\"Server source\",\"dialect\":\"spark\"}")).andExpect(status().isOk()).andReturn());
        mvc.perform(get("/api/v1/workspaces/context/CONVERSATION/"+project.path("sessionId").asText()).header("Authorization","Bearer "+token).header("X-Workspace-Id","zhice"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.workspaceId").value("data-science"));
    }
    @Test void projectAndFileDeletionAreAuthorizedAndVersionChecked() throws Exception {
        String p=project(); var file=save(p,"sql/delete.sql","SQL","SELECT 1",0);
        String id=file.path("id").asText(), other=otherUser(true);
        mvc.perform(delete(ROOT+"/"+p+"/files/"+id).param("expectedVersion","1").header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        mvc.perform(delete(ROOT+"/"+p).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        mvc.perform(delete(ROOT+"/"+p+"/files/"+id).param("expectedVersion","2").header("Authorization","Bearer "+token)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_file where id=?",Integer.class,id)).isEqualTo(1);
        mvc.perform(delete(ROOT+"/"+p+"/files/"+id).param("expectedVersion","1").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_file_version where file_id=?",Integer.class,id)).isZero();
        save(p,"sql/another.sql","SQL","SELECT 2",0);
        mvc.perform(delete(ROOT+"/"+p).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_project where id=?",Integer.class,p)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_file where project_id=?",Integer.class,p)).isZero();
    }
    @Test void emptyFoldersPersistAndRequireParentWithoutBecomingFiles() throws Exception {
        String p=project();
        String url=ROOT+"/"+p+"/folders";
        mvc.perform(post(url).header("Authorization","Bearer "+token).contentType("application/json").content("{\"path\":\"scripts/daily\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization","Bearer "+token).contentType("application/json").content("{\"path\":\"scripts\"}")).andExpect(status().isOk());
        mvc.perform(post(url).header("Authorization","Bearer "+token).contentType("application/json").content("{\"path\":\"scripts/daily\"}")).andExpect(status().isOk());
        mvc.perform(get(url).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_file where project_id=?",Integer.class,p)).isZero();
        mvc.perform(post(url).header("Authorization","Bearer "+token).contentType("application/json").content("{\"path\":\"SCRIPTS\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization","Bearer "+token).contentType("application/json").content("{\"path\":\"../escape\"}")).andExpect(status().isBadRequest());
        String other=otherUser(true);
        mvc.perform(get(url).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        mvc.perform(post(url).header("Authorization","Bearer "+other).contentType("application/json").content("{\"path\":\"unauthorized\"}")).andExpect(status().isForbidden());
        save(p,"scripts/daily/job.sql","SQL","SELECT 1;",0);
        mvc.perform(delete(ROOT+"/"+p).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_folder where project_id=?",Integer.class,p)).isZero();
    }

    @Test void folderRenameMovesSubtreeAtomicallyAndRejectsStaleOrUnsafeChanges() throws Exception {
        String p=project(),url=ROOT+"/"+p+"/folders";
        var file=save(p,"legacy/nested/query.sql","SQL","SELECT 1;",0);String f=file.path("id").asText();
        var body=new SqlStudioService.FolderWrite("legacy","renamed",Map.of(f,1));
        mvc.perform(put(url).header("Authorization","Bearer "+token).contentType("application/json").content(json(new SqlStudioService.FolderWrite("legacy","renamed",Map.of())))).andExpect(status().isConflict());
        mvc.perform(put(url).header("Authorization","Bearer "+token).contentType("application/json").content(json(new SqlStudioService.FolderWrite("legacy","legacy/nested/loop",Map.of(f,1))))).andExpect(status().isBadRequest());
        mvc.perform(put(url).header("Authorization","Bearer "+otherUser(true)).contentType("application/json").content(json(body))).andExpect(status().isForbidden());
        mvc.perform(put(url).header("Authorization","Bearer "+token).contentType("application/json").content(json(body))).andExpect(status().isOk());
        mvc.perform(get(ROOT+"/"+p+"/files/"+f).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.path").value("renamed/nested/query.sql")).andExpect(jsonPath("$.data.version").value(2)).andExpect(jsonPath("$.data.content").value("SELECT 1;"));
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_file_version where file_id=?",Integer.class,f)).isEqualTo(2);
        mvc.perform(delete(url).header("Authorization","Bearer "+token).contentType("application/json").content(json(new SqlStudioService.FolderWrite("renamed/nested",null,Map.of(f,2))))).andExpect(status().isOk());
        mvc.perform(get(ROOT+"/"+p+"/files/"+f).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.path").value("renamed/query.sql"));
        save(p,"occupied.sql","SQL","SELECT 2;",0);
        mvc.perform(put(url).header("Authorization","Bearer "+token).contentType("application/json").content(json(new SqlStudioService.FolderWrite("renamed","occupied.sql/child",Map.of(f,3))))).andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization","Bearer "+token).contentType("application/json").content(json(Map.of("path","empty")))).andExpect(status().isOk());
        mvc.perform(delete(url).header("Authorization","Bearer "+token).contentType("application/json").content(json(new SqlStudioService.FolderWrite("empty",null,Map.of())))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_folder where project_id=? and path='empty'",Integer.class,p)).isZero();
    }
    @Test void folderDeletionPromotesOverlappingPathsWithoutOverwritingAndRejectsOutsideCollisions() throws Exception {
        String p=project(),url=ROOT+"/"+p+"/folders";
        var nested=save(p,"a/a/query.sql","SQL","SELECT 'nested';",0);
        var direct=save(p,"a/query.sql","SQL","SELECT 'direct';",0);
        mvc.perform(delete(url).header("Authorization","Bearer "+token).contentType("application/json").content(json(new SqlStudioService.FolderWrite("a",null,Map.of(nested.path("id").asText(),1,direct.path("id").asText(),1))))).andExpect(status().isOk());
        mvc.perform(get(ROOT+"/"+p+"/files/"+nested.path("id").asText()).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.path").value("a/query.sql")).andExpect(jsonPath("$.data.content").value("SELECT 'nested';"));
        mvc.perform(get(ROOT+"/"+p+"/files/"+direct.path("id").asText()).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.path").value("query.sql")).andExpect(jsonPath("$.data.content").value("SELECT 'direct';"));
        mvc.perform(delete(url).header("Authorization","Bearer "+token).contentType("application/json").content(json(new SqlStudioService.FolderWrite("a",null,Map.of(nested.path("id").asText(),2))))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select path from ds_sql_file where id=?",String.class,nested.path("id").asText())).isEqualTo("a/query.sql");
    }
    @Test void folderAndFileNamesCannotCollideAndLegacyFoldersRemainUsable() throws Exception {
        String p=project();
        save(p,"legacy/query.sql","SQL","SELECT 1;",0);
        mvc.perform(get(ROOT+"/"+p+"/folders").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0]").value("legacy"));
        mvc.perform(post(ROOT+"/"+p+"/folders").header("Authorization","Bearer "+token).contentType("application/json").content("{\"path\":\"legacy/query.sql\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(ROOT+"/"+p+"/folders").header("Authorization","Bearer "+token).contentType("application/json").content("{\"path\":\"folder.sql\"}")).andExpect(status().isOk());
        mvc.perform(put(ROOT+"/"+p+"/files").header("Authorization","Bearer "+token).contentType("application/json").content(json(Map.of("path","folder.sql","kind","SQL","content","SELECT 1;","expectedVersion",0)))).andExpect(status().isBadRequest());
    }
    @Test void generationAcceptsNoMappingOrExample() throws Exception {
        String p=project();
        var prepared=prepare(p,List.of(),List.of());
        assertThat(prepared.path("taskRequest").path("query").asText()).contains("Mapping documents and SQL examples are optional");
        run(p,prepared,List.of(Map.of("path","sql/simple.sql","content","SELECT 1;")));
        assertThat(result(p,prepared).path("files").size()).isEqualTo(1);
    }
    @Test void libraryMappingIsAuthorizedFrozenContextWithoutEditorFiles() throws Exception {
        String p=project();
        var document=SearchDocument.builder().docId("context-doc").fileName("mapping.csv")
            .content("source,target\na,b").lifecycleStatus("ACTIVE").build();
        when(documentLibrary.get(eq("context-doc"),any())).thenReturn(Optional.of(document));
        String body=json(Map.of("prompt","Generate SQL","dialect","spark","mappingDocumentIds",List.of("context-doc")));
        var prepared=data(mvc.perform(post(ROOT+"/"+p+"/generations").header("Authorization","Bearer "+token)
            .contentType("application/json").content(body)).andExpect(status().isOk()).andReturn());
        assertThat(prepared.path("taskRequest").path("query").asText()).contains("mapping.csv","source,target");
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_file where project_id=?",Integer.class,p)).isZero();
        when(documentLibrary.get(eq("context-doc"),any())).thenReturn(Optional.empty());
        assertThat(result(p,prepared).path("generation").path("prepared").path("taskRequest").path("query").asText()).contains("source,target");
        mvc.perform(post(ROOT+"/"+p+"/generations").header("Authorization","Bearer "+token)
            .contentType("application/json").content(body)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_generation where project_id=?",Integer.class,p)).isEqualTo(1);
    }
    @Test void libraryImportIsAtomicAndRechecksDocumentAuthorization() throws Exception {
        String p=project();
        String owner=identities.login("admin","SqlStudioTest#2026").user().id();
        var document=SearchDocument.builder().docId("allowed").fileName("mapping.csv")
            .content("source,target\na,b").lifecycleStatus("ACTIVE").build();
        when(documentLibrary.get(eq("allowed"),any())).thenAnswer(call ->
            owner.equals(call.getArgument(1,com.chatchat.knowledgebase.search.security.SearchPermissionContext.class).userId())
                ? Optional.of(document) : Optional.empty());
        when(documentLibrary.original(eq("allowed"),any())).thenReturn(Optional.of("source,target\na,b".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(post(ROOT+"/"+p+"/library-files").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json(Map.of("kind","MAPPING","documentIds",List.of("allowed","denied"))))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_file where project_id=?",Integer.class,p)).isZero();
        var imported=data(mvc.perform(post(ROOT+"/"+p+"/library-files").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json(Map.of("kind","MAPPING","documentIds",List.of("allowed"))))).andExpect(status().isOk()).andReturn());
        assertThat(imported.size()).isEqualTo(1);
        assertThat(imported.get(0).path("content").asText()).contains("source,target");
        mvc.perform(get(ROOT+"/"+p+"/files/"+imported.get(0).path("id").asText()+"/download")
            .header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(content().bytes("source,target\na,b".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(post(ROOT+"/"+p+"/library-files").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json(Map.of("kind","MAPPING","documentIds",List.of("allowed"))))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_file where project_id=?",Integer.class,p)).isEqualTo(1);
        String other=otherUser(true);
        String otherProject=data(mvc.perform(post(ROOT).header("Authorization","Bearer "+other).contentType("application/json")
            .content("{\"name\":\"other library\",\"dialect\":\"spark\"}")).andExpect(status().isOk()).andReturn()).path("id").asText();
        mvc.perform(post(ROOT+"/"+otherProject+"/library-files").header("Authorization","Bearer "+other)
            .contentType("application/json").content(json(Map.of("kind","MAPPING","documentIds",List.of("allowed")))))
            .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_file where project_id=?",Integer.class,otherProject)).isZero();
    }
    @Test void generationRejectsSimultaneousAgentAndModelOverride() throws Exception {
        String p=project(); var mapping=save(p,"mapping/a.txt","MAPPING","source target",0);
        mvc.perform(post(ROOT+"/"+p+"/generations").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json(Map.of("prompt","Generate","dialect","spark","mappingIds",List.of(mapping.path("id").asText()),
                "referenceIds",List.of(),"agentId","agent-x","modelName","model-y")))).andExpect(status().isBadRequest());
    }
    @Test void explanationProducesReadOnlyNotesAndRejectsFileChanges() throws Exception {
        String p=project();save(p,"sql/source.sql","SQL","SELECT 1;",0);
        var prepared=data(mvc.perform(post(ROOT+"/"+p+"/generations").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json(Map.of("action","explain","prompt","Explain SELECT 1", "dialect","spark")))).andExpect(status().isOk()).andReturn());
        assertThat(prepared.path("taskRequest").path("query").asText()).contains("Return files: [] only");
        run(p,prepared,List.of());
        var view=result(p,prepared);
        assertThat(view.path("files").size()).isZero();assertThat(view.path("outputError").asText()).isEmpty();
        assertThat(view.path("rawAnswer").asText()).contains("SQL analysis");
        mvc.perform(post(applyUrl(p,prepared)).header("Authorization","Bearer "+token).contentType("application/json").content("{\"paths\":[]}")).andExpect(status().isBadRequest());
        var wrong=data(mvc.perform(post(ROOT+"/"+p+"/generations").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json(Map.of("action","explain","prompt","Explain", "dialect","spark")))).andExpect(status().isOk()).andReturn());
        run(p,wrong,List.of(Map.of("path","sql/source.sql","content","SELECT 2;")));
        assertThat(result(p,wrong).path("outputError").asText()).contains("explanation");
        assertThat(result(p,wrong).path("files").size()).isZero();
    }
    @Test void repairModePersistsIntentAndReviewBaseVersions() throws Exception {
        String p=project();save(p,"sql/source.sql","SQL","SELECT broken;",0);
        var prepared=data(mvc.perform(post(ROOT+"/"+p+"/generations").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json(Map.of("action","fix","prompt","Repair", "dialect","spark")))).andExpect(status().isOk()).andReturn());
        assertThat(prepared.path("taskRequest").path("toolInput").path("sqlStudioAction").asText()).isEqualTo("fix");
        assertThat(prepared.path("taskRequest").path("query").asText()).contains("retaining unrelated code");
        assertThat(result(p,prepared).path("generation").path("baseVersions").path("sql/source.sql").asInt()).isEqualTo(1);
        mvc.perform(post(ROOT+"/"+p+"/generations").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json(Map.of("action","unknown","prompt","Repair", "dialect","spark")))).andExpect(status().isBadRequest());
    }
    @Test void generationScopeIsPersistedAndDenyBoundaryFollowsEnterpriseAuthorization() throws Exception {
        String p=project();var prepared=prepare(p,List.of(),List.of());
        assertThat(prepared.path("taskRequest").path("toolInput").path("studioCodePlan").path("planId").asText()).isEqualTo("studio-code-test");
        verify(codePlans).create(any(),any(),eq("sql"),any(),any(),any());
        assertThat(generationScopes.find(prepared.path("taskRequest").path("sessionId").asText())).hasSize(1);
        int enterprise=-1,boundary=-1;
        for(int i=0;i<toolPolicies.size();i++) {
            if(toolPolicies.get(i) instanceof com.chatchat.api.runtime.tool.EnterpriseToolRuntimePolicyProvider)enterprise=i;
            if(toolPolicies.get(i) instanceof SqlStudioGenerationToolPolicy)boundary=i;
        }
        assertThat(enterprise).isGreaterThanOrEqualTo(0);assertThat(boundary).isGreaterThan(enterprise);
        migration.migrate();assertThat(generationScopes.find(prepared.path("taskRequest").path("sessionId").asText())).hasSize(1);
    }
    @Test void metadataContextHttpPersistsAndEnforcesRealProjectOwnership() throws Exception {
        String p=project();
        when(metadataGateway.invoke(any(),any(),anyMap())).thenReturn(new SqlStudioMetadataGateway.Result("registered", Map.of(
            "items",List.of(Map.of("assetRef","authorized-asset","sourceDialect","mysql")))));
        var context=data(mvc.perform(post(ROOT+"/"+p+"/metadata/contexts").header("Authorization","Bearer "+token)
            .contentType("application/json").content(json(Map.of("purpose","ETL","targetDialect","spark","bindings",
                List.of(Map.of("assetRef","authorized-asset","role","SOURCE","database","db")))))).andExpect(status().isOk()).andReturn());
        String d=context.path("discoveryId").asText();assertThat(d).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_metadata_context where id=?",Integer.class,d)).isEqualTo(1);
        mvc.perform(get(ROOT+"/"+p+"/metadata/contexts/"+d).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        clearInvocations(metadataGateway);
        String other=otherUser(true);
        mvc.perform(get(ROOT+"/"+p+"/metadata/contexts/"+d).header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        mvc.perform(post(ROOT+"/"+p+"/metadata/contexts/"+d+"/operations").header("Authorization","Bearer "+other)
            .contentType("application/json").content(json(Map.of("bindingId",context.path("bindings").get(0).path("bindingId").asText(),"arguments",Map.of("operation","SEARCH")))))
            .andExpect(status().isForbidden());
        verifyNoInteractions(metadataGateway);
        migration.migrate();
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_metadata_context where id=?",Integer.class,d)).isEqualTo(1);
    }


    @Test void referenceScriptsAreOptInAndUnselectedSourceIsNotSentToCline() throws Exception {
        String p=project();var chosen=save(p,"sql/chosen.sql","SQL","SELECT 'chosen marker';",0);
        save(p,"sql/private.sql","SQL","SELECT 'unselected marker';",0);
        var none=prepare(p,List.of(),List.of());
        assertThat(none.path("taskRequest").path("query").asText()).doesNotContain("chosen marker","unselected marker");
        var selected=prepare(p,List.of(),List.of(chosen.path("id").asText()));
        assertThat(selected.path("taskRequest").path("query").asText()).contains("chosen marker").doesNotContain("unselected marker");
    }
    @Test void crossDatabaseSelectionIsCapturedInClineSchemaAndRevocationBlocksSubmission() throws Exception {
        String p=project();
        when(metadataGateway.invoke(any(),any(),anyMap())).thenAnswer(call->{
            Map<String,Object> args=call.getArgument(2);
            return new SqlStudioMetadataGateway.Result("registered", "DESCRIBE".equals(args.get("operation"))
                ?Map.of("status","SUCCEEDED","snapshotId","v1","doesNotExecuteSql",true,"items",List.of(Map.of("tableName","orders","database",args.get("database"),"schema","public","columns",List.of(Map.of("name","id","columnType","BIGINT")))))
                :Map.of("items",List.of(Map.of("assetRef",args.get("assetId"),"sourceDialect","mysql"))));
        });
        var selections=new ArrayList<SqlStudioMetadataService.CodingSelection>();
        for(String database:List.of("sales","warehouse")){
            var context=data(mvc.perform(post(ROOT+"/"+p+"/metadata/contexts").header("Authorization","Bearer "+token).contentType("application/json").content(json(Map.of("purpose","QUERY","targetDialect","spark","bindings",List.of(Map.of("assetRef","source","role","SOURCE","database",database)))))).andExpect(status().isOk()).andReturn());
            selections.add(new SqlStudioMetadataService.CodingSelection(context.path("discoveryId").asText(),context.path("bindings").get(0).path("bindingId").asText(),"v1",List.of(new SqlStudioMetadataService.CodingTable("orders",database,"public"))));
        }
        var prepared=data(mvc.perform(post(ROOT+"/"+p+"/generations").header("Authorization","Bearer "+token).contentType("application/json").content(json(Map.of("prompt","Join sales orders to warehouse orders","dialect","spark","codingMetadataSelection",new SqlStudioMetadataService.CodingSelection(null,null,null,null,selections))))).andExpect(status().isOk()).andReturn());
        var schema=prepared.path("taskRequest").path("toolInput").path("sqlCodingMetadata");
        assertThat(schema.path("tables").size()).isEqualTo(2);
        assertThat(prepared.path("taskRequest").path("query").asText()).contains("sales","warehouse","BIGINT");
        String generation=prepared.path("generationId").asText();
        var outcome=mock(com.chatchat.common.runtime.coding.CodingTaskOutcome.class);
        when(outcome.taskId()).thenReturn("captured");when(outcome.status()).thenReturn(com.chatchat.common.runtime.coding.CodingTaskOutcome.Status.COMPLETED);
        when(codingFixture.submit(any(),anyString(),anyString(),anyString(),nullable(String.class),anyMap())).thenReturn(outcome);
        mvc.perform(post(ROOT+"/"+p+"/generations/"+generation+"/coding").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        verify(codingFixture).submit(any(),anyString(),contains("warehouse"),eq("spark"),nullable(String.class),argThat(value->((List<?>)value.get("tables")).size()==2));
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(metadataGateway).invoke(any(),any(),anyMap());
        mvc.perform(post(ROOT+"/"+p+"/generations/"+generation+"/coding/resume").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void metadataOperationHttpRejectsExecutionAndContextSubstitution() throws Exception {
        String p=project();
        when(metadataGateway.invoke(any(),any(),anyMap())).thenReturn(new SqlStudioMetadataGateway.Result("registered", Map.of(
            "items",List.of(Map.of("assetRef","authorized-asset","sourceDialect","mysql")))));
        var context=data(mvc.perform(post(ROOT+"/"+p+"/metadata/contexts").header("Authorization","Bearer "+token)
            .contentType("application/json").content(json(Map.of("purpose","QUERY","targetDialect","mysql","bindings",
                List.of(Map.of("assetRef","authorized-asset","role","SOURCE")))))).andExpect(status().isOk()).andReturn());
        String url=ROOT+"/"+p+"/metadata/contexts/"+context.path("discoveryId").asText()+"/operations";
        clearInvocations(metadataGateway);
        for(var args:List.of(Map.of("operation","EXECUTE","sql","SELECT 1"),Map.of("operation","SEARCH","assetId","other-asset"))) {
            mvc.perform(post(url).header("Authorization","Bearer "+token).contentType("application/json").content(json(Map.of(
                "bindingId",context.path("bindings").get(0).path("bindingId").asText(),"arguments",args)))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(metadataGateway);
    }
    @Test void codePlanFailureDoesNotCreateGenerationOrRuntimeSession() throws Exception {
        String p=project();
        int generationsBefore=jdbc.queryForObject("select count(*) from ds_sql_generation where project_id=?",Integer.class,p);
        int scopesBefore=jdbc.queryForObject("select count(*) from ds_sql_generation_scope where project_id=?",Integer.class,p);
        when(codePlans.create(any(),any(),eq("sql"),any(),any(),any())).thenThrow(new IllegalStateException("代码解释计划无效，尚未开始探索"));
        mvc.perform(post(ROOT+"/"+p+"/generations").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json(Map.of("prompt","Explain current code","action","explain","dialect","spark")))).andExpect(status().is5xxServerError());
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_generation where project_id=?",Integer.class,p)).isEqualTo(generationsBefore);
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_generation_scope where project_id=?",Integer.class,p)).isEqualTo(scopesBefore);
        verifyNoInteractions(orchestration);
    }
    @Test void planOptOutCannotBypassExistingPlanContract() throws Exception {
        String p=project();
        var prepared=data(mvc.perform(post(ROOT+"/"+p+"/generations").header("Authorization","Bearer "+token).contentType("application/json")
            .content(json(Map.of("prompt","Plan this change","dialect","spark","planRequested",false)))).andExpect(status().isOk()).andReturn());
        assertThat(prepared.path("taskRequest").path("toolInput").path("studioCodePlan").path("planId").asText()).isEqualTo("studio-code-test");
        assertThat(prepared.path("taskRequest").path("query").asText()).contains("Accepted code InterpretationPlan before exploration");
    }
    @Test void studioRunsIsolateRuntimeMemoryAndRetainHistoricalResults() throws Exception {
        String p=project();
        var first=prepare(p,List.of(),List.of());
        var second=prepare(p,List.of(),List.of());
        assertThat(first.path("taskRequest").path("sessionId").asText()).isNotBlank()
            .isNotEqualTo(second.path("taskRequest").path("sessionId").asText());
        assertThat(first.path("taskRequest").path("historyWindow").asInt(-1)).isZero();
        String task=run(p,first,List.of(Map.of("path","sql/first.sql","content","SELECT 1;")));
        assertThat(result(p,first).path("task").path("taskId").asText()).isEqualTo(task);
        assertThat(result(p,first).path("files").get(0).path("path").asText()).isEqualTo("sql/first.sql");
    }
    @Test void modelOnlyGenerationsAreDeliveredToClineWithoutRuntimeTools() throws Exception {
        String p=project(); var mapping=save(p,"mapping/a.txt","MAPPING","source target",0);
        var defaultRequest=prepare(p,List.of(mapping.path("id").asText()),List.of()).path("taskRequest");
        assertThat(defaultRequest.path("mode").asText()).isEqualTo("llm_chat");
        assertThat(defaultRequest.path("toolInput").path("codingWorkspaceProfile").asText()).isEqualTo("SQL_WORKSPACE_V2");
        assertThat(defaultRequest.path("systemPrompt").asText()).contains("sql-studio.output.v1", "untrusted");
        for (String selected : List.of("modelName")) {
            var prepared=data(mvc.perform(post(ROOT+"/"+p+"/generations").header("Authorization","Bearer "+token).contentType("application/json")
                .content(json(Map.of("prompt","Generate", "dialect","spark", "mappingIds",List.of(mapping.path("id").asText()),
                    "referenceIds",List.of(),selected,"chosen")))).andExpect(status().isOk()).andReturn());
            assertThat(prepared.path("taskRequest").path("mode").asText()).isEqualTo(selected.equals("modelName") ? "llm_chat" : "agent_chat");
            assertThat(prepared.path("taskRequest").path(selected).asText()).isEqualTo("chosen");
        }
    }
    @Test void agentModeStillUsesClineAndInheritsTheAuthorizedAgentModel() throws Exception {
        var skill=mock(com.chatchat.chat.skills.model.SkillDefinition.class);
        when(skill.id()).thenReturn("chosen");when(skill.marketStatus()).thenReturn("published");when(catalog.list()).thenReturn(List.of(skill));
        when(skill.modelName()).thenReturn("configured-agent-model");when(catalog.resolve("chosen")).thenReturn(skill);
        String p=project();
        for(String action:List.of("generate","fix","optimize")) {
            var prepared=data(mvc.perform(post(ROOT+"/"+p+"/generations").header("Authorization","Bearer "+token)
                .contentType("application/json").content(json(Map.of("prompt","Modify authorized source","dialect","spark",
                    "action",action,"agentId","chosen")))).andExpect(status().isOk()).andReturn());
            assertThat(prepared.path("taskRequest").path("modelName").asText()).isEqualTo("configured-agent-model");
            assertThat(prepared.path("taskRequest").path("toolInput").path("codingWorkspaceProfile").asText()).isEqualTo("SQL_WORKSPACE_V2");
        }
        verifyNoInteractions(orchestration);
    }
    @Test void missingClineCannotSilentlySelectLegacyGeneration() throws Exception {
        String p=project();studio.setCoding(null);
        mvc.perform(post(ROOT+"/"+p+"/generations").header("Authorization","Bearer "+token)
            .contentType("application/json").content(json(Map.of("prompt","Generate source","dialect","mysql"))))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_generation where project_id=?",Integer.class,p)).isZero();
        verifyNoInteractions(orchestration,codePlans);
    }
}
