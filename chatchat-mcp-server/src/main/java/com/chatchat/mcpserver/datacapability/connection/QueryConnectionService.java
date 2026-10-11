package com.chatchat.mcpserver.datacapability.connection;

import com.chatchat.mcpserver.datacapability.definition.CapabilityType;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/** Read-only references to centrally managed assets; no query-local connections are stored. */
@Service
@RequiredArgsConstructor
public class QueryConnectionService {
    private final HttpEndpointConfigService assets;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService databaseAssets;

    public List<AssetReference> list(CapabilityType type) {
        return assets.listAll().stream()
            .filter(asset -> typeOf(asset) != null && (type == null || typeOf(asset) == type))
            .map(asset -> new AssetReference(asset.getId(), asset.getName(), typeOf(asset), asset.isEnabled(),
                typeOf(asset) == CapabilityType.GRAPH ? "neo4j" : "elasticsearch".equalsIgnoreCase(asset.getCategory()) ? "elasticsearch" : "opensearch"))
            .toList();
    }

    public HttpEndpointConfig get(String id, CapabilityType type, boolean enabled) {
        HttpEndpointConfig asset = id.startsWith("db:")
            ? com.chatchat.mcpserver.sql.datasource.NativeQueryDatasource.connection(enabled
                ? databaseAssets.getEnabled(id.substring(3)) : databaseAssets.getById(id.substring(3)))
            : assets.getById(id);
        if (typeOf(asset) != type || (enabled && !asset.isEnabled()))
            throw new IllegalArgumentException("Datasource asset type mismatch or disabled");
        com.chatchat.mcpserver.sql.datasource.NativeQueryDatasource.validateAddress(
            type == CapabilityType.GRAPH ? "neo4j" : "opensearch", asset.getUrlTemplate());
        return asset;
    }

    private CapabilityType typeOf(HttpEndpointConfig asset) {
        String category = asset.getCategory() == null ? "" : asset.getCategory().toLowerCase(Locale.ROOT);
        return switch (category) {
            case "graph_database", "neo4j" -> CapabilityType.GRAPH;
            case "search_engine", "opensearch", "elasticsearch" -> CapabilityType.UNSTRUCTURED;
            default -> null;
        };
    }

    public record AssetReference(String id, String name, CapabilityType type, boolean enabled, String databaseType) {
        public AssetReference(String id, String name, CapabilityType type, boolean enabled) {
            this(id, name, type, enabled, type == CapabilityType.GRAPH ? "neo4j" : "opensearch");
        }
    }
}
