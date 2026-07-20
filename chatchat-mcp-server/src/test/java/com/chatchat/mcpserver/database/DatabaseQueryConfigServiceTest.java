package com.chatchat.mcpserver.database;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.mcpserver.api.ApiServiceConfigRepository;
import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import com.chatchat.mcpserver.search.LuceneSearchProperties;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseQueryConfigServiceTest {

    @TempDir
    Path tempDir;

    private final DatabaseQueryConfigRepository repository = mock(DatabaseQueryConfigRepository.class);
    private final ApiServiceConfigRepository apiServiceConfigRepository = mock(ApiServiceConfigRepository.class);
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
    private final DatabaseQueryConfigService service = new DatabaseQueryConfigService(
        repository,
        apiServiceConfigRepository,
        toolRegistry,
        new ObjectMapper(),
        datasourceConfigService
    );

    @Test
    void rejectsDatabaseQueryWithoutDatasourceAsset() {
        DatabaseQueryConfig draft = query();
        draft.setDatasourceId(null);
        draft.setJdbcUrl("jdbc:mysql://example:3306/demo");

        assertThatThrownBy(() -> service.create(draft))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("datasourceId is required");
    }

    @Test
    void savesOnlyDatasourceAssetAndClearsLegacyJdbcFields() {
        DatabaseQueryConfig draft = query();
        draft.setDatasourceId("asset-1");
        draft.setJdbcUrl("jdbc:mysql://legacy:3306/demo");
        draft.setDriverClass("com.mysql.cj.jdbc.Driver");
        draft.setUsername("legacy_user");
        draft.setPassword("legacy_password");
        draft.setReloadDrivers(true);
        draft.setGovernanceJson("{\"intent\":\"order_analysis\",\"tags\":[\"orders\",\"analysis\"],\"riskLevel\":\"safe\",\"owner\":\"bi-admin\"}");
        draft.setBusinessGroup("Order BI");
        draft.setBusinessGroupName("Order business intelligence");
        draft.setBusinessGroupDescription("Templates for order revenue and fulfillment analysis");
        draft.setTimeoutSeconds(120);
        draft.setRating(9.0);
        draft.setUsageCount(-10L);
        when(toolRegistry.hasTool("query_orders")).thenReturn(false);
        when(apiServiceConfigRepository.findByToolNameIgnoreCase("query_orders")).thenReturn(Optional.empty());
        when(repository.findByToolNameIgnoreCase("query_orders")).thenReturn(Optional.empty());
        when(datasourceConfigService.getEnabled("asset-1")).thenReturn(datasource("asset-1"));
        when(repository.save(any(DatabaseQueryConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DatabaseQueryConfig saved = service.create(draft);

        assertThat(saved.getDatasourceId()).isEqualTo("asset-1");
        assertThat(saved.getJdbcUrl()).isNull();
        assertThat(saved.getDriverClass()).isNull();
        assertThat(saved.getUsername()).isNull();
        assertThat(saved.getPassword()).isNull();
        assertThat(saved.isReloadDrivers()).isFalse();
        assertThat(saved.getDatabaseType()).isEqualTo("mysql");
        assertThat(saved.getTemplateIntent()).isEqualTo("order_analysis");
        assertThat(saved.getBusinessGroup()).isEqualTo("order_bi");
        assertThat(saved.getBusinessGroupName()).isEqualTo("Order business intelligence");
        assertThat(saved.getBusinessGroupDescription()).contains("order revenue");
        assertThat(saved.getTimeoutSeconds()).isEqualTo(120);
        assertThat(saved.getTagsJson()).contains("orders", "analysis");
        assertThat(saved.getRiskLevel()).isEqualTo("safe");
        assertThat(saved.getOwner()).isEqualTo("bi-admin");
        assertThat(saved.getRating()).isEqualTo(5.0);
        assertThat(saved.getUsageCount()).isZero();
    }

    @Test
    void normalizesConfiguredSqlStepsAndKeepsFirstSqlAsLegacyTemplate() throws Exception {
        DatabaseQueryConfig draft = query();
        draft.setDatasourceId("asset-1");
        draft.setSqlTemplate("");
        draft.setImplementationSteps("Run summary SQL before detail SQL.");
        draft.setSqlStepsJson(new ObjectMapper().writeValueAsString(List.of(
            sqlStep("summary", "Summary rows", "select count(*) cnt from orders", 2, null),
            sqlStep("detail", "Active order rows", "select * from orders where status = :status", 1,
                Map.of("status", "ACTIVE"))
        )));
        when(toolRegistry.hasTool("query_orders")).thenReturn(false);
        when(apiServiceConfigRepository.findByToolNameIgnoreCase("query_orders")).thenReturn(Optional.empty());
        when(repository.findByToolNameIgnoreCase("query_orders")).thenReturn(Optional.empty());
        when(datasourceConfigService.getEnabled("asset-1")).thenReturn(datasource("asset-1"));
        when(repository.save(any(DatabaseQueryConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DatabaseQueryConfig saved = service.create(draft);

        assertThat(saved.getImplementationSteps()).contains("summary SQL");
        assertThat(saved.getSqlTemplate()).isEqualTo("select * from orders where status = :status");
        assertThat(saved.getSqlStepsJson()).contains("DETAIL", "SUMMARY", "Active order rows", "Summary rows");
    }

    @Test
    void rejectsSqlParameterDeclaredByBothFixedValueAndMapping() throws Exception {
        DatabaseQueryConfig draft = query();
        draft.setDatasourceId("asset-1");
        DatabaseQuerySqlStep step = sqlStep("detail", "Active order rows",
            "select * from orders where status = :status", 1, Map.of("status", "ACTIVE"));
        DatabaseQueryParameterMapping mapping = new DatabaseQueryParameterMapping();
        mapping.setParameter("status");
        mapping.setSourceType("USER_INPUT");
        mapping.setSourceKey("status");
        mapping.setRequired(true);
        step.setParameterMappings(List.of(mapping));
        draft.setSqlStepsJson(new ObjectMapper().writeValueAsString(List.of(step)));
        when(datasourceConfigService.getEnabled("asset-1")).thenReturn(datasource("asset-1"));

        assertThatThrownBy(() -> service.create(draft))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("parameters must use exactly one source")
            .hasMessageContaining("status");
    }

    @Test
    void searchesDatabaseQueryTemplatesThroughLucene() {
        DatabaseQueryConfigService searchable = new DatabaseQueryConfigService(
            repository,
            apiServiceConfigRepository,
            toolRegistry,
            new ObjectMapper(),
            datasourceConfigService,
            lucene()
        );
        DatabaseQueryConfig status = query();
        status.setId("query-1");
        status.setToolName("mysql_db_status");
        status.setTitle("MySQL database status");
        status.setDescription("Read database health and status for business analysis");
        status.setBusinessGroup("ops_health");
        status.setBusinessGroupName("Operations health");
        status.setBusinessGroupDescription("Database health and status diagnostics");
        status.setSqlTemplate("show status");
        DatabaseQueryConfig orders = query();
        orders.setId("query-2");
        orders.setToolName("order_recent_rows");
        orders.setTitle("Recent order rows");
        orders.setDescription("Read latest order records");
        orders.setSqlTemplate("select id from orders order by created_at desc");
        when(repository.findAllByOrderByToolNameAsc()).thenReturn(List.of(orders, status));

        List<DatabaseQueryConfig> result = searchable.search("operations health status");

        assertThat(result).extracting(DatabaseQueryConfig::getId).startsWith("query-1");
    }

    private DatabaseQueryConfig query() {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setToolName("query_orders");
        config.setTitle("Query orders");
        config.setDescription("Read order data for analysis");
        config.setImplementationSteps("Run the configured read-only SQL steps");
        config.setSqlTemplate("select id from orders");
        config.setMaxRows(50);
        config.setTimeoutSeconds(30);
        config.setEnabled(true);
        return config;
    }

    private DatabaseQuerySqlStep sqlStep(String code,
                                         String description,
                                         String sql,
                                         int executionOrder,
                                         Map<String, Object> parameters) {
        DatabaseQuerySqlStep step = new DatabaseQuerySqlStep();
        step.setSqlCode(code);
        step.setSqlName(code);
        step.setSqlDescription(description);
        step.setSqlContent(sql);
        step.setExecutionOrder(executionOrder);
        step.setEnabled(true);
        step.setFailureStrategy("STOP");
        step.setParameters(parameters);
        return step;
    }

    private SqlDatasourceConfig datasource(String id) {
        SqlDatasourceConfig config = new SqlDatasourceConfig();
        config.setId(id);
        config.setName("orders-db");
        config.setEnabled(true);
        config.setJdbcUrl("jdbc:mysql://example:3306/orders");
        config.setDatabaseType("mysql");
        return config;
    }

    private LuceneMcpSearchService lucene() {
        LuceneSearchProperties properties = new LuceneSearchProperties();
        properties.setIndexDir(tempDir.toString());
        return new LuceneMcpSearchService(properties);
    }
}
