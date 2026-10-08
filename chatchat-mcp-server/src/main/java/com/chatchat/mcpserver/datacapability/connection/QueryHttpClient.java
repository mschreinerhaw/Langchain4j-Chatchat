package com.chatchat.mcpserver.datacapability.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfig;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

@Component @RequiredArgsConstructor
public class QueryHttpClient {
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public JsonNode post(HttpEndpointConfig asset, String path, Object body, int timeoutSeconds, Map<String, String> headers) throws Exception {
        return send(asset, path, body, timeoutSeconds, headers, false);
    }

    public JsonNode get(HttpEndpointConfig asset, String path, int timeoutSeconds) throws Exception {
        return send(asset, path, null, timeoutSeconds, Map.of(), true);
    }

    private JsonNode send(HttpEndpointConfig asset, String path, Object body, int timeoutSeconds, Map<String, String> headers, boolean get) throws Exception {
        String endpoint = asset.getUrlTemplate().replaceAll("/+$", "");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + path))
            .timeout(Duration.ofMillis(Math.min(timeoutSeconds * 1000L, Math.max(1000, asset.getTimeoutMs()))));
        if (get) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        request.setHeader("Content-Type", "application/json");
        if (asset.getHeadersJson() != null && !asset.getHeadersJson().isBlank()) {
            JsonNode assetHeaders = json.readTree(asset.getHeadersJson());
            if (!assetHeaders.isObject()) throw new IllegalArgumentException("Asset headers must be a JSON object");
            for (var iterator = assetHeaders.fields(); iterator.hasNext();) {
                var entry = iterator.next();
                if (!entry.getValue().isTextual()) throw new IllegalArgumentException("Asset header values must be strings");
                request.setHeader(entry.getKey(), entry.getValue().textValue());
            }
        }
        headers.forEach(request::setHeader);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("Query endpoint returned HTTP " + response.statusCode());
        return json.readTree(response.body());
    }
}
