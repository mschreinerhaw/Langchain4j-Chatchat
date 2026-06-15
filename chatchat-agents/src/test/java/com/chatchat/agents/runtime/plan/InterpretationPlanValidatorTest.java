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
                new InterpretationPlan.Step(1, "mcp_tool", "web_crawler", Map.of("query", "分析今天市场热点"), List.of(), null, null),
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
                    new InterpretationPlan.Step(1, "mcp_tool", "web_search", Map.of("query", "今天市场热点"), List.of(), null, null),
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
