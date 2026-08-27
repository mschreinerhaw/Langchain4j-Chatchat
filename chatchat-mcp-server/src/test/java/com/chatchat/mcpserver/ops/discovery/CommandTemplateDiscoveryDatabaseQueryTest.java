package com.chatchat.mcpserver.ops.discovery;

import com.chatchat.mcpserver.ops.command.CommandTemplateService;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.ssh.SshHostConfigService;

import com.chatchat.mcpserver.database.definition.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.category.BusinessCategory;
import com.chatchat.mcpserver.category.BusinessCategoryRepository;
import com.chatchat.mcpserver.database.category.DataQueryCategoryService;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigRepository;
import com.chatchat.mcpserver.routing.TargetKindRegistry;
import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import com.chatchat.mcpserver.search.LuceneSearchProperties;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.template.SqlTemplateService;
import com.chatchat.runtime.market.analysis.FinancialAnalysisQuerySamples;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandTemplateDiscoveryDatabaseQueryTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsRuntimeManagedFinancialTemplateWithoutExternalDatasourceRegistration() {
        DatabaseQueryConfig marginQuery = query(
            "margin-query",
            "builtin-market-margin-trade",
            FinancialAnalysisQuerySamples.INTERNAL_DATASOURCE_ID,
            category("market-category", "market_data", "市场行情"),
            "最新融资融券余额 margin trading balance"
        );
        marginQuery.setDatabaseType("h2");
        DatabaseQueryConfigService databaseQueryService = mock(DatabaseQueryConfigService.class);
        when(databaseQueryService.listEnabled()).thenReturn(List.of(marginQuery));
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        when(datasourceService.getEnabled(FinancialAnalysisQuerySamples.INTERNAL_DATASOURCE_ID))
            .thenReturn(null);
        DataQueryCategoryService categoryService = mock(DataQueryCategoryService.class);
        BusinessCategory market = category("market-category", "market_data", "市场行情");
        when(categoryService.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new DataQueryCategoryService.CategoryResolution(market, false, List.of(market)));

        CommandTemplateDiscoveryService service = new CommandTemplateDiscoveryService(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            mock(SqlTemplateService.class),
            datasourceService,
            mock(HttpEndpointConfigService.class),
            databaseQueryService,
            categoryService,
            new ObjectMapper(),
            new TemplateDiscoveryProperties(),
            lucene(),
            new TargetKindRegistry()
        );

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "business_database_query",
            "confidence", 0.95,
            "filters", Map.of(
                "intent", "融资融券余额",
                "intentEn", "margin trading balance",
                "category", "market_data",
                "env", "DEV"
            ),
            "trace", trace(),
            "limit", 10
        ));

        assertThat(result).containsEntry("returnedCount", 1);
        Map<?, ?> template = (Map<?, ?>) ((List<?>) result.get("templates")).get(0);
        assertThat(template.get("templateId")).isEqualTo("builtin-market-margin-trade");
        assertThat(template.get("mcpToolName")).isEqualTo("sql_query_execute");
        Map<?, ?> executionContext = (Map<?, ?>) template.get("executionContext");
        assertThat(executionContext.get("assetName")).isEqualTo("financial-market-runtime");
        assertThat(executionContext.get("env")).isEqualTo("DEV");
    }

    @Test
    void returnsSqlScriptExecutorForRegisteredBusinessDatabaseQueryDag() {
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
        query.setSqlTemplate("SELECT id, service_name FROM service_registry WHERE status = {{status}}");
        query.setSqlStepsJson("[{\"sqlCode\":\"services\",\"sqlName\":\"Services\",\"sqlContent\":\"SELECT id FROM service_registry\",\"executionOrder\":1,\"workflowEnabled\":true,\"enabled\":true},{\"sqlCode\":\"health\",\"sqlName\":\"Health\",\"sqlContent\":\"SELECT status FROM service_health\",\"executionOrder\":2,\"dependencies\":[\"services\"],\"workflowEnabled\":true,\"enabled\":true}]");
        query.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"}},\"required\":[\"status\"]}");
        query.setGovernanceJson("{\"intent\":\"service_status\",\"tags\":[\"service\",\"active\",\"business\"]}");
        query.setRoutingLabelsJson("[\"service-health\",\"lifecycle\"]");
        query.setCapabilitiesJson("[\"database_query\",\"sql_query_execute\"]");
        query.setTemplateIntent("service_status");
        query.setDatabaseType("mysql");
        query.setTagsJson("[\"service\",\"active\",\"business\"]");
        query.setRiskLevel("read_only");
        query.setOwner("ops-admin");
        query.setRating(4.5);
        query.setUsageCount(16);
        query.setMaxRows(80);
        query.setTimeoutSeconds(45);
        query.setJdbcUrl("jdbc:mysql://ops-host:3306/ops");
        query.setDriverClass("com.mysql.cj.jdbc.Driver");
        query.setUsername("ops_user");
        query.setPassword("secret");
        query.setReloadDrivers(true);
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
        Map<?, ?> templateConfig = (Map<?, ?>) first.get("templateConfig");
        Map<?, ?> configConnection = (Map<?, ?>) templateConfig.get("connection");
        assertThat(result).containsEntry("returnedCount", 1);
        assertThat(first.get("templateId")).isEqualTo("query_active_services");
        assertThat(first.get("mcpToolName")).isEqualTo("sql_query_execute");
        assertThat(first.get("databaseQueryId")).isEqualTo("query-1");
        assertThat(first.get("intent")).isEqualTo("service_status");
        assertThat(first.get("businessGroup").toString()).contains("service_ops", "Service operations", "active service health");
        assertThat(first.get("description").toString()).contains("Service operations", "service_ops", "active service health");
        assertThat(first.get("databaseType")).isEqualTo("mysql");
        assertThat(first.get("riskLevel")).isEqualTo("read_only");
        assertThat(first.get("owner")).isEqualTo("ops-admin");
        assertThat(first.get("tags").toString()).contains("service", "active", "business");
        assertThat(first.get("executionContext").toString()).contains("ops-mysql", "DEV", "mysql");
        assertThat(first.get("sqlExecutionBinding").toString()).contains("sql_query_execute", "executionContext");
        assertThat(first.get("datasourceAsset").toString()).contains("ops-mysql", "db_query_ops_mysql");
        assertThat(first.get("mcpDecision").toString()).contains("SQL Template Marketplace");
        assertThat(first.get("rankingFeatures").toString()).contains("dbTypeMatch", "luceneScore", "usageScore");
        assertThat(templateConfig.get("governance").toString()).contains("service_status", "business");
        assertThat(templateConfig.get("routingLabels").toString()).contains("service-health", "lifecycle");
        assertThat(templateConfig.get("capabilities").toString()).contains("database_query", "sql_query_execute");
        assertThat(templateConfig.get("maxRows")).isEqualTo(80);
        assertThat(templateConfig.get("timeoutSeconds")).isEqualTo(45);
        assertThat(configConnection.toString()).contains("ops-mysql", "DEV", "mysql");
        assertThat(configConnection.containsKey("jdbcUrl")).isFalse();
        assertThat(configConnection.containsKey("driverClass")).isFalse();
        assertThat(configConnection.containsKey("username")).isFalse();
        assertThat(configConnection.containsKey("passwordConfigured")).isFalse();
        assertThat(configConnection.containsKey("reloadDrivers")).isFalse();
        assertThat(configConnection.containsKey("password")).isFalse();
        assertThat(execution.get("mode")).isEqualTo("template_execution");
        assertThat(execution.get("executorTool")).isEqualTo("sql_query_execute");
        assertThat(execution.get("template")).isEqualTo("query_active_services");
        assertThat(execution.get("callTool")).isEqualTo("sql_query_execute");
        assertThat(first.get("parameterSchema").toString()).contains("status");
        assertThat(result.toString())
            .doesNotContain("sqlTemplate", "SELECT id", "service_registry", "WHERE status", "secret",
                "jdbc:mysql://ops-host:3306/ops", "ops_user", "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void businessCategoryRanksDatabaseTemplatesWithoutSuppressingCrossCategoryCandidates() {
        BusinessCategory market = category(
            "market-category", "market_data", "\u5e02\u573a\u884c\u60c5");
        market.setKeywordsJson("[\"融资融券\",\"margin trading\",\"securities lending\",\"信用交易\"]");
        BusinessCategory customer = category(
            "customer-category", "customer_analysis", "\u5ba2\u6237\u5206\u6790");
        DatabaseQueryConfig marginQuery = query(
            "margin-query", "query_margin_trade_latest", "ds-market", market,
            "\u6700\u65b0\u878d\u8d44\u878d\u5238\u4f59\u989d\u53ca\u53d8\u5316\u5206\u6790");
        DatabaseQueryConfig customerQuery = query(
            "customer-query", "query_customer_assets", "ds-customer", customer,
            "\u5ba2\u6237\u8d44\u4ea7\u4e0e\u753b\u50cf\u5206\u6790");
        DatabaseQueryConfigService databaseQueryService = mock(DatabaseQueryConfigService.class);
        when(databaseQueryService.listEnabled()).thenReturn(List.of(customerQuery, marginQuery));
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        SqlDatasourceConfig marketDatasource = datasource("ds-market", "market-db");
        marketDatasource.setEnvironment("DEV");
        SqlDatasourceConfig customerDatasource = datasource("ds-customer", "customer-db");
        customerDatasource.setEnvironment("DEV");
        when(datasourceService.getEnabled("ds-market")).thenReturn(marketDatasource);
        when(datasourceService.getEnabled("ds-customer")).thenReturn(customerDatasource);
        BusinessCategoryRepository categoryRepository = mock(BusinessCategoryRepository.class);
        when(categoryRepository.findByEnabledTrueOrderBySortOrderAscNameAsc())
            .thenReturn(List.of(market, customer));
        DataQueryCategoryService categoryService = new DataQueryCategoryService(
            categoryRepository,
            mock(DatabaseQueryConfigRepository.class),
            new ObjectMapper()
        );
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(true);
        when(lucene.searchDatabaseQueryTemplates(org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
            new LuceneMcpSearchService.SearchHit(
                "query_customer_assets", "database_query", 20.0f, List.of("cross-category")),
            new LuceneMcpSearchService.SearchHit(
                "query_margin_trade_latest", "database_query", 8.0f, List.of("market"))
        ));
        CommandTemplateDiscoveryService service = new CommandTemplateDiscoveryService(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            mock(SqlTemplateService.class),
            datasourceService,
            mock(HttpEndpointConfigService.class),
            databaseQueryService,
            categoryService,
            new ObjectMapper(),
            new TemplateDiscoveryProperties(),
            lucene,
            new TargetKindRegistry()
        );

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "business_database_query",
            "confidence", 0.95,
            "filters", Map.ofEntries(
                Map.entry("intent", "查询融资融券最新数据以进行观察分析"),
                Map.entry("bilingualIntent", List.of("margin trading", "融资融券数据")),
                Map.entry("intentZh", "融资融券数据观察分析"),
                Map.entry("intentEn", "margin trading and securities lending data observation"),
                Map.entry("intentAliases", List.of(
                    "融资融券", "margin financing", "securities lending", "信用交易")),
                Map.entry("keywords", List.of(
                    "融资融券", "margin trading", "securities lending", "观察", "分析")),
                Map.entry("env", "DEV"),
                Map.entry("retrievalSignals", List.of("explicit:false", "false"))
            ),
            "trace", trace(),
            "limit", 10
        ));

        assertThat(result).containsEntry("categoryRequired", false).containsEntry("returnedCount", 1);
        assertThat(result.get("selectedCategory").toString()).contains("market_data", "\u5e02\u573a\u884c\u60c5");
        assertThat(result.get("retrievalFlow").toString())
            .contains("business_category_resolution", "global_template_search_with_category_ranking",
                "sql_template_execution", "evidence_analysis", "crossCategoryResultsAllowed=true");
        assertThat(result.get("templates").toString())
            .contains("query_margin_trade_latest", "sql_query_execute", "market_data")
            .doesNotContain("query_customer_assets", "customer_analysis");
    }

    @Test
    void ambiguousBusinessCategoryDoesNotBlockTemplateSearch() {
        BusinessCategory market = category(
            "market-category", "market_data", "\u5e02\u573a\u884c\u60c5");
        BusinessCategory customer = category(
            "customer-category", "customer_analysis", "\u5ba2\u6237\u5206\u6790");
        DatabaseQueryConfig marginQuery = query(
            "margin-query", "query_margin_trade_latest", "ds-market", market,
            "\u878d\u8d44\u878d\u5238\u6570\u636e");
        DatabaseQueryConfig customerQuery = query(
            "customer-query", "query_customer_assets", "ds-customer", customer,
            "\u5ba2\u6237\u8d44\u4ea7\u6570\u636e");
        DatabaseQueryConfigService databaseQueryService = mock(DatabaseQueryConfigService.class);
        when(databaseQueryService.listEnabled()).thenReturn(List.of(marginQuery, customerQuery));
        DataQueryCategoryService categoryService = mock(DataQueryCategoryService.class);
        when(categoryService.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new DataQueryCategoryService.CategoryResolution(
                null, true, List.of(market, customer)));
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(true);
        CommandTemplateDiscoveryService service = new CommandTemplateDiscoveryService(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            mock(SqlTemplateService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class),
            databaseQueryService,
            categoryService,
            new ObjectMapper(),
            new TemplateDiscoveryProperties(),
            lucene,
            new TargetKindRegistry()
        );

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "business_database_query",
            "confidence", 0.95,
            "filters", Map.of("intent", "\u5e2e\u6211\u5206\u6790\u6700\u65b0\u6570\u636e"),
            "trace", trace()
        ));

        assertThat(result).containsEntry("categoryRequired", false).containsEntry("returnedCount", 0);
        assertThat(result.get("categoryCandidates").toString()).contains("market_data", "customer_analysis");
        assertThat((List<?>) result.get("templates")).isEmpty();
        assertThat(result.get("categoryDiagnostics").toString())
            .contains("categoryAmbiguous=true", "categoryUsage=ranking_signal_and_model_selection_metadata");
        verify(lucene, org.mockito.Mockito.atLeastOnce()).searchDatabaseQueryTemplates(
            org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingBusinessCategoryUsesDefaultAsSignalWithoutSuppressingOtherHits() {
        BusinessCategory fallback = category("default-category", "default", "默认分类");
        BusinessCategory market = category("market-category", "market_data", "市场行情");
        DatabaseQueryConfig fallbackQuery = query(
            "default-query", "query_generic_business_data", "ds-default", fallback, "通用业务数据查询");
        DatabaseQueryConfig marketQuery = query(
            "market-query", "query_margin_trade_latest", "ds-market", market, "融资融券数据");
        DatabaseQueryConfigService databaseQueryService = mock(DatabaseQueryConfigService.class);
        when(databaseQueryService.listEnabled()).thenReturn(List.of(fallbackQuery, marketQuery));
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        when(datasourceService.getEnabled("ds-default")).thenReturn(datasource("ds-default", "default-db"));
        when(datasourceService.getEnabled("ds-market")).thenReturn(datasource("ds-market", "market-db"));
        DataQueryCategoryService categoryService = mock(DataQueryCategoryService.class);
        when(categoryService.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new DataQueryCategoryService.CategoryResolution(
                fallback, false, List.of(market, fallback), true));
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(true);
        when(lucene.searchDatabaseQueryTemplates(org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
            new LuceneMcpSearchService.SearchHit(
                "query_margin_trade_latest", "database_query", 20.0f, List.of("cross-category")),
            new LuceneMcpSearchService.SearchHit(
                "query_generic_business_data", "database_query", 8.0f, List.of("default"))
        ));
        CommandTemplateDiscoveryService service = new CommandTemplateDiscoveryService(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            mock(SqlTemplateService.class),
            datasourceService,
            mock(HttpEndpointConfigService.class),
            databaseQueryService,
            categoryService,
            new ObjectMapper(),
            new TemplateDiscoveryProperties(),
            lucene,
            new TargetKindRegistry()
        );

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "business_database_query",
            "confidence", 0.95,
            "filters", Map.of("category", "missing-category", "intent", "查询业务数据"),
            "trace", trace()
        ));

        assertThat(result).containsEntry("categoryRequired", false).containsEntry("returnedCount", 2);
        assertThat(result.get("selectedCategory").toString()).contains("default");
        assertThat(result.get("categoryDiagnostics").toString())
            .contains("fallbackUsed=true", "fallbackCategory=default");
        assertThat(result.get("templates").toString())
            .contains("query_margin_trade_latest", "query_generic_business_data", "sql_query_execute", "market_data");
    }

    @Test
    void originalBusinessQuestionRecallsMaintainedTemplateOutsideSuggestedCategory() {
        BusinessCategory market = category("market-category", "market_data", "市场行情");
        BusinessCategory fallback = category("default-category", "default", "默认分类");
        DatabaseQueryConfig genericMarketQuery = query(
            "market-query", "query_market_overview", "ds-market", market, "股票指数市场行情概览");
        DatabaseQueryConfig maintainedMarginQuery = query(
            "maintained-margin-query", "query_latest_margin_observation", "ds-user", fallback,
            "最新融资融券数据观察，返回融资余额、融资买入额和融券余量");

        DatabaseQueryConfigService databaseQueryService = mock(DatabaseQueryConfigService.class);
        when(databaseQueryService.listEnabled()).thenReturn(List.of(genericMarketQuery, maintainedMarginQuery));
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        when(datasourceService.getEnabled("ds-market")).thenReturn(datasource("ds-market", "market-db"));
        when(datasourceService.getEnabled("ds-user")).thenReturn(datasource("ds-user", "user-maintained-db"));
        DataQueryCategoryService categoryService = mock(DataQueryCategoryService.class);
        when(categoryService.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new DataQueryCategoryService.CategoryResolution(
                market, false, List.of(market, fallback)));
        CommandTemplateDiscoveryService service = new CommandTemplateDiscoveryService(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            mock(SqlTemplateService.class),
            datasourceService,
            mock(HttpEndpointConfigService.class),
            databaseQueryService,
            categoryService,
            new ObjectMapper(),
            new TemplateDiscoveryProperties(),
            lucene(),
            new TargetKindRegistry()
        );

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "business_database_query",
            "confidence", 0.95,
            "filters", Map.of("intent", "获取最新融资融券数据并进行分析"),
            "trace", trace(),
            "limit", 10
        ));

        assertThat(result).containsEntry("categoryRequired", false);
        assertThat(result.get("selectedCategory").toString()).contains("market_data");
        assertThat(result.get("templates").toString())
            .contains("query_latest_margin_observation", "default");
        assertThat(result.get("categoryDiagnostics").toString())
            .contains("ranking_signal_and_model_selection_metadata");
    }

    @Test
    void databaseQuerySearchFallsBackToRelevantRegistryEntriesWhenSearchIndexReturnsNoHits() {
        BusinessCategory market = category("market-category", "market_data", "市场行情");
        DatabaseQueryConfig marginQuery = query(
            "margin-query", "query_margin_trade_latest", "ds-market", market,
            "最新融资融券余额、融资买入额和融券余量");
        DatabaseQueryConfig unrelatedQuery = query(
            "bond-query", "query_bond_settlement", "ds-market", market,
            "债券结算规模和交易笔数");
        DatabaseQueryConfigService databaseQueryService = mock(DatabaseQueryConfigService.class);
        when(databaseQueryService.listEnabled()).thenReturn(List.of(unrelatedQuery, marginQuery));
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        SqlDatasourceConfig datasource = datasource("ds-market", "market-db");
        datasource.setEnvironment("DEV");
        when(datasourceService.getEnabled("ds-market")).thenReturn(datasource);
        DataQueryCategoryService categoryService = mock(DataQueryCategoryService.class);
        when(categoryService.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new DataQueryCategoryService.CategoryResolution(market, false, List.of(market)));
        LuceneMcpSearchService search = mock(LuceneMcpSearchService.class);
        when(search.enabled()).thenReturn(true);
        when(search.searchDatabaseQueryTemplates(
            org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of());

        CommandTemplateDiscoveryService service = new CommandTemplateDiscoveryService(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            mock(SqlTemplateService.class),
            datasourceService,
            mock(HttpEndpointConfigService.class),
            databaseQueryService,
            categoryService,
            new ObjectMapper(),
            new TemplateDiscoveryProperties(),
            search,
            new TargetKindRegistry()
        );

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "business_database_query",
            "confidence", 0.95,
            "filters", Map.of(
                "intent", "分析最新融资融券数据",
                "intentEn", "latest margin trading data",
                "env", "DEV"
            ),
            "trace", trace(),
            "limit", 10
        ));

        assertThat(result).containsEntry("returnedCount", 1);
        assertThat(result.get("templates").toString())
            .contains("query_margin_trade_latest")
            .doesNotContain("query_bond_settlement");
        assertThat(result.get("resolutionTrace").toString()).contains("fallbackUsed=true");
    }

    @Test
    void clauseLimitFailureRequestsModelKeywordReviewInsteadOfReturningRegistryFallback() {
        BusinessCategory market = category("market-category", "market_data", "市场行情");
        DatabaseQueryConfig marginQuery = query(
            "margin-query", "query_margin_trade_latest", "ds-market", market,
            "最新融资融券余额、融资买入额和融券余量");
        DatabaseQueryConfigService databaseQueryService = mock(DatabaseQueryConfigService.class);
        when(databaseQueryService.listEnabled()).thenReturn(List.of(marginQuery));
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        SqlDatasourceConfig datasource = datasource("ds-market", "market-db");
        datasource.setEnvironment("DEV");
        when(datasourceService.getEnabled("ds-market")).thenReturn(datasource);
        DataQueryCategoryService categoryService = mock(DataQueryCategoryService.class);
        when(categoryService.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new DataQueryCategoryService.CategoryResolution(market, false, List.of(market)));
        LuceneMcpSearchService search = mock(LuceneMcpSearchService.class);
        when(search.enabled()).thenReturn(true);
        when(search.searchDatabaseQueryTemplates(
            org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of());
        when(search.lastSearchDiagnostic()).thenReturn(new LuceneMcpSearchService.SearchDiagnostic(
            "QUERY_CLAUSE_LIMIT_EXCEEDED",
            "too_many_nested_clauses; maxClauseCount is set to 1024",
            true
        ));

        CommandTemplateDiscoveryService service = new CommandTemplateDiscoveryService(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            mock(SqlTemplateService.class),
            datasourceService,
            mock(HttpEndpointConfigService.class),
            databaseQueryService,
            categoryService,
            new ObjectMapper(),
            new TemplateDiscoveryProperties(),
            search,
            new TargetKindRegistry()
        );

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "business_database_query",
            "confidence", 0.95,
            "filters", Map.of(
                "intent", "分析最新融资融券数据",
                "keywords", List.of("融资融券", "margin trading", "融资余额"),
                "env", "DEV"
            ),
            "trace", trace(),
            "limit", 10
        ));

        assertThat(result)
            .containsEntry("status", "MODEL_REVIEW_REQUIRED")
            .containsEntry("resultCode", "QUERY_CLAUSE_LIMIT_EXCEEDED")
            .containsEntry("retryable", true)
            .containsEntry("returnedCount", 0);
        assertThat(result.get("retrievalReview").toString())
            .contains("REWRITE_TEMPLATE_SEARCH_KEYWORDS_AND_RETRY", "maxKeywords=8");
        assertThat((List<?>) result.get("templates")).isEmpty();
    }

    private LuceneMcpSearchService lucene() {
        LuceneSearchProperties properties = new LuceneSearchProperties();
        properties.setIndexDir(tempDir.toString());
        return new LuceneMcpSearchService(properties);
    }

    private BusinessCategory category(String id, String code, String name) {
        BusinessCategory category = new BusinessCategory();
        category.setId(id);
        category.setCode(code);
        category.setName(name);
        category.setDescription(name);
        category.setDomain("finance");
        category.setKeywordsJson("[]");
        category.setEnabled(true);
        return category;
    }

    private DatabaseQueryConfig query(String id, String toolName, String datasourceId,
                                      BusinessCategory category, String description) {
        DatabaseQueryConfig query = new DatabaseQueryConfig();
        query.setId(id);
        query.setToolName(toolName);
        query.setTitle(description);
        query.setDescription(description);
        query.setDatasourceId(datasourceId);
        query.setCategoryId(category.getId());
        query.setCapabilityCategory(category.getCode());
        query.setBusinessGroup(category.getCode());
        query.setBusinessGroupName(category.getName());
        query.setDatabaseType("mysql");
        query.setSqlTemplate("select 1");
        query.setInputSchemaJson("{\"type\":\"object\",\"properties\":{}}");
        query.setTemplateIntent(category.getCode());
        query.setRiskLevel("read_only");
        query.setEnabled(true);
        return query;
    }

    private SqlDatasourceConfig datasource(String id, String name) {
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId(id);
        datasource.setName(name);
        datasource.setTitle(name);
        datasource.setToolName("db_" + name);
        datasource.setEnvironment("TEST");
        datasource.setDatabaseType("mysql");
        datasource.setEnabled(true);
        return datasource;
    }

    private Map<String, Object> trace() {
        return Map.of("plannerVersion", "v1.0", "model", "unit-test");
    }
}
