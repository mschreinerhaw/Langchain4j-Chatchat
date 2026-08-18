package com.chatchat.chat.skills;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScenarioCatalogTest {

    private final AgentScenarioCatalog catalog = new AgentScenarioCatalog();

    @Test
    void compilesEveryExplicitScenarioOfPublishedAgentsOnly() {
        SkillDefinition defaultAgent = agent(
            "financial-document",
            true,
            List.of("解释数据字典字段", "总结需求文档"),
            List.of("需求文档解析"),
            List.of("document_search"),
            List.of(),
            Map.of()
        );
        SkillDefinition sqlAgent = agent(
            "financial-sql",
            false,
            List.of("生成 Hive SQL"),
            List.of("SQL 方言转换", "查询优化"),
            List.of("metadata_search"),
            List.of(),
            Map.of()
        );
        SkillDefinition draft = withMarketStatus(sqlAgent, SkillCatalogService.MARKET_STATUS_DRAFT);

        AgentScenarioCatalog.CoverageSuite suite =
            catalog.compilePublished(List.of(defaultAgent, sqlAgent, draft));

        assertThat(suite.issues()).isEmpty();
        assertThat(suite.agents()).extracting(AgentScenarioCatalog.AgentCoverage::agentId)
            .containsExactly("financial-document", "financial-sql");
        assertThat(suite.scenarioCount()).isEqualTo(6);
        assertThat(suite.agents().get(0).scenarios())
            .extracting(AgentScenarioCatalog.Scenario::source)
            .containsExactly("quick_question", "quick_question", "usage_scenario");
    }

    @Test
    void reportsRequiredTemplateToolThatWorkflowCannotCall() {
        SkillDefinition agent = agent(
            "business-query",
            true,
            List.of("查询今日业务数据"),
            List.of(),
            List.of("sql_query_execute"),
            List.of(),
            Map.of("steps", List.of(
                Map.of("step", 1, "tool", "database_query_template_query", "required", true),
                Map.of("step", 2, "tool", "sql_query_execute", "required", true)
            ))
        );

        AgentScenarioCatalog.CoverageSuite suite = catalog.compilePublished(List.of(agent));

        assertThat(suite.issues())
            .anySatisfy(issue -> {
                assertThat(issue.agentId()).isEqualTo("business-query");
                assertThat(issue.code()).isEqualTo("WORKFLOW_TOOL_NOT_BOUND");
                assertThat(issue.message()).contains("database_query_template_query");
            });
    }

    @Test
    void acceptsEnabledToolConfigAndValidWorkflowDependencies() {
        SkillToolConfig templateTool = new SkillToolConfig(
            "template_search", "Template search", "mcp", "", List.of(), "read", 1, true
        );
        SkillDefinition agent = agent(
            "database-ops",
            true,
            List.of("排查慢 SQL"),
            List.of(),
            List.of("sql_query_execute"),
            List.of(templateTool),
            Map.of(
                "mcpWorkflow", Map.of("steps", List.of(
                    Map.of("tool", "template_search", "required", true),
                    Map.of("tool", "sql_query_execute", "required", true)
                )),
                "toolDependencies", Map.of(
                    "sql_query_execute", Map.of("dependsOn", List.of("template_search"))
                )
            )
        );

        assertThat(catalog.compilePublished(List.of(agent)).issues()).isEmpty();
    }

    @Test
    void reportsDefaultAgentCardinalityForPublishedSuite() {
        SkillDefinition first = agent(
            "first", false, List.of("场景一"), List.of(), List.of(), List.of(), Map.of()
        );
        SkillDefinition second = agent(
            "second", false, List.of("场景二"), List.of(), List.of(), List.of(), Map.of()
        );

        assertThat(catalog.compilePublished(List.of(first, second)).issues())
            .extracting(AgentScenarioCatalog.CoverageIssue::code)
            .contains("DEFAULT_AGENT_MISSING");

        assertThat(catalog.compilePublished(List.of(
            withDefault(first, true),
            withDefault(second, true)
        )).issues())
            .extracting(AgentScenarioCatalog.CoverageIssue::code)
            .contains("DEFAULT_AGENT_DUPLICATED");
    }

    @Test
    void reportsContradictoryAndDuplicatedToolConfiguration() {
        SkillToolConfig disabled = new SkillToolConfig(
            "metadata_search", "Metadata", "mcp", "", List.of(), "read", 1, false
        );
        SkillDefinition agent = agent(
            "invalid-tools",
            true,
            List.of("查询元数据"),
            List.of(),
            List.of("metadata_search"),
            List.of(disabled, disabled),
            Map.of()
        );

        assertThat(catalog.compilePublished(List.of(agent)).issues())
            .extracting(AgentScenarioCatalog.CoverageIssue::code)
            .contains("TOOL_CONFIG_DUPLICATED", "BOUND_TOOL_DISABLED");
    }

    @Test
    void reportsWorkflowShapeErrorsBeforeRuntime() {
        SkillDefinition agent = agent(
            "invalid-workflow",
            true,
            List.of("执行流程"),
            List.of(),
            List.of("known_tool"),
            List.of(),
            Map.of(
                "steps", List.of(
                    Map.of("required", true),
                    Map.of("tool", "known_tool"),
                    Map.of("tool", "known_tool")
                ),
                "toolDependencies", Map.of(
                    "unknown_target", Map.of("dependsOn", List.of("unknown_dependency"))
                )
            )
        );

        assertThat(catalog.compilePublished(List.of(agent)).issues())
            .extracting(AgentScenarioCatalog.CoverageIssue::code)
            .contains(
                "WORKFLOW_STEP_TOOL_MISSING",
                "WORKFLOW_TOOL_DUPLICATED",
                "WORKFLOW_DEPENDENCY_TOOL_UNKNOWN",
                "WORKFLOW_DEPENDENCY_UNKNOWN"
            );
    }

    private SkillDefinition agent(String id,
                                  boolean defaultAgent,
                                  List<String> quickQuestions,
                                  List<String> usageScenarios,
                                  List<String> boundTools,
                                  List<SkillToolConfig> toolConfigs,
                                  Map<String, Object> workflowConfig) {
        return new SkillDefinition(
            id,
            id,
            id + " description",
            usageScenarios,
            List.of("contract"),
            "agent_chat",
            null,
            "Follow the runtime contract.",
            null,
            List.of(),
            List.of(),
            boundTools,
            List.of(),
            List.of(),
            toolConfigs,
            null,
            workflowConfig,
            null,
            null,
            quickQuestions,
            SkillCatalogService.MARKET_STATUS_PUBLISHED,
            defaultAgent
        );
    }

    private SkillDefinition withDefault(SkillDefinition source, boolean defaultAgent) {
        return copy(source, source.marketStatus(), defaultAgent);
    }

    private SkillDefinition withMarketStatus(SkillDefinition source, String marketStatus) {
        return copy(source, marketStatus, Boolean.TRUE.equals(source.defaultAgent()));
    }

    private SkillDefinition copy(SkillDefinition source, String marketStatus, boolean defaultAgent) {
        return new SkillDefinition(
            source.id(),
            source.label(),
            source.description(),
            source.usageScenarios(),
            source.skillTags(),
            source.defaultMode(),
            source.modelName(),
            source.systemPrompt(),
            source.firstUseGreeting(),
            source.preferredToolPrefixes(),
            source.boundMcpServiceIds(),
            source.boundMcpToolNames(),
            source.boundDocumentIds(),
            source.boundDocumentTags(),
            source.toolConfigs(),
            source.routingSettings(),
            source.workflowConfig(),
            source.defaultDataAsset(),
            source.assetSelectionPolicy(),
            source.quickQuestions(),
            marketStatus,
            defaultAgent
        );
    }
}
