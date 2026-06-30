package com.chatchat.mcpserver.sql;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MetadataResolverServiceTest {

    private final SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
    private final MetadataIndexService metadataIndexService = new MetadataIndexService();
    private final TableSemanticMatcher semanticMatcher = new TableSemanticMatcher();
    private final MetadataUsageHistoryService usageHistoryService = new MetadataUsageHistoryService();
    private final MetadataResolverEngine resolverEngine = new MetadataResolverEngine(
        new DatasourceBindingService(),
        semanticMatcher,
        new RoutingFusionEngine(usageHistoryService)
    );
    private final MetadataResolverService service = new MetadataResolverService(
        datasourceConfigService,
        metadataIndexService,
        resolverEngine,
        semanticMatcher,
        usageHistoryService
    );

    @Test
    void resolvesTableLocationFromInformationSchemaAndCachesResult() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:metadata_resolver;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table customer_label (id int primary key)");
        }
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-1");
        datasource.setName("metadata-db");
        datasource.setJdbcUrl(jdbcUrl);
        datasource.setUsername("sa");
        datasource.setPassword("");
        datasource.setDatabaseType("postgresql");
        datasource.setMetadataScopeType("EXPLICIT_SCHEMA");
        datasource.setMetadataScopeValue("PUBLIC");
        metadataIndexService.refreshDatasource(datasource);

        TableResolution first = service.resolveTable(datasource, "customer_label", "PUBLIC");
        TableResolution second = service.resolveTable(datasource, "customer_label", "PUBLIC");

        assertThat(first.reason()).isEqualTo("resolved");
        assertThat(first.selectedSchema()).isEqualTo("PUBLIC");
        assertThat(first.selectedTable()).isEqualTo("CUSTOMER_LABEL");
        assertThat(first.cacheHit()).isTrue();
        assertThat(second.reason()).isEqualTo("resolved");
        assertThat(second.cacheHit()).isTrue();
        assertThat(second.candidates()).hasSize(1);
    }

    @Test
    void prefersSchemaHintWhenSameTableExistsInMultipleSchemas() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:metadata_resolver_ambiguous;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create schema tenant_a");
            statement.execute("create schema tenant_b");
            statement.execute("create table tenant_a.customer_label (id int primary key)");
            statement.execute("create table tenant_b.customer_label (id int primary key)");
        }
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-2");
        datasource.setName("metadata-db");
        datasource.setJdbcUrl(jdbcUrl);
        datasource.setUsername("sa");
        datasource.setPassword("");
        datasource.setDatabaseType("postgresql");
        datasource.setMetadataScopeType("EXPLICIT_SCHEMA");
        datasource.setMetadataScopeValue("TENANT_B");
        metadataIndexService.refreshDatasource(datasource);

        TableResolution resolution = service.resolveTable(datasource, "customer_label", "TENANT_B");

        assertThat(resolution.reason()).isEqualTo("resolved");
        assertThat(resolution.selectedSchema()).isEqualTo("TENANT_B");
        assertThat(resolution.selectedTable()).isEqualTo("CUSTOMER_LABEL");
        assertThat(resolution.candidates()).hasSize(1);
    }

    @Test
    void resolvesSemanticTableVariantWhenExactNameIsMissing() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:metadata_resolver_semantic;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table customer_label_v2 (id int primary key)");
        }
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-3");
        datasource.setName("metadata-db");
        datasource.setJdbcUrl(jdbcUrl);
        datasource.setUsername("sa");
        datasource.setPassword("");
        datasource.setDatabaseType("postgresql");
        datasource.setMetadataScopeType("EXPLICIT_SCHEMA");
        datasource.setMetadataScopeValue("PUBLIC");
        metadataIndexService.refreshDatasource(datasource);

        TableResolution resolution = service.resolveTable(datasource, "customer_label", "PUBLIC");

        assertThat(resolution.reason()).isEqualTo("resolved");
        assertThat(resolution.selectedSchema()).isEqualTo("PUBLIC");
        assertThat(resolution.selectedTable()).isEqualTo("CUSTOMER_LABEL_V2");
        assertThat(resolution.candidates()).hasSize(1);
    }
}
