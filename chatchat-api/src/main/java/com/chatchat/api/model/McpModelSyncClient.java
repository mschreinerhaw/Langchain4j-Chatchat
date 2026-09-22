package com.chatchat.api.model;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalRequestSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Sends the complete published embedding catalog to the MCP runtime on publication. */
@Component
@RequiredArgsConstructor
public class McpModelSyncClient {
    private static final String PATH = "/internal/v1/models/embeddings";
    private final Environment environment;
    private final InternalCredentialProperties credentials;
    private final ObjectMapper objectMapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public record EmbeddingModel(String name, String providerModel, String baseUrl, int dimension,
                                 Integer timeout, String apiKey, boolean enabled, boolean defaultModel) { }
    public record Snapshot(List<EmbeddingModel> models, String preferredDefault) { }

    public void synchronize(List<EmbeddingModel> models, String preferredDefault) {
        if (!credentials.isEnabled() || credentials.resolvedSecret().isBlank()) {
            throw new IllegalStateException("MCP internal credential is required to publish embedding models");
        }
        try {
            String baseUrl = environment.getProperty("chatchat.mcp.center.base-url", "http://localhost:8090")
                .replaceAll("/+$", "");
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String signature = InternalRequestSigner.sign(credentials.resolvedSecret(), "PUT", PATH, timestamp, nonce);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(InternalRequestSigner.USER_HEADER, credentials.resolvedUsername())
                .header(InternalRequestSigner.TIMESTAMP_HEADER, timestamp)
                .header(InternalRequestSigner.NONCE_HEADER, nonce)
                .header(InternalRequestSigner.SIGNATURE_HEADER, signature)
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                    new Snapshot(models, preferredDefault))))
                .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("MCP rejected model publication (HTTP " + response.statusCode() + ")");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP model publication interrupted", ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("MCP model publication failed", ex);
        }
    }
}
