package com.chatchat.mcpserver.search;

import com.chatchat.mcpserver.config.ChatChatMcpServerProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSearchAdminClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void proxiesDocumentSearchAndMapsEvidenceForAssetIndexTestPanel() throws Exception {
        AtomicReference<String> requestedPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> loginBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/enterprise/auth/login", exchange -> {
            loginBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, """
                {
                  "success": true,
                  "data": {
                    "token": "admin-token"
                  }
                }
                """);
        });
        server.createContext("/api/v1/search/document-search", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, """
                {
                  "success": true,
                  "data": {
                    "query": "net value source",
                    "intent": "evidence",
                    "total": 1,
                    "matchType": "CONTENT_HIT",
                    "results": [
                      {
                        "refId": "ref-1",
                        "chunkId": "chunk-001",
                        "fileId": "doc-001",
                        "fileName": "fund-requirement.md",
                        "section": "Data Source",
                        "chunkIndex": 3,
                        "chunkType": "paragraph",
                        "score": 18.5,
                        "content": "The complete original evidence chunk comes from TA, website and valuation systems.",
                        "citation": {
                          "source": "fund-requirement.md",
                          "locator": "page 3"
                        }
                      }
                    ],
                    "documents": [
                      {
                        "docId": "doc-001",
                        "title": "Fund requirement",
                        "fileName": "fund-requirement.md",
                        "documentType": "markdown",
                        "score": 12.2,
                        "tags": ["fund"]
                      }
                    ]
                  }
                }
                """);
        });
        server.start();

        ChatChatMcpServerProperties.DocumentSearchProperties properties = new ChatChatMcpServerProperties.DocumentSearchProperties();
        properties.setApiBaseUrl("http://localhost:" + server.getAddress().getPort());
        DocumentSearchAdminClient client = new DocumentSearchAdminClient(
            objectMapper,
            properties,
            HttpClient.newHttpClient()
        );

        Map<String, Object> result = client.search(Map.of(
            "indexType", "document_search",
            "query", "net value source",
            "limit", 2,
            "fileIdsText", "doc-001, doc-002",
            "debug", true
        ), 2);

        assertThat(requestedPath.get()).isEqualTo("/api/v1/search/document-search");
        assertThat(loginBody.get()).contains("admin").contains("123456");
        assertThat(authorization.get()).isEqualTo("Bearer admin-token");
        Map<String, Object> posted = objectMapper.readValue(requestBody.get(), new TypeReference<>() {});
        assertThat(posted).containsEntry("query", "net value source")
            .containsEntry("topK", 2)
            .containsEntry("debug", true);
        assertThat(posted.get("fileIds")).asList().containsExactly("doc-001", "doc-002");

        assertThat(result).containsEntry("indexType", "document_search")
            .containsEntry("evidenceCount", 1)
            .containsEntry("candidateDocCount", 1);
        assertThat(result.get("upstream")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("ok", true);
        assertThat(result.get("results")).asList()
            .first()
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("kind", "document_evidence")
            .containsEntry("assetType", "document")
            .containsEntry("documentId", "doc-001")
            .containsEntry("chunkId", "chunk-001")
            .containsEntry("name", "fund-requirement.md")
            .containsEntry("title", "Data Source");
    }

    private void writeJson(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
