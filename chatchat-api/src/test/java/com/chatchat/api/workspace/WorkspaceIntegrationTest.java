package com.chatchat.api.workspace;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.conversation.service.ConversationService;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.service.InteractionOrchestrationService;
import com.chatchat.chat.task.core.AgentTaskLatestRepository;
import com.chatchat.common.security.PasswordHashCodec;
import com.chatchat.common.runtime.workflow.WorkflowRuntime;
import com.chatchat.enterprise.entity.identity.*;
import com.chatchat.enterprise.repository.identity.*;
import com.chatchat.enterprise.repository.workspace.*;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real authentication, API policy, JPA registry, ownership and existing task scheduler. */
@SpringBootTest(properties = {"chatchat.api.auth.enabled=true",
    "chatchat.enterprise.bootstrap.admin-password=workspace-admin-test-password",
    "spring.datasource.url=jdbc:h2:mem:workspace_integration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class WorkspaceIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired EnterpriseAdminService identities;
    @Autowired SysUserRepository users; @Autowired SysRoleRepository roles;
    @Autowired SysUserRoleRepository userRoles; @Autowired SysRolePermissionRepository rolePermissions;
    @Autowired SysPermissionRepository permissions; @Autowired ConversationService conversations;
    @Autowired WorkspaceRegistrationRepository registry; @Autowired WorkspaceResourceOriginRepository origins;
    @Autowired WorkspacePreferenceRepository preferences;
    @Autowired WorkspaceOriginService originService;
    @Autowired AgentTaskLatestRepository tasks; @Autowired ApplicationContext context;
    @MockBean ChatModel model;
    @MockBean com.chatchat.chat.interaction.service.DatabaseRuntimeSemanticPolicySource semanticPolicy;
    @MockBean InteractionOrchestrationService orchestration;
    @MockBean com.chatchat.chat.skills.catalog.SkillCatalogService catalog;
    @MockBean com.chatchat.common.mcp.license.McpLicenseEntitlementPort workspaceLicense;
    String adminToken, tenant;
    @BeforeEach void setup() {
        when(workspaceLicense.workspaceEntitlement()).thenReturn(new com.chatchat.common.mcp.license.McpLicenseEntitlementPort.WorkspaceEntitlement(
            true, Set.of("zhice", "data-science"), "VALID"));
        var auth = identities.login("admin", "workspace-admin-test-password"); adminToken = auth.token(); tenant = auth.user().tenantId();
    }
    private String userToken(boolean science) {
        var role = new SysRole(); role.setTenantId(tenant); role.setRoleCode("WS_"+UUID.randomUUID());
        role.setRoleName("Workspace test role"); roles.saveAndFlush(role);
        Set<String> codes = new HashSet<>(List.of("account:self:read", "account:menus:read", "workspace:api:access",
            "workspace:zhice:access", "workspace:chat", "workspace:chat:conversation", "platform:tasks:monitor"));
        if (science) codes.add("capability:data-science");
        for (String code : codes) {
            var binding = new SysRolePermission(); binding.setTenantId(tenant); binding.setRoleId(role.getId());
            binding.setPermissionId(permissions.findByPermissionCode(code).orElseThrow().getId()); rolePermissions.save(binding);
        }
        var user = new SysUser(); user.setTenantId(tenant); user.setUsername("ws_"+UUID.randomUUID());
        user.setDisplayName("Workspace user"); user.setOrgId("different-organization");
        user.setPasswordHash(PasswordHashCodec.encode("workspace-test-password")); users.saveAndFlush(user);
        var binding = new SysUserRole(); binding.setTenantId(tenant); binding.setRoleId(role.getId()); binding.setUserId(user.getId());
        userRoles.saveAndFlush(binding);
        return identities.login(user.getUsername(), "workspace-test-password").token();
    }
    private JsonNode data(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString()).path("data"); }
    @Test void historyIsScopedBeforeSearchAndPaginationAndLegacyHeadersDefaultToZhice() throws Exception {
        when(workspaceLicense.workspaceEntitlement()).thenReturn(new com.chatchat.common.mcp.license.McpLicenseEntitlementPort.WorkspaceEntitlement(
            true, Set.of("zhice", "data-science", "data-exploration"), "VALID"));
        String owner=identities.login("admin", "workspace-admin-test-password").user().id();
        String marker="workspace-history-"+UUID.randomUUID();
        Map<String,String> ids=new HashMap<>();
        for(String workspace:List.of("zhice","data-exploration")) {
            var created=data(mvc.perform(post("/api/v1/conversations").header("Authorization","Bearer "+adminToken)
                .header("X-Workspace-Id",workspace).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("userId",owner,"title",marker+workspace))))
                .andExpect(status().isOk()).andReturn());
            ids.put(workspace,created.path("id").asText());
        }
        var legacy=conversations.createConversation(tenant,owner,marker+"legacy");
        conversations.createConversation(tenant,"another-owner",marker+"private");
        conversations.createConversation("another-tenant",owner,marker+"private");
        String endpoint="/api/v1/conversations/user/"+owner;
        for(String scope:List.of("","zhice","data-exploration")) {
            var request=get(endpoint+"/summaries/page").header("Authorization","Bearer "+adminToken)
                .param("keyword",marker).param("pageSize","1");
            if(!scope.isEmpty())request.header("X-Workspace-Id",scope);
            var page=data(mvc.perform(request).andExpect(status().isOk()).andReturn());
            boolean exploration=scope.equals("data-exploration");
            assertThat(page.path("total").asInt()).isEqualTo(exploration?1:2);
            assertThat(page.path("totalPages").asInt()).isEqualTo(exploration?1:2);
            assertThat(page.path("items").size()).isEqualTo(1);
            if(exploration)assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(ids.get(scope));
            else assertThat(page.path("items").get(0).path("id").asText()).isIn(ids.get("zhice"),legacy.getId());
        }
        for(String suffix:List.of("","/summaries")) {
            var history=data(mvc.perform(get(endpoint+suffix).header("Authorization","Bearer "+adminToken))
                .andExpect(status().isOk()).andReturn());
            assertThat(history.toString()).doesNotContain(ids.get("data-exploration"));
            assertThat(history.toString()).contains(ids.get("zhice"),legacy.getId());
        }
        var legacyApi=data(mvc.perform(get("/api/v1/data/history/"+owner).header("Authorization","Bearer "+adminToken)
            .header("X-Workspace-Id","data-exploration").param("keyword",marker))
            .andExpect(status().isOk()).andReturn());
        assertThat(legacyApi.size()).isEqualTo(1);
        assertThat(legacyApi.get(0).path("id").asText()).isEqualTo(ids.get("data-exploration"));
        mvc.perform(get(endpoint+"/summaries/page").header("Authorization","Bearer "+adminToken)
            .header("X-Workspace-Id","unknown-workspace")).andExpect(status().isNotFound());
        String restricted=userToken(false);
        String restrictedOwner=data(mvc.perform(get("/api/v1/enterprise/auth/me").header("Authorization","Bearer "+restricted))
            .andExpect(status().isOk()).andReturn()).path("id").asText();
        mvc.perform(get("/api/v1/conversations/user/"+restrictedOwner+"/summaries/page").header("Authorization","Bearer "+restricted)
            .header("X-Workspace-Id","data-exploration")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/data/history/"+owner).header("Authorization","Bearer "+adminToken)
            .header("X-Workspace-Id","data-exploration")).andExpect(status().isOk());
        assertThat(conversations.getConversation(tenant,ids.get("data-exploration"))).isEmpty();
        assertThat(conversations.getConversation(tenant,ids.get("zhice"))).isPresent();
        assertThat(conversations.getConversation(tenant,legacy.getId())).isPresent();
    }
    @Test void applicationSessionProvenanceIsPersistedBeforeExecutionAndCannotBeReclassified() {
        when(workspaceLicense.workspaceEntitlement()).thenReturn(new com.chatchat.common.mcp.license.McpLicenseEntitlementPort.WorkspaceEntitlement(
            true, Set.of("zhice", "data-exploration"), "VALID"));
        String owner=identities.login("admin", "workspace-admin-test-password").user().id();
        var request=new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,owner);
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USERNAME,"admin");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,tenant);
        request.addHeader("X-Workspace-Id","zhice");
        String session="application-session-"+UUID.randomUUID();
        assertThat(originService.prepareForWorkspace(request,"data-exploration",session)).isEqualTo(session);
        assertThat(originService.prepareForWorkspace(request,"data-exploration",session)).isEqualTo(session);
        assertThat(origins.findByTenantIdAndResourceTypeAndResourceId(tenant,"CONVERSATION",session).orElseThrow().getWorkspaceId())
            .isEqualTo("data-exploration");
        assertThat(conversations.getConversation(tenant,session).orElseThrow().getUserId()).isEqualTo(owner);
        assertThatThrownBy(()->originService.prepareForWorkspace(request,"zhice",session)).isInstanceOf(SecurityException.class);
    }
    @Test void loginAndDefaultLegacyMenusRemainAvailable() throws Exception {
        mvc.perform(get("/api/v1/workspaces/catalog")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].workspaceId").value(org.hamcrest.Matchers.containsInAnyOrder(
                "zhice", "data-science", "data-exploration", "business")));
        mvc.perform(post("/api/v1/enterprise/auth/login").contentType("application/json")
            .content("{\"username\":\"admin\",\"password\":\"workspace-admin-test-password\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").exists());
        var items = data(mvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer "+adminToken))
            .andExpect(status().isOk()).andReturn());
        assertThat(items.size()).isEqualTo(4);
        assertThat(registry.findById("zhice").orElseThrow().isDefaultWorkspace()).isTrue();
        mvc.perform(get("/api/v1/workspaces/zhice/manifest").header("Authorization", "Bearer "+adminToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.manifest.entryPage").value("chat"))
            .andExpect(jsonPath("$.data.menus").isNotEmpty());
        var zhice = data(mvc.perform(get("/api/v1/workspaces/zhice/manifest").header("Authorization", "Bearer "+adminToken))
            .andExpect(status().isOk()).andReturn()).path("manifest");
        assertThat(zhice.path("pages").toString()).contains("dataScienceSkills").doesNotContain("dataScienceEnvironment");
        assertThat(zhice.path("pages").toString()).contains("workspaces");
        var workspaceMenus = data(mvc.perform(get("/api/v1/workspaces/zhice/manifest").header("Authorization", "Bearer "+adminToken))
            .andExpect(status().isOk()).andReturn()).path("menus");
        var platform = java.util.stream.StreamSupport.stream(workspaceMenus.spliterator(), false)
            .filter(menu -> "platform".equals(menu.path("id").asText())).findFirst().orElseThrow();
        assertThat(platform.path("children").toString()).contains("workspaces");
        var science = data(mvc.perform(get("/api/v1/workspaces/data-science/manifest").header("Authorization", "Bearer "+adminToken))
            .andExpect(status().isOk()).andReturn()).path("manifest");
        assertThat(science.path("pages").toString()).doesNotContain("dataScienceSkills");
    }
    @Test void normalUserCanOnlySeeAndSelectAuthorizedWorkspace() throws Exception {
        String token = userToken(false);
        mvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/v1/workspaces/data-science/manifest").header("Authorization", "Bearer "+token))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/workspaces/preferences").header("Authorization", "Bearer "+token).contentType("application/json")
            .content("{\"lastWorkspaceId\":\"data-science\"}")) .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/enterprise/workspaces").header("Authorization", "Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void licenseDenialRemovesWorkspaceEvenForAnRbacAuthorizedAdministrator() throws Exception {
        when(workspaceLicense.workspaceEntitlement()).thenReturn(new com.chatchat.common.mcp.license.McpLicenseEntitlementPort.WorkspaceEntitlement(
            true, Set.of("zhice"), "VALID"));
        mvc.perform(get("/api/v1/workspaces/catalog")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].workspaceId").value(org.hamcrest.Matchers.containsInAnyOrder("zhice", "business")));
        mvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer "+adminToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/v1/workspaces/data-science/manifest").header("Authorization", "Bearer "+adminToken))
            .andExpect(status().isForbidden());
    }
    @Test void businessHistoryUsesAnExplicitGrantAndRevocationSurvivesRemovingTheHeader() throws Exception {
        String token = userToken(false);
        String user = identities.resolveSessionByToken(token).orElseThrow().id();
        String role = userRoles.findByUserId(user).get(0).getRoleId();
        for (String code : List.of("workspace:business:access", "workspace:chat:interact", "workspace:business:knowledge")) {
            var binding = new SysRolePermission();
            binding.setRoleId(role);
            binding.setTenantId(tenant);
            binding.setPermissionId(permissions.findByPermissionCode(code).orElseThrow().getId());
            rolePermissions.saveAndFlush(binding);
        }
        String authorization = "Bearer " + token;
        mvc.perform(get("/api/v1/workspaces/business/manifest").header("Authorization", authorization))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.manifest.entryPage").value("businessHome"));
        mvc.perform(get("/api/v1/search").header("Authorization", authorization).header("X-Workspace-Id", "business"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/search/documents").header("Authorization", authorization).header("X-Workspace-Id", "business")
            .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        String id = "business-test-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/data/history").header("Authorization", authorization).header("X-Workspace-Id", "business")
            .contentType("application/json").content(mapper.writeValueAsString(Map.of("userId", user, "historyId", id,
                "question", "Business question", "status", "running"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/api/v1/conversations/" + id).header("Authorization", authorization))
            .andExpect(status().isOk());
        String permission = permissions.findByPermissionCode("workspace:business:access").orElseThrow().getId();
        rolePermissions.deleteAll(rolePermissions.findByRoleId(role).stream()
            .filter(binding -> permission.equals(binding.getPermissionId())).toList());
        mvc.perform(get("/api/v1/conversations/" + id).header("Authorization", authorization))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/interactions/resources/skills").header("Authorization", authorization)
            .header("X-Workspace-Id", "business")).andExpect(status().isForbidden());
    }
    @Test void businessRoleNotesArePublishedPerPanelAndRestrictedToTenantRoleMembers() throws Exception {
        String reader = userToken(false);
        String other = userToken(false);
        String readerRole = null;
        for (String token : List.of(reader, other)) {
            var user = identities.resolveSessionByToken(token).orElseThrow();
            String role = userRoles.findByUserId(user.id()).get(0).getRoleId();
            if (token.equals(reader)) readerRole = role;
            for (String code : List.of("workspace:business:access", "workspace:chat:interact")) {
                var grant = new SysRolePermission();
                grant.setTenantId(tenant);
                grant.setRoleId(role);
                grant.setPermissionId(permissions.findByPermissionCode(code).orElseThrow().getId());
                rolePermissions.saveAndFlush(grant);
            }
        }
        var foreignRole = new SysRole();
        foreignRole.setTenantId("unrelated-workspace-tenant");
        foreignRole.setRoleCode("WS_" + UUID.randomUUID());
        foreignRole.setRoleName("Foreign role");
        roles.saveAndFlush(foreignRole);
        String api = "/api/v1/workspaces/business/role-notes/";
        String admin = "/api/v1/enterprise/workspaces/business/role-notes/";
        String guide = "{\"title\":\"Guide for reader\",\"body\":\"Validate source documents\"}";
        String outcome = "{\"title\":\"Outcome for reader\",\"body\":\"Attach evidence\"}";
        mvc.perform(put(admin + "guides/" + readerRole).header("Authorization", "Bearer " + reader)
            .contentType("application/json").content(guide)).andExpect(status().isForbidden());
        mvc.perform(put(admin + "guides/" + foreignRole.getId()).header("Authorization", "Bearer " + adminToken)
            .contentType("application/json").content(guide)).andExpect(status().isNotFound());
        mvc.perform(put(admin + "skills/" + readerRole).header("Authorization", "Bearer " + adminToken)
            .contentType("application/json").content(guide)).andExpect(status().isNotFound());
        for (String panel : List.of("guides", "outcomes")) {
            String payload = "guides".equals(panel) ? guide : outcome;
            mvc.perform(put(admin + panel + "/" + readerRole).header("Authorization", "Bearer " + adminToken)
                .contentType("application/json").content(payload)).andExpect(status().isOk());
            mvc.perform(get(api + panel).header("Authorization", "Bearer " + other))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.notes").isEmpty())
                .andExpect(jsonPath("$.data.roles").isEmpty()).andExpect(jsonPath("$.data.canPublish").value(false));
        }
        mvc.perform(get(api + "guides").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.canPublish").value(true))
            .andExpect(jsonPath("$.data.roles[?(@.name == 'Foreign role')]").isEmpty());
        mvc.perform(put(admin + "guides/" + readerRole).header("Authorization", "Bearer " + adminToken)
            .contentType("application/json").content(guide.replace("Validate", "Verify"))).andExpect(status().isOk());
        mvc.perform(get(api + "guides").header("Authorization", "Bearer " + reader))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.notes.length()").value(1))
            .andExpect(jsonPath("$.data.notes[0].body").value("Verify source documents"));
        mvc.perform(delete(admin + "guides/" + readerRole).header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
        mvc.perform(get(api + "guides").header("Authorization", "Bearer " + reader))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.notes").isEmpty());
        mvc.perform(get(api + "outcomes").header("Authorization", "Bearer " + reader))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.notes[0].title").value("Outcome for reader"));
    }
    @Test void businessUsersCanSaveAndPublishTheirOwnNotes() throws Exception {
        String author = userToken(false), reader = userToken(false), outsider = userToken(false);
        String authorId = identities.resolveSessionByToken(author).orElseThrow().id();
        String role = userRoles.findByUserId(authorId).get(0).getRoleId();
        for (String token : List.of(author, reader, outsider)) {
            String userId = identities.resolveSessionByToken(token).orElseThrow().id();
            String userRole = userRoles.findByUserId(userId).get(0).getRoleId();
            for (String code : List.of("workspace:business:access", "workspace:chat:interact")) {
                var grant = new SysRolePermission();
                grant.setTenantId(tenant);
                grant.setRoleId(userRole);
                grant.setPermissionId(permissions.findByPermissionCode(code).orElseThrow().getId());
                rolePermissions.saveAndFlush(grant);
            }
        }
        var shared = new SysUserRole();
        shared.setTenantId(tenant);
        shared.setRoleId(role);
        shared.setUserId(identities.resolveSessionByToken(reader).orElseThrow().id());
        userRoles.saveAndFlush(shared);

        for (String panel : List.of("guides", "outcomes")) {
            String api = "/api/v1/workspaces/business/notes/" + panel;
            String draft = mapper.writeValueAsString(Map.of("title", "Weekly note", "body", "**Initial**"));
            String id = data(mvc.perform(post(api).header("Authorization", "Bearer " + author)
                .contentType("application/json").content(draft)).andExpect(status().isOk()).andReturn()).asText();
            mvc.perform(get(api).header("Authorization", "Bearer " + reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.notes").isEmpty());
            String otherPanel = "guides".equals(panel) ? "outcomes" : "guides";
            mvc.perform(get("/api/v1/workspaces/business/notes/" + otherPanel).header("Authorization", "Bearer " + author))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.notes").isEmpty());
            String tooLong = mapper.writeValueAsString(Map.of("title", "Weekly note", "body", "a".repeat(20001)));
            mvc.perform(post(api).header("Authorization", "Bearer " + author)
                .contentType("application/json").content(tooLong)).andExpect(status().isBadRequest());
            String publication = mapper.writeValueAsString(Map.of("title", "Weekly note", "body", "**Published**",
                "publishTo", role, "revision", 0));
            mvc.perform(put(api + "/" + id).header("Authorization", "Bearer " + author)
                .contentType("application/json").content(publication)).andExpect(status().isOk());
            mvc.perform(post(api).header("Authorization", "Bearer " + outsider)
                .contentType("application/json").content(publication)).andExpect(status().isForbidden());
            String nextDraft = mapper.writeValueAsString(Map.of("title", "Next draft", "body", "Private edits", "revision", 1));
            mvc.perform(put(api + "/" + id).header("Authorization", "Bearer " + author)
                .contentType("application/json").content(nextDraft)).andExpect(status().isOk());
            mvc.perform(put(api + "/" + id).header("Authorization", "Bearer " + author)
                .contentType("application/json").content(publication)).andExpect(status().isConflict());
            mvc.perform(get(api).header("Authorization", "Bearer " + author)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes[0].title").value("Next draft"));
            mvc.perform(get(api).header("Authorization", "Bearer " + reader)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes[0].body").value("**Published**"))
                .andExpect(jsonPath("$.data.notes[0].owned").value(false));
            mvc.perform(get(api).header("Authorization", "Bearer " + outsider))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.notes").isEmpty());
            String deletion = api + "/" + id + "?revision=2";
            mvc.perform(delete(deletion).header("Authorization", "Bearer " + reader)).andExpect(status().isConflict());
            mvc.perform(delete(deletion).header("Authorization", "Bearer " + author)).andExpect(status().isOk());
            mvc.perform(get(api).header("Authorization", "Bearer " + reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.notes").isEmpty());
        }
    }
    @Test void authorizedUserSwitchesAndRestoresPreferencesUsingOneToken() throws Exception {
        String token = userToken(true);
        for (String id : List.of("zhice", "data-science", "zhice")) {
            mvc.perform(get("/api/v1/workspaces/"+id+"/manifest").header("Authorization", "Bearer "+token)).andExpect(status().isOk());
            mvc.perform(put("/api/v1/workspaces/preferences").header("Authorization", "Bearer "+token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("lastWorkspaceId", id)))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/workspaces/preferences").header("Authorization", "Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.lastWorkspaceId").value("zhice"));
        mvc.perform(get("/api/v1/enterprise/auth/me").header("Authorization", "Bearer "+token)).andExpect(status().isOk());
    }
    @Test void loginDefaultAndPersonalChoiceArePersistedAndIsolated() throws Exception {
        String token = userToken(true);
        String owner = data(mvc.perform(get("/api/v1/enterprise/auth/me").header("Authorization", "Bearer "+token))
            .andExpect(status().isOk()).andReturn()).path("id").asText();
        mvc.perform(get("/api/v1/workspaces/preferences").header("Authorization", "Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.defaultWorkspaceId").value("zhice"));
        mvc.perform(put("/api/v1/workspaces/preferences").header("Authorization", "Bearer "+token).contentType("application/json")
            .content("{\"lastWorkspaceId\":\"data-science\"}")).andExpect(status().isOk());
        var stored = preferences.findByTenantIdAndUserId(tenant, owner).orElseThrow();
        assertThat(stored.getDefaultWorkspaceId()).isNull();
        assertThat(stored.getLastWorkspaceId()).isEqualTo("data-science");
        mvc.perform(put("/api/v1/workspaces/preferences").header("Authorization", "Bearer "+token).contentType("application/json")
            .content("{\"defaultWorkspaceId\":\"data-science\"}")).andExpect(status().isOk());
        var user = users.findById(owner).orElseThrow();
        String newToken = identities.login(user.getUsername(), "workspace-test-password").token();
        mvc.perform(get("/api/v1/workspaces/preferences").header("Authorization", "Bearer "+newToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.defaultWorkspaceId").value("data-science"));
        assertThat(preferences.findByTenantIdAndUserId(tenant, owner).orElseThrow().getDefaultWorkspaceId()).isEqualTo("data-science");
        mvc.perform(get("/api/v1/workspaces/preferences").header("Authorization", "Bearer "+userToken(true)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.defaultWorkspaceId").value("zhice"));
    }
    @Test void platformDefaultChangesForInheritedAndLegacyPreferencesOnly() throws Exception {
        String inherited = userToken(true);
        String personal = userToken(true);
        String inheritedOwner = data(mvc.perform(get("/api/v1/enterprise/auth/me").header("Authorization", "Bearer "+inherited))
            .andExpect(status().isOk()).andReturn()).path("id").asText();
        var legacy = new com.chatchat.enterprise.entity.workspace.WorkspacePreference();
        legacy.setId(UUID.randomUUID().toString());
        legacy.setTenantId(tenant);
        legacy.setUserId(inheritedOwner);
        legacy.setDefaultWorkspaceId("zhice");
        legacy.setLastWorkspaceId("zhice");
        preferences.saveAndFlush(legacy);
        mvc.perform(put("/api/v1/workspaces/preferences").header("Authorization", "Bearer "+personal)
            .contentType("application/json").content("{\"defaultWorkspaceId\":\"zhice\"}"))
            .andExpect(status().isOk());
        String defaultEndpoint = "/api/v1/enterprise/workspaces/default";
        try {
            mvc.perform(put(defaultEndpoint).header("Authorization", "Bearer "+adminToken)
                .contentType("application/json").content("{\"workspaceId\":\"data-science\"}"))
                .andExpect(status().isOk());
            mvc.perform(get("/api/v1/workspaces/preferences").header("Authorization", "Bearer "+inherited))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.defaultWorkspaceId").value("data-science"))
                .andExpect(jsonPath("$.data.lastWorkspaceId").value("zhice"));
            mvc.perform(get("/api/v1/workspaces/preferences").header("Authorization", "Bearer "+personal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.defaultWorkspaceId").value("zhice"));
        } finally {
            mvc.perform(put(defaultEndpoint).header("Authorization", "Bearer "+adminToken)
                .contentType("application/json").content("{\"workspaceId\":\"zhice\"}"))
                .andExpect(status().isOk());
        }
    }

    @Test void registryUpdatesAreVersionedAndDisablingNeverDeletesHistory() throws Exception {
        var manifest = mapper.readValue(getClass().getResourceAsStream("/workspace/zhice.json"), WorkspaceManifest.class);
        var custom = new WorkspaceManifest(manifest.schemaVersion(), 1, "review-space", manifest.appVersion(), "Review",
            manifest.icon(), manifest.description(), manifest.entryPage(), manifest.layoutType(), manifest.pages(),
            manifest.requiredCapabilities(), manifest.optionalCapabilities(), manifest.defaultSkillIds());
        String path = "/api/v1/enterprise/workspaces/review-space";
        var created = data(mvc.perform(put(path).header("Authorization", "Bearer "+adminToken).contentType("application/json")
            .content(mapper.writeValueAsString(new WorkspaceService.Update(custom, "enabled", "workspace:zhice:access", null))))
            .andExpect(status().isOk()).andReturn());
        mvc.perform(put(path).header("Authorization", "Bearer "+adminToken).contentType("application/json")
            .content(mapper.writeValueAsString(new WorkspaceService.Update(custom, "disabled", "workspace:zhice:access", -1L))))
            .andExpect(status().isConflict());
        long count = conversations.listUserConversations(tenant, "admin").size();
        var next = new WorkspaceManifest(custom.schemaVersion(), 2, custom.workspaceId(), custom.appVersion(), custom.name(), custom.icon(),
            custom.description(), custom.entryPage(), custom.layoutType(), custom.pages(), custom.requiredCapabilities(), custom.optionalCapabilities(), custom.defaultSkillIds());
        mvc.perform(put(path).header("Authorization", "Bearer "+adminToken).contentType("application/json")
            .content(mapper.writeValueAsString(new WorkspaceService.Update(next, "disabled", "workspace:zhice:access", created.path("version").asLong()))))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/workspaces/review-space/manifest").header("Authorization", "Bearer "+adminToken)).andExpect(status().isForbidden());
        assertThat(conversations.listUserConversations(tenant, "admin")).hasSize((int)count);
        registry.deleteById("review-space");
    }
    @Test void foreignSessionAndHistoryCannotBeReadEvenWithAnotherWorkspaceHeader() throws Exception {
        var victim = conversations.createConversation(tenant, "victim-user", "Private");
        String token = userToken(true);
        mvc.perform(get("/api/v1/conversations/"+victim.getId()).header("Authorization", "Bearer "+token)
            .header("X-Workspace-Id", "data-science")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/data/history/victim-user/"+victim.getId()).header("Authorization", "Bearer "+token))
            .andExpect(status().isForbidden());
        assertThat(conversations.getConversation(tenant, victim.getId())).isPresent();
    }
    @Test void taskListsAndPagesExcludeOtherOwnersAndIgnoreSpoofedTenant() throws Exception {
        String token = userToken(true);
        String owner = data(mvc.perform(get("/api/v1/enterprise/auth/me").header("Authorization", "Bearer "+token))
            .andExpect(status().isOk()).andReturn()).path("id").asText();
        var owned = new com.chatchat.chat.task.core.AgentTaskLatestEntity();
        owned.setTenantId(tenant); owned.setUserId(owner); owned.setStatus("SUCCESS"); owned.setQuestion("owned task");
        var foreign = new com.chatchat.chat.task.core.AgentTaskLatestEntity();
        foreign.setTenantId(tenant); foreign.setUserId("foreign-user"); foreign.setStatus("SUCCESS"); foreign.setQuestion("foreign task");
        tasks.saveAllAndFlush(List.of(owned, foreign));
        try {
            var list = data(mvc.perform(get("/api/v1/agent/tasks").param("tenantId", "spoofed-tenant")
                .header("Authorization", "Bearer "+token).header("X-Workspace-Id", "data-science"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andReturn());
            assertThat(list.size()).isEqualTo(1); assertThat(list.get(0).path("taskId").asText()).isEqualTo(owned.getTaskId());
            mvc.perform(get("/api/v1/agent/tasks/page").param("tenantId", tenant).header("Authorization", "Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        } finally { tasks.deleteAll(List.of(owned, foreign)); }
    }
    @Test void newConversationHasImmutableSourceAndLegacyConversationRemainsReadable() throws Exception {
        String id = data(mvc.perform(post("/api/v1/conversations").header("Authorization", "Bearer "+adminToken)
            .header("X-Workspace-Id", "data-science").contentType("application/json").content("{\"title\":\"Scoped\"}"))
            .andExpect(status().isOk()).andReturn()).path("id").asText();
        mvc.perform(get("/api/v1/workspaces/context/CONVERSATION/"+id).header("Authorization", "Bearer "+adminToken)
            .header("X-Workspace-Id", "zhice")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workspaceId").value("data-science"));
        var legacy = conversations.createConversation(tenant, "admin", "Legacy");
        mvc.perform(get("/api/v1/conversations/"+legacy.getId()).header("Authorization", "Bearer "+adminToken)).andExpect(status().isOk());
        assertThat(origins.findByTenantIdAndResourceTypeAndResourceId(tenant, "CONVERSATION", legacy.getId())).isEmpty();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, identities.login("admin", "workspace-admin-test-password").user().id());
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USERNAME, "admin");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, tenant);
        request.addHeader("X-Workspace-Id", "stale-or-forged-space");
        assertThat(originService.prepare(request, id)).isEqualTo(id);
        assertThat(origins.findByTenantIdAndResourceTypeAndResourceId(tenant, "CONVERSATION", id).orElseThrow().getWorkspaceId())
            .isEqualTo("data-science");
    }
    @Test void backgroundTaskContinuesWhileWorkspaceIsSwitchedAndCoreIsShared() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        when(orchestration.chat(any())).thenAnswer(invocation -> {
            started.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            var request = invocation.getArgument(0, com.chatchat.chat.interaction.model.InteractionRequest.class);
            return InteractionResponse.builder().conversationId(request.getConversationId()).answer("completed after switch").mode("llm_chat").build();
        });
        String id = data(mvc.perform(post("/api/v1/agent/tasks").header("Authorization", "Bearer "+adminToken)
            .header("X-Workspace-Id", "zhice").contentType("application/json")
            .content("{\"query\":\"continue while switching\",\"mode\":\"llm_chat\"}"))
            .andExpect(status().isOk()).andReturn()).path("taskId").asText();
        assertThat(id).isNotBlank();
        try {
            assertThat(started.await(8, TimeUnit.SECONDS)).isTrue();
            mvc.perform(get("/api/v1/workspaces/data-science/manifest").header("Authorization", "Bearer "+adminToken)).andExpect(status().isOk());
            mvc.perform(put("/api/v1/workspaces/preferences").header("Authorization", "Bearer "+adminToken).contentType("application/json")
                .content("{\"lastWorkspaceId\":\"data-science\"}")).andExpect(status().isOk());
            assertThat(tasks.findById(id).orElseThrow().getStatus()).isNotIn("CANCELLED", "KILLED", "FAILED");
            assertThat(context.getBeansOfType(WorkflowRuntime.class)).hasSize(1);
        } finally { release.countDown(); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline && !"SUCCESS".equals(tasks.findById(id).orElseThrow().getStatus())) Thread.sleep(50);
        assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo("SUCCESS");
        mvc.perform(get("/api/v1/workspaces/context/TASK/"+id).header("Authorization", "Bearer "+adminToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.workspaceId").value("zhice"));
    }
}
