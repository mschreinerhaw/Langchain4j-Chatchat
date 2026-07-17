package com.chatchat.runtime.news.store;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class NewsEmbeddingClientTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void supportsOpenAiCompatibleBatchEmbeddings() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/embeddings", exchange -> {
            byte[] body = "{\"data\":[{\"embedding\":[0.1,0.2]},{\"embedding\":[0.3,0.4]}]}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        NewsRuntimeProperties.Embedding properties = new NewsRuntimeProperties.Embedding();
        properties.setEnabled(true);
        properties.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/embeddings");
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        properties.setDimension(2);
        NewsEmbeddingClient client = new NewsEmbeddingClient(properties, new ObjectMapper(), HttpClient.newHttpClient());

        assertThat(client.embedAll(java.util.List.of("公告正文", "附件分片")))
            .containsExactly(java.util.List.of(0.1F, 0.2F), java.util.List.of(0.3F, 0.4F));
    }
}
