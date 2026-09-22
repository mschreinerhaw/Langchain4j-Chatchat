package com.chatchat.mcpserver.document;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalRequestSigner;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Optional;
import java.util.UUID;

/** Reads API-owned document evidence without copying its database into MCP. */
@Component
@RequiredArgsConstructor
public class ApiDocumentEvidenceClient {
    private static final String PATH = "/internal/v1/document-search";
    private final Environment environment;
    private final InternalCredentialProperties credentials;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Optional<DocumentSearchResult> search(DocumentSearchRequest request) {
        if (!environment.getProperty("chatchat.mcp.server.document-search.api-federation-enabled", Boolean.class, true)
            || !credentials.isEnabled() || credentials.resolvedSecret().isBlank()
            || request == null || request.userId() == null || request.userId().isBlank()) {
            return Optional.empty();
        }
        try {
            String baseUrl = environment.getProperty("chatchat.mcp.authorization.api-base-url", "http://localhost:8080")
                .replaceAll("/+$", "");
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + PATH))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header(InternalRequestSigner.USER_HEADER, credentials.resolvedUsername())
                .header(InternalRequestSigner.TIMESTAMP_HEADER, timestamp)
                .header(InternalRequestSigner.NONCE_HEADER, nonce)
                .header(InternalRequestSigner.SIGNATURE_HEADER,
                    InternalRequestSigner.sign(credentials.resolvedSecret(), "POST", PATH, timestamp, nonce))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                .build();
            HttpResponse<byte[]> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("API document search returned HTTP " + response.statusCode());
            }
            JsonNode body = mapper.readTree(response.body());
            if (body.path("code").asInt(500) != 200 || body.path("data").isMissingNode()
                || body.path("data").isNull()) {
                throw new IllegalStateException("API document search rejected the request");
            }
            return Optional.of(mapper.treeToValue(body.path("data"), DocumentSearchResult.class));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("API document search interrupted", ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("API document search unavailable", ex);
        }
    }
}
