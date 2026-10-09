package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.chatchat.tools.builtin.DynamicJdbcDriverLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.util.*;

@Component
public class TrinoMetadataCollector implements MetadataCollector {
    @Autowired(required = false) private DynamicJdbcDriverLoader driverLoader;

    @Override public boolean supports(String type) { return "trino".equals(type); }

    @Override public List<MetadataObject> collect(SqlDatasourceConfig asset, List<String> namespaces) throws Exception {
        if (namespaces.isEmpty()) throw new IllegalArgumentException("请配置 Trino Catalog 或 Catalog.Schema");
        try (Connection connection = openConnection(asset)) {
            return collect(connection.getMetaData(), asset, namespaces);
        }
    }

    List<MetadataObject> collect(DatabaseMetaData metadata, SqlDatasourceConfig asset, List<String> namespaces) throws Exception {
        Map<List<String>, MetadataObject> tables = new LinkedHashMap<>();
        Map<List<String>, List<MetadataObject.Field>> columns = new LinkedHashMap<>();
        String escape = metadata.getSearchStringEscape();
        int fieldCount = 0;
        for (String namespace : namespaces) {
            String[] parts = namespace.split("\\.", 2);
            String catalog = parts[0];
            String schema = parts.length == 2 ? parts[1] : null;
            if (catalog.isBlank() || (schema != null && schema.isBlank())) throw new IllegalArgumentException("无效的 Catalog.Schema: " + namespace);
            String schemaPattern = schema == null ? null : pattern(schema, escape);
            try (ResultSet rs = metadata.getTables(catalog, schemaPattern, "%", new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"})) {
                while (rs.next()) {
                    String actualCatalog = rs.getString("TABLE_CAT"), actualSchema = rs.getString("TABLE_SCHEM"), table = rs.getString("TABLE_NAME");
                    if (!catalog.equals(actualCatalog) || actualSchema == null || table == null || (schema != null && !schema.equals(actualSchema))) continue;
                    List<String> path = List.of(actualCatalog, actualSchema, table);
                    String type = rs.getString("TABLE_TYPE");
                    tables.putIfAbsent(path, new MetadataObject(asset.getId(), "trino", type != null && type.contains("VIEW") ? "VIEW" : "TABLE",
                        actualCatalog + "." + actualSchema, table, path, rs.getString("REMARKS"), List.of(),
                        Map.of("catalog", actualCatalog, "schema", actualSchema, "tableType", type == null ? "TABLE" : type, "source", "jdbc_metadata")));
                    if (tables.size() > 20000) throw new IllegalStateException("Trino 元数据对象超过 20000，请缩小范围");
                }
            }
            try (ResultSet rs = metadata.getColumns(catalog, schemaPattern, "%", "%")) {
                while (rs.next()) {
                    String actualCatalog = rs.getString("TABLE_CAT"), actualSchema = rs.getString("TABLE_SCHEM"), table = rs.getString("TABLE_NAME");
                    if (actualCatalog == null || actualSchema == null || table == null) continue;
                    List<String> path = List.of(actualCatalog, actualSchema, table);
                    if (!tables.containsKey(path) || !catalog.equals(actualCatalog) || (schema != null && !schema.equals(actualSchema))) continue;
                    String name = rs.getString("COLUMN_NAME");
                    if (name == null) continue;
                    var fields = columns.computeIfAbsent(path, ignored -> new ArrayList<>());
                    if (fields.stream().anyMatch(field -> field.name().equals(name))) continue;
                    int nullable = rs.getInt("NULLABLE");
                    fields.add(new MetadataObject.Field(name, List.of(name), rs.getString("TYPE_NAME"),
                        nullable == DatabaseMetaData.columnNullableUnknown ? null : nullable == DatabaseMetaData.columnNullable,
                        rs.getString("REMARKS"), Map.of("ordinalPosition", rs.getInt("ORDINAL_POSITION"))));
                    if (++fieldCount > 50000) throw new IllegalStateException("Trino 元数据字段超过 50000，请缩小范围");
                }
            }
        }
        return tables.values().stream().map(table -> new MetadataObject(table.datasourceId(), table.databaseType(), table.kind(),
            table.namespace(), table.name(), table.path(), table.description(), columns.getOrDefault(table.path(), List.of()), table.attributes())).toList();
    }

    protected Connection openConnection(SqlDatasourceConfig asset) throws Exception {
        if (driverLoader != null) return driverLoader.createDataSource(asset.getJdbcUrl(), asset.getUsername(), asset.getPassword(), asset.getDriverClass(), "trino").getConnection();
        if (asset.getDriverClass() != null && !asset.getDriverClass().isBlank()) Class.forName(asset.getDriverClass());
        return DriverManager.getConnection(asset.getJdbcUrl(), asset.getUsername(), asset.getPassword());
    }

    private String pattern(String value, String escape) {
        if (escape == null || escape.isEmpty()) return value;
        return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
}
