package com.chatchat.api.controller.agent;

import com.chatchat.knowledgebase.search.document.LibraryDocumentItem;
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
        JsonNode data = get("/library?page=1&pageSize=500", context);
        return mapper.convertValue(data.path("documents"), mapper.getTypeFactory()
            .constructCollectionType(List.class, LibraryDocumentItem.class));
    }

    @Override
    public boolean exists(String documentId) {
        if (documentId == null || documentId.isBlank()) return false;
        JsonNode data = get("/documents/" + URLEncoder.encode(documentId, StandardCharsets.UTF_8),
            SearchPermissionContext.system());
        return !data.isMissingNode() && !"DELETED".equalsIgnoreCase(data.path("lifecycleStatus").asText());
    }

    private JsonNode get(String suffix, SearchPermissionContext context) {
        if (token.isBlank()) throw new IllegalStateException("document gateway token is not configured");
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/api/v1/search" + suffix))
            .timeout(Duration.ofSeconds(30))
            .header("X-Document-Gateway-Token", token)
            .header("X-Document-Tenant-Id", context.tenantId())
            .header("X-Document-User-Id", context.userId())
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
}
