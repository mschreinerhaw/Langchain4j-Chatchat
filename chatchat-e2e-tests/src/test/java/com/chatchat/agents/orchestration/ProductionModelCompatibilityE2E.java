package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.AgentPlanner;

import com.chatchat.agents.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProductionModelCompatibilityE2E {

    @TestFactory
    Stream<DynamicTest> promptAndModelOutputCompatibilityMatrix() {
        return Stream.of(
            new CompatibilityCase("legacy-prompt-plain-json", "Prompt contract v1", List.of(validPlan())),
            new CompatibilityCase("current-prompt-json-fence", "Prompt contract v2", List.of(
                "```json\n" + validPlan() + "\n```")),
            new CompatibilityCase("provider-leading-text", "Prompt contract v2", List.of(
                "Here is the requested plan:\n" + validPlan())),
            new CompatibilityCase("invalid-first-response-repair", "Prompt contract v2", List.of(
                "I refuse to return JSON", validPlan())),
            new CompatibilityCase("truncated-first-response-repair", "Prompt contract v2", List.of(
                "{\"version\":\"1.0\",\"intent\":", validPlan()))
        ).map(testCase -> DynamicTest.dynamicTest(testCase.name(), () -> verify(testCase)));
    }

    private void verify(CompatibilityCase testCase) {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public String chat(String prompt) {
                int index = Math.min(calls.getAndIncrement(), testCase.responses().size() - 1);
                return testCase.responses().get(index);
            }
        };
        AgentPlanner planner = new AgentPlanner(mock(ToolRegistry.class), new ObjectMapper());

        PlannerExecutionResult result = planner.decideNextAction(
            model,
            "Analyse only verified evidence and do not invent tool execution.",
            testCase.systemPrompt(),
            List.of("approved_search"),
            List.of(), List.of(), List.of(), List.of(),
            false, false, null, null,
            Map.of("plannerMaxRepairAttempts", 3));

        assertThat(result.plan().valid()).as(testCase.name()).isTrue();
        assertThat(result.plan().executable()).as(testCase.name()).isTrue();
        assertThat(result.decision().action()).isEqualTo("final");
        assertThat(result.decision().answer()).contains("verified evidence is unavailable");
        assertThat(calls.get()).isBetween(1, 2);
    }

    private static String validPlan() {
        return """
            {
              "version":"1.0",
              "intent":{"type":"reasoning","goal":"safe compatibility result","risk_level":"high"},
              "context":{"key_facts":[],"assumptions":[],"missing_info":["verified evidence"],"constraints":["do not fabricate"]},
              "plan":{"steps":[{
                "id":1,"action_type":"final_answer","tool_name":"",
                "input":{"answer":"A verified answer cannot be produced because verified evidence is unavailable."},
                "depends_on":[]
              }]},
              "execution_policy":{"max_steps":1,"allow_parallel":false,"allow_tool":[],"deny_tool":[]},
              "review":{"self_check":{"completeness_score":0.8,"hallucination_risk":0.0,"tool_sufficiency":true,"missing_steps":[]},"fallback_plan":[]}
            }
            """;
    }

    private record CompatibilityCase(String name, String systemPrompt, List<String> responses) {
    }
}
