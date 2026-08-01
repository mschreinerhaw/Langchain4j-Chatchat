package com.chatchat.api.contract;

import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRunStatus;
import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.service.AgentToolPolicyResolver;
import com.chatchat.chat.interaction.service.handler.AgentChatModeHandler;
import com.chatchat.chat.skills.AgentScenarioCatalog;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.chat.skills.SkillToolConfig;
import com.chatchat.chat.task.AgentLearningService;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in acceptance test against the Agent definitions maintained in an environment database.
 *
 * <p>When a JDBC URL is supplied the environment definitions are validated read-only. Otherwise
 * a deterministic maintained-Agent fixture keeps the release contract executable with zero skips.</p>
 */
class MaintainedAgentEnvironmentCoverageTest {

    private static final String AGENT_QUERY = """
        select id, label, description, usage_scenarios_json, skill_tags_json, default_mode,
               system_prompt, bound_mcp_service_ids_json, bound_mcp_tool_names_json,
               bound_document_ids_json, bound_document_tags_json, tool_configs_json,
               workflow_config_json, quick_questions_json, market_status, default_agent
          from skill_config
         order by default_agent desc, id
        """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentScenarioCatalog scenarioCatalog = new AgentScenarioCatalog();

    @TestFactory
    Stream<DynamicTest> coversEveryScenarioMaintainedByPublishedAgents() throws Exception {
        List<SkillDefinition> maintainedAgents = loadMaintainedAgents();
        AgentScenarioCatalog.CoverageSuite suite =
            scenarioCatalog.compilePublished(maintainedAgents);
        ScenarioRuntimeHarness runtimeHarness = new ScenarioRuntimeHarness(maintainedAgents);

        List<DynamicTest> tests = new ArrayList<>();
        tests.add(DynamicTest.dynamicTest("published Agent suite contract", () ->
            assertThat(suite.issues())
                .withFailMessage(() -> formatIssues(suite.issues()))
                .isEmpty()
        ));
        suite.agents().forEach(agent -> agent.scenarios().forEach(scenario ->
            tests.add(DynamicTest.dynamicTest(
                agent.agentId() + " [" + scenario.source() + "] " + scenario.query(),
                () -> {
                    assertThat(agent.defaultMode()).isEqualTo("agent_chat");
                    assertThat(scenario.query()).isNotBlank();
                    runtimeHarness.verify(agent.agentId(), scenario.query());
                }
            ))
        ));
        return tests.stream();
    }

    private List<SkillDefinition> loadMaintainedAgents() throws Exception {
        String jdbcUrl = System.getenv("CHATCHAT_AGENT_COVERAGE_JDBC_URL");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return deterministicMaintainedAgents();
        }
        Properties properties = new Properties();
        putWhenPresent(properties, "user", System.getenv("CHATCHAT_AGENT_COVERAGE_DB_USERNAME"));
        putWhenPresent(properties, "password", System.getenv("CHATCHAT_AGENT_COVERAGE_DB_PASSWORD"));
        try (Connection connection = DriverManager.getConnection(jdbcUrl, properties)) {
            connection.setReadOnly(true);
            try (ResultSet rows = connection.createStatement().executeQuery(AGENT_QUERY)) {
                List<SkillDefinition> agents = new ArrayList<>();
                while (rows.next()) {
                    agents.add(toDefinition(rows));
                }
                return agents;
            }
        }
    }

    private List<SkillDefinition> deterministicMaintainedAgents() {
        return List.of(new SkillDefinition(
            "release-fixture-agent",
            "Release fixture Agent",
            "Validates maintained Agent routing when no environment database is configured",
            List.of("分析最新公开信息并说明证据边界"),
            List.of("release", "evidence"),
            "agent_chat",
            null,
            "Use only tool evidence and state limitations.",
            null,
            List.of(),
            List.of("fixture-mcp-service"),
            List.of("web_search"),
            List.of(),
            List.of(),
            List.of(),
            null,
            Map.of(),
            null,
            null,
            List.of("请根据最新公开信息给出可追溯结论"),
            SkillCatalogService.MARKET_STATUS_PUBLISHED,
            true
        ));
    }

    private SkillDefinition toDefinition(ResultSet row) throws Exception {
        return new SkillDefinition(
            row.getString("id"),
            row.getString("label"),
            row.getString("description"),
            stringList(row.getString("usage_scenarios_json")),
            stringList(row.getString("skill_tags_json")),
            row.getString("default_mode"),
            null,
            row.getString("system_prompt"),
            null,
            List.of(),
            stringList(row.getString("bound_mcp_service_ids_json")),
            stringList(row.getString("bound_mcp_tool_names_json")),
            stringList(row.getString("bound_document_ids_json")),
            stringList(row.getString("bound_document_tags_json")),
            toolConfigs(row.getString("tool_configs_json")),
            null,
            objectMap(row.getString("workflow_config_json")),
            null,
            null,
            stringList(row.getString("quick_questions_json")),
            row.getString("market_status"),
            row.getBoolean("default_agent")
        );
    }

    private List<String> stringList(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    private List<SkillToolConfig> toolConfigs(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    private Map<String, Object> objectMap(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    private void putWhenPresent(Properties properties, String key, String value) {
        if (value != null && !value.isBlank()) {
            properties.setProperty(key, value);
        }
    }

    private String formatIssues(List<AgentScenarioCatalog.CoverageIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "No Agent coverage issues";
        }
        return issues.stream()
            .map(issue -> "[%s] agent=%s: %s".formatted(
                issue.code(),
                issue.agentId() == null ? "<suite>" : issue.agentId(),
                issue.message()
            ))
            .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }

    private static final class ScenarioRuntimeHarness {

        private final Map<String, SkillDefinition> agentsById;
        private final Map<String, List<String>> availableToolsByAgent;
        private final AgentChatModeHandler handler;
        private final AtomicReference<AgentRunRequest> lastRuntimeRequest = new AtomicReference<>();

        private ScenarioRuntimeHarness(List<SkillDefinition> maintainedAgents) {
            this.agentsById = maintainedAgents.stream()
                .filter(agent -> SkillCatalogService.MARKET_STATUS_PUBLISHED.equalsIgnoreCase(agent.marketStatus()))
                .collect(java.util.stream.Collectors.toMap(
                    SkillDefinition::id,
                    Function.identity(),
                    (first, ignored) -> first,
                    LinkedHashMap::new
                ));
            this.availableToolsByAgent = new LinkedHashMap<>();
            agentsById.values().forEach(agent ->
                availableToolsByAgent.put(agent.id(), callableTools(agent))
            );

            SkillCatalogService skillCatalog = mock(SkillCatalogService.class);
            when(skillCatalog.resolve(anyString())).thenAnswer(invocation ->
                agentsById.get(invocation.getArgument(0))
            );
            when(skillCatalog.resolveTools(anyString(), any(), any())).thenAnswer(invocation ->
                availableToolsByAgent.getOrDefault(invocation.getArgument(0), List.of())
            );

            Set<String> allToolNames = availableToolsByAgent.values().stream()
                .flatMap(List::stream)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            ToolRegistry toolRegistry = mock(ToolRegistry.class);
            when(toolRegistry.getAllToolNames()).thenReturn(allToolNames);
            when(toolRegistry.hasTool(anyString())).thenAnswer(invocation ->
                allToolNames.contains(invocation.getArgument(0))
            );
            when(toolRegistry.getToolMetadata(anyString())).thenAnswer(invocation ->
                ToolMetadata.builder()
                    .id(invocation.getArgument(0))
                    .riskLevel("low")
                    .build()
            );

            McpToolRegistryBridge bridge = mock(McpToolRegistryBridge.class);
            when(bridge.listRegisteredTools()).thenReturn(allToolNames.stream()
                .map(toolName -> new McpToolRegistryBridge.RegisteredMcpTool(
                    toolName,
                    "maintained-agent-contract",
                    "Maintained Agent contract",
                    toolName,
                    "Environment acceptance tool"
                ))
                .toList());
            AgentToolPolicyResolver toolPolicyResolver =
                new AgentToolPolicyResolver(toolRegistry, skillCatalog, bridge);

            AgentLearningService learningService = mock(AgentLearningService.class);
            when(learningService.resolveRuntimeExperience(any(), any(), any(), any()))
                .thenReturn(AgentLearningService.RuntimeExperienceContext.empty());
            AgentRuntime runtime = mock(AgentRuntime.class);
            when(runtime.run(any())).thenAnswer(invocation -> {
                AgentRunRequest request = invocation.getArgument(0);
                lastRuntimeRequest.set(request);
                return AgentRunResult.builder()
                    .runId(request.getRunId())
                    .status(AgentRunStatus.COMPLETED)
                    .answer("maintained Agent contract completed")
                    .stopReason("completed")
                    .build();
            });
            this.handler = new AgentChatModeHandler(
                runtime,
                mock(AgentOrchestrator.class),
                skillCatalog,
                toolPolicyResolver,
                learningService
            );
        }

        private void verify(String agentId, String query) {
            SkillDefinition agent = agentsById.get(agentId);
            lastRuntimeRequest.set(null);
            InteractionResponse response = handler.handle(
                InteractionRequest.builder()
                    .mode("agent_chat")
                    .skillId(agentId)
                    .query(query)
                    .userId("agent-coverage")
                    .tenantId("agent-coverage")
                    .availableTools(availableToolsByAgent.get(agentId))
                    .build(),
                InteractionContext.builder()
                    .requestId("coverage-" + agentId + "-" + Integer.toUnsignedString(query.hashCode()))
                    .conversationId("coverage-" + agentId)
                    .mode(InteractionMode.AGENT_CHAT)
                    .history(List.of())
                    .build()
            );

            AgentRunRequest runtimeRequest = lastRuntimeRequest.get();
            assertThat(response.getMetadata()).containsEntry("skillId", agentId);
            assertThat(runtimeRequest).isNotNull();
            assertThat(runtimeRequest.getSkillId()).isEqualTo(agentId);
            assertThat(runtimeRequest.getQuery()).isEqualTo(query);
            assertThat(runtimeRequest.getAvailableTools())
                .doesNotHaveDuplicates()
                .containsAll(runtimeRequest.getRequiredToolNames());
            assertThat(runtimeRequest.getBoundDocumentIds())
                .containsExactlyElementsOf(safe(agent.boundDocumentIds()));
            assertThat(runtimeRequest.getBoundDocumentTags())
                .containsExactlyElementsOf(safe(agent.boundDocumentTags()));
            if (agent.workflowConfig() != null && !agent.workflowConfig().isEmpty()) {
                assertThat(runtimeRequest.getAttributes()).containsKey("mcpWorkflow");
            }
        }

        private static List<String> callableTools(SkillDefinition agent) {
            LinkedHashSet<String> names = new LinkedHashSet<>(safe(agent.boundMcpToolNames()));
            safe(agent.toolConfigs()).stream()
                .filter(config -> config != null && !Boolean.FALSE.equals(config.enabled()))
                .map(SkillToolConfig::toolName)
                .filter(name -> name != null && !name.isBlank())
                .forEach(names::add);
            return List.copyOf(names);
        }

        private static <T> List<T> safe(List<T> values) {
            return values == null ? List.of() : values;
        }
    }
}
