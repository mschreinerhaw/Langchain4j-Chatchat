package com.chatchat.mcpserver.sql;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MetadataResolverService {

    private final SqlDatasourceConfigService datasourceConfigService;
    private final MetadataIndexService metadataIndexService;
    private final MetadataResolverEngine resolverEngine;
    private final TableSemanticMatcher semanticMatcher;
    private final MetadataUsageHistoryService usageHistoryService;

    public List<String> listDatabases(String datasourceId) {
        return metadataIndexService.listDatabases(datasourceConfigService.getEnabled(datasourceId));
    }

    public List<String> listTables(String datasourceId, String database) {
        return metadataIndexService.listTables(datasourceConfigService.getEnabled(datasourceId), database);
    }

    public List<TableLocation> resolveTable(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return List.of();
        }
        return datasourceConfigService.listEnabled().stream()
            .flatMap(datasource -> resolveTable(datasource, tableName, null).candidates().stream())
            .sorted(Comparator.comparingDouble(TableLocation::score).reversed()
                .thenComparing(location -> String.valueOf(location.datasourceId()))
                .thenComparing(location -> String.valueOf(location.database()))
                .thenComparing(location -> String.valueOf(location.table())))
            .toList();
    }

    public TableResolution resolveTable(SqlDatasourceConfig datasource, String tableName, String preferredSchema) {
        String datasourceType = resolvedDatabaseType(datasource);
        if (!Set.of("mysql", "mariadb", "postgresql", "sqlserver").contains(datasourceType)) {
            return TableResolution.unsupported(datasource == null ? null : datasource.getId(), tableName, preferredSchema, datasourceType);
        }
        long startedAt = System.currentTimeMillis();
        try {
            MetadataIndex index = metadataIndexService.indexFor(datasource);
            if (index.error() != null) {
                return new TableResolution(
                    datasource.getId(),
                    datasourceType,
                    tableName,
                    preferredSchema,
                    null,
                    tableName,
                    index.error().equals("unsupported_database_type") ? "unsupported_database_type" : "lookup_failed",
                    0.0,
                    List.of(),
                    Math.max(0, System.currentTimeMillis() - startedAt),
                    index.cacheHit(),
                    index.error()
                );
            }
            MetadataResolveContext context = new MetadataResolveContext(tableName, preferredSchema, datasource);
            List<TableLocation> ranked = resolverEngine.rank(context, semanticCandidates(datasource, tableName));
            TableLocation selected = resolverEngine.select(context, ranked);
            String reason = selected == null
                ? (ranked.isEmpty() ? "not_found" : "ambiguous")
                : "resolved";
            return new TableResolution(
                datasource.getId(),
                datasourceType,
                tableName,
                preferredSchema,
                selected == null ? null : selected.database(),
                selected == null ? tableName : selected.table(),
                reason,
                resolverEngine.confidence(ranked, selected),
                ranked,
                Math.max(0, System.currentTimeMillis() - startedAt),
                index.cacheHit(),
                null
            );
        } catch (Exception ex) {
            return new TableResolution(
                datasource == null ? null : datasource.getId(),
                datasourceType,
                tableName,
                preferredSchema,
                null,
                tableName,
                "lookup_failed",
                0.0,
                List.of(),
                Math.max(0, System.currentTimeMillis() - startedAt),
                false,
                ex.getClass().getSimpleName() + ": " + ex.getMessage()
            );
        }
    }

    public void invalidate(String datasourceId) {
        metadataIndexService.invalidate(datasourceId);
    }

    public void recordUsage(TableLocation location) {
        usageHistoryService.record(location);
    }

    private List<TableLocation> semanticCandidates(SqlDatasourceConfig datasource, String tableName) {
        List<TableLocation> exact = metadataIndexService.findTables(datasource, tableName);
        if (!exact.isEmpty()) {
            return exact;
        }
        return metadataIndexService.allTables(datasource).stream()
            .filter(location -> semanticMatcher.similarity(tableName, location.table()) > 0.0)
            .limit(100)
            .toList();
    }

    private String resolvedDatabaseType(SqlDatasourceConfig datasource) {
        if (datasource == null) {
            return "generic";
        }
        return SqlDatasourceConfigService.normalizeDatabaseType(
            datasource.getDatabaseType(),
            datasource.getJdbcUrl(),
            datasource.getDriverClass()
        );
    }
}
