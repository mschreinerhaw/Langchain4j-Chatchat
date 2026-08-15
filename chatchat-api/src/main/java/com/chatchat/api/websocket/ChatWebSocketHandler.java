package com.chatchat.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(prefix = "chatchat.websocket.legacy-chat", name = "enabled", havingValue = "true")
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

            sendMessage(session, new Message(
                "error",
                "该旧版 WebSocket 入口未绑定 Agent Runtime，请使用统一会话 API。",
                false,
                true
            ));

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
