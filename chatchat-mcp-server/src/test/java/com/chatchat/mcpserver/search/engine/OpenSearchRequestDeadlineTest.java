package com.chatchat.mcpserver.search.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.opensearch.client.Request;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenSearchRequestDeadlineTest {

    @Test
    void cancelsRequestWhenAsyncClientDoesNotCompleteWithinSearchDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2_000);
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("{}".getBytes());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        LuceneSearchProperties properties = new LuceneSearchProperties();
        properties.setEngine("opensearch");
        properties.getOpenSearch().setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        OpenSearchMcpSearchService service = new OpenSearchMcpSearchService(
            properties,
            new McpEmbeddingClient(properties, new ObjectMapper()),
            new ObjectMapper());
        Method method = OpenSearchMcpSearchService.class
            .getDeclaredMethod("performRequestWithDeadline", Request.class, int.class);
        method.setAccessible(true);

        try {
            long startNanos = System.nanoTime();
            assertThatThrownBy(() -> method.invoke(service, new Request("GET", "/slow"), 100))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - startNanos).toMillis()).isLessThan(1_000);
        } finally {
            service.close();
            server.stop(0);
        }
    }
}
