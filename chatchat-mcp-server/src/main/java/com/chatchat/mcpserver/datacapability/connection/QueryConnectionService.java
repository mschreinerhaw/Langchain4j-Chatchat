package com.chatchat.mcpserver.datacapability.connection;

import com.chatchat.mcpserver.datacapability.definition.CapabilityType;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Read-only references to centrally managed assets; no query-local connections are stored. */
@Service
@RequiredArgsConstructor
public class QueryConnectionService {
    private final HttpEndpointConfigService assets;

    public List<AssetReference> list(CapabilityType type) {
        return assets.listAll().stream()
            .filter(asset -> typeOf(asset) != null && (type == null || typeOf(asset) == type))
            .map(asset -> new AssetReference(asset.getId(), asset.getName(), typeOf(asset), asset.isEnabled()))
            .toList();
    }

    public HttpEndpointConfig get(String id, CapabilityType type, boolean enabled) {
        HttpEndpointConfig asset = assets.getById(id);
        if (typeOf(asset) != type || (enabled && !asset.isEnabled()))
            throw new IllegalArgumentException("Datasource asset type mismatch or disabled");
        if (!"POST".equalsIgnoreCase(asset.getMethod()))
            throw new IllegalArgumentException("Graph and search datasource assets must use POST");
        URI uri = URI.create(asset.getUrlTemplate());
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Datasource asset requires a HTTP(S) base address without credentials or query");
        return asset;
    }

    private CapabilityType typeOf(HttpEndpointConfig asset) {
        String category = asset.getCategory() == null ? "" : asset.getCategory().toLowerCase(Locale.ROOT);
        return switch (category) {
            case "graph_database", "neo4j" -> CapabilityType.GRAPH;
            case "search_engine", "opensearch" -> CapabilityType.UNSTRUCTURED;
            default -> null;
        };
    }

    public record AssetReference(String id, String name, CapabilityType type, boolean enabled) {}
}
