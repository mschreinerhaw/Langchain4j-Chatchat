package com.chatchat.api.controller.agent;

import com.chatchat.knowledgebase.search.document.api.library.LibraryDocumentItem;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "chatchat.document.gateway", name = "enabled", havingValue = "true")
public class McpDocumentLibraryReadPort implements DocumentLibraryReadPort {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String token;

    public McpDocumentLibraryReadPort(ObjectMapper mapper,
                                      @Value("${chatchat.mcp.center.base-url}") String baseUrl,
                                      @Value("${CHATCHAT_DOCUMENT_GATEWAY_TOKEN:}") String token) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    @Override
    public List<LibraryDocumentItem> list(SearchPermissionContext context) {
        JsonNode data = fetch("/library?page=1&pageSize=500", context);
        return mapper.convertValue(data.path("documents"), mapper.getTypeFactory()
            .constructCollectionType(List.class, LibraryDocumentItem.class));
    }

    @Override
    public Optional<SearchDocument> get(String documentId, SearchPermissionContext context) {
        if (documentId == null || documentId.isBlank()) return Optional.empty();
        JsonNode data = fetch("/documents/" + URLEncoder.encode(documentId, StandardCharsets.UTF_8), context);
        return data.isMissingNode() ? Optional.empty() : Optional.of(mapper.convertValue(data, SearchDocument.class));
    }

    @Override
    public Optional<byte[]> original(String documentId, SearchPermissionContext context) {
        if (documentId == null || documentId.isBlank()) return Optional.empty();
        HttpRequest request = request("/documents/" + URLEncoder.encode(documentId, StandardCharsets.UTF_8) + "/file", context).build();
        try {
            HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                if (response.statusCode() == 404) return Optional.empty();
                if (response.statusCode() != 200) throw new IllegalStateException("MCP document file lookup failed: HTTP " + response.statusCode());
                return Optional.of(input.readNBytes(2 * 1024 * 1024 + 1));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("MCP document lookup interrupted", exception);
        } catch (java.io.IOException exception) { throw new IllegalStateException("MCP document service unavailable", exception); }
    }

    @Override
    public boolean exists(String documentId) {
        if (documentId == null || documentId.isBlank()) return false;
        JsonNode data = fetch("/documents/" + URLEncoder.encode(documentId, StandardCharsets.UTF_8),
            SearchPermissionContext.system());
        return !data.isMissingNode() && !"DELETED".equalsIgnoreCase(data.path("lifecycleStatus").asText());
    }

    private JsonNode fetch(String suffix, SearchPermissionContext context) {
        if (token.isBlank()) throw new IllegalStateException("document gateway token is not configured");
        HttpRequest request = request(suffix, context)
            .GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = mapper.readTree(response.body());
            if (response.statusCode() == 404 || body.path("code").asInt() == 404) return MissingNode.getInstance();
            if (response.statusCode() != 200 || body.path("code").asInt() != 200) {
                throw new IllegalStateException("MCP document lookup failed: HTTP " + response.statusCode());
            }
            return body.path("data");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP document lookup interrupted", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("MCP document service unavailable", exception);
        }
    }

    private HttpRequest.Builder request(String suffix, SearchPermissionContext context) {
        if (token.isBlank()) throw new IllegalStateException("document gateway token is not configured");
        return HttpRequest.newBuilder(URI.create(baseUrl + "/internal/api/v1/search" + suffix))
            .timeout(Duration.ofSeconds(30))
            .header("X-Document-Gateway-Token", token)
            .header("X-Document-Tenant-Id", context.tenantId())
            .header("X-Document-User-Id", context.userId())
            .header("X-Document-Roles", String.join(",", context.roles()));
    }
}
