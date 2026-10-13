package com.chatchat.api.datascience.sql;

import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.service.InteractionOrchestrationService;
import com.chatchat.chat.task.core.AgentTaskLatestRepository;
import com.chatchat.common.security.PasswordHashCodec;
import com.chatchat.enterprise.entity.identity.*;
import com.chatchat.enterprise.repository.identity.*;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP authentication, object policy, H2 transactions and original scheduler.
 * Orchestration/model answers are fixtures: this is NOT real-model ETL verification. */
@SpringBootTest(properties={"chatchat.api.auth.enabled=true",
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
    @MockBean ChatModel model;
    @MockBean InteractionOrchestrationService orchestration;
    @MockBean com.chatchat.chat.interaction.service.DatabaseRuntimeSemanticPolicySource policy;
    @MockBean com.chatchat.chat.skills.catalog.SkillCatalogService catalog;
    @MockBean com.chatchat.common.mcp.license.McpLicenseEntitlementPort license;
    String token, tenant;
    @BeforeEach void setup() {
        when(license.workspaceEntitlement()).thenReturn(new com.chatchat.common.mcp.license.McpLicenseEntitlementPort.WorkspaceEntitlement(true,Set.of("zhice","data-science"),"VALID"));
        var auth=identities.login("admin","123456"); token=auth.token(); tenant=auth.user().tenantId();
    }
    JsonNode data(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data"); }
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
        String answer=json(Map.of("schemaVersion","sql-studio.output.v1","files",files));
        when(orchestration.chat(any())).thenAnswer(call -> InteractionResponse.builder()
            .conversationId(call.getArgument(0,com.chatchat.chat.interaction.model.InteractionRequest.class).getConversationId()).answer(answer).mode("agent_chat").build());
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
        var view=result(p,prepared); assertThat(view.path("files").size()).isEqualTo(1); assertThat(view.path("validation").asText()).isEqualTo("NOT_RUN");
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_file where project_id=? and kind='SQL'",Integer.class,p)).isZero();
        var saved=data(mvc.perform(post(applyUrl(p,prepared)).header("Authorization","Bearer "+token).contentType("application/json").content("{\"paths\":[\"sql/customer.sql\"]}")).andExpect(status().isOk()).andReturn()).get(0);
        assertThat(saved.path("sourceTaskId").asText()).isEqualTo(task);
        var edited=save(p,"sql/customer.sql","SQL","-- reviewed\nSELECT 1;",1); assertThat(edited.path("version").asInt()).isEqualTo(2);
        mvc.perform(get(ROOT+"/"+p+"/files/"+saved.path("id").asText()+"/versions").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get(ROOT+"/"+p+"/files/"+saved.path("id").asText()+"/download").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(content().bytes("-- reviewed\nSELECT 1;".getBytes(StandardCharsets.UTF_8)));
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
        assertThat(tasks.findById(task).orElseThrow().getSessionId()).isEqualTo(prepared.path("taskRequest").path("sessionId").asText());
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
            mvc.perform(put(ROOT+"/"+other+"/generations/"+context.path("generationId").asText()+"/task").header("Authorization","Bearer "+token).contentType("application/json").content(json(Map.of("taskId",task)))).andExpect(status().isForbidden());
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
}
