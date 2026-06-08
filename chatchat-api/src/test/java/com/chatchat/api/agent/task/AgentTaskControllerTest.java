package com.chatchat.api.agent.task;

import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.service.InteractionOrchestrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
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
        JsonNode task = waitForTaskStatus(taskId, "SUCCESS");

        org.assertj.core.api.Assertions.assertThat(task.path("data").path("answerSummary").asText())
            .contains("????");

        mockMvc.perform(get("/api/v1/agent/tasks/" + taskId + "/result")
                .param("timeoutMs", "1000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.type").value("ANSWER"))
            .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        mockMvc.perform(get("/api/v1/agent/tasks/" + taskId + "/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(3)));
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
        waitForTaskStatus(taskId, "FAILED");

        mockMvc.perform(get("/api/v1/agent/tasks/" + taskId + "/result")
                .param("timeoutMs", "1000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.type").value("ERROR"))
            .andExpect(jsonPath("$.data.status").value("FAILED"))
            .andExpect(jsonPath("$.data.payload").value(org.hamcrest.Matchers.containsString("model unavailable")));

        mockMvc.perform(get("/api/v1/agent/tasks/" + taskId + "/events"))
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

    private JsonNode waitForTaskStatus(String taskId, String expectedStatus) throws Exception {
        JsonNode lastResponse = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            String response = mockMvc.perform(get("/api/v1/agent/tasks/" + taskId))
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
}
