package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import com.chatchat.mcpserver.search.LuceneSearchProperties;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.SqlTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandTemplateDiscoveryDatabaseQueryTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsExecutableMcpToolNameForRegisteredBusinessDatabaseQuery() {
        DatabaseQueryConfigService databaseQueryService = mock(DatabaseQueryConfigService.class);
        DatabaseQueryConfig query = new DatabaseQueryConfig();
        query.setId("query-1");
        query.setToolName("query_active_services");
        query.setTitle("Query active services");
        query.setDatasourceId("ds-1");
        query.setDescription("Read active business services for operations analysis");
        query.setBusinessGroup("service_ops");
        query.setBusinessGroupName("Service operations");
        query.setBusinessGroupDescription("Business queries for active service health and lifecycle decisions");
        query.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"}},\"required\":[\"status\"]}");
        query.setGovernanceJson("{\"intent\":\"service_status\",\"tags\":[\"service\",\"active\",\"business\"]}");
        query.setCapabilitiesJson("[\"database_query\",\"sql_query_execute\"]");
        query.setTemplateIntent("service_status");
        query.setDatabaseType("mysql");
        query.setTagsJson("[\"service\",\"active\",\"business\"]");
        query.setRiskLevel("read_only");
        query.setOwner("ops-admin");
        query.setRating(4.5);
        query.setUsageCount(16);
        query.setEnabled(true);
        when(databaseQueryService.listEnabled()).thenReturn(List.of(query));
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-1");
        datasource.setName("ops-mysql");
        datasource.setTitle("Operations MySQL");
        datasource.setToolName("db_query_ops_mysql");
        datasource.setEnvironment("DEV");
        datasource.setDatabaseType("mysql");
        datasource.setEnabled(true);
        when(datasourceService.getEnabled("ds-1")).thenReturn(datasource);
        CommandTemplateDiscoveryService service = new CommandTemplateDiscoveryService(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            mock(SqlTemplateService.class),
            datasourceService,
            mock(HttpEndpointConfigService.class),
            databaseQueryService,
            new ObjectMapper(),
            new TemplateDiscoveryProperties(),
            lucene()
        );

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "business_database_query",
            "confidence", 0.9,
            "filters", Map.of("businessGroup", "service operations", "dbType", "mysql"),
            "trace", trace(),
            "limit", 5
        ));

        List<?> templates = (List<?>) result.get("templates");
        Map<?, ?> first = (Map<?, ?>) templates.get(0);
        Map<?, ?> execution = (Map<?, ?>) first.get("execution");
        assertThat(result).containsEntry("returnedCount", 1);
        assertThat(first.get("templateId")).isEqualTo("query_active_services");
        assertThat(first.get("mcpToolName")).isEqualTo("query_active_services");
        assertThat(first.get("databaseQueryId")).isEqualTo("query-1");
        assertThat(first.get("intent")).isEqualTo("service_status");
        assertThat(first.get("businessGroup").toString()).contains("service_ops", "Service operations", "active service health");
        assertThat(first.get("databaseType")).isEqualTo("mysql");
        assertThat(first.get("riskLevel")).isEqualTo("read_only");
        assertThat(first.get("owner")).isEqualTo("ops-admin");
        assertThat(first.get("tags").toString()).contains("service", "active", "business");
        assertThat(first.get("executionContext").toString()).contains("ops-mysql", "DEV", "mysql");
        assertThat(first.get("sqlExecutionBinding").toString()).contains("sql_query_execute", "executionContext");
        assertThat(first.get("datasourceAsset").toString()).contains("ops-mysql", "db_query_ops_mysql");
        assertThat(first.get("mcpDecision").toString()).contains("SQL Template Marketplace");
        assertThat(first.get("rankingFeatures").toString()).contains("dbTypeMatch", "luceneScore", "usageScore");
        assertThat(execution.get("mode")).isEqualTo("direct_mcp_tool");
        assertThat(execution.get("callTool")).isEqualTo("query_active_services");
        assertThat(first.get("parameterSchema").toString()).contains("status");
    }

    private LuceneMcpSearchService lucene() {
        LuceneSearchProperties properties = new LuceneSearchProperties();
        properties.setIndexDir(tempDir.toString());
        return new LuceneMcpSearchService(properties);
    }

    private Map<String, Object> trace() {
        return Map.of("plannerVersion", "v1.0", "model", "unit-test");
    }
}
