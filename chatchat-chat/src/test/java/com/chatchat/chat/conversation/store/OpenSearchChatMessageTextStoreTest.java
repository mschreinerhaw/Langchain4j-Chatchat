package com.chatchat.chat.conversation.store;

import com.chatchat.chat.conversation.store.OpenSearchChatMessageTextStore;

import com.chatchat.chat.conversation.store.ChatDetailStoreProperties;

import com.chatchat.chat.conversation.model.ChatMessageDetail;

import com.chatchat.chat.conversation.model.Conversation;

import com.chatchat.knowledgebase.search.SearchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSearchChatMessageTextStoreTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createsIndexAndRoundTripsChatDetail() throws IOException {
        AtomicReference<byte[]> storedSource = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, storedSource));
        server.start();

        ChatDetailStoreProperties detailProperties = new ChatDetailStoreProperties();
        detailProperties.setMaxStringLength(1024);
        detailProperties.getExternalText().setEnabled(true);
        detailProperties.getExternalText().setIndexName("chat_details_test");
        SearchProperties searchProperties = new SearchProperties();
        searchProperties.getOpenSearch().setUrl(
            "http://127.0.0.1:" + server.getAddress().getPort()
        );
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        OpenSearchChatMessageTextStore store = new OpenSearchChatMessageTextStore(
            detailProperties, searchProperties, objectMapper
        );
        store.open();
        try {
            ChatMessageDetail detail = ChatMessageDetail.builder()
                .messageId("message-http")
                .sessionId("session-http")
                .tenantId("tenant-http")
                .role("assistant")
                .content("z".repeat(256))
                .createdAt(Instant.parse("2026-07-29T00:00:02Z"))
                .build();

            store.put("document-http", detail);

            assertThat(storedSource.get()).isNotEmpty();
            assertThat(store.get("document-http")).contains(detail);
            store.putText(
                "payload-http", "agent_event_payload", "tenant-http",
                "session-http", "event-http", "q".repeat(256)
            );
            assertThat(store.getText("payload-http")).contains("q".repeat(256));
            store.delete("payload-http");
            assertThat(storedSource.get()).isNull();
        } finally {
            store.close();
        }
    }

    @Test
    void batchLoadsConversationDetailsWithOneOpenSearchRequestAndSkipsInvalidLegacyDocument() throws IOException {
        AtomicInteger multiGetRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("HEAD".equals(method) && "/chat_details_test".equals(path)) {
                respond(exchange, 200, "");
                return;
            }
            if ("POST".equals(method) && "/chat_details_test/_mget".equals(path)) {
                multiGetRequests.incrementAndGet();
                respond(exchange, 200, """
                    {
                      "docs": [
                        {
                          "_id": "document-valid",
                          "found": true,
                          "_source": {
                            "detail": {
                              "messageId": "message-valid",
                              "sessionId": "session-batch",
                              "tenantId": "tenant-batch",
                              "role": "assistant",
                              "content": "restored",
                              "createdAt": "2026-07-29T00:00:02Z"
                            }
                          }
                        },
                        {
                          "_id": "document-invalid",
                          "found": true,
                          "_source": {
                            "detail": {
                              "messageId": "message-invalid",
                              "createdAt": "not-an-instant"
                            }
                          }
                        }
                      ]
                    }
                    """);
                return;
            }
            respond(exchange, 500, "{\"error\":\"unexpected request\"}");
        });
        server.start();

        ChatDetailStoreProperties detailProperties = new ChatDetailStoreProperties();
        detailProperties.getExternalText().setEnabled(true);
        detailProperties.getExternalText().setIndexName("chat_details_test");
        SearchProperties searchProperties = new SearchProperties();
        searchProperties.getOpenSearch().setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        OpenSearchChatMessageTextStore store = new OpenSearchChatMessageTextStore(
            detailProperties,
            searchProperties,
            new ObjectMapper().findAndRegisterModules()
        );
        store.open();
        try {
            Map<String, ChatMessageDetail> details =
                store.getAll(List.of("document-valid", "document-invalid"));

            assertThat(multiGetRequests).hasValue(1);
            assertThat(details).containsOnlyKeys("document-valid");
            assertThat(details.get("document-valid").getContent()).isEqualTo("restored");
        } finally {
            store.close();
        }
    }

    private void handle(HttpExchange exchange, AtomicReference<byte[]> storedSource) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("HEAD".equals(method) && "/chat_details_test".equals(path)) {
            respond(exchange, 404, "");
            return;
        }
        if ("PUT".equals(method) && "/chat_details_test".equals(path)) {
            respond(exchange, 200, "{\"acknowledged\":true}");
            return;
        }
        if ("PUT".equals(method) && path.startsWith("/chat_details_test/_doc/")) {
            storedSource.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 201, "{\"result\":\"created\"}");
            return;
        }
        if ("GET".equals(method) && path.startsWith("/chat_details_test/_source/")) {
            byte[] source = storedSource.get();
            if (source == null) {
                respond(exchange, 404, "");
            } else {
                respond(exchange, 200, new String(source, StandardCharsets.UTF_8));
            }
            return;
        }
        if ("DELETE".equals(method) && path.startsWith("/chat_details_test/_doc/")) {
            storedSource.set(null);
            respond(exchange, 200, "{\"result\":\"deleted\"}");
            return;
        }
        respond(exchange, 500, "{\"error\":\"unexpected request\"}");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
