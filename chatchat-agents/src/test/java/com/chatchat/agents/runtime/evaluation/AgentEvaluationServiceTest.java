package com.chatchat.agents.runtime.evaluation;

import com.chatchat.agents.runtime.trace.AgentRunTrace;
import com.chatchat.agents.runtime.trace.AgentRunTraceBuilder;
import com.chatchat.agents.runtime.trace.AgentRunTraceBuilderTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvaluationServiceTest {

    private final AgentEvaluationService service = new AgentEvaluationService();
    private final AgentRunTraceBuilder traceBuilder = new AgentRunTraceBuilder();

    @Test
    void scoresExpectedEvidenceCitationKeywordsAndGrounding() {
        AgentRunTrace trace = traceBuilder.fromRun(AgentRunTraceBuilderTest.sampleRun());

        AgentEvaluationReport report = service.evaluate(trace, new AgentEvaluationCase(
            "config restart?",
            List.of("doc://file-1#chunk=3"),
            List.of("restart", "config"),
            true
        ));

        assertThat(report.contractVersion()).isEqualTo("agent_evaluation_v1");
        assertThat(report.passed()).isTrue();
        assertThat(report.metrics())
            .containsEntry("evidenceHitRate", 1.0)
            .containsEntry("citationHitRate", 1.0)
            .containsEntry("answerKeywordCoverage", 1.0)
            .containsEntry("groundingPassRate", 1.0);
        assertThat(report.missingEvidence()).isEmpty();
        assertThat(report.missingKeywords()).isEmpty();
    }
}
