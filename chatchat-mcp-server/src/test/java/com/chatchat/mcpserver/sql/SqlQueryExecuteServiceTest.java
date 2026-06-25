package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlQueryExecuteServiceTest {

    private final SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
    private final SqlTemplateService templateService = mock(SqlTemplateService.class);
    private final InvocationAuditService auditService = mock(InvocationAuditService.class);
    private final SqlQueryExecuteService service = new SqlQueryExecuteService(
        datasourceConfigService,
        new SqlSafetyService(),
        templateService,
        auditService,
        new ObjectMapper()
    );

    @Test
    void queryResultIncludesColumnMetadataCommentsAndMaskingGovernance() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:sql_query_result;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table test_customer (id int primary key, name varchar(64), phone varchar(32))");
            statement.execute("comment on column test_customer.id is 'Customer ID'");
            statement.execute("comment on column test_customer.name is 'Customer Name'");
            statement.execute("comment on column test_customer.phone is 'Phone number'");
            statement.execute("insert into test_customer (id, name, phone) values (1, 'Alice', '13800000000')");
        }

        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-1");
        datasource.setName("customer-db");
        datasource.setToolName("sql_customer");
        datasource.setJdbcUrl(jdbcUrl);
        datasource.setUsername("sa");
        datasource.setPassword("");
        datasource.setDefaultMaxRows(10);
        datasource.setDefaultTimeoutSeconds(5);
        when(datasourceConfigService.getEnabled("ds-1")).thenReturn(datasource);

        SqlQueryResult result = service.execute(Map.of(
            "datasourceId", "ds-1",
            "sql", "select id, name, phone from test_customer",
            "maxRows", 10
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.columns()).containsExactly("ID", "NAME", "PHONE");
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0)).containsEntry("PHONE", "***");
        assertThat(result.columnMetadata()).hasSize(3);
        assertThat(result.columnMetadata().get(0)).containsEntry("comment", "Customer ID");
        assertThat(result.columnMetadata().get(1)).containsEntry("comment", "Customer Name");
        assertThat(result.columnMetadata().get(2)).containsEntry("comment", "Phone number");
        assertThat(result.columnMetadata().get(2)).containsEntry("masked", true);
    }
}
