package com.chatchat.mcpserver.search;

import com.chatchat.mcpserver.api.ApiServiceConfigService;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.SshHostConfigService;
import com.chatchat.mcpserver.routing.AssetMetadataFactory;
import com.chatchat.mcpserver.sql.MetadataIndex;
import com.chatchat.mcpserver.sql.MetadataIndexService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.TableLocation;
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
    private final ApiServiceConfigService apiServiceConfigService;
    private final AssetMetadataFactory assetMetadataFactory;
    private final MetadataIndexService metadataIndexService;

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
        List<LuceneMcpSearchService.AssetDoc> sshDocs = new ArrayList<>();
        List<LuceneMcpSearchService.AssetDoc> sqlDocs = new ArrayList<>();
        List<LuceneMcpSearchService.AssetDoc> httpDocs = new ArrayList<>();
        List<LuceneMcpSearchService.AssetDoc> apiServiceDocs = new ArrayList<>();
        safe(hostConfigService.listEnabled()).stream()
            .map(assetMetadataFactory::sshAsset)
            .map(this::assetDoc)
            .forEach(sshDocs::add);
        List<SqlDatasourceConfig> datasources = safe(datasourceConfigService.listEnabled());
        datasources.stream()
            .map(assetMetadataFactory::sqlDatasource)
            .map(this::assetDoc)
            .forEach(sqlDocs::add);
        datasources.stream()
            .flatMap(datasource -> tableAssetDocs(datasource).stream())
            .forEach(sqlDocs::add);
        safe(httpEndpointConfigService.listEnabled()).stream()
            .map(assetMetadataFactory::httpEndpoint)
            .map(this::assetDoc)
            .forEach(httpDocs::add);
        safe(apiServiceConfigService.listEnabled()).stream()
            .map(config -> new LuceneMcpSearchService.AssetDoc(
                config.getId(),
                "api_service",
                config.getToolName(),
                firstText(config.getTitle(), config.getToolName()),
                config.getToolName(),
                null,
                null,
                apiServiceLabels(config.getToolName(), config.getTitle(), config.getDescription(),
                    config.getBusinessGroup(), config.getBusinessGroupName(), config.getBusinessGroupDescription(), config.getMethod()),
                "api_service_asset_registry",
                null,
                null,
                null,
                null,
                joinPath(config.getDescription(), config.getBusinessGroup(), config.getBusinessGroupName(),
                    config.getBusinessGroupDescription(), config.getMethod()),
                null,
                null
            ))
            .forEach(apiServiceDocs::add);
        luceneSearchService.indexAssets("ssh_host", sshDocs);
        luceneSearchService.indexAssets("sql_datasource", sqlDocs);
        luceneSearchService.indexAssets("http_endpoint", httpDocs);
        luceneSearchService.indexAssets("api_service", apiServiceDocs);
        long tableDocCount = sqlDocs.stream().filter(doc -> "metadata_table".equals(doc.source())).count();
        int indexed = sshDocs.size() + sqlDocs.size() + httpDocs.size() + apiServiceDocs.size();
        Map<String, Object> summary = Map.of(
            "enabled", true,
            "indexed", indexed,
            "sshHostCount", sshDocs.size(),
            "sqlDatasourceCount", datasources.size(),
            "sqlTableCount", tableDocCount,
            "httpEndpointCount", httpDocs.size(),
            "apiServiceCount", apiServiceDocs.size(),
            "indexes", Map.of(
                "service", Map.of("assetType", "ssh_host", "physicalIndex", luceneSearchService.assetIndexName("ssh_host"), "indexed", sshDocs.size()),
                "apiService", Map.of("assetType", "api_service", "physicalIndex", luceneSearchService.assetIndexName("api_service"), "indexed", apiServiceDocs.size()),
                "database", Map.of("assetType", "sql_datasource", "physicalIndex", luceneSearchService.assetIndexName("sql_datasource"), "indexed", sqlDocs.size()),
                "http", Map.of("assetType", "http_endpoint", "physicalIndex", luceneSearchService.assetIndexName("http_endpoint"), "indexed", httpDocs.size())
            )
        );
        log.info("MCP Lucene asset index refreshed, indexed {} docs ssh={} apiService={} sql={} sqlTables={} http={}",
            indexed, summary.get("sshHostCount"), summary.get("apiServiceCount"), summary.get("sqlDatasourceCount"),
            summary.get("sqlTableCount"), summary.get("httpEndpointCount"));
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

    private List<LuceneMcpSearchService.AssetDoc> tableAssetDocs(SqlDatasourceConfig datasource) {
        if (datasource == null || datasource.getId() == null || metadataIndexService == null) {
            return List.of();
        }
        MetadataIndex index = metadataIndexService.indexFor(datasource);
        if (index == null || index.error() != null || index.tables() == null || index.tables().isEmpty()) {
            return List.of();
        }
        return index.tables().stream()
            .map(table -> tableAssetDoc(datasource, table, index.databaseType()))
            .toList();
    }

    private LuceneMcpSearchService.AssetDoc tableAssetDoc(SqlDatasourceConfig datasource, TableLocation table, String databaseType) {
        String database = text(table.database());
        String tableName = text(table.table());
        String assetName = firstText(datasource.getTitle(), datasource.getName(), datasource.getToolName(), datasource.getId());
        String fullPath = joinPath(assetName, database, tableName);
        String tableComment = text(table.tableComment());
        String databaseComment = firstText(table.databaseComment(), datasource.getDescription(), datasource.getTitle(), datasource.getName());
        return new LuceneMcpSearchService.AssetDoc(
            tableDocId(datasource.getId(), database, tableName),
            "sql_datasource",
            fullPath,
            tableName,
            datasource.getToolName(),
            datasource.getEnvironment(),
            firstText(databaseType, datasource.getDatabaseType()),
            tableLabels(datasource, database, tableName, fullPath),
            "metadata_table",
            datasource.getId(),
            database,
            tableName,
            fullPath,
            joinPath(tableComment, databaseComment),
            tableComment,
            databaseComment
        );
    }

    private List<String> tableLabels(SqlDatasourceConfig datasource, String database, String tableName, String fullPath) {
        List<String> labels = new ArrayList<>();
        labels.add("metadata_table");
        labels.add("table");
        addLabel(labels, datasource.getName());
        addLabel(labels, datasource.getTitle());
        addLabel(labels, datasource.getDescription());
        addLabel(labels, datasource.getToolName());
        addLabel(labels, datasource.getEnvironment());
        addLabel(labels, datasource.getDatabaseType());
        addLabel(labels, database);
        addLabel(labels, tableName);
        addLabel(labels, fullPath);
        addLabel(labels, "database:" + database);
        addLabel(labels, "schema:" + database);
        addLabel(labels, "table:" + tableName);
        return labels.stream()
            .map(this::normalize)
            .filter(item -> item != null && !item.isBlank())
            .distinct()
            .toList();
    }

    private String tableDocId(String datasourceId, String database, String tableName) {
        return "metadata_table:" + normalize(firstText(datasourceId, "unknown"))
            + ":" + normalize(firstText(database, "unknown"))
            + ":" + normalize(firstText(tableName, "unknown"));
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

    private List<String> apiServiceLabels(String... values) {
        List<String> labels = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                addLabel(labels, value);
            }
        }
        return labels.stream()
            .map(this::normalize)
            .filter(item -> item != null && !item.isBlank())
            .distinct()
            .toList();
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String joinPath(String... values) {
        List<String> parts = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                String text = text(value);
                if (text != null) {
                    parts.add(text);
                }
            }
        }
        return String.join(".", parts);
    }

    private void addLabel(List<String> labels, String value) {
        String text = text(value);
        if (text != null) {
            labels.add(text);
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
