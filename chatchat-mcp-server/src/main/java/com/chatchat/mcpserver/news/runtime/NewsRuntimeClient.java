package com.chatchat.mcpserver.news.runtime;

import com.chatchat.common.concurrent.CancellationSupport;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalRequestSigner;
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
import java.time.Instant;
import java.util.UUID;

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
                             @Value("${chatchat.mcp.news-runtime.timeout-millis:20000}") long timeoutMillis) {
        this(mapper, credentials, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(5_000L, normalizedTimeoutMillis(timeoutMillis))))
                .followRedirects(HttpClient.Redirect.NORMAL).build(),
            baseUrl, Duration.ofMillis(normalizedTimeoutMillis(timeoutMillis)));
    }

    NewsRuntimeClient(ObjectMapper mapper, InternalCredentialProperties credentials, HttpClient client,
                      String baseUrl, Duration timeout) {
        this.mapper = mapper; this.credentials = credentials; this.client = client;
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.timeout = timeout;
    }

    private static long normalizedTimeoutMillis(long timeoutMillis) {
        return Math.max(1_000L, timeoutMillis);
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
        catch (Exception failure) {
            CancellationSupport.rethrowIfCancelled(failure, "News Runtime health check");
            return false;
        }
    }

    private JsonNode exchange(String method, String path, Object body) {
        try {
            String requestPath = "/internal/v1/news" + path;
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String signature = InternalRequestSigner.sign(credentials.resolvedSecret(), method,
                URI.create(requestPath).getPath(), timestamp, nonce);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + requestPath))
                .timeout(timeout).header("Accept", "application/json")
                .header(InternalRequestSigner.USER_HEADER, credentials.resolvedUsername())
                .header(InternalRequestSigner.TIMESTAMP_HEADER, timestamp)
                .header(InternalRequestSigner.NONCE_HEADER, nonce)
                .header(InternalRequestSigner.SIGNATURE_HEADER, signature);
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
            Thread.currentThread().interrupt();
            throw CancellationSupport.cancelled("News Runtime request", ex);
        } catch (Exception ex) {
            CancellationSupport.rethrowIfCancelled(ex, "News Runtime request");
            if (ex instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Cannot communicate with News Runtime at " + baseUrl, ex);
        }
    }

}
