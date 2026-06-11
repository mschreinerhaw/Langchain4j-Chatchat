package com.chatchat.api.agent.task;

import com.chatchat.chat.task.AgentEvent;
import com.chatchat.chat.task.AgentEventStore;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.service.InteractionOrchestrationService;
import com.chatchat.chat.task.AgentTaskLatestEntity;
import com.chatchat.chat.task.AgentTaskLatestRepository;
import com.chatchat.chat.task.AgentTaskPayload;
import com.chatchat.chat.task.AgentTaskService;
import com.chatchat.chat.task.AgentTaskSubmitRequest;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.enterprise.entity.SysAuditLog;
import com.chatchat.enterprise.repository.SysAuditLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentTaskService agentTaskService;

    @Autowired
    private AgentTaskLatestRepository latestRepository;

    @Autowired
    private AgentEventStore eventStore;

    @Autowired
    private SysAuditLogRepository auditLogRepository;

    @MockBean
    private InteractionOrchestrationService orchestrationService;

    @Test
    void submitTaskAndReadEvents() throws Exception {
        reset(orchestrationService);
        when(orchestrationService.chat(any())).thenReturn(InteractionResponse.builder()
            .conversationId("session-001")
            .requestId("request-001")
            .mode("agent_chat")
            .answer("????")
            .toolTraces(java.util.List.of(InteractionToolTrace.builder()
                .toolName("document_search")
                .displayName("Document Search")
                .success(true)
                .input(Map.of("query", "????????"))
                .output("{\"hits\":1}")
                .durationMs(32L)
                .startedAt(System.currentTimeMillis())
                .finishedAt(System.currentTimeMillis() + 32L)
                .build()))
            .metadata(Map.of("source", "mock"))
            .build());

        String requestBody = objectMapper.writeValueAsString(Map.of(
            "tenantId", "tenant-001",
            "userId", "user-001",
            "agentId", "general",
            "sessionId", "session-001",
            "query", "????????"
        ));

        String submitResponse = mockMvc.perform(post("/api/v1/agent/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.taskId").exists())
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        String taskId = objectMapper.readTree(submitResponse).path("data").path("taskId").asText();
        JsonNode task = waitForTaskStatus("tenant-001", taskId, "SUCCESS");

        org.assertj.core.api.Assertions.assertThat(task.path("data").path("answerSummary").asText())
            .contains("????");

        mockMvc.perform(get("/api/v1/agent/tasks/" + taskId + "/result")
                .param("tenantId", "tenant-001")
                .param("timeoutMs", "1000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.type").value("ANSWER"))
            .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        mockMvc.perform(get("/api/v1/agent/tasks/" + taskId + "/events")
                .param("tenantId", "tenant-001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(3)))
            .andExpect(jsonPath("$.data[*].type", hasItem("TOOL_CALL")))
            .andExpect(jsonPath("$.data[*].type", hasItem("TOOL_RESULT")))
            .andExpect(jsonPath("$.data[*].type", hasItem("COMPLETE")));

        mockMvc.perform(get("/api/v1/agent/tasks/runtime")
                .param("tenantId", "tenant-001")
                .param("latestLimit", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.runtimeName").value("Agent Runtime"))
            .andExpect(jsonPath("$.data.tenantId").value("tenant-001"))
            .andExpect(jsonPath("$.data.totalTasks").value(greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.data.successTasks").value(greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.data.latestTasks.length()").value(greaterThanOrEqualTo(1)));
    }

    @Test
    void confirmationResumeContinuesWaitingWorkerWithoutNewQuestionEvent() throws Exception {
        reset(orchestrationService);
        when(orchestrationService.chat(any())).thenReturn(
            InteractionResponse.builder()
                .conversationId("session-confirm-001")
                .requestId("request-confirm-001")
                .mode("agent_chat")
                .answer("waiting confirmation")
                .toolTraces(List.of(InteractionToolTrace.builder()
                    .toolName("document_search")
                    .displayName("Document Search")
                    .success(false)
                    .runtimeMetadata(Map.of(
                        "outcome", "confirmation_required",
                        "confirmation", Map.of(
                            "token", "token-confirm-001",
                            "toolName", "document_search"
                        )
                    ))
                    .build()))
                .metadata(Map.of("confirmationRequired", true))
                .build(),
            InteractionResponse.builder()
                .conversationId("session-confirm-001")
                .requestId("request-confirm-002")
                .mode("agent_chat")
                .answer("confirmed answer")
                .metadata(Map.of("source", "mock"))
                .build()
        );

        String requestBody = objectMapper.writeValueAsString(Map.of(
            "tenantId", "tenant-confirm-001",
            "userId", "user-confirm-001",
            "agentId", "general",
            "sessionId", "session-confirm-001",
            "query", "needs confirmation"
        ));

        String submitResponse = mockMvc.perform(post("/api/v1/agent/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn()
            .getResponse()
            .getContentAsString();

        String taskId = objectMapper.readTree(submitResponse).path("data").path("taskId").asText();
        mockMvc.perform(get("/api/v1/agent/tasks/" + taskId + "/result")
                .param("tenantId", "tenant-confirm-001")
                .param("timeoutMs", "3000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.type").value("NEEDS_CONFIRMATION"))
            .andExpect(jsonPath("$.data.status").value("WAIT_CONFIRMATION"));

        String confirmationBody = objectMapper.writeValueAsString(Map.of(
            "tenantId", "tenant-confirm-001",
            "userId", "user-confirm-001",
            "agentId", "general",
            "sessionId", "session-confirm-001",
            "resumeTaskId", taskId,
            "query", "needs confirmation",
            "toolInput", Map.of(
                "mcpConfirmation", Map.of(
                    "token", "token-confirm-001",
                    "approved", true,
                    "decision", "allow_once"
                )
            )
        ));

        mockMvc.perform(post("/api/v1/agent/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmationBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.taskId").value(taskId));

        JsonNode finalTask = waitForTaskStatus("tenant-confirm-001", taskId, "SUCCESS");
        org.assertj.core.api.Assertions.assertThat(finalTask.path("data").path("answerSummary").asText())
            .contains("confirmed answer");

        List<AgentEvent> events = eventStore.listByTask("tenant-confirm-001", "session-confirm-001", taskId, 100);
        org.assertj.core.api.Assertions.assertThat(events.stream()
                .filter(event -> "QUESTION".equalsIgnoreCase(event.getType()))
                .count())
            .isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(events.stream()
                .filter(event -> "CONFIRMATION".equalsIgnoreCase(event.getType()))
                .count())
            .isEqualTo(1);

        ArgumentCaptor<com.chatchat.chat.interaction.model.InteractionRequest> requestCaptor =
            ArgumentCaptor.forClass(com.chatchat.chat.interaction.model.InteractionRequest.class);
        verify(orchestrationService, times(2)).chat(requestCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(requestCaptor.getAllValues().get(1).getToolInput())
            .containsKey("mcpConfirmation");
    }

    @Test
    void modelErrorIsWrittenAsErrorEvent() throws Exception {
        reset(orchestrationService);
        when(orchestrationService.chat(any())).thenThrow(new IllegalStateException("model unavailable"));

        String requestBody = objectMapper.writeValueAsString(Map.of(
            "tenantId", "tenant-002",
            "userId", "user-002",
            "agentId", "general",
            "sessionId", "session-002",
            "query", "??????"
        ));

        String submitResponse = mockMvc.perform(post("/api/v1/agent/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn()
            .getResponse()
            .getContentAsString();

        String taskId = objectMapper.readTree(submitResponse).path("data").path("taskId").asText();
        waitForTaskStatus("tenant-002", taskId, "FAILED");

        mockMvc.perform(get("/api/v1/agent/tasks/" + taskId + "/result")
                .param("tenantId", "tenant-002")
                .param("timeoutMs", "1000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.type").value("ERROR"))
            .andExpect(jsonPath("$.data.status").value("FAILED"))
            .andExpect(jsonPath("$.data.payload").value(org.hamcrest.Matchers.containsString("model unavailable")));

        mockMvc.perform(get("/api/v1/agent/tasks/" + taskId + "/events")
                .param("tenantId", "tenant-002"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[?(@.type == 'ERROR')]").isNotEmpty());
    }

    @Test
    void rejectEmptyQuery() throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
            "tenantId", "tenant-001",
            "query", ""
        ));

        mockMvc.perform(post("/api/v1/agent/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void rejectMissingTenantAndCrossTenantAccess() throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
            "userId", "user-001",
            "agentId", "general",
            "sessionId", "session-001",
            "query", "missing tenant"
        ));

        mockMvc.perform(post("/api/v1/agent/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));

        reset(orchestrationService);
        when(orchestrationService.chat(any())).thenReturn(InteractionResponse.builder()
            .conversationId("session-003")
            .requestId("request-003")
            .mode("agent_chat")
            .answer("ok")
            .build());

        String validBody = objectMapper.writeValueAsString(Map.of(
            "tenantId", "tenant-003",
            "userId", "user-003",
            "agentId", "general",
            "sessionId", "session-003",
            "query", "tenant scoped"
        ));
        String submitResponse = mockMvc.perform(post("/api/v1/agent/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String taskId = objectMapper.readTree(submitResponse).path("data").path("taskId").asText();
        waitForTaskStatus("tenant-003", taskId, "SUCCESS");

        mockMvc.perform(get("/api/v1/agent/tasks/" + taskId)
                .param("tenantId", "tenant-other"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void cancelAndRetryTaskWithinTenantBoundary() throws Exception {
        reset(orchestrationService);
        when(orchestrationService.chat(any())).thenThrow(new IllegalStateException("retry me"));

        String requestBody = objectMapper.writeValueAsString(Map.of(
            "tenantId", "tenant-004",
            "userId", "user-004",
            "agentId", "general",
            "sessionId", "session-004",
            "query", "cancel then retry"
        ));

        String submitResponse = mockMvc.perform(post("/api/v1/agent/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String taskId = objectMapper.readTree(submitResponse).path("data").path("taskId").asText();
        waitForTaskStatus("tenant-004", taskId, "FAILED");

        mockMvc.perform(post("/api/v1/agent/tasks/" + taskId + "/cancel")
                .param("tenantId", "tenant-004"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.taskId").value(taskId));

        reset(orchestrationService);
        when(orchestrationService.chat(any())).thenReturn(InteractionResponse.builder()
            .conversationId("session-004")
            .requestId("request-004")
            .mode("agent_chat")
            .answer("retried")
            .build());

        String retryResponse = mockMvc.perform(post("/api/v1/agent/tasks/" + taskId + "/retry")
                .param("tenantId", "tenant-004"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.taskId").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String retryTaskId = objectMapper.readTree(retryResponse).path("data").path("taskId").asText();
        waitForTaskStatus("tenant-004", retryTaskId, "SUCCESS");
    }

    @Test
    void listToolRuntimeAuditsWithinTenantBoundary() throws Exception {
        SysAuditLog success = new SysAuditLog();
        success.setTenantId("tenant-007");
        success.setActorId("user-007");
        success.setModuleName("tool_runtime");
        success.setActionName("success");
        success.setResourceType("tool");
        success.setResourceId("mcp_demo_web_search");
        success.setResult("success");
        success.setDetail(writeJson(Map.of(
            "toolName", "mcp_demo_web_search",
            "tenantId", "tenant-007",
            "userId", "user-007",
            "mode", "tool_direct",
            "requestId", "req-007",
            "conversationId", "conv-007",
            "serviceId", "svc-demo",
            "durationMs", 28,
            "outcome", "success"
        )));
        auditLogRepository.save(success);

        SysAuditLog denied = new SysAuditLog();
        denied.setTenantId("tenant-007");
        denied.setActorId("user-007");
        denied.setModuleName("tool_runtime");
        denied.setActionName("denied");
        denied.setResourceType("tool");
        denied.setResourceId("mcp_demo_blocked_tool");
        denied.setResult("denied");
        denied.setDetail(writeJson(Map.of(
            "toolName", "mcp_demo_blocked_tool",
            "tenantId", "tenant-007",
            "userId", "user-007",
            "mode", "tool_direct",
            "errorCode", "TOOL_TENANT_POLICY_DENIED",
            "errorMessage", "blocked by tenant policy",
            "outcome", "denied"
        )));
        auditLogRepository.save(denied);

        SysAuditLog otherTenant = new SysAuditLog();
        otherTenant.setTenantId("tenant-other");
        otherTenant.setActorId("user-other");
        otherTenant.setModuleName("tool_runtime");
        otherTenant.setActionName("failed");
        otherTenant.setResourceType("tool");
        otherTenant.setResourceId("mcp_other_tool");
        otherTenant.setResult("failed");
        otherTenant.setDetail(writeJson(Map.of("toolName", "mcp_other_tool", "outcome", "failed")));
        auditLogRepository.save(otherTenant);

        mockMvc.perform(get("/api/v1/agent/tasks/runtime/tool-audits")
                .param("tenantId", "tenant-007")
                .param("limit", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(2)))
            .andExpect(jsonPath("$.data[*].tenantId", hasItem("tenant-007")))
            .andExpect(jsonPath("$.data[*].toolName", hasItem("mcp_demo_web_search")))
            .andExpect(jsonPath("$.data[*].toolName", hasItem("mcp_demo_blocked_tool")));

        mockMvc.perform(get("/api/v1/agent/tasks/runtime/tool-audits")
                .param("tenantId", "tenant-007")
                .param("outcome", "denied"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].toolName").value("mcp_demo_blocked_tool"))
            .andExpect(jsonPath("$.data[0].errorCode").value("TOOL_TENANT_POLICY_DENIED"));
    }

    @Test
    void reconcileLatestStateFromEventStoreRepairsTerminalSnapshot() {
        AgentTaskLatestEntity task = new AgentTaskLatestEntity();
        task.setTaskId("task-reconcile-001");
        task.setTenantId("tenant-005");
        task.setUserId("user-005");
        task.setAgentId("general");
        task.setSessionId("session-005");
        task.setStatus("RUNNING");
        task.setQuestion("repair latest state");
        task.setCreateTime(Instant.now());
        task.setUpdateTime(Instant.now());
        latestRepository.save(task);

        AgentTaskSubmitRequest request = new AgentTaskSubmitRequest();
        request.setTenantId(task.getTenantId());
        request.setUserId(task.getUserId());
        request.setAgentId(task.getAgentId());
        request.setSessionId(task.getSessionId());
        request.setQuery(task.getQuestion());

        eventStore.save(AgentEvent.builder()
            .eventId("evt-question-001")
            .taskId(task.getTaskId())
            .tenantId(task.getTenantId())
            .userId(task.getUserId())
            .agentId(task.getAgentId())
            .sessionId(task.getSessionId())
            .type("QUESTION")
            .status("PENDING")
            .sequence(1L)
            .payload(writeJson(new AgentTaskPayload(request)))
            .createTime(System.currentTimeMillis())
            .build());
        eventStore.save(AgentEvent.builder()
            .eventId("evt-answer-001")
            .taskId(task.getTaskId())
            .tenantId(task.getTenantId())
            .userId(task.getUserId())
            .agentId(task.getAgentId())
            .sessionId(task.getSessionId())
            .type("ANSWER")
            .status("SUCCESS")
            .sequence(2L)
            .payload(writeJson(InteractionResponse.builder().answer("recovered answer").mode("agent_chat").build()))
            .createTime(System.currentTimeMillis() + 1L)
            .build());
        eventStore.save(AgentEvent.builder()
            .eventId("evt-complete-001")
            .taskId(task.getTaskId())
            .tenantId(task.getTenantId())
            .userId(task.getUserId())
            .agentId(task.getAgentId())
            .sessionId(task.getSessionId())
            .type("COMPLETE")
            .status("SUCCESS")
            .sequence(3L)
            .payload(writeJson(Map.of("message", "Agent task completed")))
            .createTime(System.currentTimeMillis() + 2L)
            .build());

        int repaired = agentTaskService.reconcileLatestStateFromEvents();
        AgentTaskLatestEntity refreshed = latestRepository.findById(task.getTaskId()).orElseThrow();

        assertEquals(1, repaired);
        assertEquals("SUCCESS", refreshed.getStatus());
        org.assertj.core.api.Assertions.assertThat(refreshed.getAnswerSummary()).contains("recovered answer");
    }

    @Test
    void recoverActiveTaskReusesPersistedQuestionEvent() throws Exception {
        reset(orchestrationService);
        when(orchestrationService.chat(any())).thenReturn(InteractionResponse.builder()
            .conversationId("session-006")
            .requestId("request-006")
            .mode("agent_chat")
            .answer("recovered by replay")
            .build());

        AgentTaskLatestEntity task = new AgentTaskLatestEntity();
        task.setTaskId("task-recover-001");
        task.setTenantId("tenant-006");
        task.setUserId("user-006");
        task.setAgentId("general");
        task.setSessionId("session-006");
        task.setStatus("WAIT_MODEL");
        task.setQuestion("resume me");
        task.setCreateTime(Instant.now());
        task.setUpdateTime(Instant.now());
        latestRepository.save(task);

        AgentTaskSubmitRequest request = new AgentTaskSubmitRequest();
        request.setTenantId(task.getTenantId());
        request.setUserId(task.getUserId());
        request.setAgentId(task.getAgentId());
        request.setSessionId(task.getSessionId());
        request.setQuery(task.getQuestion());

        eventStore.save(AgentEvent.builder()
            .eventId("evt-question-recover")
            .taskId(task.getTaskId())
            .tenantId(task.getTenantId())
            .userId(task.getUserId())
            .agentId(task.getAgentId())
            .sessionId(task.getSessionId())
            .type("QUESTION")
            .status("PENDING")
            .sequence(1L)
            .payload(writeJson(new AgentTaskPayload(request)))
            .createTime(System.currentTimeMillis())
            .build());

        int recovered = agentTaskService.recoverActiveTasks();
        assertEquals(1, recovered);
        waitForTaskStatus("tenant-006", task.getTaskId(), "SUCCESS");

        List<AgentEvent> events = eventStore.listByTask(task.getTenantId(), task.getSessionId(), task.getTaskId(), 50);
        AgentEvent runningEvent = events.stream()
            .filter(event -> "STATUS".equalsIgnoreCase(event.getType()))
            .filter(event -> "RUNNING".equalsIgnoreCase(event.getStatus()))
            .findFirst()
            .orElseThrow();

        assertEquals("evt-question-recover", runningEvent.getParentEventId());
    }

    private JsonNode waitForTaskStatus(String tenantId, String taskId, String expectedStatus) throws Exception {
        JsonNode lastResponse = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            String response = mockMvc.perform(get("/api/v1/agent/tasks/" + taskId)
                    .param("tenantId", tenantId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
            lastResponse = objectMapper.readTree(response);
            if (expectedStatus.equals(lastResponse.path("data").path("status").asText())) {
                return lastResponse;
            }
            Thread.sleep(100);
        }
        return lastResponse;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
