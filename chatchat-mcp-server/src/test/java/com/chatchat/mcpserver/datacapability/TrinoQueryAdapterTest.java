package com.chatchat.mcpserver.datacapability;

import com.chatchat.mcpserver.datacapability.definition.*;
import com.chatchat.mcpserver.datacapability.trino.TrinoQueryAdapter;
import com.chatchat.mcpserver.sql.datasource.*;
import com.chatchat.mcpserver.sql.execution.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TrinoQueryAdapterTest {
    private final SqlDatasourceConfigService connections = mock(SqlDatasourceConfigService.class);
    private final SqlQueryExecuteService sql = mock(SqlQueryExecuteService.class);
    private final TrinoQueryAdapter adapter = new TrinoQueryAdapter(connections, sql, new SqlSafetyService());
    private CapabilityDefinition definition(Map<String, Object> options) {
        return new CapabilityDefinition("trino_query", "Trino", null, CapabilityType.TRINO, null, "trino",
            "SELECT name FROM company WHERE id = {{id}}", null, null, options, 45, 100, true, true, true);
    }
    @Test void routesToExistingGovernedSqlExecutorWithCatalogSchemaAndLimits() {
        var connection = new SqlDatasourceConfig(); connection.setJdbcUrl("jdbc:trino://localhost:8080");
        when(connections.getById("trino")).thenReturn(connection);
        when(sql.execute(anyMap())).thenReturn(new SqlQueryResult(true, "trino", "Trino", "sql", "DEV", "", "", 45, 100,
            List.of("name"), List.of(Map.of("name", "Alice")), 1, false, 1, "test", null, null, Map.of()));
        var d = definition(Map.of("catalog", "hive", "schema", "finance")); adapter.validate(d);
        assertThat(adapter.execute(d, Map.of("id", 42)).rows()).containsExactly(Map.of("name", "Alice"));
        verify(sql).execute(argThat(args -> "hive".equals(args.get("catalog")) && "finance".equals(args.get("schema"))
            && Integer.valueOf(45).equals(args.get("timeoutSeconds")) && Integer.valueOf(100).equals(args.get("maxRows"))
            && String.valueOf(args.get("sql")).endsWith("id = 42")));
    }
    @Test void refusesWrongDatasourceAndInvalidNamespaces() {
        var connection = new SqlDatasourceConfig(); connection.setJdbcUrl("jdbc:mysql://localhost/db");
        when(connections.getById("trino")).thenReturn(connection);
        assertThatThrownBy(() -> adapter.validate(definition(Map.of("catalog", "hive", "schema", "finance"))))
            .hasMessageContaining("jdbc:trino");
        connection.setJdbcUrl("jdbc:trino://localhost:8080");
        assertThatThrownBy(() -> adapter.validate(definition(Map.of("catalog", "hive;DROP", "schema", "finance"))))
            .hasMessageContaining("catalog");
    }
}
