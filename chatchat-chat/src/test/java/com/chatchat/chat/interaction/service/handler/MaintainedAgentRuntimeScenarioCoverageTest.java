package com.chatchat.chat.interaction.service.handler;

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
import com.chatchat.chat.skills.AgentScenarioCatalog;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.chat.task.AgentLearningService;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaintainedAgentRuntimeScenarioCoverageTest {

    @Test
    void coversEveryMaintainedAgentThroughTheAgentRuntimeContract() {
        List<SkillDefinition> maintainedAgents = maintainedAgents();
        Map<String, SkillDefinition> agentsById = maintainedAgents.stream()
            .collect(java.util.stream.Collectors.toMap(
                SkillDefinition::id,
                agent -> agent,
                (first, ignored) -> first,
                LinkedHashMap::new
            ));
        SkillCatalogService skillCatalog = mock(SkillCatalogService.class);
        when(skillCatalog.list()).thenReturn(maintainedAgents);
        AgentScenarioCatalog.CoverageSuite suite =
            new AgentScenarioCatalog().compile(skillCatalog.list());
        Map<String, String> expectedAgentByQuery = new LinkedHashMap<>();
        suite.agents().forEach(agent -> agent.scenarios().forEach(scenario ->
            expectedAgentByQuery.put(scenario.query(), agent.agentId())));
        when(skillCatalog.resolve(nullable(String.class))).thenAnswer(invocation -> {
            String requestedId = invocation.getArgument(0);
            if (requestedId == null || requestedId.isBlank()) {
                return maintainedAgents.stream().filter(agent -> Boolean.TRUE.equals(agent.defaultAgent()))
                    .findFirst().orElseThrow();
            }
            return agentsById.get(requestedId);
        });

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        List<String> registeredToolNames = List.of(
            "document_search",
            "mcp_contract_alpha",
            "mcp_contract_beta"
        );
        when(toolRegistry.getAllToolNames()).thenReturn(Set.copyOf(registeredToolNames));
        when(toolRegistry.hasTool(anyString())).thenAnswer(invocation ->
            registeredToolNames.contains(invocation.getArgument(0)));
        when(toolRegistry.getToolMetadata(anyString())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());

        McpToolRegistryBridge bridge = mock(McpToolRegistryBridge.class);
        when(bridge.listRegisteredTools()).thenReturn(List.of(
            registeredTool("mcp_contract_alpha", "alpha"),
            registeredTool("mcp_contract_beta", "beta")
        ));
        AgentToolPolicyResolver toolPolicyResolver =
            new AgentToolPolicyResolver(toolRegistry, skillCatalog, bridge);
        AgentLearningService learningService = mock(AgentLearningService.class);
        when(learningService.resolveRuntimeExperience(any(), any(), any(), any()))
            .thenReturn(AgentLearningService.RuntimeExperienceContext.empty());

        List<AgentRunRequest> runtimeRequests = new ArrayList<>();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.run(any())).thenAnswer(invocation -> {
            AgentRunRequest request = invocation.getArgument(0);
            runtimeRequests.add(request);
            return AgentRunResult.builder()
                .runId(request.getRunId())
                .status(AgentRunStatus.COMPLETED)
                .answer("contract scenario completed")
                .stopReason("completed")
                .metadata(Map.of("runtimeContract", "agent_loop"))
                .build();
        });
        AgentChatModeHandler handler = new AgentChatModeHandler(
            runtime,
            mock(AgentOrchestrator.class),
            skillCatalog,
            toolPolicyResolver,
            learningService
        );

        suite.agents().forEach(agentCoverage -> agentCoverage.scenarios().forEach(scenario -> {
            InteractionRequest request = InteractionRequest.builder()
                .mode("agent_chat")
                .skillId(agentCoverage.defaultAgent() ? null : agentCoverage.agentId())
                .query(scenario.query())
                .userId("scenario-user")
                .tenantId("scenario-tenant")
                .availableTools(registeredToolNames)
                .build();
            InteractionResponse response = handler.handle(request, InteractionContext.builder()
                .requestId("request-" + runtimeRequests.size())
                .conversationId("conversation-" + runtimeRequests.size())
                .mode(InteractionMode.AGENT_CHAT)
                .history(List.of())
                .build());
            assertThat(response.getMetadata()).containsEntry("skillId", agentCoverage.agentId());
        }));

        assertThat(suite.issues()).isEmpty();
        assertThat(suite.agents()).hasSameSizeAs(maintainedAgents);
        assertThat(runtimeRequests).hasSize(suite.scenarioCount()).allSatisfy(request -> {
            String expectedAgentId = expectedAgentByQuery.get(request.getQuery());
            SkillDefinition maintainedAgent = agentsById.get(expectedAgentId);
            assertThat(request.getSkillId()).isEqualTo(expectedAgentId);
            assertThat(request.getAvailableTools()).doesNotHaveDuplicates();
            assertThat(request.getAvailableTools()).allMatch(registeredToolNames::contains);
            assertThat(request.getRequiredToolNames()).allMatch(request.getAvailableTools()::contains);
            assertThat(request.getBoundDocumentIds()).containsExactlyElementsOf(maintainedAgent.boundDocumentIds());
            assertThat(request.getBoundDocumentTags()).containsExactlyElementsOf(maintainedAgent.boundDocumentTags());
            if (!maintainedAgent.workflowConfig().isEmpty()) {
                assertThat(request.getAttributes()).containsKey("mcpWorkflow");
            }
        });
    }

    private List<SkillDefinition> maintainedAgents() {
        return List.of(
            agent(
                "agent-default",
                List.of("验证默认 Agent 的 Runtime 路由"),
                List.of(),
                "默认场景",
                List.of("mcp_contract_alpha"),
                List.of(),
                List.of(),
                Map.of(),
                true
            ),
            agent(
                "agent-documents",
                List.of(),
                List.of("验证文档证据边界"),
                "文档场景",
                List.of(),
                List.of("document-contract"),
                List.of("governed"),
                Map.of(),
                false
            ),
            agent(
                "agent-workflow",
                List.of(),
                List.of(),
                "验证必需工具工作流",
                List.of("mcp_contract_beta"),
                List.of(),
                List.of(),
                Map.of("mcpWorkflow", Map.of("steps", List.of(Map.of(
                    "tool", "mcp_contract_beta",
                    "required", true
                )))),
                false
            )
        );
    }

    private SkillDefinition agent(
        String id,
        List<String> quickQuestions,
        List<String> usageScenarios,
        String description,
        List<String> boundTools,
        List<String> documentIds,
        List<String> documentTags,
        Map<String, Object> workflow,
        boolean defaultAgent
    ) {
        return new SkillDefinition(
            id,
            id,
            description,
            usageScenarios,
            List.of("contract"),
            "agent_chat",
            null,
            "Follow the Agent Runtime and Agent Loop contracts.",
            null,
            List.of(),
            List.of(),
            boundTools,
            documentIds,
            documentTags,
            List.of(),
            null,
            workflow,
            null,
            null,
            quickQuestions,
            SkillCatalogService.MARKET_STATUS_PUBLISHED,
            defaultAgent
        );
    }

    private McpToolRegistryBridge.RegisteredMcpTool registeredTool(String localName, String remoteName) {
        return new McpToolRegistryBridge.RegisteredMcpTool(
            localName,
            "contract-service",
            "Contract MCP",
            remoteName,
            "Runtime contract test tool"
        );
    }
}
