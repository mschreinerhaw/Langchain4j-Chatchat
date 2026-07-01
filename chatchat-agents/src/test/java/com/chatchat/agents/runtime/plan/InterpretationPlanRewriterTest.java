package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InterpretationPlanRewriterTest {

    @Test
    void rewritesFailedToolPlanIntoValidatedAlternativePlan() {
        CapturingChatModel chatModel = new CapturingChatModel("""
            {
              "version": "1.0",
              "intent": {"type": "web_search", "goal": "Recover with public evidence", "risk_level": "low"},
              "context": {"key_facts": ["document_search failed"], "missing_info": [], "assumptions": [], "constraints": ["Use available tools only"]},
              "plan": {
                "steps": [
                  {"id": 1, "action_type": "mcp_tool", "tool_name": "web_search", "input": {"query": "runtime evidence"}, "depends_on": []},
                  {"id": 2, "action_type": "final_answer", "tool_name": "", "input": {"answer": "Use web evidence fallback."}, "depends_on": [1]}
                ]
              },
              "execution_policy": {"max_steps": 2, "allow_parallel": false, "allow_tool": ["web_search"], "deny_tool": ["document_search"], "timeout_ms": 30000},
              "review": {"self_check": {"completeness_score": 0.7, "hallucination_risk": 0.2, "tool_sufficiency": false, "missing_steps": []}, "fallback_plan": []}
            }
            """);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("web_search")).thenReturn(true);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        InterpretationPlan originalPlan = originalPlan();
        InterpretationPlan.Step failedStep = originalPlan.steps().get(0);
        InterpretationPlanRewriter rewriter = new InterpretationPlanRewriter(
            chatModel,
            new ObjectMapper(),
            new InterpretationPlanValidator()
        );

        InterpretationPlanRewriter.RewriteResult result = rewriter.rewrite(new InterpretationPlanRewriter.RewriteRequest(
            originalPlan,
            failedStep,
            "Tool execution timed out",
            List.of("document_search failed with timeout"),
            List.of("document_search", "web_search"),
            toolRegistry
        ));

        assertThat(result.valid()).isTrue();
        assertThat(result.executable()).isTrue();
        assertThat(result.rewrittenPlan().steps()).extracting(InterpretationPlan.Step::toolName)
            .contains("web_search");
        assertThat(result.rewrittenPlan().executionPolicy().denyTool()).contains("document_search");
        assertThat(chatModel.lastPrompt())
            .contains("MCP plan rewriter")
            .contains("Tool execution timed out")
            .contains("Original plan");
    }

    @Test
    void repairsContinuationPlanThatDependsOnCompletedOriginalSteps() {
        CapturingChatModel chatModel = new CapturingChatModel("""
            {
              "version": "1.0",
              "intent": {"type": "sql_metadata", "goal": "Analyze table metrics", "risk_level": "low"},
              "context": {"key_facts": ["metadata was collected"], "missing_info": [], "assumptions": [], "constraints": ["readonly"]},
              "plan": {
                "steps": [
                  {"id": 4, "action_type": "final_answer", "tool_name": "", "input": {"answer": "Use collected metadata to recommend metrics."}, "depends_on": [3]}
                ],
                "edge_contracts": [
                  {"from": 3, "to": 4, "field": "result", "type": "any", "required": true}
                ],
                "bindings": [],
                "stability": {
                  "stable_nodes": [1, 2, 3, 4],
                  "critical_tools": ["asset_query", "template_query", "sql_query_execute"],
                  "locked_edges": true,
                  "mutable_action_types": []
                }
              },
              "execution_policy": {"max_steps": 1, "allow_parallel": false, "allow_tool": [], "deny_tool": [], "timeout_ms": 30000},
              "review": {"self_check": {"completeness_score": 0.9, "hallucination_risk": 0.1, "tool_sufficiency": true, "missing_steps": []}, "fallback_plan": []}
            }
            """);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("asset_query")).thenReturn(true);
        when(toolRegistry.hasTool("template_query")).thenReturn(true);
        when(toolRegistry.hasTool("sql_query_execute")).thenReturn(true);
        InterpretationPlan originalPlan = originalSqlMetadataPlan();
        InterpretationPlanRewriter rewriter = new InterpretationPlanRewriter(
            chatModel,
            new ObjectMapper(),
            new InterpretationPlanValidator()
        );

        InterpretationPlanRewriter.RewriteResult result = rewriter.rewrite(new InterpretationPlanRewriter.RewriteRequest(
            originalPlan,
            originalPlan.steps().get(2),
            "LLM DAG controller requested plan rewrite",
            List.of(
                "InterpretationPlan initial step 1 asset_query succeeded.",
                "InterpretationPlan initial step 2 template_query succeeded.",
                "InterpretationPlan initial step 3 sql_query_execute succeeded."
            ),
            List.of("asset_query", "template_query", "sql_query_execute"),
            toolRegistry
        ));

        assertThat(result.valid()).isTrue();
        assertThat(result.rewrittenPlan().steps()).extracting(InterpretationPlan.Step::id)
            .containsExactly(1, 2, 3, 4);
        assertThat(result.rewrittenPlan().executionPolicy().maxSteps()).isEqualTo(4);
        assertThat(result.rewrittenPlan().executionPolicy().allowTool())
            .contains("asset_query", "template_query", "sql_query_execute");
    }

    @Test
    void repairsRewrittenPlanWhenOnlyMaxStepsIsTooSmall() {
        CapturingChatModel chatModel = new CapturingChatModel("""
            {
              "version": "1.0",
              "intent": {"type": "web_search", "goal": "Recover with public evidence", "risk_level": "low"},
              "context": {"key_facts": ["document_search failed"], "missing_info": [], "assumptions": [], "constraints": ["Use available tools only"]},
              "plan": {
                "steps": [
                  {"id": 1, "action_type": "mcp_tool", "tool_name": "web_search", "input": {"query": "runtime evidence"}, "depends_on": []},
                  {"id": 2, "action_type": "mcp_tool", "tool_name": "web_search", "input": {"query": "more runtime evidence"}, "depends_on": [1]},
                  {"id": 3, "action_type": "final_answer", "tool_name": "", "input": {"answer": "Use web evidence fallback."}, "depends_on": [2]}
                ]
              },
              "execution_policy": {"max_steps": 2, "allow_parallel": false, "allow_tool": ["web_search"], "deny_tool": ["document_search"], "timeout_ms": 30000},
              "review": {"self_check": {"completeness_score": 0.7, "hallucination_risk": 0.2, "tool_sufficiency": false, "missing_steps": []}, "fallback_plan": []}
            }
            """);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("web_search")).thenReturn(true);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        InterpretationPlan originalPlan = originalPlan();
        InterpretationPlanRewriter rewriter = new InterpretationPlanRewriter(
            chatModel,
            new ObjectMapper(),
            new InterpretationPlanValidator()
        );

        InterpretationPlanRewriter.RewriteResult result = rewriter.rewrite(new InterpretationPlanRewriter.RewriteRequest(
            originalPlan,
            originalPlan.steps().get(0),
            "Tool execution timed out",
            List.of("document_search failed with timeout"),
            List.of("document_search", "web_search"),
            toolRegistry
        ));

        assertThat(result.valid()).isTrue();
        assertThat(result.rewrittenPlan().steps()).hasSize(3);
        assertThat(result.rewrittenPlan().executionPolicy().maxSteps()).isEqualTo(3);
    }

    @Test
    void returnsFailureWhenModelDoesNotReturnJsonPlan() {
        InterpretationPlanRewriter rewriter = new InterpretationPlanRewriter(
            new CapturingChatModel("not json"),
            new ObjectMapper(),
            new InterpretationPlanValidator()
        );

        InterpretationPlanRewriter.RewriteResult result = rewriter.rewrite(new InterpretationPlanRewriter.RewriteRequest(
            originalPlan(),
            originalPlan().steps().get(0),
            "failed",
            List.of(),
            List.of("document_search"),
            mock(ToolRegistry.class)
        ));

        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("Failed to parse rewritten InterpretationPlan");
    }

    @Test
    void rewritePromptForbidsUnavailableTemplateDiscoveryTool() {
        CapturingChatModel chatModel = new CapturingChatModel("""
            {
              "version": "1.0",
              "intent": {"type": "sql_metadata", "goal": "Use already observed metadata", "risk_level": "low"},
              "context": {"key_facts": ["sql_metadata_search returned columns"], "missing_info": ["template discovery unavailable"], "assumptions": [], "constraints": ["Use available tools only"]},
              "plan": {
                "steps": [
                  {"id": 1, "action_type": "final_answer", "tool_name": "", "input": {"answer": "已有元数据可用于结构说明；模板发现工具不可用，无法继续执行模板查询。"}, "depends_on": []}
                ]
              },
              "execution_policy": {"max_steps": 1, "allow_parallel": false, "allow_tool": [], "deny_tool": [], "timeout_ms": 30000},
              "review": {"self_check": {"completeness_score": 0.6, "hallucination_risk": 0.1, "tool_sufficiency": true, "missing_steps": ["template discovery unavailable"]}, "fallback_plan": []}
            }
            """);
        InterpretationPlanRewriter rewriter = new InterpretationPlanRewriter(
            chatModel,
            new ObjectMapper(),
            new InterpretationPlanValidator()
        );

        InterpretationPlanRewriter.RewriteResult result = rewriter.rewrite(new InterpretationPlanRewriter.RewriteRequest(
            originalSqlMetadataPlan(),
            originalSqlMetadataPlan().steps().get(2),
            "TOOL_ROUTING_DENIED: No typed MCP tool is available for capability=template_discovery assetType=sql_datasource",
            List.of("mcp_chatchat_mcp_server_sql_metadata_search returned 13 columns"),
            List.of("mcp_chatchat_mcp_server_sql_metadata_search", "mcp_chatchat_mcp_server_sql_query_execute"),
            mock(ToolRegistry.class)
        ));

        assertThat(result.valid()).isTrue();
        assertThat(result.rewrittenPlan().steps()).extracting(InterpretationPlan.Step::toolName)
            .doesNotContain("mcp_chatchat_mcp_server_database_ops_template_search",
                "mcp_chatchat_mcp_server_sql_datasource_template_query", "template_query");
        assertThat(chatModel.lastPrompt())
            .contains("No template discovery tool is available")
            .contains("Do not add database_ops_template_search/template_query steps");
    }

    private InterpretationPlan originalPlan() {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("document_retrieval", "Find internal evidence", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "document_search", Map.of("query", "runtime evidence"), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of("document_search"), List.of(), 30000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(0.8, 0.1, false, List.of()),
                List.of()
            )
        );
    }

    private InterpretationPlan originalSqlMetadataPlan() {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_metadata", "Analyze table metrics", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "asset_query", Map.of("assetName", "db_query_mysql_248_test_db"), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "template_query", Map.of("intent", "metadata"), List.of(1), null, null),
                new InterpretationPlan.Step(3, "mcp_tool", "sql_query_execute", Map.of(
                    "templateId", "MYSQL_TABLE_METADATA",
                    "parameters", Map.of("tableName", "lbappdeploydetail"),
                    "executionContext", Map.of("assetName", "248测试数据库", "env", "DEV")
                ), List.of(2), null, null),
                new InterpretationPlan.Step(4, "final_answer", "", Map.of("answer", "done"), List.of(3), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                4,
                false,
                List.of("asset_query", "template_query", "sql_query_execute"),
                List.of(),
                30000
            ),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(0.8, 0.1, true, List.of()),
                List.of()
            )
        );
    }

    private static final class CapturingChatModel implements ChatModel {
        private final String response;
        private String lastPrompt;

        private CapturingChatModel(String response) {
            this.response = response;
        }

        @Override
        public String chat(String message) {
            this.lastPrompt = message;
            return response;
        }

        private String lastPrompt() {
            return lastPrompt;
        }
    }
}
