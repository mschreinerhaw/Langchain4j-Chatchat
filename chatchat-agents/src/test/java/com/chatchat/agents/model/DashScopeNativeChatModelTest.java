package com.chatchat.agents.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DashScopeNativeChatModelTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void translatesLangChain4jMessagesToolsAndResponse() throws Exception {
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/aigc/multimodal-generation/generation", exchange -> {
            captured.set(mapper.readTree(exchange.getRequestBody()));
            byte[] response = """
                {
                  "output":{"choices":[{"finish_reason":"tool_calls","message":{
                    "role":"assistant","content":[],"reasoning_content":"checking",
                    "tool_calls":[{"id":"call-1","type":"function","function":{
                      "name":"weather","arguments":"{\\"city\\":\\"Hangzhou\\"}"}}]
                  }}]},
                  "usage":{"input_tokens":12,"output_tokens":4,"total_tokens":16},
                  "request_id":"request-1"
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        String endpoint = "http://localhost:" + server.getAddress().getPort()
            + "/api/v1/services/aigc/multimodal-generation/generation";
        DashScopeNativeChatModel model = new DashScopeNativeChatModel(
            endpoint, true, "test-key", "qwen3.6-plus", Duration.ofSeconds(5),
            1000, 0, HttpClient.newHttpClient(), mapper);
        ToolSpecification tool = ToolSpecification.fromJson("""
            {"name":"weather","description":"Get weather","parameters":{
              "type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}
            """);

        ChatResponse response = model.chat(ChatRequest.builder()
            .messages(List.of(SystemMessage.from("Be concise"), UserMessage.from("Weather?")))
            .toolSpecifications(List.of(tool))
            .build());

        JsonNode request = captured.get();
        assertThat(request.path("model").asText()).isEqualTo("qwen3.6-plus");
        assertThat(request.at("/input/messages/0/content/0/text").asText()).isEqualTo("Be concise");
        assertThat(request.at("/input/messages/1/content/0/text").asText()).isEqualTo("Weather?");
        assertThat(request.at("/parameters/tools/0/function/name").asText()).isEqualTo("weather");
        assertThat(request.at("/parameters/result_format").asText()).isEqualTo("message");
        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_EXECUTION);
        assertThat(response.tokenUsage().totalTokenCount()).isEqualTo(16);
        assertThat(response.aiMessage().thinking()).isEqualTo("checking");
        assertThat(response.aiMessage().toolExecutionRequests()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call-1");
            assertThat(call.name()).isEqualTo("weather");
            assertThat(call.arguments()).contains("Hangzhou");
        });
    }

    @Test
    void surfacesProviderErrorMessage() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/generation", exchange -> {
            byte[] response = "{\"code\":\"InvalidParameter\",\"message\":\"bad model\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        DashScopeNativeChatModel model = new DashScopeNativeChatModel(
            "http://localhost:" + server.getAddress().getPort() + "/generation",
            false, "test-key", "bad", Duration.ofSeconds(5), -1, 0,
            HttpClient.newHttpClient(), mapper);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.chat("hello"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("HTTP 400")
            .hasMessageContaining("bad model");
    }
}
