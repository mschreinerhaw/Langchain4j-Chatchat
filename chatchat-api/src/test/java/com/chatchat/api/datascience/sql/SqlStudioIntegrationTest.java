package com.chatchat.api.datascience.sql;

import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.api.controller.agent.DocumentLibraryReadPort;
import com.chatchat.knowledgebase.search.model.SearchDocument;
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
    @MockBean DocumentLibraryReadPort documentLibrary;
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
        String owner=identities.login("admin","123456").user().id();
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
    @Test void modelOnlyGenerationsUseNativeToolFreeRuntimeEntry() throws Exception {
        String p=project(); var mapping=save(p,"mapping/a.txt","MAPPING","source target",0);
        var defaultRequest=prepare(p,List.of(mapping.path("id").asText()),List.of()).path("taskRequest");
        assertThat(defaultRequest.path("mode").asText()).isEqualTo("llm_chat");
        assertThat(defaultRequest.path("systemPrompt").asText()).contains("sql-studio.output.v1", "untrusted");
        for (String selected : List.of("modelName", "agentId")) {
            var prepared=data(mvc.perform(post(ROOT+"/"+p+"/generations").header("Authorization","Bearer "+token).contentType("application/json")
                .content(json(Map.of("prompt","Generate", "dialect","spark", "mappingIds",List.of(mapping.path("id").asText()),
                    "referenceIds",List.of(),selected,"chosen")))).andExpect(status().isOk()).andReturn());
            assertThat(prepared.path("taskRequest").path("mode").asText()).isEqualTo(selected.equals("modelName") ? "llm_chat" : "agent_chat");
            assertThat(prepared.path("taskRequest").path(selected).asText()).isEqualTo("chosen");
        }
    }
}
