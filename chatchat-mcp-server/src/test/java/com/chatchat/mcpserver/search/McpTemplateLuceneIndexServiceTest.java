package com.chatchat.mcpserver.search;

import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.api.ApiServiceConfigService;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.ops.CommandTemplateConfig;
import com.chatchat.mcpserver.ops.CommandTemplateService;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.sql.SqlTemplateConfig;
import com.chatchat.mcpserver.sql.SqlTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpTemplateLuceneIndexServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void indexesDefaultSystemTemplatesIntoLuceneOnRefresh() {
        LuceneMcpSearchService lucene = lucene();
        CommandTemplateService commandTemplateService = mock(CommandTemplateService.class);
        SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
        HttpEndpointConfigService httpEndpointConfigService = mock(HttpEndpointConfigService.class);
        ApiServiceConfigService apiServiceConfigService = mock(ApiServiceConfigService.class);
        DatabaseQueryConfigService databaseQueryConfigService = mock(DatabaseQueryConfigService.class);
        when(commandTemplateService.listEnabled()).thenReturn(List.of(commandTemplate()));
        when(sqlTemplateService.listEnabled()).thenReturn(List.of(sqlTemplate()));
        when(httpEndpointConfigService.listEnabled()).thenReturn(List.of());
        when(apiServiceConfigService.listAll()).thenReturn(List.of());
        when(databaseQueryConfigService.listAll()).thenReturn(List.of());
        McpTemplateLuceneIndexService indexService = new McpTemplateLuceneIndexService(
            lucene,
            commandTemplateService,
            sqlTemplateService,
            httpEndpointConfigService,
            apiServiceConfigService,
            databaseQueryConfigService,
            new ObjectMapper()
        );

        indexService.refreshAll();

        assertThat(lucene.searchTemplates(new LuceneMcpSearchService.TemplateSearchRequest(
            "ssh_host", null, "system overview memory disk", 10
        ))).extracting(LuceneMcpSearchService.SearchHit::id)
            .contains("CHECK_SYSTEM_OVERVIEW");
        assertThat(lucene.searchTemplates(new LuceneMcpSearchService.TemplateSearchRequest(
            "sql_datasource", "mysql", "database status health", 10
        ))).extracting(LuceneMcpSearchService.SearchHit::id)
            .contains("MYSQL_SHOW_STATUS");
    }

    @Test
    void indexesDatabaseQueryBusinessGroupMetadataIntoLucene() {
        LuceneMcpSearchService lucene = lucene();
        CommandTemplateService commandTemplateService = mock(CommandTemplateService.class);
        SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
        HttpEndpointConfigService httpEndpointConfigService = mock(HttpEndpointConfigService.class);
        ApiServiceConfigService apiServiceConfigService = mock(ApiServiceConfigService.class);
        DatabaseQueryConfigService databaseQueryConfigService = mock(DatabaseQueryConfigService.class);
        when(commandTemplateService.listEnabled()).thenReturn(List.of());
        when(sqlTemplateService.listEnabled()).thenReturn(List.of());
        when(httpEndpointConfigService.listEnabled()).thenReturn(List.of());
        when(apiServiceConfigService.listAll()).thenReturn(List.of());
        when(databaseQueryConfigService.listAll()).thenReturn(List.of(databaseQuery()));
        McpTemplateLuceneIndexService indexService = new McpTemplateLuceneIndexService(
            lucene,
            commandTemplateService,
            sqlTemplateService,
            httpEndpointConfigService,
            apiServiceConfigService,
            databaseQueryConfigService,
            new ObjectMapper()
        );

        indexService.refreshAll();

        assertThat(lucene.searchTemplates(new LuceneMcpSearchService.TemplateSearchRequest(
            "database_query", "mysql", "fulfillment lifecycle order services", 10
        ))).extracting(LuceneMcpSearchService.SearchHit::id)
            .contains("db-query-1");
    }

    @Test
    void indexesApiServiceTemplatesIntoLucene() {
        LuceneMcpSearchService lucene = lucene();
        CommandTemplateService commandTemplateService = mock(CommandTemplateService.class);
        SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
        HttpEndpointConfigService httpEndpointConfigService = mock(HttpEndpointConfigService.class);
        ApiServiceConfigService apiServiceConfigService = mock(ApiServiceConfigService.class);
        DatabaseQueryConfigService databaseQueryConfigService = mock(DatabaseQueryConfigService.class);
        when(commandTemplateService.listEnabled()).thenReturn(List.of());
        when(sqlTemplateService.listEnabled()).thenReturn(List.of());
        when(httpEndpointConfigService.listEnabled()).thenReturn(List.of());
        when(apiServiceConfigService.listAll()).thenReturn(List.of(apiService()));
        when(databaseQueryConfigService.listAll()).thenReturn(List.of());
        McpTemplateLuceneIndexService indexService = new McpTemplateLuceneIndexService(
            lucene,
            commandTemplateService,
            sqlTemplateService,
            httpEndpointConfigService,
            apiServiceConfigService,
            databaseQueryConfigService,
            new ObjectMapper()
        );

        indexService.refreshAll();

        List<LuceneMcpSearchService.SearchHit> hits = lucene.searchTemplates(
            new LuceneMcpSearchService.TemplateSearchRequest("api_service", null, "risk alert event market", 10));
        assertThat(hits).extracting(LuceneMcpSearchService.SearchHit::id)
            .contains("api_market_event_alert");
        assertThat(hits.get(0).name()).isEqualTo("Market event alert");
        assertThat(hits.get(0).source()).isEqualTo("api_service_registry");
    }

    private CommandTemplateConfig commandTemplate() {
        CommandTemplateConfig config = new CommandTemplateConfig();
        config.setCode("CHECK_SYSTEM_OVERVIEW");
        config.setTitle("System overview");
        config.setDescription("Read-only host load, memory, disk and process overview.");
        config.setCategory("system_diagnostic");
        config.setRiskLevel("LOW");
        config.setIntentSignalsJson("[\"system\",\"overview\",\"memory\",\"disk\"]");
        config.setEnabled(true);
        return config;
    }

    private SqlTemplateConfig sqlTemplate() {
        SqlTemplateConfig config = new SqlTemplateConfig();
        config.setCode("MYSQL_SHOW_STATUS");
        config.setTitle("MySQL status variables");
        config.setDescription("Show MySQL server status counters for health and performance inspection.");
        config.setDatabaseType("mysql");
        config.setCategory("maintenance_instance");
        config.setRiskLevel("LOW");
        config.setSqlTemplate("SHOW STATUS");
        config.setIntentSignalsJson("[\"db_status\",\"status\",\"health\",\"instance\"]");
        config.setEnabled(true);
        return config;
    }

    private DatabaseQueryConfig databaseQuery() {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("db-query-1");
        config.setToolName("order_status_query");
        config.setTitle("Order status query");
        config.setDescription("Query order status by order id.");
        config.setBusinessGroup("order_services");
        config.setBusinessGroupName("Order services");
        config.setBusinessGroupDescription("Templates for fulfillment lifecycle checks.");
        config.setSqlTemplate("SELECT status FROM orders WHERE order_id = :orderId");
        config.setTemplateIntent("order status lookup");
        config.setDatabaseType("mysql");
        config.setRiskLevel("read_only");
        config.setOwner("ops");
        config.setEnabled(true);
        return config;
    }

    private ApiServiceConfig apiService() {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setToolName("api_market_event_alert");
        config.setTitle("Market event alert");
        config.setDescription("Query market event alerts and risk notifications.");
        config.setBusinessGroup("risk_event");
        config.setBusinessGroupName("Risk event");
        config.setBusinessGroupDescription("API templates for market risk event monitoring.");
        config.setMethod("GET");
        config.setUrlTemplate("https://example.internal/events");
        config.setGovernanceJson("{\"riskLevel\":\"low\",\"intent\":\"market alert event\"}");
        config.setEnabled(true);
        return config;
    }

    private LuceneMcpSearchService lucene() {
        LuceneSearchProperties properties = new LuceneSearchProperties();
        properties.setIndexDir(tempDir.toString());
        return new LuceneMcpSearchService(properties);
    }
}
