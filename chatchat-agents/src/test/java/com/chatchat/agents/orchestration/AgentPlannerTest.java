package com.chatchat.agents.orchestration;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
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
    void describesPublisherApplicabilityWithoutTurningItIntoToolSelectionPolicy() throws Exception {
        AgentPlanner planner = new AgentPlanner(new TestToolRegistry(true), new ObjectMapper());
        Method method = AgentPlanner.class.getDeclaredMethod("describeTools", List.class, Map.class);
        method.setAccessible(true);

        String description = (String) method.invoke(
            planner,
            List.of("mcp_chatchat_mcp_server_sql_query_execute"),
            Map.of()
        );

        assertThat(description)
            .contains("Governed SQL query execution")
            .contains("Use when: A registered template and logical target are available")
            .contains("Not for: Ad-hoc SQL; Selecting or replacing Agent-bound tools")
            .contains("descriptive metadata only; never authorizes adding, selecting, or replacing tools");
    }

    @Test
    void mcpControlPlanePromptDoesNotTeachInventedDockerServiceLabels() throws Exception {
        AgentPlanner planner = new AgentPlanner(new TestToolRegistry(), new ObjectMapper());
        Method method = AgentPlanner.class.getDeclaredMethod(
            "appendMcpControlPlaneToolContracts",
            StringBuilder.class,
            List.class
        );
        method.setAccessible(true);
        StringBuilder prompt = new StringBuilder();

        method.invoke(planner, prompt, List.of(
            "mcp_chatchat_mcp_server_asset_query",
            "mcp_chatchat_mcp_server_template_query",
            "mcp_chatchat_mcp_server_linux_command_execute"
        ));

        assertThat(prompt.toString())
            .doesNotContain("service:docker", "docker_service")
            .contains("Do not invent service labels such as service:<topic>")
            .contains("{\"filters\":{},\"limit\":10}")
            .contains("<existing-service-label>")
            .contains("<asset-name-from-typed-asset-discovery>")
            .contains("Never concatenate an assetName with descriptive text")
            .contains("Model intent recognition is required before asset discovery")
            .contains("filters.queryTerms")
            .contains("filters.intentCandidates")
            .contains("score >= 0.75")
            .contains("if none reaches 0.75, use the top two candidates")
            .contains("semantic retrieval filters")
            .contains("分析MySQL服务器管理进程信息")
            .contains("templates[] is ranked by relevanceScore")
            .contains("filters.intentAliases")
            .contains("filters.keywords")
            .contains("filters.bilingualIntent")
            .contains("filters.intentZh")
            .contains("filters.intentEn")
            .contains("Do not rely on Chinese-only or English-only intent")
            .contains("not as semantic ranking")
            .contains("Follow the dependency order configured by the user/runtime")
            .doesNotContain("Required execution flow for live host analysis");
    }

    @Test
    void plannerPromptIncludesAgentRuntimeOsWorkflowGraph() throws Exception {
        AgentPlanner planner = new AgentPlanner(new TestToolRegistry(), new ObjectMapper());
        Method method = AgentPlanner.class.getDeclaredMethod(
            "buildPlannerPrompt",
            String.class,
            String.class,
            List.class,
            List.class,
            List.class,
            List.class,
            List.class,
            boolean.class,
            boolean.class,
            String.class,
            String.class,
            Map.class
        );
        method.setAccessible(true);
        List<Map<String, Object>> workflow = List.of(
            Map.of("step", "asset_discovery", "tool", "mcp_chatchat_mcp_server_asset_query", "required", true),
            Map.of(
                "step", "template_retrieval",
                "tool", "mcp_chatchat_mcp_server_template_query",
                "required", true,
                "dependsOn", List.of("asset_discovery"),
                "confirmation", "required_for_write"
            )
        );

        String prompt = (String) method.invoke(
            planner,
            "分析本地MySQL测试服务InnoDB状态",
            "",
            List.of("mcp_chatchat_mcp_server_asset_query", "mcp_chatchat_mcp_server_template_query"),
            List.of(),
            List.of(),
            List.of(),
            List.of("mcp_chatchat_mcp_server_asset_query", "mcp_chatchat_mcp_server_template_query"),
            true,
            false,
            null,
            null,
            Map.of("mcpWorkflow", workflow)
        );

        assertThat(prompt)
            .contains("MCP tool orchestration contract from current Agent Runtime OS")
            .contains("mandatory reasoning and execution graph")
            .contains("tool=mcp_chatchat_mcp_server_asset_query")
            .contains("tool=mcp_chatchat_mcp_server_template_query")
            .contains("step asset_discovery")
            .contains("dependsOn=[asset_discovery]")
            .contains("confirmation=required_for_write")
            .contains("NEVER create a binding from templates[i].parameterSchema")
            .contains("keep input.parameters={}")
            .contains("DAG controller emits template_parameter_protocol_v1")
            .contains("MUST use exactly $.assets[0].asset.name")
            .contains("never use $.assets[0].assetName");
    }

    @Test
    void plannerPromptTreatsConfiguredAgentEnvironmentAsAuthoritative() throws Exception {
        AgentPlanner planner = new AgentPlanner(new TestToolRegistry(), new ObjectMapper());
        Method method = AgentPlanner.class.getDeclaredMethod(
            "buildPlannerPrompt",
            String.class,
            String.class,
            List.class,
            List.class,
            List.class,
            List.class,
            List.class,
            boolean.class,
            boolean.class,
            String.class,
            String.class,
            Map.class
        );
        method.setAccessible(true);

        String prompt = (String) method.invoke(
            planner,
            "分析测试数据库",
            "",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            false,
            false,
            null,
            null,
            Map.of("agentRuntimeEnvironment", "DEV")
        );

        assertThat(prompt)
            .contains("Agent runtime environment contract")
            .contains("configured runtime environment is DEV")
            .contains("authoritative for MCP discovery filters and executionContext.env")
            .contains("keep DEV and do not guess or override it");
    }

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
    void rejectsInterpretationPlanThatSkipsConfiguredAssetDiscoveryOrder() throws Exception {
        AgentPlanner planner = new AgentPlanner(new TestToolRegistry(), new ObjectMapper());
        List<String> requiredTools = List.of(
            "mcp_chatchat_mcp_server_asset_query",
            "mcp_chatchat_mcp_server_template_query"
        );
        PlannerValidationContext context = new PlannerValidationContext(
            requiredTools,
            true,
            false,
            null,
            null,
            requiredTools,
            "分析本地MySQL测试服务InnoDB状态"
        );
        String raw = """
            {
              "version": "1.0",
              "intent": {"type": "sql_query", "goal": "分析本地MySQL测试服务InnoDB状态", "risk_level": "medium"},
              "context": {"key_facts": [], "constraints": []},
              "plan": {
                "steps": [
                  {
                    "id": 1,
                    "action_type": "mcp_tool",
                    "tool_name": "mcp_chatchat_mcp_server_template_query",
                    "input": {
                      "candidates": [{"targetKind": "database", "confidence": 0.95}],
                      "finalDecision": "database",
                      "filters": {"intent": "分析本地MySQL测试服务InnoDB状态"},
                      "limit": 10
                    },
                    "depends_on": []
                  },
                  {
                    "id": 2,
                    "action_type": "mcp_tool",
                    "tool_name": "mcp_chatchat_mcp_server_asset_query",
                    "input": {
                      "candidates": [{"targetKind": "database", "confidence": 0.95}],
                      "finalDecision": "database",
                      "filters": {},
                      "limit": 10
                    },
                    "depends_on": []
                  },
                  {
                    "id": 3,
                    "action_type": "final_answer",
                    "tool_name": "",
                    "input": {"answer": "待工具返回后生成报告"},
                    "depends_on": [1, 2]
                  }
                ],
                "edge_contracts": [],
                "bindings": []
              },
              "execution_policy": {
                "max_steps": 3,
                "allow_tool": [
                  "mcp_chatchat_mcp_server_asset_query",
                  "mcp_chatchat_mcp_server_template_query"
                ],
                "fallback_mode": "safe_answer"
              },
              "review": {"self_check": {"tool_sufficiency": false, "missing_steps": []}, "fallback_plan": []}
            }
            """;

        Method parseDecision = AgentPlanner.class.getDeclaredMethod("parseDecision", String.class, PlannerValidationContext.class);
        parseDecision.setAccessible(true);
        AgentDecision decision = (AgentDecision) parseDecision.invoke(planner, raw, context);

        assertThat(decision.action()).isEqualTo("final");
        assertThat(decision.reason()).isEqualTo("invalid_interpretation_plan");
        assertThat((List<?>) decision.executionPlan().get("interpretationPlanRuntimeIssues"))
            .anySatisfy(issue -> assertThat(String.valueOf(issue)).contains("Mandatory tools must appear in configured order"));
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
                      "decision": "asset_discovery was rejected by confirmation, so assume env prod and service docker",
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
            .anySatisfy(issue -> assertThat(String.valueOf(issue)).contains("Do not use a reasoning step to replace typed asset discovery"));
    }

    @Test
    void rejectsTemplateToSqlPlanThatAssumesAssetRoutingContext() throws Exception {
        AgentPlanner planner = new AgentPlanner(new TestToolRegistry(), new ObjectMapper());
        List<String> requiredTools = List.of(
            "mcp_chatchat_mcp_server_asset_query",
            "mcp_chatchat_mcp_server_template_query",
            "mcp_chatchat_mcp_server_sql_query_execute"
        );
        PlannerValidationContext context = new PlannerValidationContext(
            requiredTools,
            true,
            false,
            null,
            null,
            requiredTools,
            "Analyze local MySQL test InnoDB status"
        );
        String raw = """
            {
              "version": "1.0",
              "intent": {"type": "data_query", "goal": "Analyze local MySQL test InnoDB status", "risk_level": "low"},
              "context": {
                "key_facts": ["Target asset name and env are known by assumption."],
                "assumptions": ["Asset local MySQL test service is already registered as env test datasource."],
                "constraints": ["Read only SQL."]
              },
              "plan": {
                "steps": [
                  {
                    "id": 1,
                    "action_type": "mcp_tool",
                    "tool_name": "mcp_chatchat_mcp_server_template_query",
                    "input": {"filters": {"assetName": "local MySQL test service", "env": "test"}, "limit": 10},
                    "depends_on": []
                  },
                  {
                    "id": 2,
                    "action_type": "mcp_tool",
                    "tool_name": "mcp_chatchat_mcp_server_sql_query_execute",
                    "input": {"templateId": "PLACEHOLDER", "parameters": {}},
                    "depends_on": [1]
                  },
                  {
                    "id": 3,
                    "action_type": "final_answer",
                    "tool_name": "",
                    "input": {"answer": "after tools"},
                    "depends_on": [2]
                  }
                ],
                "edge_contracts": [],
                "bindings": []
              },
              "execution_policy": {
                "max_steps": 3,
                "allow_tool": [
                  "mcp_chatchat_mcp_server_template_query",
                  "mcp_chatchat_mcp_server_sql_query_execute"
                ],
                "fallback_mode": "safe_answer"
              },
              "review": {"self_check": {"tool_sufficiency": false, "missing_steps": []}, "fallback_plan": []}
            }
            """;

        Method parseDecision = AgentPlanner.class.getDeclaredMethod("parseDecision", String.class, PlannerValidationContext.class);
        parseDecision.setAccessible(true);
        AgentDecision decision = (AgentDecision) parseDecision.invoke(planner, raw, context);

        assertThat(decision.action()).isEqualTo("final");
        assertThat(decision.reason()).isEqualTo("invalid_interpretation_plan");
        assertThat((List<?>) decision.executionPlan().get("interpretationPlanRuntimeIssues"))
            .anySatisfy(issue -> assertThat(String.valueOf(issue)).contains("Mandatory tool is missing"))
            .anySatisfy(issue -> assertThat(String.valueOf(issue)).contains("plan context must not assume assetName/env/datasource"));
    }

    @Test
    void acceptsDomainPrefixedAssetQueryAsTypedAssetDiscovery() throws Exception {
        AgentPlanner planner = new AgentPlanner(new TestToolRegistry(), new ObjectMapper());
        List<String> requiredTools = List.of(
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            "mcp_chatchat_mcp_server_sql_datasource_template_query",
            "mcp_chatchat_mcp_server_sql_query_execute"
        );
        PlannerValidationContext context = new PlannerValidationContext(
            requiredTools,
            true,
            false,
            null,
            null,
            requiredTools,
            "分析248测试数据库中表t_ad_dict_entr_supn"
        );
        String raw = """
            {
              "version": "1.0",
              "intent": {"type": "sql_query", "goal": "分析248测试数据库中表t_ad_dict_entr_supn", "risk_level": "low"},
              "context": {
                "key_facts": ["用户指定环境为248测试数据库"],
                "assumptions": ["248测试数据库已注册为SQL数据源资产"],
                "constraints": ["必须依次执行asset_query → template_query → query_execute三个工具"]
              },
              "plan": {
                "steps": [
                  {
                    "id": 1,
                    "action_type": "mcp_tool",
                    "tool_name": "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    "input": {"candidates": [{"targetKind": "database", "confidence": 0.9}], "finalDecision": "database", "filters": {}, "limit": 10},
                    "depends_on": []
                  },
                  {
                    "id": 2,
                    "action_type": "mcp_tool",
                    "tool_name": "mcp_chatchat_mcp_server_sql_datasource_template_query",
                    "input": {"candidates": [{"targetKind": "database", "confidence": 0.9}], "finalDecision": "database", "filters": {"intent": "分析表 t_ad_dict_entr_supn"}, "limit": 10},
                    "depends_on": [1]
                  },
                  {
                    "id": 3,
                    "action_type": "mcp_tool",
                    "tool_name": "mcp_chatchat_mcp_server_sql_query_execute",
                    "input": {"parameters": {"tableName": "t_ad_dict_entr_supn"}},
                    "depends_on": [2]
                  },
                  {
                    "id": 4,
                    "action_type": "final_answer",
                    "tool_name": "",
                    "input": {"answer": "after tools"},
                    "depends_on": [3]
                  }
                ],
                "edge_contracts": [
                  {"from": 1, "to": 2, "field": "assets[0].asset.name", "type": "string", "required": false},
                  {"from": 2, "to": 3, "field": "templates[0].templateId", "type": "string", "required": true}
                ],
                "bindings": [
                  {"from": 1, "output_path": "$.assets[0].asset.name", "to": 2, "input_field": "filters.assetName", "type": "jsonpath", "required": false},
                  {"from": 2, "output_path": "$.templates[0].templateId", "to": 3, "input_field": "templateId", "type": "jsonpath", "required": true}
                ]
              },
              "execution_policy": {
                "max_steps": 6,
                "allow_tool": [
                  "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                  "mcp_chatchat_mcp_server_sql_datasource_template_query",
                  "mcp_chatchat_mcp_server_sql_query_execute"
                ],
                "fallback_mode": "safe_answer"
              },
              "review": {"self_check": {"tool_sufficiency": false, "missing_steps": []}, "fallback_plan": []}
            }
            """;

        Method parseDecision = AgentPlanner.class.getDeclaredMethod("parseDecision", String.class, PlannerValidationContext.class);
        parseDecision.setAccessible(true);
        AgentDecision decision = (AgentDecision) parseDecision.invoke(planner, raw, context);

        assertThat((List<?>) decision.executionPlan().get("interpretationPlanRuntimeIssues"))
            .noneSatisfy(issue -> assertThat(String.valueOf(issue)).contains("plan context must not assume assetName/env/datasource"));
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

    @Test
    @SuppressWarnings("unchecked")
    void experiencePriorRewardsResilienceWithoutChangingWorkflowTools() throws Exception {
        AgentPlanner planner = new AgentPlanner(new TestToolRegistry(), new ObjectMapper());
        String tool = "mcp_chatchat_mcp_server_asset_query";
        List<InterpretationPlan.Step> steps = List.of(
            new InterpretationPlan.Step(1, "mcp_tool", tool, Map.of(), List.of(), null, null),
            new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
        );
        InterpretationPlan resilientPlan = new InterpretationPlan(
            "1.0", null, null, new InterpretationPlan.Plan(steps),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of(tool), List.of(), null, 1, "partial_result"),
            null
        );
        InterpretationPlan barePlan = new InterpretationPlan(
            "1.0", null, null, new InterpretationPlan.Plan(steps),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of(tool), List.of(), null, 0, null),
            null
        );
        Map<String, Object> prior = Map.of(
            "matchedExperienceIds", List.of("exp-1"),
            "preferredToolChains", List.of("asset_query"),
            "failedCount", 2,
            "confidence", 0.7,
            "workflowMutationAllowed", false
        );
        PlannerValidationContext context = new PlannerValidationContext(
            List.of(tool), true, false, null, null, List.of(tool), "discover assets", prior
        );
        Method scoreMethod = AgentPlanner.class.getDeclaredMethod(
            "deterministicPlanScoreDetails", AgentDecision.class, PlannerValidationContext.class);
        scoreMethod.setAccessible(true);
        AgentDecision resilientDecision = new AgentDecision("plan", null, Map.of(), null, null, Map.of(), true, resilientPlan);
        AgentDecision bareDecision = new AgentDecision("plan", null, Map.of(), null, null, Map.of(), true, barePlan);
        Map<String, Object> resilientScore = (Map<String, Object>) scoreMethod.invoke(planner, resilientDecision, context);
        Map<String, Object> bareScore = (Map<String, Object>) scoreMethod.invoke(planner, bareDecision, context);

        assertThat((Integer) resilientScore.get("experienceFit")).isGreaterThan((Integer) bareScore.get("experienceFit"));
        assertThat((Integer) resilientScore.get("total")).isGreaterThan((Integer) bareScore.get("total"));
        Map<String, Object> experience = (Map<String, Object>) resilientScore.get("experience");
        assertThat(experience.get("matchedExperienceIds")).isEqualTo(List.of("exp-1"));
        assertThat(experience.get("workflowMutationAllowed")).isEqualTo(false);
        assertThat(experience.get("candidateToolChain")).isEqualTo("asset_query");
        Method optimizeMethod = AgentPlanner.class.getDeclaredMethod(
            "shouldOptimizeFromExperience", PlanCandidate.class, PlannerValidationContext.class);
        optimizeMethod.setAccessible(true);
        PlanCandidate bareCandidate = new PlanCandidate(
            1, "A", "{}", bareDecision, "NONE", "fingerprint",
            (Integer) bareScore.get("total"), bareScore);
        assertThat((Boolean) optimizeMethod.invoke(planner, bareCandidate, context)).isTrue();
        Method workflowMethod = AgentPlanner.class.getDeclaredMethod(
            "sameExperienceWorkflowContract", AgentDecision.class, AgentDecision.class);
        workflowMethod.setAccessible(true);
        assertThat((Boolean) workflowMethod.invoke(planner, bareDecision, resilientDecision)).isTrue();
        InterpretationPlan mutatedPlan = new InterpretationPlan(
            "1.0", null, null,
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "final_answer", "", Map.of("answer", "done"), List.of(), null, null)
            )),
            barePlan.executionPolicy(), null
        );
        AgentDecision mutatedDecision = new AgentDecision(
            "plan", null, Map.of(), null, null, Map.of(), true, mutatedPlan);
        assertThat((Boolean) workflowMethod.invoke(planner, bareDecision, mutatedDecision)).isFalse();
    }

    private static class TestToolRegistry implements ToolRegistry {
        private final boolean includeApplicability;
        private final Set<String> tools = Set.of(
            "mcp_chatchat_mcp_server_asset_query",
            "mcp_chatchat_mcp_server_template_query",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            "mcp_chatchat_mcp_server_sql_datasource_template_query",
            "mcp_chatchat_mcp_server_sql_query_execute",
            "mcp_chatchat_mcp_server_linux_command_execute"
        );

        private TestToolRegistry() {
            this(false);
        }

        private TestToolRegistry(boolean includeApplicability) {
            this.includeApplicability = includeApplicability;
        }

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
            ToolMetadata.ToolMetadataBuilder builder = ToolMetadata.builder()
                .id(toolName)
                .riskLevel("low")
                .parameters(parameters(toolName));
            if (includeApplicability && "mcp_chatchat_mcp_server_sql_query_execute".equals(toolName)) {
                builder.description("SQL execution gateway").metadata(Map.of(
                    "mcpToolMeta", Map.of(
                        "applicability", Map.of(
                            "scopeLabel", "Governed SQL query execution",
                            "useWhen", List.of("A registered template and logical target are available"),
                            "notFor", List.of("Ad-hoc SQL", "Selecting or replacing Agent-bound tools")
                        )
                    )
                ));
            }
            return builder.build();
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
                || "mcp_chatchat_mcp_server_template_query".equals(toolName)
                || "mcp_chatchat_mcp_server_sql_datasource_asset_query".equals(toolName)
                || "mcp_chatchat_mcp_server_sql_datasource_template_query".equals(toolName)) {
                return List.of();
            }
            return List.of(ToolParameter.builder().name("template").type("string").required(true).build());
        }
    }
}
