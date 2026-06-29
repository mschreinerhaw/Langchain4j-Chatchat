package com.chatchat.mcpserver.sql;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataEngineQueryPlannerServiceTest {

    @Test
    void buildsJoinDagAndCostModelForRelatedTables() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:data_engine_planner;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table customer (id int primary key, name varchar(64))");
            statement.execute("create table customer_order (id int primary key, customer_id int, amount decimal(10,2), created_at timestamp)");
        }
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-plan");
        datasource.setName("planner-db");
        datasource.setToolName("db_query_planner");
        datasource.setJdbcUrl(jdbcUrl);
        datasource.setUsername("sa");
        datasource.setPassword("");
        datasource.setDatabaseType("postgresql");
        datasource.setEnvironment("DEV");

        SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
        when(datasourceConfigService.getEnabled("ds-plan")).thenReturn(datasource);
        MetadataIndexService metadataIndexService = new MetadataIndexService();
        TableSemanticMatcher semanticMatcher = new TableSemanticMatcher();
        MetadataUsageHistoryService usageHistoryService = new MetadataUsageHistoryService();
        MetadataResolverService resolverService = new MetadataResolverService(
            datasourceConfigService,
            metadataIndexService,
            new MetadataResolverEngine(
                new DatasourceBindingService(),
                semanticMatcher,
                new RoutingFusionEngine(usageHistoryService)
            ),
            semanticMatcher,
            usageHistoryService
        );
        DataEngineQueryPlannerService planner = new DataEngineQueryPlannerService(
            datasourceConfigService,
            resolverService,
            metadataIndexService,
            semanticMatcher,
            new JoinGraphBuilder(metadataIndexService),
            new CostModelRouter(metadataIndexService)
        );

        QueryPlan plan = planner.plan(Map.of(
            "datasourceId", "ds-plan",
            "question", "analyze customer order trend",
            "tables", java.util.List.of("customer", "customer_order")
        ));

        assertThat(plan.schemaVersion()).isEqualTo("query_plan.v4");
        assertThat(plan.steps()).extracting(PlanNode::type).contains("SELECT", "JOIN", "AGG");
        assertThat(plan.joinGraph().edges()).hasSize(1);
        assertThat(plan.joinGraph().edges().get(0).confidence()).isGreaterThan(0.8);
        assertThat(plan.costModel()).containsEntry("schemaVersion", "cost_model.v1");
        assertThat(plan.toDiagnostic()).containsEntry("strategy", "PARALLEL_SELECT_THEN_JOIN");
    }
}
