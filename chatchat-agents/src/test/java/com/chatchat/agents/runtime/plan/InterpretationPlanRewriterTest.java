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
