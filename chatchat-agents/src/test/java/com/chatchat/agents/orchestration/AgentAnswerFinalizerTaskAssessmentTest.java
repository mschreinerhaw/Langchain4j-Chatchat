package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentAnswerReview;
import com.chatchat.agents.runtime.AgentAnswerReviewer;
import com.chatchat.common.interaction.InteractionToolTrace;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAnswerFinalizerTaskAssessmentTest {

    @Test
    void reviewerCannotReplaceASelectedBusinessResult() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(
                AgentAnswerReview.REVISED,
                "## 无法直接完成设计\n\n请补充数据库版本。",
                "Missing optional context"
            );
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("protectedCandidateAnswer", true);
        metadata.put("answerOrigin", "planner_generated_recovered");
        metadata.put("answerLifecycleStatus", "SELECTED");
        String design = """
            # 客户信息表设计

            | 字段 | 类型 |
            |---|---|
            | cust_id | BIGINT |
            """;

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
            null,
            "设计客户信息表",
            null,
            List.of(),
            metadata,
            List.of(),
            design,
            () -> false,
            "final_answer"
        );

        assertThat(result.answer())
            .contains("# 客户信息表设计")
            .contains("cust_id")
            .doesNotContain("无法直接完成设计");
        assertThat(result.metadata())
            .containsEntry("answerDecision", AnswerDecisionEngine.NO_REWRITE)
            .containsEntry("answerDecisionReason", "protected_business_result_retained")
            .containsEntry("answerReviewAuthority", "diagnostic_only")
            .containsEntry("answerReviewRewriteApplied", false);
    }

    @Test
    void attachesPublicAssessmentContractToFinalExecutionMetadata() {
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("interpretationPlanEvidenceHistory", List.of(Map.of(
            "sufficient", false,
            "evidenceUsed", List.of("已返回的企业标准字段"),
            "missingEvidence", List.of("未召回的核心字段")
        )));

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "已生成基于现有标准字段的初版结构。",
            List.of(InteractionToolTrace.builder()
                .toolName("enterprise_metadata_search")
                .success(true)
                .build()),
            metadata,
            List.of("enterprise_metadata_search returned structured JSON")
        );

        assertThat(result.metadata())
            .containsEntry("taskResultAssessmentContractVersion", "task_result_assessment_v1")
            .containsEntry("taskResultExecutionStatus", "SUCCESS")
            .containsEntry("taskResultEvidenceStatus", "PARTIAL")
            .containsEntry("taskResultFulfillmentStatus", "PARTIAL")
            .containsEntry("taskResultDeliveryDecision", "PARTIAL_ARTIFACT")
            .containsKey("taskResultAssessment");
    }

    @Test
    void blocksInsufficientEvidenceRefusalWhenMcpReturnedQueryRows() {
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            null,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("oracle_tablespace_query")
            .success(true)
            .output("""
                {"success":true,"columns":["tablespace","size_mb"],"rows":[{"tablespace":"USERS","size_mb":102400}],"rowCount":1}
                """)
            .build();

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "关键指标不完整，证据不足，无法分析。",
            List.of(trace),
            new LinkedHashMap<>(),
            List.of("oracle_tablespace_query returned structured rows")
        );

        assertThat(result.answer())
            .contains("基于 MCP 查询结果的分析")
            .contains("USERS")
            .contains("102400")
            .doesNotContain("证据不足，无法分析");
        assertThat(result.metadata())
            .containsEntry("mcpResultEvidenceAvailability", "AVAILABLE")
            .containsEntry("mcpResultAnswerAllowed", true)
            .containsEntry("evidenceRefusalBlocked", true)
            .containsEntry("taskResultEvidenceStatus", "PARTIAL")
            .containsEntry("taskResultEvidenceAvailability", "AVAILABLE")
            .containsEntry("taskResultAnalysisCapability", "PARTIAL")
            .containsEntry("taskResultAnswerAllowed", true)
            .containsEntry("taskResultDeliveryDecision", "PARTIAL_ARTIFACT")
            .containsEntry("answerAssemblyMode", "PARTIAL");
    }
}
