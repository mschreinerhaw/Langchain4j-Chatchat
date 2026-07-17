package com.chatchat.mcpserver.news;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Service
public class NewsRuntimeClient {
    private final ObjectMapper mapper;
    private final InternalCredentialProperties credentials;
    private final HttpClient client;
    private final String baseUrl;
    private final Duration timeout;

    @Autowired
    public NewsRuntimeClient(ObjectMapper mapper, InternalCredentialProperties credentials,
                             @Value("${chatchat.mcp.news-runtime.base-url:http://localhost:8091}") String baseUrl,
                             @Value("${chatchat.mcp.news-runtime.timeout-millis:30000}") long timeoutMillis) {
        this(mapper, credentials, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
            baseUrl, Duration.ofMillis(timeoutMillis));
    }

    NewsRuntimeClient(ObjectMapper mapper, InternalCredentialProperties credentials, HttpClient client,
                      String baseUrl, Duration timeout) {
        this.mapper = mapper; this.credentials = credentials; this.client = client;
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.timeout = timeout;
    }

    public JsonNode get(String path) { return exchange("GET", path, null); }
    public JsonNode post(String path, Object body) { return exchange("POST", path, body); }
    public JsonNode put(String path, Object body) { return exchange("PUT", path, body); }
    public void delete(String path) { exchange("DELETE", path, null); }

    public ToolOutput invoke(String toolName, ToolInput input) {
        return mapper.convertValue(post("/tools/" + toolName, input), ToolOutput.class);
    }

    public boolean available() {
        try { return "UP".equals(get("/health").path("status").asText()); }
        catch (Exception ignored) { return false; }
    }

    private JsonNode exchange(String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/news" + path))
                .timeout(timeout).header("Accept", "application/json").header("Authorization", authorization());
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            JsonNode envelope = mapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || envelope.path("code").asInt(500) >= 400) {
                throw new IllegalStateException("News Runtime HTTP " + response.statusCode() + ": "
                    + envelope.path("message").asText("request failed"));
            }
            return envelope.path("data");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("News Runtime request interrupted", ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Cannot communicate with News Runtime at " + baseUrl, ex);
        }
    }

    private String authorization() {
        String pair = credentials.resolvedUsername() + ":" + credentials.resolvedSecret();
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }
}
