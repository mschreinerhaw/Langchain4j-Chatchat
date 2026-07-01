package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InterpretationPlanValidatorTest {

    private final InterpretationPlanValidator validator = new InterpretationPlanValidator();

    @Test
    void validPlanIsExecutableAndTopologicallyOrdered() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .riskLevel("low")
            .build());

        InterpretationPlanValidator.ValidationResult result = validator.validate(
            validPlan("low", List.of("document_search"), List.of()),
            toolRegistry,
            Set.of("document_search")
        );

        assertThat(result.valid()).isTrue();
        assertThat(result.executable()).isTrue();
        assertThat(result.approvalRequired()).isFalse();
        assertThat(result.orderedSteps()).extracting(InterpretationPlan.Step::id)
            .containsExactly(1, 2);
    }

    @Test
    void rejectsRegisteredToolThatIsNotRequestScopedAvailable() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_template_query")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_sql_datasource_template_query"))
            .thenReturn(ToolMetadata.builder()
                .id("mcp_chatchat_mcp_server_sql_datasource_template_query")
                .riskLevel("low")
                .build());

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_metadata", "Discover SQL templates", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1,
                    "mcp_tool",
                    "mcp_chatchat_mcp_server_sql_datasource_template_query",
                    Map.of("filters", Map.of("assetName", "248测试数据库")),
                    List.of(),
                    null,
                    null
                ),
                finalStep(2, List.of(1))
            )),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_sql_metadata_search", "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
            review(true)
        );

        InterpretationPlanValidator.ValidationResult result = validator.validate(
            plan,
            toolRegistry,
            Set.of("mcp_chatchat_mcp_server_sql_metadata_search", "mcp_chatchat_mcp_server_sql_query_execute")
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(InterpretationPlanValidator.ValidationIssue::message)
            .anyMatch(message -> message.contains("Tool is not registered or available"));
    }

    @Test
    void highRiskPlanRequiresExplicitAllowApproval() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("sql_query")).thenReturn(true);
        when(toolRegistry.getToolMetadata("sql_query")).thenReturn(ToolMetadata.builder()
            .id("sql_query")
            .riskLevel("high")
            .build());

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_query", "Run a governed SQL query", "high"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "sql_query", Map.of("sql", "select 1"), List.of(), null, null),
                finalStep(2, List.of(1))
            )),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of(), List.of(), 30000),
            review(true)
        );

        InterpretationPlanValidator.ValidationResult result = validator.validate(plan, toolRegistry);

        assertThat(result.valid()).isTrue();
        assertThat(result.executable()).isFalse();
        assertThat(result.approvalRequired()).isTrue();
        assertThat(result.approvalRequests())
            .singleElement()
            .satisfies(issue -> assertThat(issue.message()).contains("High-risk tool requires explicit allow_tool approval"));
    }

    @Test
    void rejectsDeniedToolMissingDependencyCycleAndMissingFinalAnswer() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("tool_chain", "Use document evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "document_search", Map.of("query", "x"), List.of(2), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "document_search", Map.of("query", "y"), List.of(1), null, null),
                new InterpretationPlan.Step(3, "mcp_tool", "document_search", Map.of("query", "z"), List.of(99), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(5, false, List.of(), List.of("document_search"), 30000),
            review(false)
        );

        InterpretationPlanValidator.ValidationResult result = validator.validate(plan, toolRegistry);

        assertThat(result.valid()).isFalse();
        assertThat(result.executable()).isFalse();
        assertThat(result.errors()).extracting(InterpretationPlanValidator.ValidationIssue::message)
            .anyMatch(message -> message.contains("denied"))
            .anyMatch(message -> message.contains("Dependency step does not exist: 99"))
            .anyMatch(message -> message.contains("Plan must be a DAG"))
            .anyMatch(message -> message.contains("Exactly one final_answer step is required"));
    }

    @Test
    void rejectsToolStepMissingRequiredMetadataInput() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("web_crawler")).thenReturn(true);
        when(toolRegistry.getToolMetadata("web_crawler")).thenReturn(ToolMetadata.builder()
            .id("web_crawler")
            .riskLevel("low")
            .parameters(List.of(ToolParameter.builder()
                .name("url")
                .type("string")
                .required(true)
                .build()))
            .build());

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("web_search", "Fetch a selected page", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "web_crawler", Map.of("query", "鍒嗘瀽浠婂ぉ甯傚満鐑偣"), List.of(), null, null),
                finalStep(2, List.of(1))
            )),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of("web_crawler"), List.of(), 30000),
            review(true)
        );

        InterpretationPlanValidator.ValidationResult result = validator.validate(plan, toolRegistry, Set.of("web_crawler"));

        assertThat(result.valid()).isFalse();
        assertThat(result.executable()).isFalse();
        assertThat(result.errors()).extracting(InterpretationPlanValidator.ValidationIssue::message)
            .anyMatch(message -> message.contains("Required tool input is missing for web_crawler: url"));
    }

    @Test
    void allowsRequiredToolInputProvidedByBinding() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("web_search")).thenReturn(true);
        when(toolRegistry.hasTool("web_crawler")).thenReturn(true);
        when(toolRegistry.getToolMetadata("web_search")).thenReturn(ToolMetadata.builder()
            .id("web_search")
            .riskLevel("low")
            .parameters(List.of(ToolParameter.builder()
                .name("query")
                .type("string")
                .required(true)
                .build()))
            .build());
        when(toolRegistry.getToolMetadata("web_crawler")).thenReturn(ToolMetadata.builder()
            .id("web_crawler")
            .riskLevel("low")
            .parameters(List.of(ToolParameter.builder()
                .name("url")
                .type("string")
                .required(true)
                .build()))
            .build());

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("web_search", "Search then crawl", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "web_search", Map.of("query", "浠婂ぉ甯傚満鐑偣"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "web_crawler", Map.of(), List.of(1), null, null),
                    finalStep(3, List.of(2))
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(1, "$.results[0].url", 2, "url", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of("web_search", "web_crawler"), List.of(), 30000),
            review(true)
        );

        InterpretationPlanValidator.ValidationResult result = validator.validate(
            plan,
            toolRegistry,
            Set.of("web_search", "web_crawler")
        );

        assertThat(result.valid()).isTrue();
        assertThat(result.executable()).isTrue();
    }

    @Test
    void validatesRequiredAndOptionalDependencyContracts() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("asset_query")).thenReturn(true);
        when(toolRegistry.hasTool("template_query")).thenReturn(true);
        when(toolRegistry.hasTool("execute_tool")).thenReturn(true);
        when(toolRegistry.getToolMetadata("asset_query"))
            .thenReturn(ToolMetadata.builder().id("asset_query").riskLevel("low").build());
        when(toolRegistry.getToolMetadata("template_query"))
            .thenReturn(ToolMetadata.builder().id("template_query").riskLevel("low").build());
        when(toolRegistry.getToolMetadata("execute_tool"))
            .thenReturn(ToolMetadata.builder().id("execute_tool").riskLevel("low").build());

        InterpretationPlan valid = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("tool_chain", "Execute governed workflow", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "asset_query", Map.of("query", "db"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "template_query", Map.of("query", "metadata"), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "mcp_tool", "execute_tool", Map.of("template", "T1"), List.of(2), null, null),
                    finalStep(4, List.of(3))
                ),
                List.of(),
                List.of(
                    new InterpretationPlan.DependencyContract(1, 2, true, null, "template discovery requires asset context", "stop"),
                    new InterpretationPlan.DependencyContract(1, 3, false, "only when execution needs refreshed asset context", "optional context refresh", "skip")
                ),
                List.of(),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(4, false, List.of("asset_query", "template_query", "execute_tool"), List.of(), 30000),
            review(true)
        );

        assertThat(validator.validate(valid, toolRegistry, Set.of("asset_query", "template_query", "execute_tool")).valid())
            .isTrue();

        InterpretationPlan invalid = new InterpretationPlan(
            valid.version(),
            valid.intent(),
            valid.context(),
            new InterpretationPlan.Plan(
                valid.steps(),
                List.of(),
                List.of(
                    new InterpretationPlan.DependencyContract(1, 3, true, null, "required but missing from depends_on", "stop"),
                    new InterpretationPlan.DependencyContract(2, 3, false, null, null, "skip")
                ),
                List.of(),
                null
            ),
            valid.executionPolicy(),
            valid.review()
        );

        InterpretationPlanValidator.ValidationResult result =
            validator.validate(invalid, toolRegistry, Set.of("asset_query", "template_query", "execute_tool"));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(InterpretationPlanValidator.ValidationIssue::message)
            .anyMatch(message -> message.contains("Required dependency contract must also appear in target depends_on"))
            .anyMatch(message -> message.contains("Optional dependency contract requires condition or reason"));
    }

    @Test
    void rejectsRawSqlNestedInsideSqlTemplateParameters() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_sql_query_execute"))
            .thenReturn(ToolMetadata.builder()
                .id("mcp_chatchat_mcp_server_sql_query_execute")
                .riskLevel("low")
                .build());

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Analyze table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1,
                    "mcp_tool",
                    "mcp_chatchat_mcp_server_sql_query_execute",
                    Map.of(
                        "templateId", "MYSQL_INNODB_TRX",
                        "parameters", Map.of("sql", "SHOW CREATE TABLE rdsm_ad.t_ad_dict_entr_supn")
                    ),
                    List.of(),
                    null,
                    null
                ),
                finalStep(2, List.of(1))
            )),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
            review(true)
        );

        InterpretationPlanValidator.ValidationResult result = validator.validate(
            plan,
            toolRegistry,
            Set.of("mcp_chatchat_mcp_server_sql_query_execute")
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(InterpretationPlanValidator.ValidationIssue::message)
            .anyMatch(message -> message.contains("Raw SQL is not a template parameter"));
    }


    @Test
    void rejectsAssetNameSchemaBindingForSqlExecute() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_asset_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_sql_datasource_asset_query"))
            .thenReturn(ToolMetadata.builder()
                .id("mcp_chatchat_mcp_server_sql_datasource_asset_query")
                .riskLevel("low")
                .build());
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_sql_query_execute"))
            .thenReturn(ToolMetadata.builder()
                .id("mcp_chatchat_mcp_server_sql_query_execute")
                .riskLevel("low")
                .build());

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Analyze table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1,
                        "mcp_tool",
                        "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                        Map.of("filters", Map.of("assetName", "test_db_248"), "trace", Map.of("plannerVersion", "v1")),
                        List.of(),
                        null,
                        null
                    ),
                    new InterpretationPlan.Step(
                        2,
                        "mcp_tool",
                        "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of(
                            "templateId", "MYSQL_TABLE_METADATA",
                            "parameters", Map.of("tableName", "lbappdeploydetail")
                        ),
                        List.of(1),
                        null,
                        null
                    ),
                    finalStep(3, List.of(2))
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(
                    1,
                    "$.assets[0].asset.name",
                    2,
                    "parameters.schemaName",
                    "jsonpath",
                    false
                )),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                4,
                false,
                List.of(
                    "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    "mcp_chatchat_mcp_server_sql_query_execute"
                ),
                List.of(),
                30000
            ),
            review(true)
        );

        InterpretationPlanValidator.ValidationResult result = validator.validate(
            plan,
            toolRegistry,
            Set.of(
                "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                "mcp_chatchat_mcp_server_sql_query_execute"
            )
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(InterpretationPlanValidator.ValidationIssue::message)
            .anyMatch(message -> message.contains("Do not bind asset_query assets[].asset.name"));
    }

    @Test
    void rejectsSqlExecuteWithoutAnyExecutionContextSource() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_sql_query_execute"))
            .thenReturn(ToolMetadata.builder()
                .id("mcp_chatchat_mcp_server_sql_query_execute")
                .riskLevel("low")
                .build());

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Analyze table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1,
                        "mcp_tool",
                        "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of(
                            "templateId", "MYSQL_TABLE_METADATA",
                            "parameters", Map.of("tableName", "lbappdeploydetail")
                        ),
                        List.of(),
                        null,
                        null
                    ),
                    finalStep(2, List.of(1))
                ),
                List.of(),
                List.of(),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
            review(true)
        );

        InterpretationPlanValidator.ValidationResult result = validator.validate(
            plan,
            toolRegistry,
            Set.of("mcp_chatchat_mcp_server_sql_query_execute")
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(InterpretationPlanValidator.ValidationIssue::message)
            .anyMatch(message -> message.contains("sql_query_execute requires logical executionContext"));
    }

    @Test
    void allowsBusinessTemplateBridgeWithoutDatasourceExecutionContext() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_business_query_template_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_business_query_template_search"))
            .thenReturn(ToolMetadata.builder()
                .id("mcp_chatchat_mcp_server_business_query_template_search")
                .riskLevel("low")
                .build());
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_sql_query_execute"))
            .thenReturn(ToolMetadata.builder()
                .id("mcp_chatchat_mcp_server_sql_query_execute")
                .riskLevel("low")
                .build());

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "分析行情数据发生较大波动时异常提醒数据", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1,
                        "mcp_tool",
                        "mcp_chatchat_mcp_server_business_query_template_search",
                        Map.of("filters", Map.of("intent", "行情数据大幅波动时异常提醒")),
                        List.of(),
                        null,
                        null
                    ),
                    new InterpretationPlan.Step(
                        2,
                        "mcp_tool",
                        "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of(
                            "templateId", "edayQuqtMoni",
                            "parameters", Map.of("etl_date", "20260601")
                        ),
                        List.of(1),
                        null,
                        null
                    ),
                    finalStep(3, List.of(2))
                ),
                List.of(),
                List.of(),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_business_query_template_search",
                    "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
            review(true)
        );

        InterpretationPlanValidator.ValidationResult result = validator.validate(
            plan,
            toolRegistry,
            Set.of("mcp_chatchat_mcp_server_business_query_template_search",
                "mcp_chatchat_mcp_server_sql_query_execute")
        );

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsTableMetadataTemplateWithEmptyParameters() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_sql_query_execute"))
            .thenReturn(ToolMetadata.builder()
                .id("mcp_chatchat_mcp_server_sql_query_execute")
                .riskLevel("low")
                .build());

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Analyze table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1,
                        "reasoning",
                        "",
                        Map.of("tableName", "lbappdeploydetail"),
                        List.of(),
                        null,
                        null
                    ),
                    new InterpretationPlan.Step(
                        2,
                        "mcp_tool",
                        "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of(
                            "templateId", "MYSQL_TABLE_METADATA",
                            "executionContext", Map.of("assetName", "248-test-db", "env", "DEV"),
                            "parameters", Map.of()
                        ),
                        List.of(),
                        null,
                        null
                    ),
                    finalStep(2, List.of(1))
                ),
                List.of(),
                List.of(),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
            review(true)
        );

        InterpretationPlanValidator.ValidationResult result = validator.validate(
            plan,
            toolRegistry,
            Set.of("mcp_chatchat_mcp_server_sql_query_execute")
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(InterpretationPlanValidator.ValidationIssue::message)
            .anyMatch(message -> message.contains("requires parameters.tableName"));
    }

    @Test
    void allowsTableMetadataTemplateWhenTableNameBindingExists() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_sql_query_execute"))
            .thenReturn(ToolMetadata.builder()
                .id("mcp_chatchat_mcp_server_sql_query_execute")
                .riskLevel("low")
                .build());

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Analyze table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1,
                        "reasoning",
                        "",
                        Map.of("tableName", "lbappdeploydetail"),
                        List.of(),
                        null,
                        null
                    ),
                    new InterpretationPlan.Step(
                        2,
                        "mcp_tool",
                        "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of(
                            "templateId", "MYSQL_TABLE_METADATA",
                            "executionContext", Map.of("assetName", "248-test-db", "env", "DEV"),
                            "parameters", Map.of()
                        ),
                        List.of(1),
                        null,
                        null
                    ),
                    finalStep(3, List.of(2))
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(
                    1,
                    "$.tableName",
                    2,
                    "parameters.tableName",
                    "jsonpath",
                    true
                )),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
            review(true)
        );

        InterpretationPlanValidator.ValidationResult result = validator.validate(
            plan,
            toolRegistry,
            Set.of("mcp_chatchat_mcp_server_sql_query_execute")
        );

        assertThat(result.errors()).extracting(InterpretationPlanValidator.ValidationIssue::message)
            .noneMatch(message -> message.contains("requires parameters.tableName"));
    }

    @Test
    void rejectsHttpTemplateExecutionWithRawRequestFields() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_http_request_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_http_request_execute"))
            .thenReturn(ToolMetadata.builder()
                .id("mcp_chatchat_mcp_server_http_request_execute")
                .riskLevel("low")
                .build());

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("http_request", "Call governed HTTP endpoint", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1,
                        "mcp_tool",
                        "mcp_chatchat_mcp_server_http_request_execute",
                        Map.of(
                            "template", "SERVICE_HEALTH",
                            "executionContext", Map.of("assetName", "service-api", "env", "DEV"),
                            "parameters", Map.of("serviceId", "demo", "url", "https://example.invalid/health"),
                            "body", Map.of("unsafe", true)
                        ),
                        List.of(),
                        null,
                        null
                    ),
                    finalStep(2, List.of(1))
                ),
                List.of(),
                List.of(),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_http_request_execute"),
                List.of(),
                30000
            ),
            review(true)
        );

        InterpretationPlanValidator.ValidationResult result = validator.validate(
            plan,
            toolRegistry,
            Set.of("mcp_chatchat_mcp_server_http_request_execute")
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(InterpretationPlanValidator.ValidationIssue::message)
            .anyMatch(message -> message.contains("Raw HTTP request fields are not template parameters")
                || message.contains("HTTP template execution must use a returned template id"));
    }

    @Test
    void rejectsSshTemplateExecutionWithRawCommandFields() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_linux_command_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_linux_command_execute"))
            .thenReturn(ToolMetadata.builder()
                .id("mcp_chatchat_mcp_server_linux_command_execute")
                .riskLevel("low")
                .build());

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("ssh_command", "Inspect host", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1,
                        "mcp_tool",
                        "mcp_chatchat_mcp_server_linux_command_execute",
                        Map.of(
                            "template", "CHECK_DISK",
                            "executionContext", Map.of("assetName", "app-host", "env", "DEV"),
                            "parameters", Map.of("path", "/data", "command", "rm -rf /tmp/x"),
                            "host", "192.168.1.10"
                        ),
                        List.of(),
                        null,
                        null
                    ),
                    finalStep(2, List.of(1))
                ),
                List.of(),
                List.of(),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_linux_command_execute"),
                List.of(),
                30000
            ),
            review(true)
        );

        InterpretationPlanValidator.ValidationResult result = validator.validate(
            plan,
            toolRegistry,
            Set.of("mcp_chatchat_mcp_server_linux_command_execute")
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(InterpretationPlanValidator.ValidationIssue::message)
            .anyMatch(message -> message.contains("Raw SSH command/target fields are not template parameters")
                || message.contains("SSH template execution must use a returned template id"));
    }

    @Test
    void rejectsSqlExecuteWithOnlyInlineJsonPathExecutionContext() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_sql_query_execute"))
            .thenReturn(ToolMetadata.builder()
                .id("mcp_chatchat_mcp_server_sql_query_execute")
                .riskLevel("low")
                .build());

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Analyze table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1,
                        "mcp_tool",
                        "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of(
                            "templateId", "MYSQL_TABLE_METADATA",
                            "executionContext", Map.of(
                                "assetName", "$.assets[0].asset.name",
                                "env", "$.assets[0].asset.environment"
                            ),
                            "parameters", Map.of("tableName", "lbappdeploydetail")
                        ),
                        List.of(),
                        null,
                        null
                    ),
                    finalStep(2, List.of(1))
                ),
                List.of(),
                List.of(),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
            review(true)
        );

        InterpretationPlanValidator.ValidationResult result = validator.validate(
            plan,
            toolRegistry,
            Set.of("mcp_chatchat_mcp_server_sql_query_execute")
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(InterpretationPlanValidator.ValidationIssue::message)
            .anyMatch(message -> message.contains("sql_query_execute requires logical executionContext"));
    }

    @Test
    void deserializesSnakeCaseJsonPlan() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        InterpretationPlan plan = objectMapper.readValue("""
            {
              "version": "1.0",
              "intent": {"type": "document_retrieval", "goal": "Find evidence", "risk_level": "low"},
              "context": {"key_facts": [], "constraints": []},
              "plan": {
                "steps": [
                  {"id": 1, "action_type": "final_answer", "tool_name": "", "input": {"answer": "done"}, "depends_on": []}
                ]
              },
              "review": {
                "self_check": {"completeness_score": 0.9, "hallucination_risk": 0.1, "tool_sufficiency": true, "missing_steps": []}
              }
            }
            """, InterpretationPlan.class);

        assertThat(plan.intent().riskLevel()).isEqualTo("low");
        assertThat(plan.steps()).singleElement()
            .satisfies(step -> {
                assertThat(step.actionType()).isEqualTo("final_answer");
                assertThat(step.input()).containsEntry("answer", "done");
            });
        assertThat(InterpretationPlanJsonSchema.SCHEMA).contains("final_answer", "deny_tool", "self_check");
    }

    @Test
    void validatesPolicyBoundsAndStabilityReferences() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("document_retrieval", "Find evidence", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "document_search", Map.of("query", "x"), List.of(), null, null),
                    finalStep(2, List.of(1))
                ),
                List.of(),
                new InterpretationPlan.Stability(List.of(99), List.of("document_search"), false, List.of("unknown_action"))
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("document_search"),
                List.of(),
                30000,
                -1,
                "unsafe_mode",
                Map.of("document_search", 1.5),
                -1.0,
                0,
                1.2
            ),
            review(true)
        );

        InterpretationPlanValidator.ValidationResult result = validator.validate(plan, toolRegistry);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(InterpretationPlanValidator.ValidationIssue::message)
            .anyMatch(message -> message.contains("max_rewrite_times"))
            .anyMatch(message -> message.contains("fallback_mode"))
            .anyMatch(message -> message.contains("cost_budget"))
            .anyMatch(message -> message.contains("latency_budget_ms"))
            .anyMatch(message -> message.contains("accuracy_vs_speed"))
            .anyMatch(message -> message.contains("tool priority"))
            .anyMatch(message -> message.contains("Stable node does not exist"))
            .anyMatch(message -> message.contains("Unsupported mutable action_type"));
    }

    private InterpretationPlan validPlan(String riskLevel, List<String> allowTools, List<String> denyTools) {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("document_retrieval", "Answer from internal docs", riskLevel),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "document_search", Map.of("query", "internal definition"), List.of(), null, null),
                finalStep(2, List.of(1))
            )),
            new InterpretationPlan.ExecutionPolicy(5, false, allowTools, denyTools, 30000),
            review(true)
        );
    }

    private InterpretationPlan.Context context() {
        return new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of("Use registered MCP tools only"));
    }

    private InterpretationPlan.Step finalStep(int id, List<Integer> dependsOn) {
        return new InterpretationPlan.Step(id, "final_answer", "", Map.of("answer", "done"), dependsOn, null, null);
    }

    private InterpretationPlan.Review review(boolean toolSufficiency) {
        return new InterpretationPlan.Review(
            new InterpretationPlan.SelfCheck(0.9, 0.1, toolSufficiency, List.of()),
            List.of()
        );
    }
}
