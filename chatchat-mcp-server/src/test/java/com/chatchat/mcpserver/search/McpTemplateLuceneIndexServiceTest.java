package com.chatchat.mcpserver.search;

import com.chatchat.mcpserver.api.registry.ApiServiceConfig;
import com.chatchat.mcpserver.api.registry.ApiServiceConfigService;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.ops.command.CommandTemplateConfig;
import com.chatchat.mcpserver.ops.command.CommandTemplateService;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.jmx.JmxTemplateConfig;
import com.chatchat.mcpserver.ops.jmx.JmxTemplateService;
import com.chatchat.mcpserver.sql.template.SqlTemplateConfig;
import com.chatchat.mcpserver.sql.template.SqlTemplateService;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
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
    void rebuildsSelectedEnabledJmxTemplateIndex() {
        LuceneMcpSearchService lucene = lucene();
        JmxTemplateService jmxTemplateService = mock(JmxTemplateService.class);
        JmxTemplateConfig template = jmxTemplate();
        when(jmxTemplateService.getById("jmx-kafka")).thenReturn(template);
        McpTemplateLuceneIndexService indexService = new McpTemplateLuceneIndexService(
            lucene,
            mock(CommandTemplateService.class),
            mock(SqlTemplateService.class),
            jmxTemplateService,
            mock(HttpEndpointConfigService.class),
            mock(ApiServiceConfigService.class),
            mock(DatabaseQueryConfigService.class),
            mock(SqlDatasourceConfigService.class),
            new ObjectMapper()
        );

        assertThat(indexService.rebuildJmxTemplates(List.of("jmx-kafka")))
            .containsEntry("templateType", "jmx")
            .containsEntry("indexed", 1);
        assertThat(lucene.searchTemplates(new LuceneMcpSearchService.TemplateSearchRequest(
            "jmx_endpoint", "java", "kafka jvm", 10
        ))).extracting(LuceneMcpSearchService.SearchHit::id)
            .contains("JMX_KAFKA_BROKER_OVERVIEW");
    }

    @Test
    void indexesDefaultSystemTemplatesIntoLuceneOnRefresh() {
        LuceneMcpSearchService lucene = lucene();
        CommandTemplateService commandTemplateService = mock(CommandTemplateService.class);
        SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
        HttpEndpointConfigService httpEndpointConfigService = mock(HttpEndpointConfigService.class);
        JmxTemplateService jmxTemplateService = mock(JmxTemplateService.class);
        ApiServiceConfigService apiServiceConfigService = mock(ApiServiceConfigService.class);
        DatabaseQueryConfigService databaseQueryConfigService = mock(DatabaseQueryConfigService.class);
        when(commandTemplateService.listEnabled()).thenReturn(List.of(commandTemplate()));
        when(sqlTemplateService.listEnabled()).thenReturn(List.of(sqlTemplate()));
        when(httpEndpointConfigService.listEnabled()).thenReturn(List.of());
        when(jmxTemplateService.listEnabled()).thenReturn(List.of(jmxTemplate()));
        when(apiServiceConfigService.listAll()).thenReturn(List.of());
        when(databaseQueryConfigService.listAll()).thenReturn(List.of());
        McpTemplateLuceneIndexService indexService = new McpTemplateLuceneIndexService(
            lucene,
            commandTemplateService,
            sqlTemplateService,
            jmxTemplateService,
            httpEndpointConfigService,
            apiServiceConfigService,
            databaseQueryConfigService,
            mock(SqlDatasourceConfigService.class),
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
        assertThat(lucene.searchTemplates(new LuceneMcpSearchService.TemplateSearchRequest(
            "jmx_endpoint", "java", "kafka replica jvm monitoring", 10
        ))).extracting(LuceneMcpSearchService.SearchHit::id)
            .contains("JMX_KAFKA_BROKER_OVERVIEW");
    }

    @Test
    void indexesDslStepSignalsIntoTemplateLucene() {
        LuceneMcpSearchService lucene = lucene();
        CommandTemplateService commandTemplateService = mock(CommandTemplateService.class);
        SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
        HttpEndpointConfigService httpEndpointConfigService = mock(HttpEndpointConfigService.class);
        JmxTemplateService jmxTemplateService = mock(JmxTemplateService.class);
        ApiServiceConfigService apiServiceConfigService = mock(ApiServiceConfigService.class);
        DatabaseQueryConfigService databaseQueryConfigService = mock(DatabaseQueryConfigService.class);
        CommandTemplateConfig commandTemplate = commandTemplate();
        commandTemplate.setId("CHECK_MYSQLD_PROCESS");
        commandTemplate.setCode("CHECK_MYSQLD_PROCESS");
        commandTemplate.setTitle("Host diagnostics");
        commandTemplate.setDescription("Structured command template.");
        commandTemplate.setIntentSignalsJson("[]");
        commandTemplate.setCommandTemplate("""
            {
              "templateCode": "CHECK_MYSQLD_PROCESS",
              "templateName": "MySQL daemon process check",
              "templateType": "LINUX_CMD",
              "targetType": "LINUX",
              "executionMode": "SEQUENTIAL",
              "continueOnError": true,
              "steps": [
                {
                  "stepCode": "MYSQLD_PROCESS",
                  "stepName": "MySQL daemon process",
                  "stepType": "SHELL",
                  "order": 1,
                  "command": "ps aux | grep -Ei 'mysqld|mariadbd'",
                  "analysisHint": "Judge whether mysqld or mariadbd management daemon is running."
                }
              ],
              "analysisPolicy": {
                "outputSections": ["summary", "evidence"]
              }
            }
            """);
        when(commandTemplateService.listEnabled()).thenReturn(List.of(commandTemplate));
        when(sqlTemplateService.listEnabled()).thenReturn(List.of());
        when(httpEndpointConfigService.listEnabled()).thenReturn(List.of());
        when(jmxTemplateService.listEnabled()).thenReturn(List.of());
        when(apiServiceConfigService.listAll()).thenReturn(List.of());
        when(databaseQueryConfigService.listAll()).thenReturn(List.of());
        McpTemplateLuceneIndexService indexService = new McpTemplateLuceneIndexService(
            lucene,
            commandTemplateService,
            sqlTemplateService,
            jmxTemplateService,
            httpEndpointConfigService,
            apiServiceConfigService,
            databaseQueryConfigService,
            mock(SqlDatasourceConfigService.class),
            new ObjectMapper()
        );

        indexService.refreshAll();

        assertThat(lucene.searchTemplates(new LuceneMcpSearchService.TemplateSearchRequest(
            "ssh_host", null, "mariadbd management daemon evidence", 10
        ))).extracting(LuceneMcpSearchService.SearchHit::id)
            .contains("CHECK_MYSQLD_PROCESS");
    }

    @Test
    void indexesDatabaseQueryBusinessGroupMetadataIntoLucene() {
        LuceneMcpSearchService lucene = lucene();
        CommandTemplateService commandTemplateService = mock(CommandTemplateService.class);
        SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
        HttpEndpointConfigService httpEndpointConfigService = mock(HttpEndpointConfigService.class);
        JmxTemplateService jmxTemplateService = mock(JmxTemplateService.class);
        ApiServiceConfigService apiServiceConfigService = mock(ApiServiceConfigService.class);
        DatabaseQueryConfigService databaseQueryConfigService = mock(DatabaseQueryConfigService.class);
        when(commandTemplateService.listEnabled()).thenReturn(List.of());
        when(sqlTemplateService.listEnabled()).thenReturn(List.of());
        when(httpEndpointConfigService.listEnabled()).thenReturn(List.of());
        when(jmxTemplateService.listEnabled()).thenReturn(List.of());
        when(apiServiceConfigService.listAll()).thenReturn(List.of());
        when(databaseQueryConfigService.listAll()).thenReturn(List.of(databaseQuery()));
        SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
        when(datasourceConfigService.getEnabled("ds-1")).thenReturn(datasource());
        McpTemplateLuceneIndexService indexService = new McpTemplateLuceneIndexService(
            lucene,
            commandTemplateService,
            sqlTemplateService,
            jmxTemplateService,
            httpEndpointConfigService,
            apiServiceConfigService,
            databaseQueryConfigService,
            datasourceConfigService,
            new ObjectMapper()
        );

        indexService.refreshAll();

        List<LuceneMcpSearchService.SearchHit> hits = lucene.searchDatabaseQueryTemplates(new LuceneMcpSearchService.TemplateSearchRequest(
            "database_query", "mysql", "fulfillment lifecycle order services", 10
        ));
        assertThat(hits).extracting(LuceneMcpSearchService.SearchHit::id)
            .contains("db-query-1");
        assertThat(hits.get(0).source()).isEqualTo("database_query_template_registry");
        assertThat(hits.get(0).name()).isEqualTo("Order status query");
        assertThat(lucene.searchTemplates(new LuceneMcpSearchService.TemplateSearchRequest(
            "database_query", "mysql", "fulfillment lifecycle order services", 10
        ))).isEmpty();
    }

    @Test
    void indexesApiServiceTemplatesIntoLucene() {
        LuceneMcpSearchService lucene = lucene();
        CommandTemplateService commandTemplateService = mock(CommandTemplateService.class);
        SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
        HttpEndpointConfigService httpEndpointConfigService = mock(HttpEndpointConfigService.class);
        JmxTemplateService jmxTemplateService = mock(JmxTemplateService.class);
        ApiServiceConfigService apiServiceConfigService = mock(ApiServiceConfigService.class);
        DatabaseQueryConfigService databaseQueryConfigService = mock(DatabaseQueryConfigService.class);
        when(commandTemplateService.listEnabled()).thenReturn(List.of());
        when(sqlTemplateService.listEnabled()).thenReturn(List.of());
        when(httpEndpointConfigService.listEnabled()).thenReturn(List.of());
        when(jmxTemplateService.listEnabled()).thenReturn(List.of());
        when(apiServiceConfigService.listAll()).thenReturn(List.of(apiService()));
        when(databaseQueryConfigService.listAll()).thenReturn(List.of());
        McpTemplateLuceneIndexService indexService = new McpTemplateLuceneIndexService(
            lucene,
            commandTemplateService,
            sqlTemplateService,
            jmxTemplateService,
            httpEndpointConfigService,
            apiServiceConfigService,
            databaseQueryConfigService,
            mock(SqlDatasourceConfigService.class),
            new ObjectMapper()
        );

        indexService.refreshAll();

        List<LuceneMcpSearchService.SearchHit> hits = lucene.searchApiServiceTemplates(
            new LuceneMcpSearchService.TemplateSearchRequest("api_service", null, "risk alert event market", 10));
        assertThat(hits).extracting(LuceneMcpSearchService.SearchHit::id)
            .contains("api_market_event_alert");
        assertThat(hits.get(0).name()).isEqualTo("Market event alert");
        assertThat(hits.get(0).source()).isEqualTo("api_service_registry");
        assertThat(lucene.searchTemplates(
            new LuceneMcpSearchService.TemplateSearchRequest("api_service", null, "risk alert event market", 10)))
            .isEmpty();
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

    private JmxTemplateConfig jmxTemplate() {
        JmxTemplateConfig config = new JmxTemplateConfig();
        config.setId("jmx-kafka");
        config.setCode("JMX_KAFKA_BROKER_OVERVIEW");
        config.setTitle("Kafka Broker JMX overview");
        config.setDescription("Kafka replica, request and JVM monitoring metrics.");
        config.setCategory("kafka_monitoring");
        config.setRiskLevel("LOW");
        config.setIntentSignalsJson("[\"kafka\",\"replica\",\"jvm\",\"monitoring\"]");
        config.setQueriesJson("[{\"name\":\"memory\",\"objectName\":\"java.lang:type=Memory\",\"attributes\":[\"HeapMemoryUsage\"]}]");
        config.setEnabled(true);
        return config;
    }

    private DatabaseQueryConfig databaseQuery() {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("db-query-1");
        config.setToolName("order_status_query");
        config.setTitle("Order status query");
        config.setDatasourceId("ds-1");
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

    private SqlDatasourceConfig datasource() {
        SqlDatasourceConfig config = new SqlDatasourceConfig();
        config.setId("ds-1");
        config.setName("orders-mysql");
        config.setTitle("Order Service MySQL");
        config.setToolName("db_query_orders_mysql");
        config.setDescription("Datasource for fulfillment lifecycle order services.");
        config.setDatabaseType("mysql");
        config.setEnvironment("DEV");
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
