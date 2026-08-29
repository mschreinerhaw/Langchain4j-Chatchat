package com.chatchat.api.agent.published;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.chat.task.core.AgentTaskResponse;
import com.chatchat.chat.task.core.AgentTaskService;
import com.chatchat.chat.task.core.AgentTaskSubmitRequest;
import com.chatchat.chat.task.event.AgentEvent;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class PublishedAgentApiControllerTest {

    private final AgentTaskService taskService = mock(AgentTaskService.class);
    private final SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);
    private final EnterpriseAdminService enterpriseAdminService = mock(EnterpriseAdminService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PublishedAgentApiController controller = new PublishedAgentApiController(
            taskService, skillCatalogService, enterpriseAdminService, objectMapper);
        mockMvc = standaloneSetup(controller).build();
        when(skillCatalogService.isPublished("finance-agent")).thenReturn(true);
        when(skillCatalogService.resolve("finance-agent")).thenReturn(publishedAgent());
        when(enterpriseAdminService.canAccessAgent(anyString(), anyString())).thenReturn(true);
        when(taskService.listEventsAfter(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(List.of());
    }

    @Test
    void submitsWithAuthenticatedIdentityAndImmutablePublishedAgentContract() throws Exception {
        when(taskService.submit(org.mockito.ArgumentMatchers.any())).thenReturn(task("user-a", "RUNNING", "EXECUTING"));

        mockMvc.perform(post("/api/v1/published-agents/finance-agent/questions")
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "user-a")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USERNAME, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question":"How is revenue?",
                      "sessionId":"conversation-1",
                      "idempotencyKey":"request-1",
                      "parameters":{"region":"east","__agentRunId":"forged"}
                    }
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.taskId").value("task-1"))
            .andExpect(jsonPath("$.data.statusUrl").value("/api/v1/published-agents/finance-agent/questions/task-1/status"))
            .andExpect(jsonPath("$.data.answerUrl").value("/api/v1/published-agents/finance-agent/questions/task-1/answer"));

        ArgumentCaptor<AgentTaskSubmitRequest> captor = ArgumentCaptor.forClass(AgentTaskSubmitRequest.class);
        verify(taskService).submit(captor.capture());
        AgentTaskSubmitRequest submitted = captor.getValue();
        assertThat(submitted.getTenantId()).isEqualTo("tenant-a");
        assertThat(submitted.getUserId()).isEqualTo("user-a");
        assertThat(submitted.getAgentId()).isEqualTo("finance-agent");
        assertThat(submitted.getSkillId()).isEqualTo("finance-agent");
        assertThat(submitted.getMode()).isEqualTo("agent_chat");
        assertThat(submitted.getToolInput()).containsEntry("region", "east").doesNotContainKey("__agentRunId");
        assertThat(submitted.getIdempotencyKey()).startsWith("published-api:").hasSize(78);
    }

    @Test
    void deniesReadingAnotherUsersTaskInsideTheSameTenant() throws Exception {
        when(taskService.get("tenant-a", "task-1")).thenReturn(Optional.of(task("user-b", "RUNNING", "EXECUTING")));

        mockMvc.perform(get("/api/v1/published-agents/finance-agent/questions/task-1/status")
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "user-a")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USERNAME, "alice"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void returnsRunStatusAndFullFinalAnswerForTheOwner() throws Exception {
        AgentTaskResponse completed = task("user-a", "SUCCESS", "SUCCEEDED");
        when(taskService.get("tenant-a", "task-1")).thenReturn(Optional.of(completed));
        when(taskService.finalNotificationContent("tenant-a", "task-1")).thenReturn(Optional.of(
            new AgentTaskService.AgentNotificationContent("Revenue grew 12%.", List.of(Map.of("title", "Q2 report")))));

        mockMvc.perform(get("/api/v1/published-agents/finance-agent/questions/task-1/status")
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "user-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.terminal").value(true))
            .andExpect(jsonPath("$.data.answerAvailable").value(true))
            .andExpect(jsonPath("$.data.events").isArray())
            .andExpect(jsonPath("$.data.eventCursor").value(0))
            .andExpect(jsonPath("$.data.hasMoreEvents").value(false));

        mockMvc.perform(get("/api/v1/published-agents/finance-agent/questions/task-1/answer")
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "user-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ready").value(true))
            .andExpect(jsonPath("$.data.answer").value("Revenue grew 12%."))
            .andExpect(jsonPath("$.data.references[0].title").value("Q2 report"));
    }

    @Test
    void returnsIncrementalSanitizedEventsWithCursor() throws Exception {
        when(taskService.get("tenant-a", "task-1"))
            .thenReturn(Optional.of(task("user-a", "RUNNING", "EXECUTING")));
        when(taskService.listEventsAfter("tenant-a", "task-1", 7L, 3)).thenReturn(List.of(
            AgentEvent.builder()
                .eventId("event-8")
                .sequence(8L)
                .type("TOOL_CALL")
                .status("WAIT_TOOL")
                .eventScope("TASK")
                .toolName("finance_lookup")
                .payload("{\"message\":\"Calling finance lookup\",\"toolName\":\"finance_lookup\",\"authorization\":\"Bearer secret\",\"tenantId\":\"private\",\"answer\":\"must only be returned by the answer endpoint\",\"debug\":{\"trace\":\"private\"},\"metadata\":{\"taskId\":\"tenant:run:tool\",\"stage\":\"TOOL_STARTED\"},\"__runtime\":{\"secret\":\"private\"}}")
                .createTime(1_787_932_800_000L)
                .build(),
            AgentEvent.builder().eventId("event-9").sequence(9L).type("STATUS")
                .status("RUNNING").payload("{\"message\":\"working\"}").createTime(1_787_932_801_000L).build(),
            AgentEvent.builder().eventId("event-10").sequence(10L).type("THINK")
                .status("RUNNING").payload("{}").createTime(1_787_932_802_000L).build()
        ));

        mockMvc.perform(get("/api/v1/published-agents/finance-agent/questions/task-1/status")
                .param("afterSequence", "7")
                .param("eventLimit", "2")
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "user-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.events.length()").value(2))
            .andExpect(jsonPath("$.data.events[0].eventId").value("event-8"))
            .andExpect(jsonPath("$.data.events[0].sequence").value(8))
            .andExpect(jsonPath("$.data.events[0].payload.message").value("Calling finance lookup"))
            .andExpect(jsonPath("$.data.events[0].payload.toolName").value("finance_lookup"))
            .andExpect(jsonPath("$.data.events[0].payload.metadata.stage").value("TOOL_STARTED"))
            .andExpect(jsonPath("$.data.events[0].payload.authorization").doesNotExist())
            .andExpect(jsonPath("$.data.events[0].payload.tenantId").doesNotExist())
            .andExpect(jsonPath("$.data.events[0].payload.metadata.taskId").doesNotExist())
            .andExpect(jsonPath("$.data.events[0].payload.__runtime").doesNotExist())
            .andExpect(jsonPath("$.data.events[0].payload.answer").doesNotExist())
            .andExpect(jsonPath("$.data.events[0].payload.debug").doesNotExist())
            .andExpect(jsonPath("$.data.eventCursor").value(9))
            .andExpect(jsonPath("$.data.hasMoreEvents").value(true));
    }

    @Test
    void exposesCurlExamplesOnlyToPlatformAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/published-agents/finance-agent/curl-example")
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "user-a")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USERNAME, "alice"))
            .andExpect(status().isForbidden());

        when(enterpriseAdminService.hasAllAgentAccess("admin-id")).thenReturn(true);
        mockMvc.perform(get("/api/v1/published-agents/finance-agent/curl-example")
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "platform")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "admin-id")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USERNAME, "admin"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.submitCurl").value(org.hamcrest.Matchers.containsString("/questions")))
            .andExpect(jsonPath("$.data.statusCurl").value(org.hamcrest.Matchers.containsString("/status")))
            .andExpect(jsonPath("$.data.answerCurl").value(org.hamcrest.Matchers.containsString("/answer")))
            .andExpect(jsonPath("$.data.tokenEnvironmentVariable").value("AGENT_TOKEN"))
            .andExpect(jsonPath("$.data.completeExample").value(org.hamcrest.Matchers.containsString("AGENT_BASE_URL")))
            .andExpect(jsonPath("$.data.completeExample").value(org.hamcrest.Matchers.containsString("AGENT_TOKEN")))
            .andExpect(jsonPath("$.data.completeExample").value(org.hamcrest.Matchers.containsString("TASK_ID=$(")))
            .andExpect(jsonPath("$.data.completeExample").value(org.hamcrest.Matchers.containsString("while true")))
            .andExpect(jsonPath("$.data.completeExample").value(org.hamcrest.Matchers.containsString(".data.terminal")))
            .andExpect(jsonPath("$.data.completeExample").value(org.hamcrest.Matchers.containsString(".data.eventCursor")))
            .andExpect(jsonPath("$.data.statusCurl").value(org.hamcrest.Matchers.containsString("afterSequence=")))
            .andExpect(jsonPath("$.data.completeExample").value(org.hamcrest.Matchers.containsString(".data.answerAvailable")))
            .andExpect(jsonPath("$.data.completeExample").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("export TASK_ID"))));
    }

    @Test
    void rejectsAnUnpublishedAgentBeforeTaskSubmission() throws Exception {
        when(skillCatalogService.isPublished("finance-agent")).thenReturn(false);

        mockMvc.perform(post("/api/v1/published-agents/finance-agent/questions")
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "user-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"hello\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404));
    }

    private AgentTaskResponse task(String userId, String status, String canonicalState) {
        Instant now = Instant.parse("2026-08-28T10:00:00Z");
        return new AgentTaskResponse(
            "task-1", "execution-1", "execution-1", "attempt-1", null, 1, canonicalState,
            "tenant-a", userId, "finance-agent", "conversation-1", status, "How is revenue?",
            "Revenue grew 12%.", null, null, null, null, null, null, null, now, now
        );
    }

    private SkillDefinition publishedAgent() {
        return new SkillDefinition(
            "finance-agent", "Finance Agent", "Finance", List.of(), List.of(), "agent_chat", null,
            "system", null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
            Map.of(), List.of(), "published", false
        );
    }
}
