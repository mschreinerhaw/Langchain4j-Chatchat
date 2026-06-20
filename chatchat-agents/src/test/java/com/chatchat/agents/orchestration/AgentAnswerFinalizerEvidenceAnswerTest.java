package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentAnswerReview;
import com.chatchat.agents.runtime.AgentAnswerReviewer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAnswerFinalizerEvidenceAnswerTest {

    @Test
    @SuppressWarnings("unchecked")
    void attachesUnifiedEvidenceAnswerMetadata() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "根据证据，配置变更后需要重启服务 doc://file-1#chunk=3。",
            List.of(),
            new java.util.LinkedHashMap<>(),
            List.of("""
                Unified evidence context (contractVersion=evidence_v1):
                [Evidence 1]
                type: DOCUMENT
                citation: doc://file-1#chunk=3
                content:
                restart service after config change
                """)
        );

        assertThat(result.metadata())
            .containsEntry("answerContractVersion", "evidence_answer_v1")
            .containsEntry("groundingStatus", "grounded")
            .containsEntry("answerAssemblyMode", "FULL");
        Map<String, Object> evidenceAnswer = (Map<String, Object>) result.metadata().get("evidenceAnswer");
        assertThat(evidenceAnswer)
            .containsEntry("confidence", "high");
        assertThat((List<Map<String, Object>>) evidenceAnswer.get("citations"))
            .extracting(item -> item.get("refId"))
            .containsExactly("doc://file-1#chunk=3");
        Map<String, Object> assemblyPolicy = (Map<String, Object>) result.metadata().get("answerAssemblyPolicy");
        assertThat(assemblyPolicy)
            .containsEntry("contractVersion", "answer_assembly_policy_v1")
            .containsEntry("mode", "FULL");
    }
}
