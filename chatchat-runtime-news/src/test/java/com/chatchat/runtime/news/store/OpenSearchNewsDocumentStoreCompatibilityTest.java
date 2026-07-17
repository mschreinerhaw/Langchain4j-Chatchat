package com.chatchat.runtime.news.store;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenSearchNewsDocumentStoreCompatibilityTest {
    private HttpServer server;
    private OpenSearchNewsDocumentStore store;

    @AfterEach
    void stop() throws Exception {
        if (store != null) store.close();
        if (server != null) server.stop(0);
    }

    @Test
    void closesExistingOpenIndexBeforeEnablingStaticKnnSetting() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String request = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            requests.add(request);
            boolean createConflict = "PUT".equals(exchange.getRequestMethod())
                && !exchange.getRequestURI().getPath().contains("/_");
            byte[] body = (createConflict ? "{\"error\":\"resource_already_exists_exception\",\"status\":400}" : "{}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(createConflict ? 400 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        NewsRuntimeProperties runtime = new NewsRuntimeProperties();
        runtime.getOpenSearch().setEndpoint("http://localhost:" + server.getAddress().getPort());
        runtime.getOpenSearch().setIndexName("runtime-news");
        NewsEmbeddingClient embeddings = mock(NewsEmbeddingClient.class);
        when(embeddings.enabled()).thenReturn(true);
        store = new OpenSearchNewsDocumentStore(runtime, new ObjectMapper(),
            new NewsIndexNamingStrategy(runtime), embeddings);

        String index = store.ensureDailyIndex();

        assertThat(requests).containsExactly(
            "PUT /" + index,
            "POST /" + index + "/_close",
            "PUT /" + index + "/_settings",
            "POST /" + index + "/_open",
            "PUT /" + index + "/_mapping"
        );
    }
}
