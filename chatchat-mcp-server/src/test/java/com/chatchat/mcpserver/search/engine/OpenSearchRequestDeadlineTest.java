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
    @Test void callerOwnedMarketDiscoverySkipsOptionalLlmRefinementButKeepsHttpBudgets() throws Exception {
        var captured=new java.util.concurrent.atomic.AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{captured.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            byte[] body="{\"hits\":{\"hits\":[]}}".getBytes();exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        var properties=new LuceneSearchProperties();properties.setEngine("opensearch");properties.getOpenSearch().setUrl("http://127.0.0.1:"+server.getAddress().getPort());
        var mapper=new ObjectMapper();var service=new OpenSearchMcpSearchService(properties,new McpEmbeddingClient(properties,mapper),mapper);
        var budget=new com.chatchat.common.retrieval.query.QueryOptimizationBudget();budget.setLongTextThreshold(8);budget.setModelRefinementEnabled(true);
        var refiner=org.mockito.Mockito.mock(com.chatchat.common.retrieval.query.QueryRefiner.class);
        var optimizer=new com.chatchat.common.retrieval.query.SearchQueryOptimizer(budget,refiner);service.setQueryOptimizer(optimizer);
        try {
            assertThat(service.searchMarketCatalog("financial-data-asset","banking market outlook",5,true)).isEmpty();
            org.mockito.Mockito.verifyNoInteractions(refiner);assertThat(captured.get()).contains("banking market outlook","timeout");
        } finally {service.close();optimizer.close();server.stop(0);}
    }

    @Test
    void cancelsRequestWhenAsyncClientDoesNotCompleteWithinSearchDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fast",exchange->{exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write("{}".getBytes());exchange.close();});
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
            // Client/JDK initialization is setup, not the timed async cancellation under test.
            method.invoke(service,new Request("GET","/fast"),5000);
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
