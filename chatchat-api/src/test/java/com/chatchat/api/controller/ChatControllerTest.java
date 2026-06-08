package com.chatchat.api.controller;

import com.chatchat.knowledgebase.rag.RAGService;
import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for ChatController
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RAGService ragService;

    @BeforeEach
    public void setUp() {
        // Mock RAGService behavior
        when(ragService.retrieveContext(anyString(), anyString(), anyInt()))
            .thenReturn(Collections.singletonList(
                Document.from("Test document content")
            ));

        when(ragService.buildContextualPrompt(anyString(), anyList(), anyString()))
            .thenReturn("System: You are helpful\n\nContext Documents:\n--- Document 1 ---\nTest document content\n\nQuestion: test");

        when(ragService.getStats(anyString()))
            .thenReturn(new RAGService.RetrievalStats(100, 1000, 1024000));
    }

    @Test
    public void testSendMessage() throws Exception {
        ChatController.ChatRequest request = new ChatController.ChatRequest(
            "conv-123",
            "What is AI?",
            "kb-001",
            "You are a helpful AI assistant"
        );

        String requestBody = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/v1/chat/message")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").isNotEmpty())
            .andExpect(jsonPath("$.data.response", notNullValue()))
            .andExpect(jsonPath("$.data.contextDocumentsUsed").value(1));
    }

    @Test
    public void testSendMessageWithEmptyMessage() throws Exception {
        ChatController.ChatRequest request = new ChatController.ChatRequest(
            "conv-123",
            "",
            "kb-001",
            null
        );

        String requestBody = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/v1/chat/message")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message", containsString("empty")));
    }

    @Test
    public void testSendMessageWithoutKnowledgeBase() throws Exception {
        ChatController.ChatRequest request = new ChatController.ChatRequest(
            "conv-123",
            "What is AI?",
            "",
            null
        );

        String requestBody = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/v1/chat/message")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message", containsString("Knowledge base")));
    }

    @Test
    public void testStreamChat() throws Exception {
        ChatController.ChatRequest request = new ChatController.ChatRequest(
            "conv-123",
            "What is AI?",
            "kb-001",
            null
        );

        String requestBody = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/v1/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message", containsString("Streaming")));
    }

    @Test
    public void testGetStats() throws Exception {
        mockMvc.perform(get("/api/v1/chat/stats/kb-001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.documentCount").value(100))
            .andExpect(jsonPath("$.data.totalEmbeddings").value(1000))
            .andExpect(jsonPath("$.data.storageSize").value(1024000));
    }

    @Test
    public void testHealth() throws Exception {
        mockMvc.perform(get("/api/v1/chat/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").value("OK"));
    }
}
