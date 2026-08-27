package com.chatchat.api.controller;

import com.chatchat.chat.conversation.service.ConversationService;
import com.chatchat.chat.conversation.model.Conversation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for ConversationController
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConversationService conversationService;

    @MockBean
    private ChatModel chatModel;

    @Test
    public void testCreateConversation() throws Exception {
        String requestBody = objectMapper.writeValueAsString(
            new ConversationController.CreateConversationRequest("user-001", "Test Conversation")
        );

        mockMvc.perform(post("/api/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("Conversation created successfully"))
            .andExpect(jsonPath("$.data.id").exists())
            .andExpect(jsonPath("$.data.userId").value("user-001"))
            .andExpect(jsonPath("$.data.title").value("Test Conversation"));
    }

    @Test
    public void testGetConversation() throws Exception {
        // First create a conversation
        String createRequestBody = objectMapper.writeValueAsString(
            new ConversationController.CreateConversationRequest("user-001", "Test Conversation")
        );

        String createResponse = mockMvc.perform(post("/api/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequestBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        // Extract conversation ID from response
        JsonNode response = objectMapper.readTree(createResponse);
        String conversationId = response.path("data").path("id").asText();

        // Get the conversation
        mockMvc.perform(get("/api/v1/conversations/" + conversationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.id").value(conversationId));
    }

    @Test
    public void testGetConversationWithPersistedMessages() throws Exception {
        String createRequestBody = objectMapper.writeValueAsString(
            new ConversationController.CreateConversationRequest("user-003", "Message Store Test")
        );

        String createResponse = mockMvc.perform(post("/api/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequestBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode response = objectMapper.readTree(createResponse);
        String conversationId = response.path("data").path("id").asText();

        conversationService.appendMessage(conversationId, "user", "分析贵州茅台");
        conversationService.appendMessage(conversationId, "assistant", "这是一个示例回答");

        mockMvc.perform(get("/api/v1/conversations/" + conversationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.messages[0].role").value("user"))
            .andExpect(jsonPath("$.data.messages[0].content").value("分析贵州茅台"))
            .andExpect(jsonPath("$.data.messages[1].role").value("assistant"));
    }

    @Test
    public void historyListReturnsSummariesWithoutHydratingMessageDetails() throws Exception {
        String userId = "history-summary-user";
        String createResponse = mockMvc.perform(post("/api/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new ConversationController.CreateConversationRequest(userId, "History summary title")
                )))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String conversationId = objectMapper.readTree(createResponse).path("data").path("id").asText();
        conversationService.appendMessage(conversationId, "user", "large legacy message placeholder");

        mockMvc.perform(get("/api/v1/data/history/" + userId).param("limit", "30"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].id").value(conversationId))
            .andExpect(jsonPath("$.data[0].question").value("History summary title"))
            .andExpect(jsonPath("$.data[0].messages").isEmpty());

        mockMvc.perform(get("/api/v1/conversations/" + conversationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.messages[0].content").value("large legacy message placeholder"));
    }

    @Test
    public void lightweightSummaryEndpointExcludesConversationMessages() throws Exception {
        String userId = "lightweight-history-user";
        String createResponse = mockMvc.perform(post("/api/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new ConversationController.CreateConversationRequest(userId, "Lightweight title")
                )))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String conversationId = objectMapper.readTree(createResponse).path("data").path("id").asText();
        conversationService.appendMessage(conversationId, "user", "must only be loaded by detail endpoint");

        mockMvc.perform(get("/api/v1/conversations/user/" + userId + "/summaries").param("limit", "30"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(conversationId))
            .andExpect(jsonPath("$.data[0].title").value("Lightweight title"))
            .andExpect(jsonPath("$.data[0].messages").doesNotExist());

        mockMvc.perform(get("/api/v1/conversations/" + conversationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.messages[0].content").value("must only be loaded by detail endpoint"));
    }

    @Test
    public void testListUserConversations() throws Exception {
        // Create conversations
        String userId = "user-002";
        String createRequestBody = objectMapper.writeValueAsString(
            new ConversationController.CreateConversationRequest(userId, "Test Conversation")
        );

        mockMvc.perform(post("/api/v1/conversations")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createRequestBody))
            .andExpect(status().isOk());

        // List user conversations
        mockMvc.perform(get("/api/v1/conversations/user/" + userId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    public void testDeleteConversation() throws Exception {
        // Create a conversation
        String requestBody = objectMapper.writeValueAsString(
            new ConversationController.CreateConversationRequest("user-001", "Test Conversation")
        );

        String createResponse = mockMvc.perform(post("/api/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode response = objectMapper.readTree(createResponse);
        String conversationId = response.path("data").path("id").asText();

        // Delete the conversation
        mockMvc.perform(delete("/api/v1/conversations/" + conversationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        // Verify it's deleted
        mockMvc.perform(get("/api/v1/conversations/" + conversationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    public void testCannotDeleteConversationInProgress() throws Exception {
        Conversation conversation = conversationService.createConversation(
            "default", "user-running", "Running conversation");
        conversationService.updateConversationSummary(
            "default", conversation.getId(), "user-running", conversation.getTitle(), "running");

        mockMvc.perform(delete("/api/v1/conversations/" + conversation.getId()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value(
                "Conversation is still in progress and cannot be deleted: " + conversation.getId()));

        mockMvc.perform(get("/api/v1/conversations/" + conversation.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(conversation.getId()))
            .andExpect(jsonPath("$.data.status").value("running"));
    }

    @Test
    public void testRenameConversation() throws Exception {
        Conversation conversation = conversationService.createConversation("default", "user-rename", "Old title");

        mockMvc.perform(patch("/api/v1/conversations/" + conversation.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Quarterly analysis\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("Quarterly analysis"));

        mockMvc.perform(get("/api/v1/conversations/" + conversation.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("Quarterly analysis"));
    }

    @Test
    public void testDeleteOneConversationMessage() throws Exception {
        Conversation conversation = conversationService.createConversation("default", "user-message-delete", "Delete answer");
        Conversation.Message question = new Conversation.Message(
            "question-1", "user", "Question", java.time.LocalDateTime.now(), List.of(), null);
        Conversation.Message answer = new Conversation.Message(
            "answer-1", "assistant", "Answer to remove", java.time.LocalDateTime.now(), List.of(), null);
        conversationService.replaceMessages(
            "default", conversation.getId(), "user-message-delete", List.of(question, answer));

        mockMvc.perform(delete("/api/v1/conversations/" + conversation.getId() + "/messages/answer-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/v1/conversations/" + conversation.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.messages.length()").value(1))
            .andExpect(jsonPath("$.data.messages[0].id").value("question-1"));
    }
}
