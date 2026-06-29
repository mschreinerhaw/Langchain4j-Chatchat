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

        assertThat(plan.schemaVersion()).isEqualTo("query_plan.v6");
        assertThat(plan.steps()).extracting(PlanNode::type).contains("SELECT", "JOIN", "AGG");
        assertThat(plan.joinGraph().edges()).hasSize(1);
        assertThat(plan.joinGraph().edges().get(0).confidence()).isGreaterThan(0.8);
        assertThat(plan.costModel()).containsEntry("schemaVersion", "cost_model.v1");
        assertThat(plan.toDiagnostic()).containsEntry("strategy", "PARALLEL_SELECT_THEN_JOIN");
        Map<?, ?> diagnostics = plan.diagnostics();
        assertThat(String.valueOf(diagnostics.get("compilerVersion"))).isEqualTo("retrieval_augmented_query_compiler_v6");
        Map<?, ?> semanticIR = (Map<?, ?>) diagnostics.get("semanticIR");
        assertThat(String.valueOf(semanticIR.get("schemaVersion"))).isEqualTo("semantic_ir.v1");
        java.util.List<String> stages = ((java.util.List<?>) diagnostics.get("compilerPipeline")).stream()
            .map(stage -> String.valueOf(((Map<?, ?>) stage).get("stage")))
            .toList();
        assertThat(stages)
            .containsExactly(
                "SEMANTIC_IR_BUILD",
                "TABLE_RESOLUTION",
                "RETRIEVAL_PLAN",
                "QUERY_GRAPH_COMPILE",
                "COST_BASED_OPTIMIZE",
                "EXECUTION_DAG_GENERATE"
            );
        Map<?, ?> retrievalPlan = (Map<?, ?>) diagnostics.get("retrievalPlan");
        assertThat(retrievalPlan.get("retrievalNeeded")).isEqualTo(false);
    }

    @Test
    void insertsDocumentRetrievalAndMergeNodesForMetricDefinitionQuestion() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:unified_query_compiler;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table customer (id int primary key, name varchar(64))");
            statement.execute("create table customer_order (id int primary key, customer_id int, amount decimal(10,2), created_at timestamp)");
        }
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-unified");
        datasource.setName("unified-db");
        datasource.setToolName("db_query_unified");
        datasource.setJdbcUrl(jdbcUrl);
        datasource.setUsername("sa");
        datasource.setPassword("");
        datasource.setDatabaseType("postgresql");
        datasource.setEnvironment("DEV");

        SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
        when(datasourceConfigService.getEnabled("ds-unified")).thenReturn(datasource);
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
            "datasourceId", "ds-unified",
            "question", "analyze customer order trend and explain metric definition",
            "tables", java.util.List.of("customer", "customer_order")
        ));

        assertThat(plan.schemaVersion()).isEqualTo("query_plan.v6");
        assertThat(plan.strategy()).isEqualTo("UNIFIED_SQL_RAG_DAG");
        assertThat(plan.steps()).extracting(PlanNode::type)
            .contains("SELECT", "JOIN", "AGG", "DOC_RETRIEVAL", "MERGE");
        PlanNode docNode = plan.steps().stream()
            .filter(node -> "DOC_RETRIEVAL".equals(node.type()))
            .findFirst()
            .orElseThrow();
        assertThat(docNode.attributes()).containsEntry("toolName", "document_search");
        PlanNode mergeNode = plan.steps().stream()
            .filter(node -> "MERGE".equals(node.type()))
            .findFirst()
            .orElseThrow();
        assertThat(mergeNode.dependencies()).contains(docNode.id());
        Map<?, ?> retrievalPlan = (Map<?, ?>) plan.diagnostics().get("retrievalPlan");
        assertThat(retrievalPlan.get("retrievalNeeded")).isEqualTo(true);
        assertThat(String.valueOf(retrievalPlan.get("retrievalType"))).isEqualTo("document_search");
        assertThat(String.valueOf(retrievalPlan.get("priority"))).isEqualTo("HIGH");
    }
}
