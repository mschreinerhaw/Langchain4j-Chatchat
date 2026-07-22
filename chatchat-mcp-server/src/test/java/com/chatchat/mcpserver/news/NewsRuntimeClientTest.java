package com.chatchat.mcpserver.news;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalRequestSigner;
import com.chatchat.common.tool.ToolInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NewsRuntimeClientTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void sendsResolvedSignedInternalAccountAndUnwrapsRuntimeEnvelope() throws Exception {
        AtomicReference<String> signature = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/v1/news/health", exchange -> {
            String timestamp = exchange.getRequestHeaders().getFirst(InternalRequestSigner.TIMESTAMP_HEADER);
            String nonce = exchange.getRequestHeaders().getFirst(InternalRequestSigner.NONCE_HEADER);
            signature.set(exchange.getRequestHeaders().getFirst(InternalRequestSigner.SIGNATURE_HEADER));
            assertThat(signature.get()).isEqualTo(InternalRequestSigner.sign("secret-value", "GET",
                exchange.getRequestURI().getPath(), timestamp, nonce));
            byte[] body = "{\"code\":200,\"data\":{\"status\":\"UP\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        InternalCredentialProperties credentials = new InternalCredentialProperties();
        credentials.setUsername("internal-news"); credentials.setSecret("secret-value");
        NewsRuntimeClient client = new NewsRuntimeClient(new ObjectMapper(), credentials,
            java.net.http.HttpClient.newHttpClient(), "http://localhost:" + server.getAddress().getPort(), Duration.ofSeconds(5));

        assertThat(client.get("/health").path("status").asText()).isEqualTo("UP");
        assertThat(signature.get()).isNotBlank();
    }

    @Test
    void signsInternalFinancialToolRequestWithoutTransmittingSecret() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        InternalCredentialProperties credentials = new InternalCredentialProperties();
        credentials.setUsername("internal-user");
        credentials.setSecret("test-secret");
        server.createContext("/internal/v1/news/tools/get_financial_data", exchange -> {
            String timestamp = exchange.getRequestHeaders().getFirst(InternalRequestSigner.TIMESTAMP_HEADER);
            String nonce = exchange.getRequestHeaders().getFirst(InternalRequestSigner.NONCE_HEADER);
            String expected = InternalRequestSigner.sign("test-secret", "POST", exchange.getRequestURI().getPath(), timestamp, nonce);
            assertThat(exchange.getRequestHeaders().getFirst(InternalRequestSigner.USER_HEADER)).isEqualTo("internal-user");
            assertThat(exchange.getRequestHeaders().getFirst(InternalRequestSigner.SIGNATURE_HEADER)).isEqualTo(expected);
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            byte[] response = "{\"code\":200,\"data\":{\"success\":true,\"data\":{\"count\":0}}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        NewsRuntimeClient client = new NewsRuntimeClient(new ObjectMapper(), credentials,
            java.net.http.HttpClient.newHttpClient(), "http://localhost:" + server.getAddress().getPort(), Duration.ofSeconds(5));
        var output = client.invoke("get_financial_data", ToolInput.builder()
            .parameters(Map.of("dataset", "etf_scale_daily")).build());
        assertThat(output.isSuccess()).isTrue();
    }
}
