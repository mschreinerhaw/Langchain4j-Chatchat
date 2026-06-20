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

    @Test
    void replacesNoMatchFallbackWhenDocumentEvidenceExists() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "\u672a\u80fd\u5728\u5f53\u524d\u77e5\u8bc6\u5e93\u4e2d\u68c0\u7d22\u5230\u76f8\u5173\u6587\u6863\u6216\u8bf4\u660e\u6750\u6599\u3002",
            List.of(),
            new java.util.LinkedHashMap<>(),
            List.of("""
                Unified evidence context (contractVersion=evidence_v1):
                [Evidence 1]
                type: DOCUMENT
                citation: doc://livedata-report#chunk=2
                source: \u57fa\u4e8elivedata\u6570\u636e\u7f16\u7ec7\u7684\u62a5\u8868\u5f00\u53d1.docx
                section: SQL\u5f00\u53d1
                content:
                \u5728livedata\u6570\u636e\u7f16\u7ec7\u6a21\u5757\u7ef4\u62a4\u6570\u636e\u6e90\uff0c\u5728\u6570\u636e\u89c6\u7a97\u4e2d\u5f00\u53d1\u62a5\u8868SQL\uff0c\u4fdd\u5b58SQL\u6570\u636e\u96c6\u540e\u8fdb\u884cBI\u62a5\u8868\u5236\u4f5c\u4e0e\u53d1\u5e03\u3002
                """)
        );

        assertThat(result.answer())
            .contains("doc://livedata-report#chunk=2")
            .contains("\u5728livedata\u6570\u636e\u7f16\u7ec7\u6a21\u5757\u7ef4\u62a4\u6570\u636e\u6e90")
            .doesNotContain("\u672a\u80fd\u5728\u5f53\u524d\u77e5\u8bc6\u5e93\u4e2d\u68c0\u7d22\u5230");
        assertThat(result.metadata())
            .containsEntry("evidenceForcedAnswer", true)
            .containsEntry("groundingStatus", "grounded");
    }
}
