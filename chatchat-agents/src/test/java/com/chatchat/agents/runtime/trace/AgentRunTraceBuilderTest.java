package com.chatchat.agents.runtime.trace;

import com.chatchat.agents.runtime.AgentObservation;
import com.chatchat.agents.runtime.AgentRun;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRunStatus;
import com.chatchat.common.interaction.InteractionToolTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentRunTraceBuilderTest {

    private final AgentRunTraceBuilder builder = new AgentRunTraceBuilder();

    @Test
    void buildsTraceFromRunEvidenceAnswerAndToolCalls() {
        AgentRunTrace trace = builder.fromRun(sampleRun());

        assertThat(trace.contractVersion()).isEqualTo("agent_run_trace_v1");
        assertThat(trace.question()).isEqualTo("config restart?");
        assertThat(trace.toolCalls()).hasSize(1);
        assertThat(trace.toolCalls().get(0).success()).isTrue();
        assertThat(trace.evidence()).hasSize(1);
        assertThat(trace.evidence().get(0).refId()).isEqualTo("doc://file-1#chunk=3");
        assertThat(trace.evidence().get(0).citationUsed()).isTrue();
        assertThat(trace.answer().confidence()).isEqualTo("high");
        assertThat(trace.grounding().status()).isEqualTo("grounded");
        assertThat(trace.failureReasons()).isEmpty();
    }

    public static AgentRun sampleRun() {
        Map<String, Object> citation = Map.of(
            "refId", "doc://file-1#chunk=3",
            "type", "DOCUMENT",
            "fileId", "file-1",
            "fileName", "ops.pdf",
            "chunkIndex", 3
        );
        Map<String, Object> evidenceAnswer = Map.of(
            "answer", "Restart service after config change doc://file-1#chunk=3",
            "citations", List.of(citation),
            "confidence", "high",
            "missingInfo", List.of()
        );
        Map<String, Object> metadata = Map.of(
            "answerContractVersion", "evidence_answer_v1",
            "evidenceAnswer", evidenceAnswer,
            "availableEvidenceCitations", List.of(citation),
            "groundingStatus", "grounded"
        );
        return AgentRun.builder()
            .runId("run-trace-1")
            .status(AgentRunStatus.COMPLETED)
            .request(AgentRunRequest.builder()
                .runId("run-trace-1")
                .requestId("req-trace-1")
                .query("config restart?")
                .tenantId("tenant-a")
                .userId("user-a")
                .build())
            .result(AgentRunResult.builder()
                .runId("run-trace-1")
                .status(AgentRunStatus.COMPLETED)
                .answer("Restart service after config change doc://file-1#chunk=3")
                .toolTraces(List.of(InteractionToolTrace.builder()
                    .toolName("document_search")
                    .success(true)
                    .input(Map.of("query", "config restart"))
                    .output("document_search returned doc://file-1#chunk=3")
                    .durationMs(12L)
                    .build()))
                .metadata(metadata)
                .build())
            .observations(List.of(AgentObservation.text(
                "document_evidence",
                "document_search",
                """
                    Unified evidence context (contractVersion=evidence_v1):
                    [Evidence 1]
                    type: DOCUMENT
                    citation: doc://file-1#chunk=3
                    content:
                    Restart service after config change.
                    """)))
            .metadata(metadata)
            .startedAt(1L)
            .finishedAt(2L)
            .build();
    }
}
