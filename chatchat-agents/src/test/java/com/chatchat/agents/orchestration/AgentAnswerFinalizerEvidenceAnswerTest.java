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

    @Test
    void deterministicAnswerLockDoesNotOverrideUsableAnalysisAnswer() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "ADS \u4e1a\u52a1\u903b\u8f91\u9a8c\u8bc1\u4e3b\u8981\u68c0\u67e5\u7cfb\u7edf\u6570\u636e\u8d28\u91cf\u62a5\u544a\u3001\u8d26\u6237\u5f02\u5e38\u660e\u7ec6\u548c\u8bc1\u5238\u7c7b\u522b\u6620\u5c04\uff0c\u53ef\u7528\u6587\u6863 SQL \u4f5c\u4e3a\u6838\u9a8c\u4f9d\u636e\u3002",
            List.of(),
            new java.util.LinkedHashMap<>(),
            List.of("""
                Deterministic answer lock (contractVersion=evidence_execution_contract_v2_2):
                decision: ANSWER_ALLOWED
                contractHash: abc123
                graphViewHash: def456
                fromGraphOnly: true
                executable: true
                evidencePath: evidence:1:chunk -> evidence:1:sql_trusted
                sourceRefs: doc://file-1#chunk=0
                lockedAnswer:
                ---BEGIN_LOCKED_ANSWER---
                \u6839\u636e\u53ef\u6267\u884c\u8bc1\u636e\u5951\u7ea6\uff0cSQL\u4e3a\uff1aselect * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i doc://file-1#chunk=0
                ---END_LOCKED_ANSWER---
                """)
        );

        assertThat(result.answer())
            .contains("ADS")
            .contains("\u4e1a\u52a1\u903b\u8f91\u9a8c\u8bc1")
            .doesNotContain("select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i");
        assertThat(result.metadata())
            .containsEntry("deterministicAnswerAvailable", true)
            .containsEntry("deterministicAnswerUsedAsEvidence", true)
            .containsEntry("deterministicAnswerContractVersion", "evidence_execution_contract_v2_2")
            .containsEntry("deterministicAnswerContractHash", "abc123")
            .containsEntry("deterministicAnswerGraphViewHash", "def456");
    }

    @Test
    void deterministicAnswerLockOnlyReplacesFailureFallback() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "\u672a\u80fd\u83b7\u53d6SQL\u5185\u5bb9\u3002",
            List.of(),
            new java.util.LinkedHashMap<>(),
            List.of("""
                Deterministic answer lock (contractVersion=evidence_execution_contract_v2_2):
                decision: ANSWER_ALLOWED
                contractHash: abc123
                graphViewHash: def456
                lockedAnswer:
                ---BEGIN_LOCKED_ANSWER---
                \u6839\u636e\u53ef\u6267\u884c\u8bc1\u636e\u5951\u7ea6\uff0cSQL\u4e3a\uff1aselect * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i doc://file-1#chunk=0
                ---END_LOCKED_ANSWER---
                """)
        );

        assertThat(result.answer())
            .contains("select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i")
            .contains("doc://file-1#chunk=0")
            .doesNotContain("\u672a\u80fd\u83b7\u53d6SQL\u5185\u5bb9");
        assertThat(result.metadata())
            .containsEntry("deterministicAnswerAvailable", true)
            .containsEntry("deterministicAnswerFallbackApplied", true);
    }

    @Test
    void protocolAnswerIsNotReinterpretedBySecondSummaryModel() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
            null,
            "ads\u6570\u636e\u9a8c\u8bc1-\u4e1a\u52a1\u903b\u8f91\u9a8c\u8bc1 \u8bf4\u7684\u662f\u4ec0\u4e48\u5185\u5bb9\uff1f",
            null,
            List.of(),
            new java.util.LinkedHashMap<>(),
            List.of("""
                Unified evidence context (contractVersion=evidence_v1):
                [Evidence 1]
                type: DOCUMENT
                citation: doc://ads#chunk=2
                content:
                --\u7cfb\u7edf\u6570\u636e\u8d28\u91cf\u62a5\u544a select data_date, eval_rslt from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i;
                --\u8d26\u6237\u6570\u636e\u8d28\u91cf\u62a5\u544a-\u5f02\u5e38\u5ba2\u6237 select cust_num, ast_nett_amt from gdp_ads.ads_ids_acc_data_qlty_rpt_d_i where ast_nett_amt <> 0;
                """),
            """
                {
                  "uiResponse": {
                    "contractVersion": "ui_response_v1",
                    "answer": "\u7ed3\u8bba\uff1a\u6839\u636e\u5df2\u68c0\u7d22\u6587\u6863...",
                    "citations": [{"sourceRef":"doc://ads#chunk=2","text":"system report sql"}],
                    "evidenceSummary": "\u6587\u6863\u5185\u5bb9\u5305\u542b\u7cfb\u7edf\u6570\u636e\u8d28\u91cf\u62a5\u544a"
                  },
                  "debug": {"reasoningTrace": {}, "trustedSql": {}}
                }
                """,
            () -> false,
            "final_answer"
        );

        assertThat(result.answer())
            .contains("uiResponse")
            .contains("doc://ads#chunk=2")
            .contains("reasoningTrace");
        assertThat(result.metadata())
            .doesNotContainKey("finalMarkdownSummaryApplied")
            .doesNotContainKey("finalMarkdownSummaryReason");
    }
}
