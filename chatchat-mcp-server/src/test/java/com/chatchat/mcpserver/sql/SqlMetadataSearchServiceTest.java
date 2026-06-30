package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import com.chatchat.mcpserver.search.LuceneSearchProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlMetadataSearchServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void returnsSqlExecutionBindingForMatchedMetadataTableDocument() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:sql_metadata_search;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create schema rdsm_ad");
            statement.execute("create table rdsm_ad.lbappdeploydetail (id int primary key, app_name varchar(64))");
        }

        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-248");
        datasource.setName("248-test");
        datasource.setTitle("248 test database");
        datasource.setToolName("db_query_mysql_248_test_db");
        datasource.setEnvironment("DEV");
        datasource.setDatabaseType("mysql");
        datasource.setJdbcUrl(jdbcUrl);
        datasource.setUsername("sa");
        datasource.setPassword("");

        SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
        when(datasourceConfigService.listEnabled()).thenReturn(List.of(datasource));

        LuceneSearchProperties properties = new LuceneSearchProperties();
        properties.setIndexDir(tempDir.toString());
        LuceneMcpSearchService luceneSearchService = new LuceneMcpSearchService(properties);
        luceneSearchService.indexAssets(List.of(
            new LuceneMcpSearchService.AssetDoc(
                "metadata_table:ds-248:rdsm_ad:lbappdeploydetail",
                "sql_datasource",
                "248-test.rdsm_ad.lbappdeploydetail",
                "lbappdeploydetail",
                "db_query_mysql_248_test_db",
                "DEV",
                "mysql",
                List.of("metadata_table", "database:rdsm_ad", "schema:rdsm_ad", "table:lbappdeploydetail"),
                "metadata_table",
                "ds-248",
                "rdsm_ad",
                "lbappdeploydetail",
                "248-test.rdsm_ad.lbappdeploydetail",
                "应用部署明细",
                "应用部署明细",
                "测试数据库"
            ),
            new LuceneMcpSearchService.AssetDoc(
                "metadata_table:ds-248:dbo:sqlserver_table",
                "sql_datasource",
                "248-test.dbo.sqlserver_table",
                "sqlserver_table",
                "db_query_mysql_248_test_db",
                "DEV",
                "mysql",
                List.of("metadata_table", "database:dbo", "schema:dbo", "table:sqlserver_table"),
                "metadata_table",
                "ds-248",
                "dbo",
                "sqlserver_table",
                "248-test.dbo.sqlserver_table"
            )
        ));

        SqlMetadataSearchService service = new SqlMetadataSearchService(
            luceneSearchService,
            datasourceConfigService,
            new MetadataIndexService()
        );

        Map<String, Object> result = service.search(Map.of(
            "query", "lbappdeploydetail",
            "executionContext", Map.of("env", "DEV", "databaseType", "mysql"),
            "limit", 5
        ));

        assertThat(result).containsEntry("success", true);
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertThat(results).hasSize(1);
        Map<String, Object> first = results.get(0);
        assertThat(first.get("source")).isEqualTo("lucene");
        assertThat((Map<String, Object>) first.get("location"))
            .containsEntry("database", "rdsm_ad")
            .containsEntry("table", "lbappdeploydetail")
            .containsEntry("tableComment", "应用部署明细")
            .containsEntry("databaseComment", "测试数据库");
        Map<String, Object> binding = (Map<String, Object>) first.get("sqlExecutionBinding");
        assertThat((Map<String, Object>) binding.get("parameters"))
            .containsEntry("databaseName", "rdsm_ad")
            .containsEntry("schemaName", "rdsm_ad")
            .containsEntry("tableName", "lbappdeploydetail");
        assertThat((Map<String, Object>) binding.get("executionContext"))
            .containsEntry("assetName", "248-test")
            .containsEntry("env", "DEV");

        Map<String, Object> qualifiedResult = service.search(Map.of(
            "tableName", "rdsm_ad.lbappdeploydetail",
            "executionContext", Map.of("env", "DEV", "databaseType", "mysql"),
            "limit", 5
        ));
        List<Map<String, Object>> qualifiedResults = (List<Map<String, Object>>) qualifiedResult.get("results");
        assertThat(qualifiedResults).hasSize(1);
        Map<String, Object> qualifiedBinding = (Map<String, Object>) qualifiedResults.get(0).get("sqlExecutionBinding");
        assertThat((Map<String, Object>) qualifiedBinding.get("parameters"))
            .containsEntry("databaseName", "rdsm_ad")
            .containsEntry("schemaName", "rdsm_ad")
            .containsEntry("tableName", "lbappdeploydetail");

        Map<String, Object> sqlServerStyleResult = service.search(Map.of(
            "tableName", "user.dbo.sqlserver_table",
            "executionContext", Map.of("env", "DEV", "databaseType", "mysql"),
            "limit", 5
        ));
        List<Map<String, Object>> sqlServerStyleResults = (List<Map<String, Object>>) sqlServerStyleResult.get("results");
        assertThat(sqlServerStyleResults).hasSize(1);
        Map<String, Object> sqlServerStyleBinding = (Map<String, Object>) sqlServerStyleResults.get(0).get("sqlExecutionBinding");
        assertThat((Map<String, Object>) sqlServerStyleBinding.get("parameters"))
            .containsEntry("databaseName", "user")
            .containsEntry("schemaName", "dbo")
            .containsEntry("tableName", "sqlserver_table");
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitTableSearchIncludesCachedColumnMetadataByDefault() {
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-livebos");
        datasource.setName("248测试数据库");
        datasource.setToolName("db_query_mysql_248_test_db");
        datasource.setEnvironment("DEV");
        datasource.setDatabaseType("mysql");

        TableLocation table = new TableLocation(
            "ds-livebos",
            "livebos",
            "livebos",
            "os_historystep",
            "BASE TABLE",
            12L,
            "历史步骤表",
            "流程数据库",
            1.0
        );
        MetadataColumn stepId = new MetadataColumn(
            "ds-livebos",
            "livebos",
            "livebos",
            "os_historystep",
            "step_id",
            "varchar",
            "varchar(64)",
            "PRI",
            "步骤ID",
            false,
            1
        );

        SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
        when(datasourceConfigService.listEnabled()).thenReturn(List.of(datasource));
        MetadataIndexService metadataIndexService = mock(MetadataIndexService.class);
        when(metadataIndexService.allTables(datasource)).thenReturn(List.of(table));
        when(metadataIndexService.columns(datasource, table)).thenReturn(List.of(stepId));
        LuceneMcpSearchService luceneSearchService = mock(LuceneMcpSearchService.class);
        when(luceneSearchService.enabled()).thenReturn(false);

        SqlMetadataSearchService service = new SqlMetadataSearchService(
            luceneSearchService,
            datasourceConfigService,
            metadataIndexService
        );

        Map<String, Object> result = service.search(Map.of(
            "tableName", "livebos.os_historystep",
            "executionContext", Map.of("assetName", "248测试数据库", "env", "DEV", "databaseType", "mysql")
        ));

        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertThat(results).hasSize(1);
        Map<String, Object> first = results.get(0);
        assertThat(first).containsEntry("columnCount", 1);
        List<Map<String, Object>> columns = (List<Map<String, Object>>) first.get("columns");
        assertThat(columns).hasSize(1);
        assertThat(columns.get(0))
            .containsEntry("name", "step_id")
            .containsEntry("dataType", "varchar")
            .containsEntry("columnType", "varchar(64)")
            .containsEntry("columnKey", "PRI")
            .containsEntry("comment", "步骤ID");
    }
}
