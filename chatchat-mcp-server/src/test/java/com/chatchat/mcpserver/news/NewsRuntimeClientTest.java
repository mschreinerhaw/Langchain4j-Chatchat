package com.chatchat.mcpserver.news;

import com.chatchat.common.security.InternalCredentialProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NewsRuntimeClientTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void sendsResolvedInternalAccountAndUnwrapsRuntimeEnvelope() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/v1/news/health", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"code\":200,\"data\":{\"status\":\"UP\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        InternalCredentialProperties credentials = new InternalCredentialProperties();
        credentials.setUsername("internal-news"); credentials.setSecret("secret-value");
        NewsRuntimeClient client = new NewsRuntimeClient(new ObjectMapper(), credentials, HttpClient.newHttpClient(),
            "http://localhost:" + server.getAddress().getPort(), Duration.ofSeconds(5));

        assertThat(client.get("/health").path("status").asText()).isEqualTo("UP");
        assertThat(authorization.get()).isEqualTo("Basic aW50ZXJuYWwtbmV3czpzZWNyZXQtdmFsdWU=");
    }
}
