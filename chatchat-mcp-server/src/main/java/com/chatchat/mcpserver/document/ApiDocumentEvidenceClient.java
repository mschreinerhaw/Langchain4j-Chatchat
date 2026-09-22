package com.chatchat.mcpserver.document;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalRequestSigner;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.retrieval.ResourceAuthorizationRequest;
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
import java.util.Set;
import java.util.UUID;

/** Reads API-owned document evidence without copying its database into MCP. */
@Component
@RequiredArgsConstructor
public class ApiDocumentEvidenceClient {
    private static final String PATH = "/internal/v1/document-search";
    private static final String AUTHORIZATION_PATH = "/internal/v1/resource-authorization";
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

    /** Uses the API's current database grants; transport or parse errors deny the evidence. */
    public Set<String> allowedDocumentIds(String tenantId, String userId, Set<String> candidateIds) {
        return allowedResourceIds(ResourceAuthorizationPort.KNOWLEDGE, tenantId, userId, candidateIds);
    }

    public Set<String> allowedResourceIds(String resourceType, String tenantId, String userId,
                                          Set<String> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) return Set.of();
        if (resourceType == null || resourceType.isBlank() || candidateIds.size() > 500
            || tenantId == null || tenantId.isBlank()
            || userId == null || userId.isBlank() || !credentials.isEnabled()
            || credentials.resolvedSecret().isBlank()) {
            throw new IllegalStateException("Document authorization context is unavailable");
        }
        try {
            String baseUrl = environment.getProperty("chatchat.mcp.authorization.api-base-url", "http://localhost:8080")
                .replaceAll("/+$", "");
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + AUTHORIZATION_PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(InternalRequestSigner.USER_HEADER, credentials.resolvedUsername())
                .header(InternalRequestSigner.TIMESTAMP_HEADER, timestamp)
                .header(InternalRequestSigner.NONCE_HEADER, nonce)
                .header(InternalRequestSigner.SIGNATURE_HEADER,
                    InternalRequestSigner.sign(credentials.resolvedSecret(), "POST", AUTHORIZATION_PATH, timestamp, nonce))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                    new ResourceAuthorizationRequest(tenantId, userId, resourceType, candidateIds))))
                .build();
            HttpResponse<byte[]> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw new IllegalStateException("API authorization returned HTTP " + response.statusCode());
            JsonNode body = mapper.readTree(response.body());
            if (body.path("code").asInt(500) != 200 || !body.path("data").path("allowedIds").isArray()) {
                throw new IllegalStateException("API authorization rejected the request");
            }
            Set<String> allowed = new java.util.HashSet<>();
            body.path("data").path("allowedIds").forEach(item -> allowed.add(item.asText()));
            allowed.retainAll(candidateIds);
            return Set.copyOf(allowed);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("API authorization interrupted", ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("API authorization unavailable", ex);
        }
    }
}
