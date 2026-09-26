package com.chatchat.chat.skills.federation;

import com.chatchat.common.skills.federation.SkillSourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class McpSkillSourceRepositoryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::respond);
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void discoversDescribesAndReadsAStandardMcpSkill() {
        McpSkillSourceRepository repository = new McpSkillSourceRepository(objectMapper);
        SkillSourceRepository.SourceConnection connection =
            new SkillSourceRepository.SourceConnection("source-1", endpoint, "Bearer secret");

        repository.verifyCapabilities(connection);
        SkillSourceRepository.SkillPage page = repository.discover(connection, null, 100);
        SkillSourceRepository.SkillManifest manifest = repository.describe(connection, page.skills().get(0).uri());
        SkillSourceRepository.SkillResource content = repository.read(connection, manifest.uri());

        assertThat(page.skills()).hasSize(1);
        assertThat(manifest.name()).isEqualTo("investment-analysis");
        assertThat(manifest.description()).isEqualTo("Analyze investments");
        assertThat(manifest.resources()).singleElement().satisfies(resource -> {
            assertThat(resource.digest()).startsWith("sha256:");
            assertThat(resource.size()).isEqualTo(7);
        });
        assertThat(new String(content.bytes(), StandardCharsets.UTF_8)).isEqualTo("# Skill");
        assertThat(lastRequest.get().path("params").path("_meta")
            .path("io.modelcontextprotocol/protocolVersion").asText()).isEqualTo("2026-07-28");
    }

    private void respond(HttpExchange exchange) throws IOException {
        JsonNode request = objectMapper.readTree(exchange.getRequestBody());
        lastRequest.set(request);
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer secret");
        String method = request.path("method").asText();
        String result = switch (method) {
            case "server/discover" -> """
                {"capabilities":{"resources":{},"extensions":{"io.modelcontextprotocol/skills":{}}}}
                """;
            case "skills/list" -> """
                {"resultType":"complete","ttlMs":300000,"cacheScope":"public","skills":[{"uri":"skill://investment-analysis/SKILL.md","frontmatter":{"name":"investment-analysis","description":"Analyze investments"},"resources":[{"uri":"skill://investment-analysis/SKILL.md","digest":"sha256:8addf76af50e290686b3213f6ff86dc3f4b944cd73ed6d93e7f1504ebd091cc8","size":7}]}]}
                """;
            case "skills/get" -> """
                {"resultType":"complete","ttlMs":300000,"cacheScope":"public","skill":{"uri":"skill://investment-analysis/SKILL.md","frontmatter":{"name":"investment-analysis","description":"Analyze investments"},"resources":[{"uri":"skill://investment-analysis/SKILL.md","digest":"sha256:8addf76af50e290686b3213f6ff86dc3f4b944cd73ed6d93e7f1504ebd091cc8","size":7}]}}
                """;
            case "resources/read" -> """
                {"contents":[{"uri":"skill://investment-analysis/SKILL.md","mimeType":"text/markdown","text":"# Skill"}]}
                """;
            default -> throw new IllegalArgumentException("Unexpected method " + method);
        };
        byte[] response = ("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":" + result + "}")
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
