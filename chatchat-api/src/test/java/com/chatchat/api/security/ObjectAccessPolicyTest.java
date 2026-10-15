package com.chatchat.api.security;

import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.api.exception.GlobalExceptionHandler;
import com.chatchat.api.controller.chat.ConversationController;
import com.chatchat.api.controller.datascience.DataQueryController;
import com.chatchat.api.workspace.WorkspaceOriginService;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ObjectAccessPolicyTest {
    JdbcTemplate jdbc; ObjectAccessService access; EnterpriseAdminService identities; MockMvc mvc;
    WorkspaceOriginService origins;
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", ""));
        jdbc.execute("create table chat_session(session_id varchar primary key, tenant_id varchar, user_id varchar)");
        jdbc.execute("create table sys_user(id varchar primary key)");
        jdbc.execute("create table agent_task_latest(task_id varchar primary key, tenant_id varchar, user_id varchar, idempotency_key varchar, session_id varchar)");
        jdbc.execute("create table ui_artifact(artifact_id varchar primary key, task_id varchar, tenant_id varchar)");
        for (String id : new String[]{"owned", "foreign", "other-tenant"}) {
            String tenant = id.equals("other-tenant") ? "t2" : "t1";
            String owner = id.equals("foreign") ? "u2" : "u1";
            jdbc.update("insert into chat_session values(?,?,?)", id, tenant, owner);
            jdbc.update("insert into agent_task_latest(task_id, tenant_id, user_id) values(?,?,?)", id, tenant, owner);
            jdbc.update("insert into ui_artifact values(?,?,?)", id, id, tenant);
        }
        identities = mock(EnterpriseAdminService.class);
        access = new ObjectAccessService(jdbc, identities);
        origins=mock(WorkspaceOriginService.class);
        when(origins.prepare(any(),anyString())).thenAnswer(call->call.getArgument(1));
        mvc = MockMvcBuilders.standaloneSetup(new Surface())
            .addInterceptors(new ObjectAccessInterceptor(access, mock(AgentRuntime.class)))
            .setControllerAdvice(new GlobalExceptionHandler(), new ObjectRequestAdvice(access, origins))
            .build();
    }
    private MockHttpServletRequest identity(MockHttpServletRequest request) {
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "u1");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USERNAME, "alice");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, "t1");
        return request;
    }
    @Test void legacyUsernameCannotImpersonateAnotherStablePrincipal() {
        jdbc.update("insert into sys_user values (?)", "alice");
        var request = identity(new MockHttpServletRequest());
        assertThat(access.ownerAliases(request)).containsExactly("u1");
        assertThatThrownBy(() -> access.requireOwner(request, "t1", "alice", false))
            .isInstanceOf(com.chatchat.api.exception.RuntimeScopeAccessDeniedException.class);
    }
    @Test void resumingAndReusingAnotherUsersIdempotencyKeyAreRejectedBeforeDispatch() {
        var request = identity(new MockHttpServletRequest());
        jdbc.update("update agent_task_latest set idempotency_key='private-key' where task_id='foreign'");
        assertThatThrownBy(() -> access.requireTaskSubmission(request, "foreign", null))
            .isInstanceOf(com.chatchat.api.exception.RuntimeScopeAccessDeniedException.class);
        assertThatThrownBy(() -> access.requireTaskSubmission(request, null, "private-key"))
            .isInstanceOf(com.chatchat.api.exception.RuntimeScopeAccessDeniedException.class);
        assertThatCode(() -> access.requireTaskSubmission(request, "owned", "new-key")).doesNotThrowAnyException();
    }
    @Test void tenantWideRuntimeSummariesRequireExistingManagementPermission() throws Exception {
        mvc.perform(get("/api/v1/agent/runtime/snapshot").with(this::identity)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/agent/runtime/quality").with(this::identity)).andExpect(status().isForbidden());
        when(identities.hasPermission("u1", "platform:tasks:all")).thenReturn(true);
        mvc.perform(get("/api/v1/agent/runtime/snapshot").with(this::identity)).andExpect(status().isOk());
    }
    @Test void ownerCanReadEachObjectThroughEverySurface() throws Exception {
        for (String prefix : new String[]{"conversations", "agent/tasks", "ui-artifacts"})
            mvc.perform(get("/api/v1/"+prefix+"/owned").with(this::identity)).andExpect(status().isOk());
    }
    @Test void sameTenantOtherUserCannotReadModifyOrSubscribe() throws Exception {
        for (String path : new String[]{"conversations/foreign", "agent/tasks/foreign", "agent/tasks/foreign/events/stream", "ui-artifacts/foreign", "ui-artifacts/foreign/resources/file"})
            mvc.perform(get("/api/v1/"+path).with(this::identity)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/conversations/foreign").with(this::identity)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/ui-artifacts/foreign").with(this::identity)).andExpect(status().isForbidden());
    }
    @Test void crossTenantDeniedEvenWhenOwnerIdMatches() throws Exception {
        for (String prefix : new String[]{"conversations", "agent/tasks", "ui-artifacts"})
            mvc.perform(get("/api/v1/"+prefix+"/other-tenant").with(this::identity)).andExpect(status().isForbidden());
    }
    @Test void workspaceAndOrganizationClaimsCannotAuthorizeForeignObjects() throws Exception {
        for (String workspace : new String[]{"zhice", "data-science", "administrator"})
            mvc.perform(get("/api/v1/conversations/foreign").with(this::identity)
                .header("X-Workspace-Id", workspace).param("orgId", "other-org").param("tenantId", "t2"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/conversations/owned").with(this::identity).header("X-Workspace-Id", "another-app"))
            .andExpect(status().isOk());
    }
    @Test void historyPathAndBodyCannotImpersonateOrOverwriteAnotherUser() throws Exception {
        mvc.perform(get("/api/v1/conversations/user/u2").with(this::identity)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/data/history/alice/foreign").with(this::identity)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/data/history").with(this::identity).accept("application/json").contentType("application/json")
            .content("{\"userId\":\"u2\",\"conversationId\":\"owned\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/data/history").with(this::identity).accept("application/json").contentType("application/json")
            .content("{\"userId\":\"alice\",\"conversationId\":\"foreign\"}")).andExpect(status().isForbidden());
    }
    @Test void sessionReuseDeniedBeforeModelOrTaskDispatch() throws Exception {
        mvc.perform(post("/api/v1/interactions/chat").with(this::identity).accept("application/json").contentType("application/json")
            .content("{\"query\":\"run\",\"conversationId\":\"foreign\"}")).andExpect(status().isForbidden());
    }
    @Test void taskIngressUsesStableSessionsBeforeTaskAcceptance() throws Exception {
        String body="{\"query\":\"calculate\",\"idempotencyKey\":\"same-key\"}";
        String expected=com.chatchat.chat.task.core.AgentTaskIds.sessionId("t1","same-key");
        for(int attempt=0;attempt<2;attempt++)
            mvc.perform(post("/api/v1/agent/tasks").with(this::identity).accept("application/json").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(expected))
                .andExpect(jsonPath("$.tenantId").value("t1")).andExpect(jsonPath("$.userId").value("u1"));
    }
    @Test void taskIngressReusesAnAuthorizedLegacySessionAndRejectsForeignIdempotencyKeys() throws Exception {
        jdbc.update("update agent_task_latest set idempotency_key='legacy-key', session_id='owned' where task_id='owned'");
        mvc.perform(post("/api/v1/agent/tasks").with(this::identity).accept("application/json").contentType("application/json")
            .content("{\"query\":\"calculate\",\"idempotencyKey\":\"legacy-key\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value("owned"));
        jdbc.update("update agent_task_latest set idempotency_key='foreign-key', session_id='foreign' where task_id='foreign'");
        mvc.perform(post("/api/v1/agent/tasks").with(this::identity).accept("application/json").contentType("application/json")
            .content("{\"query\":\"calculate\",\"idempotencyKey\":\"foreign-key\"}"))
            .andExpect(status().isForbidden());
        verify(origins,never()).prepare(any(),eq("foreign"));
    }
    @Test void legacyUsernameOwnerIsCompatibleButNoImplicitTenantOrOrgPrivilege() {
        jdbc.update("insert into chat_session values('legacy','t1','alice')");
        var request = identity(new MockHttpServletRequest());
        assertThatCode(() -> access.requireConversation(request, "legacy", false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> access.requireOwner(request, "t1", "bob", false)).isInstanceOf(RuntimeException.class);
    }
    @Test void taskManagementDoesNotGrantConversationOwnership() {
        when(identities.hasPermission("u1", "platform:tasks:all")).thenReturn(true);
        var request = identity(new MockHttpServletRequest());
        assertThatCode(() -> access.requireTask(request, "foreign")).doesNotThrowAnyException();
        assertThatThrownBy(() -> access.requireConversation(request, "foreign", false)).isInstanceOf(RuntimeException.class);
    }
    @Test void unattributedArtifactIsNotPublicOrDeleted() throws Exception {
        jdbc.update("insert into ui_artifact values('orphan','missing','t1')");
        mvc.perform(get("/api/v1/ui-artifacts/orphan").with(this::identity)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from ui_artifact where artifact_id='orphan'", Integer.class)).isEqualTo(1);
    }
    @RestController static class Surface {
        @PostMapping("/api/v1/agent/tasks") com.chatchat.chat.task.core.AgentTaskSubmitRequest task(
            @RequestBody com.chatchat.chat.task.core.AgentTaskSubmitRequest request) { return request; }
        @GetMapping({"/api/v1/conversations/{conversationId}", "/api/v1/agent/tasks/{taskId}", "/api/v1/agent/tasks/{taskId}/events/stream",
            "/api/v1/agent/runtime/snapshot", "/api/v1/agent/runtime/quality",
            "/api/v1/ui-artifacts/{artifactId}", "/api/v1/ui-artifacts/{artifactId}/resources/{resourceId}", "/api/v1/conversations/user/{userId}"})
        String read() { return "ok"; }
        @DeleteMapping({"/api/v1/conversations/{conversationId}", "/api/v1/ui-artifacts/{artifactId}", "/api/v1/data/history/{userId}/{historyId}"})
        String remove() { return "ok"; }
        @PostMapping("/api/v1/interactions/chat") String chat(@RequestBody InteractionRequest request) { throw new AssertionError("unauthorized dispatch"); }
        @PostMapping("/api/v1/data/history") String history(@RequestBody DataQueryController.HistoryRequest request) { throw new AssertionError("unauthorized mutation"); }
    }
}
