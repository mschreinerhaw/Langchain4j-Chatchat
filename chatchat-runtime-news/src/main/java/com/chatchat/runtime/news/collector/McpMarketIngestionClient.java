package com.chatchat.runtime.news.collector;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalRequestSigner;
import com.chatchat.runtime.news.model.RawNewsItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Sends structured exchange observations to the MCP-owned market capability. */
@Service
public class McpMarketIngestionClient {
    private static final String PATH = "/internal/v1/market/observations";
    private final ObjectMapper mapper;
    private final InternalCredentialProperties credentials;
    private final HttpClient client;
    private final String baseUrl;

    public McpMarketIngestionClient(ObjectMapper mapper, InternalCredentialProperties credentials,
                                    @Value("${chatchat.runtime.news.mcp-server.base-url:http://localhost:8090}") String baseUrl) {
        this.mapper = mapper;
        this.credentials = credentials;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public void accept(RawNewsItem item) {
        if (!accepts(item)) return;
        try {
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String signature = InternalRequestSigner.sign(credentials.resolvedSecret(), "POST", PATH, timestamp, nonce);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + PATH)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header(InternalRequestSigner.USER_HEADER, credentials.resolvedUsername())
                .header(InternalRequestSigner.TIMESTAMP_HEADER, timestamp)
                .header(InternalRequestSigner.NONCE_HEADER, nonce)
                .header(InternalRequestSigner.SIGNATURE_HEADER, signature)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload(item)), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var envelope = mapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || envelope.path("code").asInt(500) >= 400) {
                throw new IllegalStateException("MCP Market capability failed: HTTP " + response.statusCode()
                    + ", code=" + envelope.path("code").asInt(500) + ", message="
                    + envelope.path("message").asText("request failed"));
            }
            if (envelope.path("data").isMissingNode() || envelope.path("data").isNull()) {
                throw new IllegalStateException("MCP Market capability returned no stored observation");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP Market ingestion interrupted", ex);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot send market observation to MCP Server at " + baseUrl
                + ": " + ex.getMessage(), ex);
        }
    }

    public boolean accepts(RawNewsItem item) {
        if (item == null || item.metadata() == null) return false;
        Map<String, Object> metadata = item.metadata();
        return metadata.containsKey("dataset") || metadata.containsKey("datasetCode")
            || metadata.containsKey("quoteCode") || metadata.containsKey("indexCode");
    }

    private Map<String, Object> payload(RawNewsItem item) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", item.source().id());
        source.put("code", item.source().code());
        source.put("name", item.source().name());
        source.put("entryUrl", item.source().entryUrl());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", source);
        value.put("title", item.title());
        value.put("content", item.content());
        value.put("summary", item.summary());
        value.put("author", item.author());
        value.put("sourceUrl", item.sourceUrl());
        value.put("publishTime", item.publishTime());
        value.put("language", item.language());
        value.put("categories", item.categories());
        value.put("tags", item.tags());
        value.put("metadata", item.metadata());
        return value;
    }
}
