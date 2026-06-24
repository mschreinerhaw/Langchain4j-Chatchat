package com.chatchat.agents.orchestration;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPlannerTest {

    @Test
    void normalizesRecoverableInterpretationPlanBeforeValidation() throws Exception {
        AgentPlanner planner = new AgentPlanner(new TestToolRegistry(), new ObjectMapper());
        List<String> requiredTools = List.of(
            "mcp_chatchat_mcp_server_asset_query",
            "mcp_chatchat_mcp_server_linux_command_execute"
        );
        PlannerValidationContext context = new PlannerValidationContext(
            requiredTools,
            true,
            false,
            null,
            null,
            requiredTools,
            "Analyze Docker host load"
        );
        String raw = """
            {
              "version": "1.0",
              "intent": {
                "type": "system_operation",
                "goal": "Analyze Docker host load",
                "riskLevel": "medium"
              },
              "context": {
                "keyFacts": ["Need asset discovery and read-only command template execution"],
                "constraints": ["Do not pass concrete host address"]
              },
              "plan": {
                "steps": [
                  {
                    "id": 1,
                    "actionType": "mcp_tool",
                    "toolName": "mcp_chatchat_mcp_server_asset_query",
                    "input": {"context": "docker"},
                    "dependsOn": [],
                    "outputContract": {"type": "json", "schemaHint": "asset list"}
                  },
                  {
                    "id": 2,
                    "actionType": "mcp_tool",
                    "toolName": "mcp_chatchat_mcp_server_linux_command_execute",
                    "input": {
                      "command_template": "CHECK_SYSTEM_OVERVIEW",
                      "executionContext": {"service": "docker"}
                    },
                    "dependsOn": [1],
                    "outputContract": {"type": "json", "schemaHint": "ssh steps"}
                  },
                  {
                    "id": 3,
                    "actionType": "final_answer",
                    "toolName": "",
                    "input": {"answer": "待工具输出后生成报告"},
                    "dependsOn": [2]
                  }
                ],
                "edgeContracts": [],
                "bindings": [],
                "stability": {
                  "stableNodes": [1, 2, 3],
                  "criticalTools": [
                    "mcp_chatchat_mcp_server_asset_query",
                    "mcp_chatchat_mcp_server_linux_command_execute"
                  ],
                  "lockedEdges": true,
                  "mutableActionTypes": []
                }
              },
              "executionPolicy": {
                "maxSteps": 3,
                "allowParallel": false,
                "allowTool": [
                  "mcp_chatchat_mcp_server_asset_query",
                  "mcp_chatchat_mcp_server_linux_command_execute"
                ],
                "denyTool": [],
                "maxRewriteTimes": 1,
                "fallbackMode": "safe_answer",
                "toolPriority": {
                  "mcp_chatchat_mcp_server_asset_query": 1.0,
                  "mcp_chatchat_mcp_server_linux_command_execute": 2.0
                },
                "accuracyVsSpeed": 1.2
              },
              "review": {
                "selfCheck": {
                  "completenessScore": 0.8,
                  "hallucinationRisk": 0.1,
                  "toolSufficiency": false,
                  "missingSteps": []
                },
                "fallbackPlan": []
              }
            }
            """;

        Method parseDecision = AgentPlanner.class.getDeclaredMethod("parseDecision", String.class, PlannerValidationContext.class);
        parseDecision.setAccessible(true);
        AgentDecision decision = (AgentDecision) parseDecision.invoke(planner, raw, context);

        assertThat(decision).isNotNull();
        assertThat(decision.action()).isEqualTo("tool");
        assertThat(decision.reason()).isEqualTo("Analyze Docker host load");
        assertThat(decision.toolName()).isEqualTo("mcp_chatchat_mcp_server_asset_query");
        Map<?, ?> filters = (Map<?, ?>) decision.arguments().get("filters");
        assertThat(filters.get("service")).isEqualTo("docker");
        assertThat(decision.executionPlan()).containsEntry("interpretationPlanValid", true);
        assertThat(decision.interpretationPlan().executionPolicy().toolPriority())
            .containsEntry("mcp_chatchat_mcp_server_linux_command_execute", 1.0);
        assertThat(decision.interpretationPlan().executionPolicy().accuracyVsSpeed()).isEqualTo(1.0);
        assertThat(decision.interpretationPlan().steps().get(1).input()).containsEntry("template", "CHECK_SYSTEM_OVERVIEW");
    }

    @Test
    void rejectsReasoningStepThatGuessesAssetRoutingContext() throws Exception {
        AgentPlanner planner = new AgentPlanner(new TestToolRegistry(), new ObjectMapper());
        List<String> requiredTools = List.of(
            "mcp_chatchat_mcp_server_asset_query",
            "mcp_chatchat_mcp_server_linux_command_execute"
        );
        PlannerValidationContext context = new PlannerValidationContext(
            requiredTools,
            true,
            false,
            null,
            null,
            requiredTools,
            "Analyze Docker host load"
        );
        String raw = """
            {
              "version": "1.0",
              "intent": {"type": "system_operation", "goal": "Analyze Docker host load", "risk_level": "medium"},
              "context": {
                "key_facts": ["Need asset discovery"],
                "constraints": ["Do not pass concrete host address"]
              },
              "plan": {
                "steps": [
                  {
                    "id": 1,
                    "action_type": "reasoning",
                    "tool_name": "",
                    "input": {
                      "decision": "asset_query was rejected by confirmation, so assume env prod and service docker",
                      "output": {"env": "prod", "service": "docker"}
                    },
                    "depends_on": []
                  },
                  {
                    "id": 2,
                    "action_type": "mcp_tool",
                    "tool_name": "mcp_chatchat_mcp_server_linux_command_execute",
                    "input": {"template": "CHECK_SYSTEM_OVERVIEW", "executionContext": {"env": "prod", "service": "docker"}},
                    "depends_on": [1]
                  },
                  {
                    "id": 3,
                    "action_type": "final_answer",
                    "tool_name": "",
                    "input": {"answer": "after tool"},
                    "depends_on": [2]
                  }
                ],
                "edge_contracts": [],
                "bindings": []
              },
              "execution_policy": {
                "max_steps": 3,
                "allow_tool": [
                  "mcp_chatchat_mcp_server_asset_query",
                  "mcp_chatchat_mcp_server_linux_command_execute"
                ],
                "fallback_mode": "safe_answer"
              },
              "review": {
                "self_check": {"tool_sufficiency": false, "missing_steps": []},
                "fallback_plan": []
              }
            }
            """;

        Method parseDecision = AgentPlanner.class.getDeclaredMethod("parseDecision", String.class, PlannerValidationContext.class);
        parseDecision.setAccessible(true);
        AgentDecision decision = (AgentDecision) parseDecision.invoke(planner, raw, context);

        assertThat(decision.action()).isEqualTo("final");
        assertThat(decision.reason()).isEqualTo("invalid_interpretation_plan");
        assertThat((List<?>) decision.executionPlan().get("interpretationPlanRuntimeIssues"))
            .anySatisfy(issue -> assertThat(String.valueOf(issue)).contains("Do not use a reasoning step to replace asset_query"));
    }

    @Test
    void normalizesCompoundContextStringAsExactAssetName() throws Exception {
        AgentPlanner planner = new AgentPlanner(new TestToolRegistry(), new ObjectMapper());
        List<String> requiredTools = List.of("mcp_chatchat_mcp_server_asset_query");
        PlannerValidationContext context = new PlannerValidationContext(
            requiredTools,
            true,
            false,
            null,
            null,
            requiredTools,
            "Find docker_service"
        );
        String raw = """
            {
              "version": "1.0",
              "intent": {"type": "system_operation", "goal": "Find docker_service", "risk_level": "low"},
              "context": {"key_facts": [], "constraints": []},
              "plan": {
                "steps": [
                  {
                    "id": 1,
                    "action_type": "mcp_tool",
                    "tool_name": "mcp_chatchat_mcp_server_asset_query",
                    "input": {"context": "docker_service", "limit": 10},
                    "depends_on": []
                  },
                  {
                    "id": 2,
                    "action_type": "final_answer",
                    "tool_name": "",
                    "input": {"answer": "done"},
                    "depends_on": [1]
                  }
                ],
                "edge_contracts": [],
                "bindings": []
              },
              "execution_policy": {
                "max_steps": 2,
                "allow_tool": ["mcp_chatchat_mcp_server_asset_query"],
                "fallback_mode": "safe_answer"
              },
              "review": {"self_check": {"tool_sufficiency": false, "missing_steps": []}, "fallback_plan": []}
            }
            """;

        Method parseDecision = AgentPlanner.class.getDeclaredMethod("parseDecision", String.class, PlannerValidationContext.class);
        parseDecision.setAccessible(true);
        AgentDecision decision = (AgentDecision) parseDecision.invoke(planner, raw, context);

        Map<?, ?> filters = (Map<?, ?>) decision.arguments().get("filters");
        assertThat(filters.get("assetName")).isEqualTo("docker_service");
        assertThat(filters.containsKey("service")).isFalse();
    }

    @Test
    void normalizesTemplateQueryContextStringAsExactAssetName() throws Exception {
        AgentPlanner planner = new AgentPlanner(new TestToolRegistry(), new ObjectMapper());
        List<String> requiredTools = List.of("mcp_chatchat_mcp_server_template_query");
        PlannerValidationContext context = new PlannerValidationContext(
            requiredTools,
            true,
            false,
            null,
            null,
            requiredTools,
            "Find templates for docker_service"
        );
        String raw = """
            {
              "version": "1.0",
              "intent": {"type": "system_operation", "goal": "Find templates for docker_service", "risk_level": "low"},
              "context": {"key_facts": [], "constraints": []},
              "plan": {
                "steps": [
                  {
                    "id": 1,
                    "action_type": "mcp_tool",
                    "tool_name": "mcp_chatchat_mcp_server_template_query",
                    "input": {"assetType": "ssh_host", "context": "docker_service", "limit": 10},
                    "depends_on": []
                  },
                  {
                    "id": 2,
                    "action_type": "final_answer",
                    "tool_name": "",
                    "input": {"answer": "done"},
                    "depends_on": [1]
                  }
                ],
                "edge_contracts": [],
                "bindings": []
              },
              "execution_policy": {
                "max_steps": 2,
                "allow_tool": ["mcp_chatchat_mcp_server_template_query"],
                "fallback_mode": "safe_answer"
              },
              "review": {"self_check": {"tool_sufficiency": false, "missing_steps": []}, "fallback_plan": []}
            }
            """;

        Method parseDecision = AgentPlanner.class.getDeclaredMethod("parseDecision", String.class, PlannerValidationContext.class);
        parseDecision.setAccessible(true);
        AgentDecision decision = (AgentDecision) parseDecision.invoke(planner, raw, context);

        Map<?, ?> filters = (Map<?, ?>) decision.arguments().get("filters");
        assertThat(filters.get("assetName")).isEqualTo("docker_service");
        assertThat(filters.containsKey("service")).isFalse();
    }

    private static class TestToolRegistry implements ToolRegistry {
        private final Set<String> tools = Set.of(
            "mcp_chatchat_mcp_server_asset_query",
            "mcp_chatchat_mcp_server_template_query",
            "mcp_chatchat_mcp_server_linux_command_execute"
        );

        @Override
        public void registerTool(String toolName, Tool tool) {
        }

        @Override
        public void registerTool(String toolName, ToolMetadata metadata, EnhancedTool tool) {
        }

        @Override
        public Tool getTool(String toolName) {
            return null;
        }

        @Override
        public EnhancedTool getEnhancedTool(String toolName) {
            return null;
        }

        @Override
        public ToolMetadata getToolMetadata(String toolName) {
            if (!hasTool(toolName)) {
                return null;
            }
            return ToolMetadata.builder()
                .id(toolName)
                .riskLevel("low")
                .parameters(parameters(toolName))
                .build();
        }

        @Override
        public String executeTool(ToolExecutionRequest request) {
            return null;
        }

        @Override
        public ToolOutput executeEnhancedTool(String toolName, ToolInput toolInput) {
            return null;
        }

        @Override
        public List<Tool> getAllTools() {
            return List.of();
        }

        @Override
        public List<EnhancedTool> getAllEnhancedTools() {
            return List.of();
        }

        @Override
        public boolean hasTool(String toolName) {
            return tools.contains(toolName);
        }

        @Override
        public Set<String> getAllToolNames() {
            return tools;
        }

        @Override
        public void unregisterTool(String toolName) {
        }

        private List<ToolParameter> parameters(String toolName) {
            if ("mcp_chatchat_mcp_server_asset_query".equals(toolName)
                || "mcp_chatchat_mcp_server_template_query".equals(toolName)) {
                return List.of();
            }
            return List.of(ToolParameter.builder().name("template").type("string").required(true).build());
        }
    }
}
