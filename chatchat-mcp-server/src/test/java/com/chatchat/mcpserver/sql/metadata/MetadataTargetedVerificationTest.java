package com.chatchat.mcpserver.sql.metadata;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MetadataTargetedVerificationTest {
    @Test void readsOneEscapedObjectThroughJdbcMetadataWithoutStatementsOrWholeDatabasePatterns() throws Exception {
        var source=new SqlDatasourceConfig();source.setId("a");source.setDatabaseType("mysql");source.setJdbcUrl("jdbc:fixture:db");source.setUsername("fixture");source.setPassword("fixture");source.setMetadataScopeValue("db");
        var connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);when(metadata.supportsCatalogsInTableDefinitions()).thenReturn(true);when(metadata.getSearchStringEscape()).thenReturn("\\");
        var tables=mock(ResultSet.class);when(tables.next()).thenReturn(true,false);when(tables.getString("TABLE_NAME")).thenReturn("ld_dataservice_api");when(tables.getString("TABLE_CAT")).thenReturn("db");when(tables.getString("TABLE_TYPE")).thenReturn("TABLE");
        var fields=mock(ResultSet.class);when(fields.next()).thenReturn(true,false);when(fields.getString("TABLE_NAME")).thenReturn("ld_dataservice_api");when(fields.getString("TABLE_CAT")).thenReturn("db");when(fields.getString("COLUMN_NAME")).thenReturn("sql_content");when(fields.getString("TYPE_NAME")).thenReturn("TEXT");
        when(metadata.getTables(eq("db"),isNull(),eq("ld\\_dataservice\\_api"),any())).thenReturn(tables);
        when(metadata.getColumns("db",null,"ld\\_dataservice\\_api","%")).thenReturn(fields);
        when(metadata.getPrimaryKeys("db",null,"ld_dataservice_api")).thenReturn(mock(ResultSet.class));when(metadata.getImportedKeys("db",null,"ld_dataservice_api")).thenReturn(mock(ResultSet.class));
        try(var driver=mockStatic(DriverManager.class)){
            driver.when(()->DriverManager.getConnection("jdbc:fixture:db","fixture","fixture")).thenReturn(connection);
            var index=new MetadataIndexService().verifyTable(source,"db","db","ld_dataservice_api","INDEX_STALE");
            assertThat(index.tables()).hasSize(1);assertThat(index.tableColumns().get("db.ld_dataservice_api")).extracting(MetadataColumn::name).containsExactly("sql_content");
            verify(connection).setReadOnly(true);verify(connection,never()).createStatement();verify(connection,never()).prepareStatement(anyString());
            verify(metadata,never()).getTables(any(),any(),eq("%"),any());verify(metadata,never()).getColumns(any(),any(),eq("%"),any());verify(connection).close();
        }
    }
}
