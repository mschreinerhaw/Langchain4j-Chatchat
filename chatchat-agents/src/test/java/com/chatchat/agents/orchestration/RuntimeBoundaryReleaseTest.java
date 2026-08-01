package com.chatchat.agents.orchestration;

import com.chatchat.agents.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RuntimeBoundaryReleaseTest {

    @Test
    void attributesConflictingEvidenceToEveryOpposingSource() {
        EvidenceTrustEvaluator.TrustResult result = new EvidenceTrustEvaluator().evaluate(List.of(
            Map.of("content", "Feature X is supported and available", "source_url", "https://docs.example/a", "title", "A", "score", 0.9),
            Map.of("content", "Feature X is not supported and unavailable", "source_url", "https://docs.example/b", "title", "B", "score", 0.9)
        ));

        assertThat(result.metadata()).containsEntry("contradictionDetected", true)
            .containsEntry("requestMoreEvidence", true);
        assertThat((List<?>) result.metadata().get("conflictAttribution"))
            .hasSize(2)
            .anySatisfy(item -> {
                Map<?, ?> source = (Map<?, ?>) item;
                assertThat(source.get("sourceRef")).isEqualTo("https://docs.example/a");
                assertThat(source.get("stance")).isEqualTo("positive");
            })
            .anySatisfy(item -> {
                Map<?, ?> source = (Map<?, ?>) item;
                assertThat(source.get("sourceRef")).isEqualTo("https://docs.example/b");
                assertThat(source.get("stance")).isEqualTo("negative");
            });
    }

    @Test
    void plannerUsesInjectedClockAcrossUtcYearBoundaryAndRuntimeTimezone() {
        Clock clock = Clock.fixed(Instant.parse("2025-12-31T16:00:00Z"), ZoneId.of("UTC"));
        AgentPlanner planner = new AgentPlanner(mock(ToolRegistry.class), new ObjectMapper(), clock);
        AtomicReference<String> prompt = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override
            public String chat(String message) {
                prompt.set(message);
                return validFinalPlan();
            }
        };

        planner.decideNextAction(model, "今天是什么日期", null, List.of(), List.of(), List.of(), List.of(),
            List.of(), false, false, null, null, Map.of("timezone", "Asia/Shanghai"));

        assertThat(prompt.get()).contains("Current date is 2026-01-01 in timezone Asia/Shanghai");
    }

    @Test
    void plannerUsesLeapDayWithoutSystemClockDependency() {
        Clock clock = Clock.fixed(Instant.parse("2024-02-29T04:00:00Z"), ZoneId.of("UTC"));
        AgentPlanner planner = new AgentPlanner(mock(ToolRegistry.class), new ObjectMapper(), clock);
        AtomicReference<String> prompt = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override
            public String chat(String message) {
                prompt.set(message);
                return validFinalPlan();
            }
        };

        planner.decideNextAction(model, "today", null, List.of(), List.of(), List.of(), List.of(),
            List.of(), false, false, null, null, Map.of("timezone", "America/New_York"));

        assertThat(prompt.get()).contains("Current date is 2024-02-28 in timezone America/New_York");
    }

    private static String validFinalPlan() {
        return """
            {"version":"1.0","intent":{"type":"reasoning","goal":"date","risk_level":"low"},
            "context":{"key_facts":[],"assumptions":[],"missing_info":[],"constraints":[]},
            "plan":{"steps":[{"id":1,"action_type":"final_answer","tool_name":"","input":{"answer":"ok"},"depends_on":[]}]},
            "execution_policy":{"max_steps":1,"allow_parallel":false,"allow_tool":[],"deny_tool":[]},
            "review":{"self_check":{"completeness_score":1.0,"hallucination_risk":0.0,"tool_sufficiency":true,"missing_steps":[]},"fallback_plan":[]}}
            """;
    }
}
