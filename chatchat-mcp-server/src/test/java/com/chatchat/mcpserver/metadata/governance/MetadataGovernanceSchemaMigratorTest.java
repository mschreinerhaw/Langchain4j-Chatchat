package com.chatchat.mcpserver.metadata.governance;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataGovernanceSchemaMigratorTest {

    @Test
    void widensLegacyMysqlPolicyColumn() throws Exception {
        Fixture fixture = fixture("MySQL", "TINYTEXT");

        new MetadataGovernanceSchemaMigrator(fixture.dataSource()).migrate();

        verify(fixture.statement()).executeUpdate(
            "alter table mcp_metadata_governance_policy modify column policy_json longtext not null"
        );
    }

    @Test
    void leavesLongtextAndNonMysqlDatabasesUntouched() throws Exception {
        Fixture mysql = fixture("MySQL", "LONGTEXT");
        Fixture h2 = fixture("H2", "CHARACTER LARGE OBJECT");

        new MetadataGovernanceSchemaMigrator(mysql.dataSource()).migrate();
        new MetadataGovernanceSchemaMigrator(h2.dataSource()).migrate();

        verify(mysql.connection(), never()).createStatement();
        verify(h2.connection(), never()).createStatement();
    }

    private Fixture fixture(String product, String columnType) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet columns = mock(ResultSet.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("runtime");
        when(connection.createStatement()).thenReturn(statement);
        when(metadata.getDatabaseProductName()).thenReturn(product);
        when(metadata.getColumns("runtime", null,
            "mcp_metadata_governance_policy", "policy_json")).thenReturn(columns);
        when(columns.next()).thenReturn(true);
        when(columns.getString("TYPE_NAME")).thenReturn(columnType);
        return new Fixture(dataSource, connection, statement);
    }

    private record Fixture(DataSource dataSource, Connection connection, Statement statement) {
    }
}
