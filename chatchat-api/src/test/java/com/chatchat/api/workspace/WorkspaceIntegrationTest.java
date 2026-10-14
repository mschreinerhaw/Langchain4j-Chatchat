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
        var auth = identities.login("admin", "123456"); adminToken = auth.token(); tenant = auth.user().tenantId();
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
    @Test void loginAndDefaultLegacyMenusRemainAvailable() throws Exception {
        mvc.perform(get("/api/v1/workspaces/catalog")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(post("/api/v1/enterprise/auth/login").contentType("application/json")
            .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").exists());
        var items = data(mvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer "+adminToken))
            .andExpect(status().isOk()).andReturn());
        assertThat(items.size()).isEqualTo(2);
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
            .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer "+adminToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/v1/workspaces/data-science/manifest").header("Authorization", "Bearer "+adminToken))
            .andExpect(status().isForbidden());
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
        assertThat(stored.getDefaultWorkspaceId()).isEqualTo("zhice");
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
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, identities.login("admin", "123456").user().id());
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
