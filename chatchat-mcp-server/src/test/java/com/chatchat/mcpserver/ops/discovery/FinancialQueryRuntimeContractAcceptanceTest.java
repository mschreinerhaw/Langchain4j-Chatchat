package com.chatchat.mcpserver.ops.discovery;

import com.chatchat.mcpserver.ops.command.CommandTemplateService;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.ssh.SshHostConfigService;

import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.category.BusinessCategory;
import com.chatchat.mcpserver.database.category.DataQueryCategoryService;
import com.chatchat.mcpserver.routing.target.TargetKindRegistry;
import com.chatchat.mcpserver.search.engine.LuceneMcpSearchService;
import com.chatchat.mcpserver.search.engine.LuceneSearchProperties;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.template.SqlTemplateService;
import com.chatchat.runtime.market.analysis.FinancialAnalysisQuerySamples;
import com.chatchat.runtime.market.analysis.FinancialMarketQueryExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinancialQueryRuntimeContractAcceptanceTest {

    private static final String QUESTION = "帮我分析最新交易日证券与指数涨跌";
    private static final String RUNTIME_NAMESPACE =
        "mcp_acceptance_" + Long.toUnsignedString(System.nanoTime()) + "_capability_gateway_";
    private static final String DISCOVERY_TOOL = RUNTIME_NAMESPACE + "database_query_template_query";
    private static final String EXECUTION_TOOL = RUNTIME_NAMESPACE + "sql_query_execute";

    @TempDir
    Path tempDir;

    @Test
    void discoversMaintainedQuerySampleAndExecutesItThroughRuntimeOwnedBinding() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        List<DatabaseQueryConfig> maintainedQueries = FinancialAnalysisQuerySamples.all().stream()
            .map(sample -> enabledConfig(sample, objectMapper))
            .toList();
        DatabaseQueryConfigService queryConfigService = mock(DatabaseQueryConfigService.class);
        when(queryConfigService.listEnabled()).thenReturn(maintainedQueries);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        when(datasourceService.getEnabled(FinancialAnalysisQuerySamples.INTERNAL_DATASOURCE_ID))
            .thenReturn(financialDatasource());
        CommandTemplateDiscoveryService discoveryService = discoveryService(
            queryConfigService, datasourceService, objectMapper);

        JdbcDataSource dataSource = marketDataSource();
        FinancialMarketQueryExecutor queryExecutor = new FinancialMarketQueryExecutor(dataSource);
        AtomicReference<Map<String, Object>> discoveryResult = new AtomicReference<>();
        AtomicReference<Map<String, Object>> executionInput = new AtomicReference<>();
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> execute(
            invocation.getArgument(0),
            discoveryService,
            maintainedQueries,
            queryExecutor,
            discoveryResult,
            executionInput
        ));
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());

        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            request -> {
                Integer next = request.remainingStepIds().stream().sorted().findFirst().orElse(null);
                if (next != null && next == 3) {
                    return InterpretationPlanRuntime.DagDecision.finalAnswer(
                        next, "已基于查询证据完成最新交易日证券与指数涨跌分析。", "evidence ready");
                }
                return InterpretationPlanRuntime.DagDecision.executeStep(next, "execute next contract step");
            }
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan(),
                toolRegistry,
                List.of(DISCOVERY_TOOL, EXECUTION_TOOL),
                "tenant-market",
                "request-market-analysis",
                "conversation-market-analysis",
                "user-market",
                Map.of("originalUserQuery", QUESTION)
            )
        );

        assertThat(result.success())
            .as("status=%s error=%s metadata=%s", result.status(), result.errorMessage(), result.metadata())
            .isTrue();
        assertThat(discoveryResult.get())
            .containsEntry("schemaVersion", CommandTemplateDiscoveryService.RESULT_SCHEMA_VERSION)
            .containsEntry("targetKind", "business_database_query")
            .containsEntry("categoryRequired", false);
        assertThat((Integer) discoveryResult.get().get("returnedCount")).isPositive();
        assertThat(discoveryResult.get().get("selectedCategory").toString())
            .contains("market_data", "\u5e02\u573a\u884c\u60c5");
        assertThat(discoveryResult.get().get("retrievalFlow").toString())
            .contains("business_category_resolution", "global_template_search_with_category_ranking",
                "sql_template_execution", "evidence_analysis");
        List<?> templates = (List<?>) discoveryResult.get().get("templates");
        Map<?, ?> selected = (Map<?, ?>) templates.get(0);
        assertThat(selected.get("templateId")).isEqualTo("sample_market_latest_movers");
        assertThat(selected.get("requiredParameters")).isEqualTo(List.of());
        Map<?, ?> selectedExecutionContext = (Map<?, ?>) selected.get("executionContext");
        assertThat(selectedExecutionContext.get("env")).isEqualTo("DEV");
        assertThat(selectedExecutionContext.get("environment")).isEqualTo("DEV");
        Map<?, ?> selectedDatasourceAsset = (Map<?, ?>) selected.get("datasourceAsset");
        assertThat(selectedDatasourceAsset.get("environment")).isEqualTo("DEV");
        assertThat(selected.toString())
            .doesNotContain("SELECT observation_date", "FROM market_quote_daily");

        Map<String, Object> boundInput = executionInput.get();
        assertThat(boundInput)
            .containsEntry("templateId", selected.get("templateId"))
            .containsEntry("template", selected.get("templateId"));
        Map<?, ?> boundExecutionContext = (Map<?, ?>) boundInput.get("executionContext");
        assertThat(boundExecutionContext.get("env")).isEqualTo("DEV");
        assertThat(boundExecutionContext.get("environment")).isEqualTo("DEV");
        assertThat(boundInput.toString()).doesNotContain("SELECT", "market_quote_daily");

        InterpretationPlanRuntime.StepExecution queryStep = result.steps().stream()
            .filter(step -> Integer.valueOf(2).equals(step.stepId()))
            .findFirst()
            .orElseThrow();
        Map<?, ?> queryOutput = (Map<?, ?>) queryStep.output();
        assertThat(queryOutput.get("schemaVersion")).isEqualTo("database_query_result.v1");
        assertThat(queryOutput.get("rowCount")).isEqualTo(3);
        assertThat(queryOutput.get("possiblyTruncated")).isEqualTo(false);
        List<?> rows = (List<?>) queryOutput.get("rows");
        assertThat(rows.stream().map(row -> String.valueOf(((Map<?, ?>) row).get("quote_name"))).toList())
            .containsExactly("领涨证券", "上证指数", "领跌证券");
        assertThat(rows).allSatisfy(row ->
            assertThat(((Map<?, ?>) row).get("observation_date").toString()).isEqualTo("2026-07-27"));
        assertThat(result.finalAnswer()).contains("查询证据", "最新交易日", "证券与指数涨跌");
    }

    private InterpretationPlan plan() {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", QUESTION, "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of("只使用已维护的数据查询能力")),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1,
                        "mcp_tool",
                        DISCOVERY_TOOL,
                        Map.of(
                            "finalDecision", "business_database_query",
                            "candidates", List.of(Map.of(
                                "targetKind", "business_database_query",
                                "confidence", 0.95
                            )),
                            "filters", Map.of(
                                "category", "market_data",
                                "intent", QUESTION,
                                "env", "DEV"),
                            "limit", 1
                        ),
                        List.of(),
                        new InterpretationPlan.OutputContract("object", "template_query_result.v1"),
                        null
                    ),
                    new InterpretationPlan.Step(
                        2,
                        "mcp_tool",
                        EXECUTION_TOOL,
                        Map.of("purpose", QUESTION, "parameters", Map.of()),
                        List.of(1),
                        new InterpretationPlan.OutputContract("object", "database_query_result.v1"),
                        null
                    ),
                    new InterpretationPlan.Step(
                        3,
                        "final_answer",
                        "",
                        Map.of(
                            "answer", "基于步骤 2 的查询证据完成最新交易日证券与指数涨跌分析。",
                            "artifact_contract", Map.of(
                                "schema_version", "runtime_artifact_contract.v1",
                                "artifact_type", "evidence_analysis",
                                "delivery_state", "complete"
                            )
                        ),
                        List.of(2),
                        null,
                        null
                    )
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(
                    1, "$.templates[0].templateId", 2, "templateId", "jsonpath", true
                )),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of(DISCOVERY_TOOL, EXECUTION_TOOL),
                List.of(),
                30_000
            ),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(0.95, 0.05, true, List.of()),
                List.of("查询无结果时明确说明证据不足")
            )
        );
    }

    private ToolRuntimeExecution execute(
        ToolRuntimeRequest request,
        CommandTemplateDiscoveryService discoveryService,
        List<DatabaseQueryConfig> maintainedQueries,
        FinancialMarketQueryExecutor queryExecutor,
        AtomicReference<Map<String, Object>> discoveryResult,
        AtomicReference<Map<String, Object>> executionInput
    ) {
        Map<String, Object> arguments = new LinkedHashMap<>(request.getToolInput().getParameters());
        if (DISCOVERY_TOOL.equals(request.getToolName())) {
            Map<String, Object> result = discoveryService.query(arguments);
            discoveryResult.set(result);
            return success(request.getToolName(), result);
        }
        if (!EXECUTION_TOOL.equals(request.getToolName())) {
            return failure(request.getToolName(), "unexpected tool");
        }
        Map<String, Object> executorArguments = firstExecutableBatchArguments(arguments);
        executionInput.set(executorArguments);
        String templateId = String.valueOf(executorArguments.get("templateId"));
        DatabaseQueryConfig selected = maintainedQueries.stream()
            .filter(config -> templateId.equals(config.getToolName()))
            .findFirst()
            .orElseThrow();
        FinancialMarketQueryExecutor.QueryResult queryResult =
            queryExecutor.execute(selected.getSqlTemplate(), Map.of(), selected.getMaxRows(), selected.getTimeoutSeconds());
        return success(request.getToolName(), Map.of(
            "schemaVersion", "database_query_result.v1",
            "templateId", templateId,
            "rowCount", queryResult.rowCount(),
            "columns", queryResult.columns(),
            "rows", queryResult.rows(),
            "possiblyTruncated", queryResult.possiblyTruncated(),
            "evidenceBoundary", "仅代表已采集数据和维护样例定义的结果范围"
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstExecutableBatchArguments(Map<String, Object> arguments) {
        Object rawCalls = arguments.get("calls");
        if (!(rawCalls instanceof Iterable<?> calls)) {
            return arguments;
        }
        for (Object item : calls) {
            if (!(item instanceof Map<?, ?> rawCall) || rawCall.containsKey("preflightErrorCode")) {
                continue;
            }
            Object rawArguments = rawCall.get("arguments");
            if (!(rawArguments instanceof Map<?, ?> childArguments)) {
                continue;
            }
            Object templateId = childArguments.get("templateId");
            if ("sample_market_latest_movers".equals(String.valueOf(templateId))) {
                return new LinkedHashMap<>((Map<String, Object>) childArguments);
            }
        }
        throw new IllegalStateException("Runtime-owned template batch did not contain an executable market query");
    }

    private ToolRuntimeExecution success(String toolName, Object data) {
        return new ToolRuntimeExecution(
            ToolOutput.success(data),
            ToolMetadata.builder().id(toolName).riskLevel("low").build(),
            null,
            "success",
            Map.of()
        );
    }

    private ToolRuntimeExecution failure(String toolName, String message) {
        return new ToolRuntimeExecution(
            ToolOutput.failure(message),
            ToolMetadata.builder().id(toolName).riskLevel("low").build(),
            null,
            "failed",
            Map.of()
        );
    }

    private CommandTemplateDiscoveryService discoveryService(
        DatabaseQueryConfigService queryConfigService,
        SqlDatasourceConfigService datasourceService,
        ObjectMapper objectMapper
    ) {
        LuceneSearchProperties searchProperties = new LuceneSearchProperties();
        searchProperties.setIndexDir(tempDir.toString());
        BusinessCategory market = new BusinessCategory();
        market.setId("market-category");
        market.setCode("market_data");
        market.setName("\u5e02\u573a\u884c\u60c5");
        market.setDescription("\u8bc1\u5238\u3001\u6307\u6570\u4e0e\u878d\u8d44\u878d\u5238\u6570\u636e\u5206\u6790");
        market.setDomain("finance");
        market.setKeywordsJson("[\"\u8bc1\u5238\",\"\u6307\u6570\",\"\u878d\u8d44\u878d\u5238\"]");
        market.setEnabled(true);
        DataQueryCategoryService categoryService = mock(DataQueryCategoryService.class);
        when(categoryService.resolve(any(), any())).thenReturn(
            new DataQueryCategoryService.CategoryResolution(market, false, List.of(market)));
        when(categoryService.keywords(market))
            .thenReturn(List.of("\u8bc1\u5238", "\u6307\u6570", "\u878d\u8d44\u878d\u5238"));
        return new CommandTemplateDiscoveryService(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            mock(SqlTemplateService.class),
            datasourceService,
            mock(HttpEndpointConfigService.class),
            queryConfigService,
            categoryService,
            objectMapper,
            new TemplateDiscoveryProperties(),
            new LuceneMcpSearchService(searchProperties),
            new TargetKindRegistry()
        );
    }

    private DatabaseQueryConfig enabledConfig(
        FinancialAnalysisQuerySamples.Sample sample,
        ObjectMapper objectMapper
    ) {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId(sample.id());
        config.setToolName(sample.toolName());
        config.setTitle(sample.title());
        config.setDatasourceId(FinancialAnalysisQuerySamples.INTERNAL_DATASOURCE_ID);
        config.setDescription(sample.description());
        config.setImplementationSteps(sample.implementationSteps());
        config.setSqlTemplate(sample.sql());
        config.setInputSchemaJson(json(objectMapper, sample.inputSchema()));
        config.setTagsJson(json(objectMapper, sample.tags()));
        config.setTemplateIntent(sample.intent());
        config.setCategoryId("market-category");
        config.setCapabilityCategory("market_data");
        config.setBusinessGroup("market_data");
        config.setBusinessGroupName("\u5e02\u573a\u884c\u60c5");
        config.setBusinessGroupDescription("\u8bc1\u5238\u3001\u6307\u6570\u4e0e\u878d\u8d44\u878d\u5238\u6570\u636e\u5206\u6790");
        config.setDatabaseType("h2");
        config.setRiskLevel("read_only");
        config.setOwner("data-capability-center");
        config.setMaxRows(sample.maxRows());
        config.setTimeoutSeconds(10);
        config.setEnabled(true);
        return config;
    }

    private String json(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private SqlDatasourceConfig financialDatasource() {
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId(FinancialAnalysisQuerySamples.INTERNAL_DATASOURCE_ID);
        datasource.setName("financial-market-runtime");
        datasource.setTitle("金融市场 Runtime 数据");
        datasource.setToolName("financial_market_data");
        datasource.setEnvironment("TEST");
        datasource.setDatabaseType("h2");
        datasource.setEnabled(true);
        return datasource;
    }

    private JdbcDataSource marketDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:financial-runtime-contract-" + System.nanoTime()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            create table market_quote_daily (
                id bigint primary key,
                observation_date date,
                collected_at timestamp,
                source_code varchar(64),
                source_url varchar(1000),
                quote_code varchar(32),
                quote_name varchar(160),
                instrument_type varchar(32),
                previous_close decimal(20,4),
                open decimal(20,4),
                high decimal(20,4),
                low decimal(20,4),
                close decimal(20,4),
                change_pct decimal(20,4),
                volume10_k_units decimal(20,4),
                amount10_k_cny decimal(20,4),
                amount100_m_cny decimal(20,4)
            )
            """);
        insertQuote(jdbc, 1, "2026-07-26", "2026-07-26 15:00:00",
            "000001", "历史证券", "STOCK", 1.0);
        insertQuote(jdbc, 2, "2026-07-27", "2026-07-27 14:55:00",
            "600001", "领涨证券", "STOCK", 2.1);
        insertQuote(jdbc, 3, "2026-07-27", "2026-07-27 15:00:00",
            "600001", "领涨证券", "STOCK", 3.2);
        insertQuote(jdbc, 4, "2026-07-27", "2026-07-27 15:00:00",
            "000001", "上证指数", "INDEX", 0.8);
        insertQuote(jdbc, 5, "2026-07-27", "2026-07-27 15:00:00",
            "600002", "领跌证券", "STOCK", -1.4);
        return dataSource;
    }

    private void insertQuote(
        JdbcTemplate jdbc,
        long id,
        String observationDate,
        String collectedAt,
        String code,
        String name,
        String type,
        double changePct
    ) {
        jdbc.update("""
            insert into market_quote_daily(
                id, observation_date, collected_at, source_code, source_url,
                quote_code, quote_name, instrument_type, previous_close, open,
                high, low, close, change_pct, volume10_k_units, amount10_k_cny, amount100_m_cny
            ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            id,
            Date.valueOf(observationDate),
            Timestamp.valueOf(collectedAt),
            "acceptance-source",
            "https://example.test/market/" + code,
            code,
            name,
            type,
            100,
            100,
            104,
            98,
            100 + changePct,
            changePct,
            10,
            100,
            1
        );
    }
}
