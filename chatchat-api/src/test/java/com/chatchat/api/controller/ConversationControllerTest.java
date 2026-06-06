package com.chatchat.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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
}
