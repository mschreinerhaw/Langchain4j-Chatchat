package com.chatchat.mcpserver.sql.metadata;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrinoMetadataCollectorTest {
    @Test void catalogAndSchemaIdentityKeepSameNamedTablesAndColumnsSeparate() throws Exception {
        var metadata = mock(DatabaseMetaData.class);
        when(metadata.getSearchStringEscape()).thenReturn("\\");
        var hiveTables = rows(table("hive", "sales", "orders"), table("hive", "archive", "orders"));
        var icebergTables = rows(table("iceberg", "sales", "orders"));
        var hiveColumns = rows(column("hive", "sales", "live_id"), column("hive", "archive", "archive_id"));
        var icebergColumns = rows(column("iceberg", "sales", "lake_id"));
        when(metadata.getTables(eq("hive"), isNull(), eq("%"), any())).thenReturn(hiveTables);
        when(metadata.getTables(eq("iceberg"), eq("sales"), eq("%"), any())).thenReturn(icebergTables);
        when(metadata.getColumns("hive", null, "%", "%")).thenReturn(hiveColumns);
        when(metadata.getColumns("iceberg", "sales", "%", "%")).thenReturn(icebergColumns);
        var asset = NativeMetadataCollectorTest.asset("trino", "jdbc:trino://localhost:8080/hive/sales");
        var objects = new TrinoMetadataCollector().collect(metadata, asset, List.of("hive", "iceberg.sales"));
        assertThat(objects).hasSize(3);
        assertThat(objects).extracting(MetadataObject::id).doesNotHaveDuplicates();
        var index = MetadataIndex.collected(asset.getId(), "trino", objects);
        assertThat(index.tableColumns().get(MetadataIndex.tableKey("hive", "sales", "orders"))).extracting(MetadataColumn::name).containsExactly("live_id");
        assertThat(index.tableColumns().get(MetadataIndex.tableKey("hive", "archive", "orders"))).extracting(MetadataColumn::name).containsExactly("archive_id");
        assertThat(index.tableColumns().get(MetadataIndex.tableKey("iceberg", "sales", "orders"))).extracting(MetadataColumn::name).containsExactly("lake_id");
    }

    @Test void scopeUsesLiteralSchemaPatternsAndDoesNotIndexDriverOverreach() throws Exception {
        var metadata = mock(DatabaseMetaData.class);
        when(metadata.getSearchStringEscape()).thenReturn("\\");
        var tables = rows(table("hive", "sales_prod", "orders"), table("hive", "salesXprod", "secret"));
        var columns = rows();
        when(metadata.getTables(eq("hive"), eq("sales\\_prod"), eq("%"), any())).thenReturn(tables);
        when(metadata.getColumns("hive", "sales\\_prod", "%", "%")).thenReturn(columns);
        var objects = new TrinoMetadataCollector().collect(metadata, NativeMetadataCollectorTest.asset("trino", "jdbc:trino://localhost/hive"), List.of("hive.sales_prod"));
        assertThat(objects).extracting(MetadataObject::name).containsExactly("orders");
    }

    @Test void defaultsRetainTheCatalogSchemaHierarchyAndNeverUseLoginAsAnIndex() {
        var trino = NativeMetadataCollectorTest.asset("trino", "jdbc:trino://localhost:8080/hive/sales?SSL=true");
        assertThat(MetadataScopes.defaults(trino)).containsExactly("hive.sales");
        assertThat(MetadataScopes.defaults(NativeMetadataCollectorTest.asset("elasticsearch", "http://localhost:9200"))).isEmpty();
        assertThat(MetadataScopes.defaults(NativeMetadataCollectorTest.asset("neo4j", "http://localhost:7474"))).containsExactly("neo4j");
    }

    @Test void connectionPickerReturnsQualifiedNamespacesInsteadOfBareSchemas() throws Exception {
        var connection = mock(Connection.class); var metadata = mock(DatabaseMetaData.class);
        when(connection.getCatalog()).thenReturn("hive"); when(connection.getSchema()).thenReturn("sales");
        when(connection.getMetaData()).thenReturn(metadata);
        var schemas = rows(Map.of("TABLE_CATALOG", "hive", "TABLE_SCHEM", "sales"), Map.of("TABLE_CATALOG", "iceberg", "TABLE_SCHEM", "sales"));
        when(metadata.getSchemas()).thenReturn(schemas);
        assertThat(MetadataScopes.trinoNamespaces(connection)).containsExactly("hive", "hive.sales", "iceberg.sales");
    }

    private Map<String, Object> table(String catalog, String schema, String name) {
        return Map.of("TABLE_CAT", catalog, "TABLE_SCHEM", schema, "TABLE_NAME", name, "TABLE_TYPE", "TABLE", "REMARKS", "orders");
    }
    private Map<String, Object> column(String catalog, String schema, String name) {
        return Map.of("TABLE_CAT", catalog, "TABLE_SCHEM", schema, "TABLE_NAME", "orders", "COLUMN_NAME", name, "TYPE_NAME", "bigint", "REMARKS", "id", "NULLABLE", 1, "ORDINAL_POSITION", 1);
    }
    @SafeVarargs private final ResultSet rows(Map<String, Object>... rows) throws Exception {
        var rs = mock(ResultSet.class); var position = new AtomicInteger(-1);
        when(rs.next()).thenAnswer(ignored -> position.incrementAndGet() < rows.length);
        when(rs.getString(anyString())).thenAnswer(call -> { var value = rows[position.get()].get(call.getArgument(0)); return value == null ? null : value.toString(); });
        when(rs.getInt(anyString())).thenAnswer(call -> rows[position.get()].getOrDefault(call.getArgument(0), 0));
        return rs;
    }
}
