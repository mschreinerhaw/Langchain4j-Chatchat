package com.chatchat.mcpserver.search;

import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.SshHostConfigService;
import com.chatchat.mcpserver.routing.AssetMetadataFactory;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpAssetLuceneIndexService {

    private final LuceneMcpSearchService luceneSearchService;
    private final SshHostConfigService hostConfigService;
    private final SqlDatasourceConfigService datasourceConfigService;
    private final HttpEndpointConfigService httpEndpointConfigService;
    private final AssetMetadataFactory assetMetadataFactory;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refreshAll();
    }

    public synchronized Map<String, Object> refreshAll() {
        if (luceneSearchService == null || !luceneSearchService.enabled()) {
            log.info("MCP Lucene asset index refresh skipped because Lucene is disabled");
            return Map.of("enabled", false, "indexed", 0);
        }
        List<LuceneMcpSearchService.AssetDoc> docs = new ArrayList<>();
        safe(hostConfigService.listEnabled()).stream()
            .map(assetMetadataFactory::sshAsset)
            .map(this::assetDoc)
            .forEach(docs::add);
        safe(datasourceConfigService.listEnabled()).stream()
            .map(assetMetadataFactory::sqlDatasource)
            .map(this::assetDoc)
            .forEach(docs::add);
        safe(httpEndpointConfigService.listEnabled()).stream()
            .map(assetMetadataFactory::httpEndpoint)
            .map(this::assetDoc)
            .forEach(docs::add);
        luceneSearchService.indexAssets(docs);
        Map<String, Object> summary = Map.of(
            "enabled", true,
            "indexed", docs.size(),
            "sshHostCount", safe(hostConfigService.listEnabled()).size(),
            "sqlDatasourceCount", safe(datasourceConfigService.listEnabled()).size(),
            "httpEndpointCount", safe(httpEndpointConfigService.listEnabled()).size()
        );
        log.info("MCP Lucene asset index refreshed, indexed {} assets ssh={} sql={} http={}",
            docs.size(), summary.get("sshHostCount"), summary.get("sqlDatasourceCount"), summary.get("httpEndpointCount"));
        return summary;
    }

    @SuppressWarnings("unchecked")
    private LuceneMcpSearchService.AssetDoc assetDoc(Map<String, Object> metadata) {
        Map<String, Object> asset = metadata == null || !(metadata.get("asset") instanceof Map<?, ?> map)
            ? Map.of()
            : new LinkedHashMap<>((Map<String, Object>) map);
        Map<String, Object> routingHints = metadata == null || !(metadata.get("routingHints") instanceof Map<?, ?> map)
            ? Map.of()
            : new LinkedHashMap<>((Map<String, Object>) map);
        Map<String, Object> capabilities = metadata == null || !(metadata.get("capabilities") instanceof Map<?, ?> map)
            ? Map.of()
            : new LinkedHashMap<>((Map<String, Object>) map);
        return new LuceneMcpSearchService.AssetDoc(
            text(asset.get("id")),
            text(metadata == null ? null : metadata.get("assetType")),
            text(asset.get("name")),
            text(asset.get("displayName")),
            text(asset.get("toolName")),
            text(asset.get("environment")),
            text(capabilities.get("databaseType")),
            labels(routingHints.get("labels")),
            "asset_registry"
        );
    }

    private List<String> labels(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(String::valueOf)
                .map(this::normalize)
                .filter(item -> item != null && !item.isBlank())
                .toList();
        }
        return List.of();
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
