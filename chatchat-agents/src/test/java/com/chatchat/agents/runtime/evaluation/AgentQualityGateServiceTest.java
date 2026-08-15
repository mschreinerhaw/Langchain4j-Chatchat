package com.chatchat.agents.runtime.evaluation;

import com.chatchat.agents.runtime.trace.AgentRunTrace;
import com.chatchat.agents.runtime.trace.AgentRunTraceBuilder;
import com.chatchat.agents.runtime.trace.AgentRunTraceBuilderTest;
import com.chatchat.agents.runtime.trace.EvidenceTrace;
import com.chatchat.agents.runtime.trace.ToolCallTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentQualityGateServiceTest {

    private final AgentEvaluationService evaluator = new AgentEvaluationService();
    private final AgentQualityGateService gate = new AgentQualityGateService();

    @Test
    void passesAHealthyEvaluationWindow() {
        AgentEvaluationReport report = evaluator.evaluate(sampleTrace(), goldCase());

        AgentQualityGateReport result = gate.evaluate(List.of(report), AgentQualityGateThresholds.releaseDefaults());

        assertThat(result.contractVersion()).isEqualTo("agent_quality_gate_v1");
        assertThat(result.passed()).isTrue();
        assertThat(result.casePassRate()).isEqualTo(1.0D);
        assertThat(result.dimensionAverages()).containsOnly(
            Map.entry("retrieval", 1.0D),
            Map.entry("toolSelection", 1.0D),
            Map.entry("parameterAccuracy", 1.0D),
            Map.entry("evidenceCompleteness", 1.0D));
    }

    @Test
    void blocksReleaseWhenARegressionIsPresent() {
        AgentRunTrace trace = sampleTrace();
        ToolCallTrace original = trace.toolCalls().get(0);
        ToolCallTrace wrong = new ToolCallTrace(original.step(), "web_search", original.displayName(), true,
            Map.of("query", "wrong"), original.outputPreview(), null, original.durationMs(), original.startedAt(),
            original.finishedAt(), original.mcpCallId(), null, original.governance(), original.runtimeMetadata());
        AgentRunTrace degraded = AgentEvaluationServiceTest.copyWith(trace, List.of(wrong),
            List.of(new EvidenceTrace("doc://noise", "DOCUMENT", "noise", "web_search", false,
                "ALLOW", "irrelevant", Map.of())));

        AgentQualityGateReport result = gate.evaluate(
            List.of(evaluator.evaluate(degraded, goldCase())), AgentQualityGateThresholds.releaseDefaults());

        assertThat(result.passed()).isFalse();
        assertThat(result.failedRunIds()).containsExactly("run-trace-1");
        assertThat(result.failures()).anyMatch(value -> value.startsWith("toolSelection="));
        assertThat(result.failures()).anyMatch(value -> value.startsWith("parameterAccuracy="));
    }

    @Test
    void blocksReleaseWhenGoldSuiteDoesNotLabelEveryRequiredDimension() {
        AgentEvaluationReport legacy = evaluator.evaluate(sampleTrace(), new AgentEvaluationCase(
            "config restart?", List.of("doc://file-1#chunk=3"), List.of("restart"), true));

        AgentQualityGateReport result = gate.evaluate(
            List.of(legacy), AgentQualityGateThresholds.releaseDefaults());

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).contains(
            "toolSelection has no gold-labelled cases",
            "parameterAccuracy has no gold-labelled cases");
    }

    private AgentRunTrace sampleTrace() {
        return new AgentRunTraceBuilder().fromRun(AgentRunTraceBuilderTest.sampleRun());
    }

    private AgentEvaluationCase goldCase() {
        return new AgentEvaluationCase("config restart?", List.of("doc://file-1#chunk=3"),
            List.of("restart"), true,
            List.of(new AgentEvaluationCase.RetrievalExpectation("doc://file-1#chunk=3", List.of(), 1)),
            List.of(new AgentEvaluationCase.ToolExpectation("document_search",
                Map.of("query", "config restart"))),
            AgentEvaluationCase.Thresholds.strict());
    }
}
