package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/** Native scopes never fall back to the login user as a database or index name. */
public final class MetadataScopes {
    private MetadataScopes() {}
    public static List<String> split(String value) {
        return value == null ? List.of() : Arrays.stream(value.split("[,;\\r\\n]+"))
            .map(String::trim).filter(item -> !item.isEmpty()).distinct().toList();
    }

    public static List<String> defaults(SqlDatasourceConfig datasource) {
        if (datasource == null) return List.of();
        List<String> explicit = split(datasource.getMetadataScopeValue());
        if (!explicit.isEmpty()) return explicit;
        String type = SqlDatasourceConfigService.normalizeDatabaseType(datasource.getDatabaseType(), datasource.getJdbcUrl(), datasource.getDriverClass());
        if ("neo4j".equals(type)) return List.of("neo4j");
        if ("opensearch".equals(type) || "elasticsearch".equals(type)) return List.of();
        if ("trino".equals(type)) {
            try {
                String path = URI.create(datasource.getJdbcUrl().substring(5)).getPath();
                List<String> parts = Arrays.stream(path.split("/")).filter(part -> !part.isBlank()).toList();
                return parts.isEmpty() ? List.of() : List.of(String.join(".", parts));
            } catch (RuntimeException ex) { return List.of(); }
        }
        return List.of();
    }

    public static boolean adapted(String type) {
        return List.of("trino", "neo4j", "opensearch", "elasticsearch").contains(type);
    }

    public static List<String> trinoNamespaces(java.sql.Connection connection) throws java.sql.SQLException {
        java.util.Set<String> namespaces = new java.util.LinkedHashSet<>();
        String catalog = connection.getCatalog(), schema = connection.getSchema();
        if (catalog != null && !catalog.isBlank()) {
            namespaces.add(catalog);
            if (schema != null && !schema.isBlank()) namespaces.add(catalog + "." + schema);
        }
        var metadata = connection.getMetaData();
        try (var schemas = metadata.getSchemas()) {
            while (schemas.next() && namespaces.size() < 200) {
                String schemaCatalog = schemas.getString("TABLE_CATALOG"), schemaName = schemas.getString("TABLE_SCHEM");
                if (schemaCatalog != null && schemaName != null) namespaces.add(schemaCatalog + "." + schemaName);
            }
        }
        return namespaces.stream().sorted().toList();
    }

    public static String fingerprint(SqlDatasourceConfig datasource) {
        String key = java.util.stream.Stream.of(datasource.getDatabaseType(), datasource.getJdbcUrl(), datasource.getDriverClass(),
            datasource.getUsername(), datasource.getPassword(), datasource.getMetadataScopeType(), datasource.getMetadataScopeValue(),
            datasource.getAllowedTablesJson(), datasource.getSensitiveTablesJson(), datasource.getSensitiveFieldsJson())
            .map(value -> java.util.Objects.toString(value, ""))
            .map(value -> value.length() + ":" + value).collect(java.util.stream.Collectors.joining());
        return MetadataObject.digest(key);
    }
}
