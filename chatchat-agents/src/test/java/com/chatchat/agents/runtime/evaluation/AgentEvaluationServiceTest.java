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

class AgentEvaluationServiceTest {

    private final AgentEvaluationService service = new AgentEvaluationService();
    private final AgentRunTraceBuilder traceBuilder = new AgentRunTraceBuilder();

    @Test
    void scoresExpectedEvidenceCitationKeywordsAndGrounding() {
        AgentRunTrace trace = traceBuilder.fromRun(AgentRunTraceBuilderTest.sampleRun());

        AgentEvaluationReport report = service.evaluate(trace, goldCase());

        assertThat(report.contractVersion()).isEqualTo("agent_evaluation_v2");
        assertThat(report.passed()).isTrue();
        assertThat(report.metrics())
            .containsEntry("retrievalPrecision", 1.0)
            .containsEntry("retrievalRecall", 1.0)
            .containsEntry("toolSelectionF1", 1.0)
            .containsEntry("parameterAccuracy", 1.0)
            .containsEntry("evidenceHitRate", 1.0)
            .containsEntry("citationHitRate", 1.0)
            .containsEntry("evidenceCompleteness", 1.0)
            .containsEntry("answerKeywordCoverage", 1.0)
            .containsEntry("groundingPassRate", 1.0);
        assertThat(report.dimensions()).allSatisfy((name, dimension) -> {
            assertThat(dimension.passed()).as(name).isTrue();
            assertThat(dimension.score()).as(name).isEqualTo(1.0D);
        });
        assertThat(report.missingEvidence()).isEmpty();
        assertThat(report.missingKeywords()).isEmpty();
    }

    @Test
    void penalizesIrrelevantRetrievalAndIncorrectParameters() {
        AgentRunTrace original = traceBuilder.fromRun(AgentRunTraceBuilderTest.sampleRun());
        ToolCallTrace tool = original.toolCalls().get(0);
        ToolCallTrace wrongParameters = new ToolCallTrace(tool.step(), tool.toolName(), tool.displayName(),
            tool.success(), Map.of("query", "unrelated query"), tool.outputPreview(), tool.errorMessage(),
            tool.durationMs(), tool.startedAt(), tool.finishedAt(), tool.mcpCallId(), tool.evidenceId(),
            tool.governance(), tool.runtimeMetadata());
        EvidenceTrace noise = new EvidenceTrace("doc://noise", "DOCUMENT", "noise.pdf", "document_search",
            false, "ALLOW", "Unrelated content", Map.of());
        AgentRunTrace degraded = copyWith(original, List.of(wrongParameters),
            List.of(original.evidence().get(0), noise));

        AgentEvaluationReport report = service.evaluate(degraded, goldCase());

        assertThat(report.passed()).isFalse();
        assertThat(report.metrics().get("retrievalPrecision")).isEqualTo(0.5D);
        assertThat(report.metrics().get("parameterAccuracy")).isZero();
        assertThat(report.dimensions().get("retrieval").details())
            .contains("1 irrelevant retrieval result(s)");
        assertThat(report.dimensions().get("parameterAccuracy").details().get(0))
            .contains("document_search.query");
    }

    private AgentEvaluationCase goldCase() {
        return new AgentEvaluationCase(
            "config restart?",
            List.of("doc://file-1#chunk=3"),
            List.of("restart", "config"),
            true,
            List.of(new AgentEvaluationCase.RetrievalExpectation(
                "doc://file-1#chunk=3", List.of("restart service"), 1)),
            List.of(new AgentEvaluationCase.ToolExpectation(
                "document_search", Map.of("query", "config restart"))),
            AgentEvaluationCase.Thresholds.strict()
        );
    }

    static AgentRunTrace copyWith(AgentRunTrace trace, List<ToolCallTrace> tools, List<EvidenceTrace> evidence) {
        return new AgentRunTrace(trace.contractVersion(), trace.traceId(), trace.taskId(), trace.runId(),
            trace.requestId(), trace.conversationId(), trace.tenantId(), trace.userId(), trace.agentId(),
            trace.modelName(), trace.modelCallId(), trace.question(), trace.status(), trace.startedAt(),
            trace.finishedAt(), trace.latencyMs(), trace.tokenUsage(), tools, evidence, trace.answer(),
            trace.grounding(), trace.failureReasons(), trace.events());
    }
}
