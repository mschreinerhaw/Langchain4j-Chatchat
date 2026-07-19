package com.chatchat.tools.livedata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LivedataSessionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void logsInWithoutConfiguredCredentials() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startLoginServer(requestBody, "{\"code\":0,\"sessionId\":\"issued-session\"}");
        LivedataAutoRegistrationProperties properties = properties();

        String sessionId = service(properties).currentSessionId();

        assertThat(sessionId).isEqualTo("issued-session");
        JsonNode requestJson = objectMapper.readTree(requestBody.get());
        assertThat(requestJson.isObject()).isTrue();
        assertThat(requestJson.isEmpty()).isTrue();
    }

    @Test
    void sendsCredentialsWhenTheyAreConfiguredAndAcceptsLoginIdResponse() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startLoginServer(requestBody, "{\"data\":{\"loginId\":\"issued-login\"}}");
        LivedataAutoRegistrationProperties properties = properties();
        properties.setLoginId("account");
        properties.setLoginPwd("secret");

        String sessionId = service(properties).currentSessionId();

        assertThat(sessionId).isEqualTo("issued-login");
        JsonNode requestJson = objectMapper.readTree(requestBody.get());
        assertThat(requestJson.path("loginId").asText()).isEqualTo("account");
        assertThat(requestJson.path("loginPwd").asText()).isEqualTo("secret");
    }

    @Test
    void reportsDisabledLoginInsteadOfMissingCredentials() {
        LivedataAutoRegistrationProperties properties = properties();
        properties.setLoginEnabled(false);

        assertThatThrownBy(() -> service(properties).currentSessionId())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("login is disabled");
    }

    private void startLoginServer(AtomicReference<String> requestBody, String responseBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    private LivedataAutoRegistrationProperties properties() {
        LivedataAutoRegistrationProperties properties = new LivedataAutoRegistrationProperties();
        properties.setLoginEnabled(true);
        properties.setServiceBaseUrl(server == null
            ? "http://127.0.0.1:1"
            : "http://127.0.0.1:" + server.getAddress().getPort());
        properties.setLoginPath("/login");
        properties.setLoginTimeoutMs(2000);
        return properties;
    }

    private LivedataSessionService service(LivedataAutoRegistrationProperties properties) {
        return new LivedataSessionService(() -> properties, objectMapper);
    }
}
