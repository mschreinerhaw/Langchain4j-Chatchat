package com.chatchat.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for real-time chat messaging
 */
@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * Performs the after connection established operation.
     *
     * @param session the session value
     * @throws Exception if the operation fails
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("WebSocket connection established: {}", session.getId());
        sessions.put(session.getId(), session);

        // Send welcome message
        sendMessage(session, new Message(
            "system",
            "连接成功，你现在可以开始提问"
        ));
    }

    /**
     * Handles the text message.
     *
     * @param session the session value
     * @param message the message value
     * @throws Exception if the operation fails
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            ChatMessage request = objectMapper.readValue(message.getPayload(), ChatMessage.class);
            log.info("Received message from {}: {}", session.getId(), request);

            // Simulate AI response
            String response = generateResponse(request.getMessage());

            // Send streaming response
            String[] words = response.split("");
            for (String word : words) {
                sendMessage(session, new Message("assistant", word, true));
                Thread.sleep(14); // Simulate typing
            }

            // Send final message marker
            sendMessage(session, new Message("assistant", "", false, true));

        } catch (Exception e) {
            log.error("Error handling WebSocket message", e);
            sendMessage(session, new Message("error", "处理请求时出错: " + e.getMessage()));
        }
    }

    /**
     * Performs the after connection closed operation.
     *
     * @param session the session value
     * @param status the status value
     * @throws Exception if the operation fails
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("WebSocket connection closed: {}", session.getId());
        sessions.remove(session.getId());
    }

    /**
     * Generate AI response (placeholder - will be replaced with real LLM call)
     */
    private String generateResponse(String question) {
        // This will be replaced with actual LLM/agent logic.
        if (question.contains("客户")) {
            return "根据当前数据，客户总数为852户，其中高净值客户142户，环比增长稳定。";
        } else if (question.contains("风险")) {
            return "客户风险分布中，低风险占比46%，中低风险占比28%，整体风险可控。";
        } else if (question.contains("两融")) {
            return "两融总资产12.85万元，总负债4.54万元，业务规模稳定增长。";
        } else if (question.contains("流失")) {
            return "近期资产流失主要集中在2位高净值客户，已标记为重点回访对象。";
        }
        return "您提出的问题已记录，我们正在分析相关数据，即将为您生成详细分析报告。";
    }

    /**
     * Send message to a specific session
     */
    private void sendMessage(WebSocketSession session, Message message) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        }
    }

    /**
     * Broadcast message to all connected clients
     */
    public void broadcastMessage(Message message) {
        sessions.values().forEach(session -> {
            try {
                sendMessage(session, message);
            } catch (IOException e) {
                log.error("Error broadcasting message", e);
            }
        });
    }

    /**
     * Chat message from client
     */
    public static class ChatMessage {
        public String conversationId;
        public String message;
        public String skill;
        public String model;

        /**
         * Returns the message.
         *
         * @return the message
         */
        public String getMessage() {
            return message;
        }
    }

    /**
     * Message to send to client
     */
    public static class Message {
        public String type; // "user", "assistant", "system", "error"
        public String content;
        public boolean streaming; // For streaming response
        public boolean complete; // Message is complete
        public long timestamp;

        /**
         * Creates a new ChatWebSocketHandler instance.
         *
         * @param type the type value
         * @param content the content value
         */
        public Message(String type, String content) {
            this.type = type;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
            this.streaming = false;
            this.complete = false;
        }

        /**
         * Creates a new ChatWebSocketHandler instance.
         *
         * @param type the type value
         * @param content the content value
         * @param streaming the streaming value
         */
        public Message(String type, String content, boolean streaming) {
            this(type, content);
            this.streaming = streaming;
        }

        /**
         * Creates a new ChatWebSocketHandler instance.
         *
         * @param type the type value
         * @param content the content value
         * @param streaming the streaming value
         * @param complete the complete value
         */
        public Message(String type, String content, boolean streaming, boolean complete) {
            this(type, content, streaming);
            this.complete = complete;
        }
    }
}
